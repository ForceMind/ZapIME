// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Synthetic data only. Exercises production storage/ZIP code without native model parsing. */
public final class VoiceKeyModelsSmoke {
    static final String DICT="dict.decoder.utf8.txt",MODEL="synthetic.xnet",OTHER="otherxxxx.xnet",INNER="ime_asr_decoder_model.zip";
    static final byte[] WORDS="synthetic dictionary".getBytes(StandardCharsets.UTF_8),WEIGHTS="synthetic non-model bytes".getBytes(StandardCharsets.UTF_8);
    static final BooleanSupplier RUN=()->false;
    interface Task{void run()throws Exception;}
    static void check(boolean valid,String why){if(!valid)throw new AssertionError(why);}
    static void refused(Task task)throws Exception{try{task.run();}catch(IOException expected){return;}throw new AssertionError("invalid input accepted");}
    static String hash(byte[] data)throws Exception{return VoiceKeyModelStore.hex(MessageDigest.getInstance("SHA-256").digest(data));}
    static byte[] zip(Map<String,byte[]> entries)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(Map.Entry<String,byte[]> e:entries.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}return bytes.toByteArray();}
    static LinkedHashMap<String,byte[]> standard(){LinkedHashMap<String,byte[]> m=new LinkedHashMap<>();m.put(DICT,WORDS);m.put(MODEL,WEIGHTS);return m;}
    static final class Fixture {
        final byte[] inner,pkg;final VoiceKeyModelStore.Catalog catalog;
        Fixture(byte[] inner,Map<String,VoiceKeyModelStore.Spec> specs)throws Exception{this.inner=inner;pkg=zip(Collections.singletonMap(INNER,inner));catalog=new VoiceKeyModelStore.Catalog("synthetic-v1",pkg.length,hash(pkg),Collections.singleton(INNER),new VoiceKeyModelStore.Spec(INNER,null,inner.length,hash(inner)),specs);}
        Fixture()throws Exception{this(zip(standard()),specs(standard()));}
    }
    static Map<String,VoiceKeyModelStore.Spec> specs(Map<String,byte[]> data)throws Exception{Map<String,VoiceKeyModelStore.Spec> specs=new LinkedHashMap<>();for(Map.Entry<String,byte[]> e:data.entrySet())specs.put(e.getKey(),new VoiceKeyModelStore.Spec(e.getKey(),e.getKey(),e.getValue().length,hash(e.getValue())));return specs;}
    static File dir(Path root,VoiceKeyModelStore.Catalog catalog){return root.resolve(catalog.id).toFile();}
    static void preserved(VoiceKeyModelStore store)throws Exception{File p=store.verified(RUN);check(Arrays.equals(Files.readAllBytes(new File(p,DICT).toPath()),WORDS),"dictionary changed");check(Arrays.equals(Files.readAllBytes(new File(p,MODEL).toPath()),WEIGHTS),"model changed");}
    static long u32(byte[] b,int n){return (b[n]&255L)|((b[n+1]&255L)<<8)|((b[n+2]&255L)<<16)|((b[n+3]&255L)<<24);}
    static void put32(byte[] b,int n,long v){for(int j=0;j<4;j++)b[n+j]=(byte)(v>>>(j*8));}
    static byte[] centralMode(byte[] zip,long mode){byte[] copy=zip.clone();for(int n=0;n<copy.length-46;n++)if(u32(copy,n)==0x02014b50L){put32(copy,n+38,mode<<16);break;}return copy;}
    static byte[] replace(byte[] source,String old,String text){byte[] out=source.clone(),a=old.getBytes(StandardCharsets.US_ASCII),b=text.getBytes(StandardCharsets.US_ASCII);check(a.length==b.length,"fixture name lengths");for(int n=0;n<=out.length-a.length;n++){boolean match=true;for(int j=0;j<a.length;j++)if(out[n+j]!=a[j]){match=false;break;}if(match)System.arraycopy(b,0,out,n,b.length);}return out;}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("zapime-voicekey-models-");List<String> passed=new ArrayList<>();Fixture f=new Fixture();VoiceKeyModelStore store=new VoiceKeyModelStore(root.resolve("profile").toFile(),f.catalog);
        try{
            check(!store.installed()&&!store.recoverable(),"missing model hints");store.importPackage(new ByteArrayInputStream(f.pkg),RUN);preserved(store);check(store.installed(),"installed hint");passed.add("fixed_catalog_roundtrip");
            Path model=new File(dir(root.resolve("profile"),f.catalog),MODEL).toPath();Files.setLastModifiedTime(model,FileTime.fromMillis(123456789000L));FileTime before=Files.getLastModifiedTime(model);store.importPackage(new ByteArrayInputStream(f.pkg),RUN);check(before.equals(Files.getLastModifiedTime(model)),"valid reimport replaced immutable files");passed.add("valid_reimport_preserves_inode_metadata");
            byte[] corrupt=f.pkg.clone();corrupt[5]^=1;refused(()->store.importPackage(new ByteArrayInputStream(corrupt),RUN));preserved(store);passed.add("package_authentication_precedes_zip");
            refused(()->store.importPackage(new ByteArrayInputStream(Arrays.copyOf(f.pkg,f.pkg.length-1)),RUN));refused(()->store.importPackage(new ByteArrayInputStream(Arrays.copyOf(f.pkg,f.pkg.length+1)),RUN));preserved(store);passed.add("package_size_bounds");
            refused(()->store.importPackage(new ByteArrayInputStream(f.pkg),()->true));AtomicBoolean stop=new AtomicBoolean();InputStream cancellable=new ByteArrayInputStream(f.pkg){@Override public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,Math.min(len,8));stop.set(true);return n;}};refused(()->store.importPackage(cancellable,stop::get));preserved(store);passed.add("cancel_preserves_previous");
            InputStream broken=new InputStream(){int count;public int read()throws IOException{if(count++>4)throw new IOException("synthetic read failure");return 1;}};refused(()->store.importPackage(broken,RUN));refused(()->VoiceKeyModelStore.copy(new ByteArrayInputStream(WEIGHTS),new OutputStream(){public void write(int b)throws IOException{throw new IOException("synthetic ENOSPC");}},WEIGHTS.length,hash(WEIGHTS),RUN));preserved(store);passed.add("read_write_failures_preserve_previous");
            for(String path:new String[]{"../bad.xnet","unknown.txt","nested.zip"}){LinkedHashMap<String,byte[]> extra=standard();extra.put(path,new byte[]{1});Fixture bad=new Fixture(zip(extra),specs(standard()));VoiceKeyModelStore guarded=new VoiceKeyModelStore(root.resolve("unsafe-"+passed.size()).toFile(),bad.catalog);refused(()->guarded.importPackage(new ByteArrayInputStream(bad.pkg),RUN));}passed.add("unknown_traversal_nested_members");
            LinkedHashMap<String,byte[]> duplicates=standard();duplicates.put(OTHER,WEIGHTS);byte[] dup=replace(zip(duplicates),OTHER,MODEL);Fixture badDuplicate=new Fixture(dup,specs(duplicates));refused(()->new VoiceKeyModelStore(root.resolve("duplicate").toFile(),badDuplicate.catalog).importPackage(new ByteArrayInputStream(badDuplicate.pkg),RUN));passed.add("duplicate_inner_members");
            Fixture symbolic=new Fixture(centralMode(f.inner,0120777),specs(standard()));refused(()->new VoiceKeyModelStore(root.resolve("zip-symlink").toFile(),symbolic.catalog).importPackage(new ByteArrayInputStream(symbolic.pkg),RUN));passed.add("zip_symlink_mode_rejected");
            byte[] declared=f.inner.clone();for(int n=0;n<declared.length-46;n++)if(u32(declared,n)==0x02014b50L){put32(declared,n+24,VoiceKeyModelStore.MAX_BYTES+1);break;}Fixture huge=new Fixture(declared,specs(standard()));refused(()->new VoiceKeyModelStore(root.resolve("huge").toFile(),huge.catalog).importPackage(new ByteArrayInputStream(huge.pkg),RUN));passed.add("zip_declared_expansion_limit");
            Map<String,VoiceKeyModelStore.Spec> wrong=specs(standard());wrong.put(MODEL,new VoiceKeyModelStore.Spec(MODEL,MODEL,WEIGHTS.length,hash(WORDS)));Fixture wrongMember=new Fixture(f.inner,wrong);refused(()->new VoiceKeyModelStore(root.resolve("wrong-member").toFile(),wrongMember.catalog).importPackage(new ByteArrayInputStream(wrongMember.pkg),RUN));passed.add("member_fixed_hash_rejected");
            Files.write(model,new byte[]{1});File main=dir(root.resolve("profile"),f.catalog);Files.write(new File(main,VoiceKeyModelStore.MARKER).toPath(),f.catalog.marker());refused(()->store.verified(RUN));store.importPackage(new ByteArrayInputStream(f.pkg),RUN);preserved(store);passed.add("self_manifest_cannot_bless_corruption");
            Path profile=root.resolve("profile"),incoming=profile.resolve("incoming-"+f.catalog.id),previous=profile.resolve("previous-"+f.catalog.id),current=profile.resolve(f.catalog.id);Files.move(current,incoming);Files.createDirectory(current);Files.write(current.resolve("invalid"),new byte[]{1});check(store.recoverable(),"staged recovery hint");preserved(store);check(!Files.exists(incoming),"staged recovery left source");passed.add("interrupted_publish_recovers_completed_staging");
            VoiceKeyModelStore.remove(previous.toFile());Files.move(current,previous);Files.createDirectory(current);Files.write(current.resolve("invalid"),new byte[]{1});Files.createDirectory(incoming);Files.write(incoming.resolve("invalid"),new byte[]{1});preserved(store);check(!Files.exists(previous),"backup recovery incomplete");passed.add("invalid_staging_recovers_previous");
            Path external=root.resolve("external");Files.createDirectory(external);Path sentinel=external.resolve("sentinel");Files.write(sentinel,WEIGHTS);Path linked=root.resolve("linked");Files.createSymbolicLink(linked,external);refused(()->new VoiceKeyModelStore(linked.toFile(),f.catalog).importPackage(new ByteArrayInputStream(f.pkg),RUN));check(Arrays.equals(Files.readAllBytes(sentinel),WEIGHTS),"symlink target changed");Files.delete(model);Files.createSymbolicLink(model,sentinel);refused(()->store.verified(RUN));check(Files.exists(sentinel),"member symlink target changed");passed.add("filesystem_symlinks_fail_closed");
            byte[] copyBuffer=new byte[WEIGHTS.length];InputStream zeroReads=new ByteArrayInputStream(WEIGHTS){boolean zero=true;@Override public synchronized int read(byte[] b,int o,int l){if(zero){zero=false;return 0;}return super.read(b,o,l);}};ByteArrayOutputStream out=new ByteArrayOutputStream();VoiceKeyModelStore.copy(zeroReads,out,WEIGHTS.length,hash(WEIGHTS),RUN);check(Arrays.equals(out.toByteArray(),WEIGHTS),"zero-progress stream corrupted");passed.add("zero_progress_read_is_bounded");
            check(VoiceKeyModelCatalog.available(),"production source catalog missing");VoiceKeyModelStore.Catalog pinned=VoiceKeyModelCatalog.require();check(pinned.outputs.size()==2&&pinned.members.size()==6&&pinned.packageSize==102920639L&&pinned.packageSha.equals("b3729fbe928b25740cc9cd781537175b1db4a445bd78bf464267c0c4633e0863"),"production reviewed pins changed");passed.add("compiled_catalog_has_fixed_official_pins");
            StringBuilder report=new StringBuilder("{\"synthetic\":true,\"scope\":\"Production model storage with synthetic non-model bytes; no inference, JNI, actual ENOSPC/process-kill or phone acceptance.\",\"cases\":[");for(int n=0;n<passed.size();n++){if(n>0)report.append(',');report.append("{\"id\":\"").append(passed.get(n)).append("\",\"passed\":true}");}report.append("]}\n");Path destination=Paths.get(args[0]);Files.createDirectories(destination.getParent());Files.write(destination,report.toString().getBytes(StandardCharsets.UTF_8));System.out.println("PASS VoiceKey fixed catalog/model IO boundaries: "+passed.size());
        } finally {VoiceKeyModelStore.remove(root.toFile());}
    }
}
