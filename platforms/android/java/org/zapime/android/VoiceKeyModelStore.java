// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Bounded, source-pinned model storage. No JNI parsing or inference/cache side effects. */
final class VoiceKeyModelStore {
    static final long MAX_BYTES=256L*1024*1024;
    static final String MARKER="zapime-voicekey.identity";
    static final class Spec {
        final String path,output,sha256;final long size;
        Spec(String path,String output,long size,String sha256){
            if(!safePath(path)||size<0||size>MAX_BYTES||!sha256.matches("[0-9a-f]{64}")||(output!=null&&!output.matches("[A-Za-z0-9._-]{1,120}")))throw new IllegalArgumentException("catalog_member");
            this.path=path;this.output=output;this.size=size;this.sha256=sha256;
        }
    }
    static final class Catalog {
        final String id,packageSha;final long packageSize;final Spec inner;
        final Set<String> outerNames;final Map<String,Spec> members,outputs;
        Catalog(String id,long size,String sha,Set<String> outer,Spec inner,Map<String,Spec> members){
            if(!id.matches("[a-z0-9-]{1,80}")||size<1||size>MAX_BYTES||!sha.matches("[0-9a-f]{64}")||outer.isEmpty()||outer.size()>128||!outer.contains(inner.path)||members.isEmpty()||members.size()>128)throw new IllegalArgumentException("catalog");
            Map<String,Spec> copied=new LinkedHashMap<>(),outputs=new LinkedHashMap<>();int dict=0,models=0;long total=0;
            for(String name:outer)if(!safePath(name))throw new IllegalArgumentException("catalog_path");
            for(Map.Entry<String,Spec> entry:members.entrySet()){
                Spec spec=entry.getValue();if(spec.path.endsWith(".zip"))throw new IllegalArgumentException("catalog_nested_archive");if(!entry.getKey().equals(spec.path))throw new IllegalArgumentException("catalog_member");copied.put(entry.getKey(),spec);total+=spec.size;
                if(total>MAX_BYTES)throw new IllegalArgumentException("catalog_size");
                if(spec.output!=null){if(spec.size<1||outputs.put(spec.output,spec)!=null)throw new IllegalArgumentException("catalog_output");if(spec.output.equals("dict.decoder.utf8.txt"))dict++;else if(spec.output.endsWith(".xnet"))models++;else throw new IllegalArgumentException("catalog_output");}
            }
            if(dict!=1||models<1||models>8)throw new IllegalArgumentException("catalog_models");
            this.id=id;packageSize=size;packageSha=sha;outerNames=Collections.unmodifiableSet(new HashSet<>(outer));this.inner=inner;this.members=Collections.unmodifiableMap(copied);this.outputs=Collections.unmodifiableMap(outputs);
        }
        byte[] marker(){return ("ZapIME/VoiceKey/1\n"+id+"\n"+packageSha+"\n").getBytes(StandardCharsets.US_ASCII);}
    }
    private final File root,current,incoming,previous,pkg,inner;private final Catalog catalog;
    VoiceKeyModelStore(File root,Catalog catalog){this.root=root;this.catalog=catalog;current=new File(root,catalog.id);incoming=new File(root,"incoming-"+catalog.id);previous=new File(root,"previous-"+catalog.id);pkg=new File(root,"package.tmp");inner=new File(root,"inner.tmp");}
    static void check(BooleanSupplier stop)throws IOException{if(stop.getAsBoolean())throw new IOException("cancelled");}
    static boolean safePath(String path){if(path==null||path.isEmpty()||path.startsWith("/")||path.indexOf('\\')>=0||path.indexOf('\0')>=0||path.length()>240)return false;String trimmed=path.endsWith("/")?path.substring(0,path.length()-1):path;for(String p:trimmed.split("/",-1))if(p.isEmpty()||p.equals(".")||p.equals("..")||!p.matches("[A-Za-z0-9._-]+"))return false;return true;}
    boolean installed(){return hint(current);}
    boolean recoverable(){return hint(incoming)||hint(previous);}
    private boolean hint(File dir){return !Files.isSymbolicLink(root.toPath())&&!Files.isSymbolicLink(dir.toPath())&&dir.isDirectory()&&new File(dir,MARKER).isFile();}
    private void root(boolean create)throws IOException{if(Files.isSymbolicLink(root.toPath()))throw new IOException("voicekey_storage");if(create&&!root.exists()&&!root.mkdirs())throw new IOException("voicekey_storage");if(!root.isDirectory())throw new IOException("voicekey_model_missing");}
    File verified(BooleanSupplier stop)throws Exception{
        check(stop);root(false);IOException original;
        try{return verify(current,stop);}catch(IOException bad){original=bad;check(stop);}
        boolean staged=valid(incoming,stop);if(staged){check(stop);publish();return current.getCanonicalFile();}
        if(valid(previous,stop)){check(stop);remove(current);Files.move(previous.toPath(),current.toPath(),StandardCopyOption.ATOMIC_MOVE);return current.getCanonicalFile();}
        throw original;
    }
    private boolean valid(File file,BooleanSupplier stop)throws Exception{try{verify(file,stop);return true;}catch(IOException bad){check(stop);return false;}}
    private File verify(File directory,BooleanSupplier stop)throws Exception{
        check(stop);if(Files.isSymbolicLink(directory.toPath())||!directory.isDirectory())throw new IOException("voicekey_model_missing");
        Set<String> wanted=new HashSet<>(catalog.outputs.keySet());wanted.add(MARKER);String[] actual=directory.list();if(actual==null||!wanted.equals(new HashSet<>(Arrays.asList(actual))))throw new IOException("voicekey_model_members");
        for(Spec spec:catalog.outputs.values())verifyFile(new File(directory,spec.output),spec.size,spec.sha256,stop);
        byte[] marker=catalog.marker();verifyFile(new File(directory,MARKER),marker.length,hex(MessageDigest.getInstance("SHA-256").digest(marker)),stop);check(stop);return directory.getCanonicalFile();
    }
    void importPackage(InputStream source,BooleanSupplier stop)throws Exception{
        check(stop);if(source==null)throw new IOException("voicekey_package_missing");root(true);
        long required=catalog.packageSize+catalog.inner.size+8192;for(Spec spec:catalog.outputs.values())required+=spec.size;if(root.getUsableSpace()<required)throw new IOException("voicekey_storage_space");
        try{verified(stop);}catch(IOException invalid){check(stop);}
        remove(incoming);if(!incoming.mkdir())throw new IOException("voicekey_storage");boolean committing=false;
        try{
            remove(pkg);try(OutputStream out=Files.newOutputStream(pkg.toPath(),StandardOpenOption.CREATE_NEW)){copy(source,out,catalog.packageSize,catalog.packageSha,stop);}
            inspectZip(pkg,catalog.outerNames,stop);
            try(ZipFile zip=new ZipFile(pkg)){
                ZipEntry entry=zip.getEntry(catalog.inner.path);if(entry==null||entry.isDirectory())throw new IOException("voicekey_inner_archive");remove(inner);
                try(InputStream in=zip.getInputStream(entry);FileOutputStream out=new FileOutputStream(inner)){copy(in,out,catalog.inner.size,catalog.inner.sha256,stop);out.getFD().sync();}
            }
            inspectZip(inner,catalog.members.keySet(),stop);
            try(ZipFile zip=new ZipFile(inner)){
                for(Spec spec:catalog.members.values()){
                    check(stop);ZipEntry entry=zip.getEntry(spec.path);if(entry==null||entry.getSize()!=spec.size)throw new IOException("voicekey_model_members");
                    try(InputStream in=zip.getInputStream(entry)){
                        if(spec.output==null)copy(in,null,spec.size,spec.sha256,stop);
                        else try(FileOutputStream out=new FileOutputStream(new File(incoming,spec.output))){copy(in,out,spec.size,spec.sha256,stop);out.getFD().sync();}
                    }
                }
            }
            try(FileOutputStream out=new FileOutputStream(new File(incoming,MARKER))){out.write(catalog.marker());out.getFD().sync();}
            verify(incoming,stop);check(stop);remove(pkg);remove(inner);
            // Re-import of the same immutable model never replaces a valid mmap inode.
            if(valid(current,stop))return;
            committing=true;publish();
        }finally{if(!committing)remove(incoming);remove(pkg);remove(inner);}
    }
    private void publish()throws IOException{
        remove(previous);boolean displaced=false;
        if(Files.exists(current.toPath(),LinkOption.NOFOLLOW_LINKS)){Files.move(current.toPath(),previous.toPath(),StandardCopyOption.ATOMIC_MOVE);displaced=true;}
        try{Files.move(incoming.toPath(),current.toPath(),StandardCopyOption.ATOMIC_MOVE);}
        catch(IOException failed){if(displaced&&!Files.exists(current.toPath(),LinkOption.NOFOLLOW_LINKS))try{Files.move(previous.toPath(),current.toPath(),StandardCopyOption.ATOMIC_MOVE);}catch(IOException restore){failed.addSuppressed(restore);}throw failed;}
        // Keep at most one previous directory. Cleanup of an already committed
        // publication must not falsely report failure; the next replacement
        // removes this known backup before displacing current.
    }
    static void copy(InputStream in,OutputStream out,long size,String sha,BooleanSupplier stop)throws Exception{
        if(size<0||size>MAX_BYTES)throw new IOException("voicekey_model_size");MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];long count=0;
        while(true){check(stop);int n=in.read(buffer);if(n<0)break;if(n==0){int value=in.read();if(value<0)break;buffer[0]=(byte)value;n=1;}count+=n;if(count>size)throw new IOException("voicekey_model_size");if(out!=null)out.write(buffer,0,n);hash.update(buffer,0,n);}
        check(stop);if(count!=size||!hex(hash.digest()).equals(sha))throw new IOException("voicekey_model_checksum");
    }
    private static void verifyFile(File file,long size,String sha,BooleanSupplier stop)throws Exception{if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()!=size)throw new IOException("voicekey_model_checksum");try(InputStream in=new FileInputStream(file)){copy(in,null,size,sha,stop);}}
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();}
    static void remove(File path)throws IOException{remove(path,0,new int[]{0});}
    private static void remove(File path,int depth,int[] count)throws IOException{
        if(!Files.exists(path.toPath(),LinkOption.NOFOLLOW_LINKS))return;if(depth>8||++count[0]>256)throw new IOException("voicekey_cleanup_bounds");
        if(Files.isDirectory(path.toPath(),LinkOption.NOFOLLOW_LINKS)){File[] files=path.listFiles();if(files==null)throw new IOException("voicekey_storage");for(File file:files)remove(file,depth+1,count);}
        Files.delete(path.toPath());
    }
    /** Inspect central attributes before Java's ZIP API discards Unix symlink mode. */
    static void inspectZip(File file,Set<String> names,BooleanSupplier stop)throws Exception{
        try(RandomAccessFile input=new RandomAccessFile(file,"r")){
            long length=input.length();if(length<22||length>MAX_BYTES)throw new IOException("voicekey_zip_size");int span=(int)Math.min(length,65557);byte[] end=new byte[span];input.seek(length-span);input.readFully(end);int offset=-1;
            for(int p=span-22;p>=0;p--)if(u32(end,p)==0x06054b50L&&p+22+u16(end,p+20)==span){offset=p;break;}
            if(offset<0||u16(end,offset+4)!=0||u16(end,offset+6)!=0)throw new IOException("voicekey_zip_format");
            int entries=u16(end,offset+10);long size=u32(end,offset+12),start=u32(end,offset+16),eocd=length-span+offset;
            if(entries!=u16(end,offset+8)||entries<1||entries>128||entries!=names.size()||size==0xffffffffL||start==0xffffffffL||start+size!=eocd)throw new IOException("voicekey_zip_members");
            Set<String> seen=new HashSet<>();input.seek(start);
            for(int n=0;n<entries;n++){
                check(stop);byte[] header=new byte[46];input.readFully(header);if(u32(header,0)!=0x02014b50L||(u16(header,8)&1)!=0||(u16(header,10)!=0&&u16(header,10)!=8)||(u32(header,24)>MAX_BYTES||u32(header,20)>MAX_BYTES))throw new IOException("voicekey_zip_entry");
                int nameSize=u16(header,28),extra=u16(header,30),comment=u16(header,32);long mode=u32(header,38)>>>16;
                if(nameSize<1||nameSize>240||((mode&0170000)!=0&&(mode&0170000)!=0100000&&(mode&0170000)!=0040000)||input.getFilePointer()+nameSize+extra+comment>start+size)throw new IOException("voicekey_zip_entry");
                byte[] raw=new byte[nameSize];input.readFully(raw);for(byte b:raw)if(b<32||b>126)throw new IOException("voicekey_zip_name");String name=new String(raw,StandardCharsets.US_ASCII);
                if(!safePath(name)||!names.contains(name)||!seen.add(name))throw new IOException("voicekey_zip_members");input.seek(input.getFilePointer()+extra+comment);
            }
            if(input.getFilePointer()!=start+size||!seen.equals(names))throw new IOException("voicekey_zip_members");
        }
    }
    private static int u16(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8);}
    private static long u32(byte[] b,int p){return (long)u16(b,p)|((long)u16(b,p+2)<<16);}
}
