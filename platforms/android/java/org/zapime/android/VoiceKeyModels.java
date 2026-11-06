// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/**
 * Experimental VoiceKey WeChat offline model import. User supplies the official
 * archive; nothing is downloaded or bundled. The source package is checked
 * against the immutable size and digest published by the pinned VoiceKey source.
 * The legacy MD5 identity is insufficient for hostile redistribution: ship
 * externally only after pinning an independent SHA-256 and verifying rights.
 */
final class VoiceKeyModels {
    private static final long PACKAGE_SIZE = 102_920_639L;
    private static final String PACKAGE_MD5 = "28386481eb94ff73b28ee3949c0cf690";
    private static final long MAX_MODEL_BYTES = 256L * 1024 * 1024;
    private static final String MANIFEST = "zapime-voicekey.properties";

    private VoiceKeyModels(){}
    /**
     * The pinned VoiceKey aarch64 kernel currently uses SDOT without runtime
     * dispatch. Refuse to load it on ARM devices without asimddp/dotprod.
     * An unavailable feature report fails closed until upstream adds fallback.
     */
    static boolean cpuSupported(){
        String[] abis=android.os.Build.SUPPORTED_ABIS;
        if(abis.length==0||!"arm64-v8a".equals(abis[0]))return true;
        try(BufferedReader input=new BufferedReader(new FileReader("/proc/cpuinfo"))){
            String line;
            while((line=input.readLine())!=null){
                if(line.startsWith("Features")||line.startsWith("features")){
                    return line.contains("asimddp")||line.contains("dotprod");
                }
            }
        }catch(IOException ignored){}
        return false;
    }
    private static File root(Context context){return new File(context.getFilesDir(),"voicekey");}
    private static File current(Context context){return new File(root(context),"current");}
    private static File staging(Context context){return new File(root(context),"incoming");}
    private static File archive(Context context){return new File(root(context),"package.tmp");}
    private static void check(BooleanSupplier cancelled)throws IOException {
        if(cancelled.getAsBoolean())throw new IOException("cancelled");
    }
    private static String digest(byte[] bytes){
        StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte b:bytes)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
        return out.toString();
    }
    private static String sha(File file,BooleanSupplier cancelled)throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[65536];
        try(InputStream in=new FileInputStream(file)){
            int n;
            while((n=in.read(buffer))!=-1){check(cancelled);md.update(buffer,0,n);}
        }
        return digest(md.digest());
    }
    static boolean installed(Context context){
        File dir=current(context);
        return dir.isDirectory()&&new File(dir,MANIFEST).isFile();
    }
    static File verified(Context context,BooleanSupplier cancelled)throws Exception {
        if(!cpuSupported())throw new IOException("voicekey_cpu_unsupported");
        File dir=current(context);
        if(Files.isSymbolicLink(dir.toPath())||!dir.isDirectory())throw new IOException("voicekey_model_missing");
        File manifest=new File(dir,MANIFEST);
        if(Files.isSymbolicLink(manifest.toPath())||manifest.length()>8192)throw new IOException("voicekey_model_invalid");
        Properties props=new Properties();
        try(InputStream in=new FileInputStream(manifest)){props.load(in);}
        if(!"1".equals(props.getProperty("schema")))throw new IOException("voicekey_model_invalid");
        String[] files=dir.list();
        if(files==null||files.length<3||files.length>10)throw new IOException("voicekey_model_members");
        int dict=0,xnet=0;long total=0;
        for(String name:files){
            check(cancelled);
            if(name.equals(MANIFEST))continue;
            if(!name.matches("[A-Za-z0-9._-]{1,120}"))throw new IOException("voicekey_model_name");
            if(name.equals("dict.decoder.utf8.txt"))dict++;
            else if(name.endsWith(".xnet"))xnet++;
            else throw new IOException("voicekey_model_members");
            File file=new File(dir,name);
            if(Files.isSymbolicLink(file.toPath())||!file.isFile())throw new IOException("voicekey_model_member");
            long len=file.length();total+=len;
            if(len<1||total>MAX_MODEL_BYTES)throw new IOException("voicekey_model_size");
            if(!Long.toString(len).equals(props.getProperty(name+".size"))||
                !sha(file,cancelled).equals(props.getProperty(name+".sha256")))
                throw new IOException("voicekey_model_checksum");
        }
        if(dict!=1||xnet<1||xnet>8||!Integer.toString(files.length-1).equals(props.getProperty("members")))
            throw new IOException("voicekey_model_members");
        return dir.getCanonicalFile();
    }
    static synchronized void importPackage(Context context,InputStream source,BooleanSupplier cancelled)throws Exception {
        if(!cpuSupported())throw new IOException("voicekey_cpu_unsupported");
        File parent=root(context);
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("voicekey_storage");
        if(Files.isSymbolicLink(parent.toPath()))throw new IOException("voicekey_storage");
        File pkg=archive(context),temp=staging(context),dest=current(context);
        remove(temp);
        if(!temp.mkdir())throw new IOException("voicekey_storage");
        boolean published=false;
        try {
            MessageDigest md=MessageDigest.getInstance("MD5");
            try(OutputStream out=new FileOutputStream(pkg)){
                byte[] buffer=new byte[65536];int n;long length=0;
                while((n=source.read(buffer))!=-1){
                    check(cancelled);length+=n;
                    if(length>PACKAGE_SIZE)throw new IOException("voicekey_package_size");
                    out.write(buffer,0,n);md.update(buffer,0,n);
                }
                if(length!=PACKAGE_SIZE||!PACKAGE_MD5.equals(digest(md.digest())))
                    throw new IOException("voicekey_package_integrity");
            }
            unpack(pkg,temp,cancelled);
            if(!NativeVoiceKey.checkModel(temp.getCanonicalPath()))
                throw new IOException("voicekey_model_format");
            // Record digests of installed model files. The app-private directory
            // is rehashed before each recording to detect accidental corruption.
            Properties props=new Properties();props.setProperty("schema","1");
            String[] names=temp.list();
            if(names==null)throw new IOException("voicekey_model_members");
            props.setProperty("members",Integer.toString(names.length));
            for(String name:names){
                File file=new File(temp,name);
                props.setProperty(name+".size",Long.toString(file.length()));
                props.setProperty(name+".sha256",sha(file,cancelled));
            }
            try(FileOutputStream out=new FileOutputStream(new File(temp,MANIFEST))){props.store(out,"ZapIME VoiceKey model digests");out.getFD().sync();}
            // Keep the old installed model intact when import or verification fails.
            check(cancelled);
            File saved=new File(parent,"previous");
            remove(saved);
            if(dest.exists())Files.move(dest.toPath(),saved.toPath(),StandardCopyOption.ATOMIC_MOVE);
            try{Files.move(temp.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE);published=true;}
            catch(IOException moveError){
                if(saved.exists())Files.move(saved.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE);
                throw moveError;
            }
            remove(saved);
        } finally {
            if(!published)remove(temp);
            if(pkg.exists()&&!pkg.delete())pkg.deleteOnExit();
        }
    }
    private static void unpack(File pkg,File dest,BooleanSupplier cancelled)throws Exception {
        boolean hasDict=false;int xnet=0;int members=0;long total=0;
        try(ZipFile outer=new ZipFile(pkg)){
            ZipEntry inner=outer.getEntry("ime_asr_decoder_model.zip");
            if(inner==null||inner.isDirectory()||inner.getSize()>MAX_MODEL_BYTES)
                throw new IOException("voicekey_inner_archive");
            try(ZipInputStream zip=new ZipInputStream(outer.getInputStream(inner))){
                ZipEntry entry;
                while((entry=zip.getNextEntry())!=null){
                    check(cancelled);
                    if(entry.isDirectory())continue;
                    String raw=entry.getName();String name=raw.substring(raw.lastIndexOf('/')+1);
                    boolean dict=name.equals("dict.decoder.utf8.txt"),model=name.endsWith(".xnet");
                    if(!dict&&!model)continue;
                    if(!name.matches("[A-Za-z0-9._-]{1,120}")||++members>9)
                        throw new IOException("voicekey_model_members");
                    File file=new File(dest,name);
                    if(file.exists())throw new IOException("voicekey_duplicate_model");
                    if(dict){if(hasDict)throw new IOException("voicekey_duplicate_dict");hasDict=true;}
                    if(model)xnet++;
                    long memberSize=0;
                    try(FileOutputStream out=new FileOutputStream(file)){
                        byte[] buffer=new byte[65536];int n;
                        while((n=zip.read(buffer))!=-1){
                            check(cancelled);memberSize+=n;total+=n;
                            if(memberSize>MAX_MODEL_BYTES||total>MAX_MODEL_BYTES)throw new IOException("voicekey_model_size");
                            out.write(buffer,0,n);
                        }
                        out.getFD().sync();
                    }
                    if(memberSize==0)throw new IOException("voicekey_model_empty");
                }
            }
        }
        if(!hasDict||xnet<1||xnet>8)throw new IOException("voicekey_model_members");
    }
    private static void remove(File file)throws IOException {
        if(Files.isSymbolicLink(file.toPath()))throw new IOException("voicekey_symlink");
        if(!file.exists())return;
        if(file.isDirectory()){
            File[] children=file.listFiles();
            if(children==null)throw new IOException("voicekey_storage");
            for(File child:children)remove(child);
        }
        if(!file.delete())throw new IOException("voicekey_storage");
    }
}
