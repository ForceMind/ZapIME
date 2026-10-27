// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;
import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import java.util.UUID;
import java.util.Arrays;
import java.security.KeyStore;
import org.json.JSONObject;

/** Runs in the signed target UID. Only synthetic PCM is ever supplied to JNI. */
public final class VoiceInstrumentation extends Instrumentation {
    private Class<?> nativeVoice,models;
    private String stage="start";
    private File fixtures;
    private static final Class<?>[] NONE=new Class<?>[0];
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private Object invoke(Class<?> type,String name,Class<?>[] signature,Object...args)throws Exception {Method method=type.getDeclaredMethod(name,signature);method.setAccessible(true);return method.invoke(null,args);}
    private Object nativeCall(String name,Class<?>[] signature,Object...args)throws Exception{return invoke(nativeVoice,name,signature,args);}
    private JSONObject json(Object bytes)throws Exception{return new JSONObject(new String((byte[])bytes,StandardCharsets.UTF_8));}
    private void require(boolean valid,String reason)throws Exception{if(!valid)throw new Exception(reason);}
    private void pass(String name){Bundle event=new Bundle();event.putString("stream","PASS "+name+"\n");sendStatus(0,event);}
    private byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private long allocate()throws Exception{return (Long)nativeCall("allocate",NONE);}
    private boolean close(long handle)throws Exception{return (Boolean)nativeCall("close",new Class<?>[]{long.class},handle);}
    private JSONObject start(long handle,File model)throws Exception{return json(nativeCall("start",new Class<?>[]{long.class,byte[].class,byte[].class},handle,bytes(model.getAbsolutePath()),bytes(new File(getTargetContext().getApplicationInfo().nativeLibraryDir,"libsherpa-onnx-c-api.so").getAbsolutePath())));}
    private JSONObject accept(long handle,short[] pcm,int count)throws Exception{return json(nativeCall("accept",new Class<?>[]{long.class,short[].class,int.class},handle,pcm,count));}
    private File verified()throws Exception{return (File)invoke(models,"verified",new Class<?>[]{Context.class,BooleanSupplier.class},getTargetContext(),(BooleanSupplier)()->false);}
    private void importZip(InputStream input,BooleanSupplier cancelled)throws Exception{invoke(models,"importZip",new Class<?>[]{Context.class,InputStream.class,BooleanSupplier.class},getTargetContext(),input,cancelled);}
    private byte[] badZip(String name)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry(name));zip.write(new byte[]{1,2,3});zip.closeEntry();}return bytes.toByteArray();}
    private void rejectImport(String member)throws Exception{boolean rejected=false;try{importZip(new ByteArrayInputStream(badZip(member)),()->false);}catch(InvocationTargetException e){rejected=true;}require(rejected,"import accepted invalid member");}
    private void finalPcmRace()throws Exception{
        // Freeze a timed-out poll before it returns. The real producer API then
        // publishes the final PCM and completion in that exact scheduling gap.
        class LatePoll extends ArrayBlockingQueue<short[]> {
            final CountDownLatch timedOut=new CountDownLatch(1),resume=new CountDownLatch(1);
            final AtomicBoolean first=new AtomicBoolean(true);
            LatePoll(){super(2);}
            @Override public short[] poll(long time,TimeUnit unit)throws InterruptedException{if(first.compareAndSet(true,false)){timedOut.countDown();if(!resume.await(5,TimeUnit.SECONDS))throw new InterruptedException("race fixture timeout");return null;}return super.poll(time,unit);}
        }
        LatePoll backing=new LatePoll();Class<?> type=Class.forName("org.zapime.android.VoicePcmQueue",true,getTargetContext().getClassLoader());Constructor<?> constructor=type.getDeclaredConstructor(BlockingQueue.class);constructor.setAccessible(true);Object queue=constructor.newInstance(backing);
        Method next=type.getDeclaredMethod("next",AtomicBoolean.class),offer=type.getDeclaredMethod("offer",short[].class),finish=type.getDeclaredMethod("finish");next.setAccessible(true);offer.setAccessible(true);finish.setAccessible(true);
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean cancelled=new AtomicBoolean();short[] tail={17,-31,4};CountDownLatch done=new CountDownLatch(1);
        Thread consumer=new Thread(()->{try{require(next.invoke(queue,cancelled)==tail,"final PCM was lost after timed-out poll");require(next.invoke(queue,cancelled)==null,"EOF did not follow final PCM");}catch(Throwable error){failure.set(error);}finally{done.countDown();}},"voice-queue-race");consumer.start();
        try{require(backing.timedOut.await(5,TimeUnit.SECONDS),"race fixture did not reach poll");require((Boolean)offer.invoke(queue,(Object)tail),"final PCM offer failed");finish.invoke(queue);backing.resume.countDown();require(done.await(5,TimeUnit.SECONDS),"queue completion stalled");require(failure.get()==null,"ordered final PCM regression");}
        finally{cancelled.set(true);backing.resume.countDown();consumer.interrupt();consumer.join(1000);}
        pass("deterministic timed-out-poll/final-PCM/completion race preserves tail then EOF");
    }
    private void recordingPolicyState()throws Exception{
        Class<?> type=Class.forName("org.zapime.android.VoiceRecordingState",true,getTargetContext().getClassLoader());
        Constructor<?> constructor=type.getDeclaredConstructor(int.class,Runnable.class);constructor.setAccessible(true);
        Method observe=type.getDeclaredMethod("observe",int.class,boolean.class),close=type.getDeclaredMethod("close");observe.setAccessible(true);close.setAccessible(true);
        AtomicInteger calls=new AtomicInteger();Object state=constructor.newInstance(17,(Runnable)calls::incrementAndGet);
        observe.invoke(state,18,true);observe.invoke(state,17,false);require(calls.get()==0,"foreign/unmuted recording treated as silenced");
        observe.invoke(state,17,true);observe.invoke(state,17,false);observe.invoke(state,17,true);require(calls.get()==1,"recording silence must be terminal and delivered once");
        Object closed=constructor.newInstance(17,(Runnable)calls::incrementAndGet);close.invoke(closed);observe.invoke(closed,17,true);require(calls.get()==1,"late callback after close");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),closing=new CountDownLatch(1),done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        Object racing=constructor.newInstance(17,(Runnable)()->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("silence race fixture timeout");}catch(InterruptedException e){throw new AssertionError(e);}});
        Thread event=new Thread(()->{try{observe.invoke(racing,17,true);}catch(Throwable e){failure.set(e);}}),closer=new Thread(()->{closing.countDown();try{close.invoke(racing);}catch(Throwable e){failure.set(e);}finally{done.countDown();}});
        try{event.start();require(entered.await(5,TimeUnit.SECONDS),"silence race not entered");closer.start();require(closing.await(5,TimeUnit.SECONDS),"close race not entered");require(!done.await(100,TimeUnit.MILLISECONDS),"close returned before active callback drained");release.countDown();require(done.await(5,TimeUnit.SECONDS),"close stalled");require(failure.get()==null,"silence close race failed");}
        finally{release.countDown();event.join(1000);if(closer.getState()!=Thread.State.NEW)closer.join(1000);}
        pass("recording policy state: own-session only, terminal silence, late-event rejection and close drains callback (deterministic; not real AppOps)");
    }
    private void modelPublicationRecovery(File model)throws Exception{
        // Simulate filesystem states left by interrupted publication. This is
        // not an actual process-kill/power-loss or SAF file-picker test.
        Path destination=model.toPath(),parent=destination.getParent();String id=model.getName();Path incoming=parent.resolve("incoming-"+id),previous=parent.resolve("previous-"+id);
        Files.move(destination,incoming,StandardCopyOption.ATOMIC_MOVE);Files.createDirectory(destination);Files.write(destination.resolve("msime-model.json"),bytes("{}"));
        require((Boolean)invoke(models,"recoverable",new Class<?>[]{Context.class},getTargetContext()),"completed staging recovery hint absent");
        require(verified().equals(model),"verified staging did not recover interrupted publication");require(!Files.exists(incoming)&&!Files.exists(previous),"publication recovery left move state");
        Files.move(destination,previous,StandardCopyOption.ATOMIC_MOVE);Files.createDirectory(incoming);Files.write(incoming.resolve("msime-model.json"),bytes("{}"));
        require(verified().equals(model),"invalid staging displaced valid backup");require(!Files.exists(previous),"backup recovery did not restore destination");
        Files.delete(incoming.resolve("msime-model.json"));Files.delete(incoming);
        // Never promote a complete but modified staging model. Keep this copy
        // recoverable by restoring its exact trusted manifest after rejection.
        Files.move(destination,incoming,StandardCopyOption.ATOMIC_MOVE);Path manifest=incoming.resolve("msime-model.json");byte[] trusted=Files.readAllBytes(manifest);Files.write(manifest,bytes("{}"));
        boolean rejected=false;try{verified();}catch(InvocationTargetException expected){rejected=true;}
        require(rejected&&!Files.exists(destination)&&Files.exists(incoming),"untrusted staging was published");Files.write(manifest,trusted);require(verified().equals(model),"repaired trusted staging did not recover");
        boolean cancelled=false;try{invoke(models,"verified",new Class<?>[]{Context.class,BooleanSupplier.class},getTargetContext(),(BooleanSupplier)()->true);}catch(InvocationTargetException expected){cancelled=true;}
        require(cancelled&&verified().equals(model),"cancelled verification changed installed model");
        pass("model publication filesystem-state recovery: rehashed staging, valid backup, tamper rejection and cancellation (not power-loss acceptance)");
    }
    private byte[] vaultLoad(Class<?> vault,UUID profile)throws Exception{return (byte[])invoke(vault,"load",new Class<?>[]{Context.class,UUID.class},getTargetContext(),profile);}
    private void vaultSave(Class<?> vault,UUID profile,byte[] material)throws Exception{invoke(vault,"save",new Class<?>[]{Context.class,UUID.class,byte[].class},getTargetContext(),profile,material);}
    private void rejectVaultLoad(Class<?> vault,UUID profile)throws Exception{boolean rejected=false;try{byte[] data=vaultLoad(vault,profile);if(data!=null)Arrays.fill(data,(byte)0);}catch(InvocationTargetException expected){rejected=expected.getCause() instanceof IOException;}require(rejected,"unreadable sync material accepted");}
    private void syncSecretStorage()throws Exception{
        Class<?> vault=Class.forName("org.zapime.android.SyncSecrets",true,getTargetContext().getClassLoader());UUID a=UUID.randomUUID(),b=UUID.randomUUID();byte[] material=bytes("ZapIME synthetic material "+UUID.randomUUID()),other=bytes("other synthetic material");
        try{
            require(vaultLoad(vault,a)==null,"fresh sync profile not empty");vaultSave(vault,a,material);byte[] actual=vaultLoad(vault,a);try{require(Arrays.equals(material,actual),"Keystore material roundtrip");}finally{Arrays.fill(actual,(byte)0);}
            File aFile=(File)invoke(vault,"file",new Class<?>[]{Context.class,UUID.class},getTargetContext(),a),bFile=(File)invoke(vault,"file",new Class<?>[]{Context.class,UUID.class},getTargetContext(),b);
            require(aFile.getCanonicalFile().getParentFile().equals(getTargetContext().getNoBackupFilesDir().getCanonicalFile()),"material not in noBackup private directory");byte[] first=Files.readAllBytes(aFile.toPath());vaultSave(vault,a,material);byte[] second=Files.readAllBytes(aFile.toPath());require(!Arrays.equals(first,second),"AES-GCM nonce reused for repeated save");
            KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);String alias=(String)invoke(vault,"alias",new Class<?>[]{UUID.class},a);require(store.getKey(alias,null).getEncoded()==null,"wrapping key unexpectedly exportable");
            boolean oversized=false;try{vaultSave(vault,a,new byte[65537]);}catch(InvocationTargetException expected){oversized=true;}require(oversized&&Arrays.equals(Files.readAllBytes(aFile.toPath()),second),"oversized material changed saved cipher");
            byte[] bad=second.clone();bad[bad.length-1]^=1;Files.write(aFile.toPath(),bad);rejectVaultLoad(vault,a);Files.write(aFile.toPath(),second);
            vaultSave(vault,b,other);byte[] bWire=Files.readAllBytes(bFile.toPath());Files.write(bFile.toPath(),second);rejectVaultLoad(vault,b);Files.write(bFile.toPath(),bWire);
            // Emulate an unfinished AtomicFile write, not actual process/power loss.
            android.util.AtomicFile atomic=new android.util.AtomicFile(aFile);FileOutputStream unfinished=atomic.startWrite();try{unfinished.write(new byte[]{1,2,3});unfinished.getFD().sync();}finally{unfinished.close();}
            actual=vaultLoad(vault,a);try{require(Arrays.equals(material,actual),"unfinished atomic write lost previous material");}finally{Arrays.fill(actual,(byte)0);}
            CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);Thread holder=new Thread(()->{synchronized(vault){held.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}});AtomicBoolean rejectedMain=new AtomicBoolean();AtomicLong mainMillis=new AtomicLong();
            try{holder.start();require(held.await(5,TimeUnit.SECONDS),"vault contention fixture unavailable");runOnMainSync(()->{long begin=SystemClock.uptimeMillis();try{byte[] unexpected=vaultLoad(vault,a);if(unexpected!=null)Arrays.fill(unexpected,(byte)0);}catch(InvocationTargetException expected){rejectedMain.set(expected.getCause() instanceof IOException&&"sync_material_requires_worker".equals(expected.getCause().getMessage()));}catch(Exception ignored){}mainMillis.set(SystemClock.uptimeMillis()-begin);});require(rejectedMain.get()&&mainMillis.get()<1800,"main-thread guard blocked behind vault I/O");}finally{release.countDown();holder.join(1000);}
            store.deleteEntry(alias);rejectVaultLoad(vault,a);boolean replaced=false;try{vaultSave(vault,a,material);replaced=true;}catch(InvocationTargetException expected){}require(!replaced&&!store.containsAlias(alias),"missing key was silently recreated over saved material");
            actual=vaultLoad(vault,b);try{require(Arrays.equals(other,actual),"key loss damaged other profile");}finally{Arrays.fill(actual,(byte)0);}
        }finally{
            Arrays.fill(material,(byte)0);Arrays.fill(other,(byte)0);
            Exception cleanup=null;for(UUID profile:new UUID[]{a,b})try{invoke(vault,"forget",new Class<?>[]{Context.class,UUID.class},getTargetContext(),profile);}catch(Exception e){cleanup=e;}if(cleanup!=null)throw cleanup;
        }
        pass("actual AndroidKeyStore AES-GCM material roundtrip, nonce freshness, non-exportable wrapping key, tamper/account isolation, unfinished AtomicFile rollback, worker guard and key-loss fail-closed (synthetic only)");
    }
    private void emptyPcmResults(File model)throws Exception{
        org.json.JSONArray cases=new org.json.JSONArray();
        for(String name:new String[]{"no_samples","all_zero","ignored_tail","positive_lsb","negative_lsb","cancelled_empty"}){
            long handle=allocate();require(handle!=0,"PCM evidence allocation");
            boolean expected=name.equals("positive_lsb")||name.equals("negative_lsb");int samples=0;
            try{
                require(start(handle,model).optBoolean("ready"),"PCM evidence model load");
                if(name.equals("cancelled_empty")){
                    nativeCall("cancel",new Class<?>[]{long.class},handle);
                    require("cancelled".equals(json(nativeCall("finish",new Class<?>[]{long.class},handle)).optString("error")),"empty fast finish bypassed cancellation");
                    cases.put(new JSONObject().put("id",name).put("accepted_samples",0).put("cancelled_before_finish",true).put("passed",true));continue;
                }
                if(!name.equals("no_samples")){
                    short[] pcm=name.equals("all_zero")?new short[1600]:new short[]{0,(short)(name.equals("negative_lsb")?-1:1)};
                    int count=name.equals("ignored_tail")?1:pcm.length;samples+=count;
                    JSONObject partial=accept(handle,pcm,count);
                    require(!partial.has("error")&&partial.has("has_nonzero_pcm")&&partial.getBoolean("has_nonzero_pcm")==expected,"PCM evidence valid-prefix classification");
                    if(!expected)require(partial.getString("text").isEmpty(),"zero-only partial text escaped");
                    // Trailing exact zero samples must neither create evidence nor
                    // remove a prior +/-1 sample. Rejected counts must not add it.
                    require("invalid_audio".equals(accept(handle,new short[]{1},2).optString("error")),"evidence count overrun accepted");
                    partial=accept(handle,new short[1600],1600);samples+=1600;
                    require(!partial.has("error")&&partial.has("has_nonzero_pcm")&&partial.getBoolean("has_nonzero_pcm")==expected,"trailing zero changed PCM evidence");
                    if(!expected)require(partial.getString("text").isEmpty(),"trailing zero-only partial text escaped");
                }
                JSONObject complete=json(nativeCall("finish",new Class<?>[]{long.class},handle));
                require(!complete.has("error")&&complete.has("has_nonzero_pcm")&&complete.getBoolean("has_nonzero_pcm")==expected,"final PCM evidence mismatch");
                if(!expected)require(complete.getString("text").isEmpty(),"zero/no-sample final text escaped");
                require("invalid_session".equals(json(nativeCall("finish",new Class<?>[]{long.class},handle)).optString("error")),"empty finish reusable");
                require("invalid_session".equals(accept(handle,new short[]{1},1).optString("error")),"finished empty session accepted audio");
                cases.put(new JSONObject().put("id",name).put("accepted_samples",samples).put("has_nonzero_pcm",expected).put("empty_required",!expected).put("passed",true));
            }finally{require(close(handle),"close PCM evidence session");}
        }
        JSONObject report=new JSONObject().put("synthetic",true).put("api_level",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]).put("cases",cases).put("scope","Real JNI and pinned model with synthetic exact-zero/prefix/one-LSB PCM; not VAD, system-mute inference, microphone speech or accuracy acceptance");
        Files.write(new File(fixtures,"empty-pcm-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
        pass("actual JNI empty/zero PCM returns empty partial/final text; valid count prefix, +/-1 LSB, trailing quiet and repeat-finish boundaries");
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            nativeVoice=Class.forName("org.zapime.android.NativeVoice",true,getTargetContext().getClassLoader());models=Class.forName("org.zapime.android.VoiceModels",true,getTargetContext().getClassLoader());
            stage="ordered PCM completion";finalPcmRace();
            stage="recording policy state";recordingPolicyState();
            stage="sync material Keystore";syncSecretStorage();
            stage="learning accepted-commit service method";LearningHostProbe.run(this);
            pass("learning service-method synthetic accepted/rejected/excluded commits, no editor reads and declared-field privacy allowlist (not active translation UI)");
            fixtures=new File(getTargetContext().getExternalFilesDir(null),"voice-fixtures");
            if(!fixtures.isDirectory()&&!fixtures.mkdirs())throw new IOException("test result storage unavailable");
            stage="model boundary";rejectImport("../tokens.txt");rejectImport("msime-model.json");rejectImport("tokens.txt");
            try(InputStream in=getContext().getAssets().open("voice-fixtures/model.zip")){importZip(in,()->false);}File model=verified();
            byte[] manifest=Files.readAllBytes(new File(model,"msime-model.json").toPath());rejectImport("tokens.txt");require(verified().equals(model),"invalid import replaced model");
            boolean cancelled=false;try(InputStream in=getContext().getAssets().open("voice-fixtures/model.zip")){try{importZip(in,()->true);}catch(InvocationTargetException e){cancelled=true;}}require(cancelled,"cancelled import accepted");require(verified().equals(model),"cancel damaged installed model");
            Files.write(new File(model,"msime-model.json").toPath(),bytes("{}"));boolean tampered=false;try{verified();}catch(InvocationTargetException e){tampered=true;}finally{Files.write(new File(model,"msime-model.json").toPath(),manifest);}require(tampered,"tampered manifest accepted");verified();pass("verified import, traversal/config/checksum/cancellation rejection and preservation");
            stage="model publication recovery";modelPublicationRecovery(model);
            stage="native ownership";long[] handles=new long[4];for(int i=0;i<4;i++){handles[i]=allocate();require(handles[i]!=0,"allocation");}require(allocate()==0,"unbounded handles");for(long h:handles)require(close(h),"close allocated handle");
            long handle=allocate();final long ownerHandle=handle;AtomicReference<Throwable> wrongFailure=new AtomicReference<>();Thread wrong=new Thread(()->{try{require(!close(ownerHandle),"wrong owner closed handle");require("invalid_owner".equals(accept(ownerHandle,new short[1],1).optString("error")),"wrong owner accepted PCM");}catch(Throwable e){wrongFailure.set(e);}});wrong.start();wrong.join();require(wrongFailure.get()==null,"wrong-owner boundary");
            require(start(handle,new File(getTargetContext().getFilesDir(),"missing-model")).has("error"),"missing model accepted");nativeCall("cancel",new Class<?>[]{long.class},handle);require("cancelled".equals(start(handle,model).optString("error")),"cancel before load ignored");require(close(handle)&&!close(handle),"stale handle close");pass("bounded handles, wrong-thread/stale ownership and load cancellation");
            stage="empty PCM result boundary";emptyPcmResults(model);
            stage="real native synthetic recognition";handle=allocate();require(model.getAbsolutePath().equals(model.getCanonicalPath()),"model path not canonical");long loadStart=SystemClock.elapsedRealtime();JSONObject started=start(handle,model);long loadMillis=SystemClock.elapsedRealtime()-loadStart;require(started.optBoolean("ready"),"native model load: "+started.optString("error"));require("invalid_audio".equals(accept(handle,new short[1601],1601).optString("error")),"oversized PCM accepted");require("invalid_audio".equals(accept(handle,new short[1],2).optString("error")),"PCM count overrun accepted");
            long decodeStart=SystemClock.elapsedRealtime();int samples=0;try(InputStream in=getContext().getAssets().open("voice-fixtures/synthetic.pcm")){byte[] buffer=new byte[3200];while(true){int n=0,count;while(n<buffer.length&&(count=in.read(buffer,n,buffer.length-n))!=-1)n+=count;if(n==0)break;require(n%2==0,"invalid synthetic PCM");short[] pcm=new short[n/2];ByteBuffer.wrap(buffer,0,n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);require(!accept(handle,pcm,pcm.length).has("error"),"native stream decode");samples+=pcm.length;}}
            String text=json(nativeCall("finish",new Class<?>[]{long.class},handle)).getString("text");for(String fragment:new String[]{"你好","离线语音输入测试","三点开会"})require(text.contains(fragment),"synthetic recognition fragment missing");require(samples>16000&&samples<16000*60,"fixture duration");require("invalid_session".equals(json(nativeCall("finish",new Class<?>[]{long.class},handle)).optString("error")),"finished session reused");require(close(handle),"close decoded session");pass("actual Android JNI streaming recognition of synthetic Mandarin; no transcript logged");
            long decodeMillis=SystemClock.elapsedRealtime()-decodeStart;JSONObject report=new JSONObject().put("synthetic",true).put("platform","Android emulator").put("api_level",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]).put("model","x-asr-streaming-int8-2026-06-05").put("configured_cpu_threads",2).put("audio_samples",samples).put("audio_seconds",samples/16000.0).put("recognizer_load_ms",loadMillis).put("decode_with_jni_ms",decodeMillis).put("rtf",decodeMillis/(samples/16.0)).put("recognized_codepoints",text.codePointCount(0,text.length())).put("expected_fragments_passed",true).put("capture","synthetic PCM fed without real-time pacing; not microphone or end-to-end latency");Files.write(new File(fixtures,"native-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            stage="duration and cancellation";handle=allocate();require(start(handle,model).optBoolean("ready"),"native reload");short[] silence=new short[1600];for(int i=0;i<600;i++)require(!accept(handle,silence,silence.length).has("error"),"bounded silence stream");require("audio_limit".equals(accept(handle,new short[1],1).optString("error")),"60 second sample bound ignored");nativeCall("cancel",new Class<?>[]{long.class},handle);require("cancelled".equals(json(nativeCall("finish",new Class<?>[]{long.class},handle)).optString("error")),"decode cancellation ignored");require(close(handle),"close cancelled decode");pass("actual 60 second PCM bound and cancellation");
            stage="microphone lifecycle";Activity activity=startActivitySync(new Intent().setClassName(getTargetContext(),"org.zapime.android.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            capture(false);getUiAutomation().grantRuntimePermission("org.zapime.android",android.Manifest.permission.RECORD_AUDIO);capture(true);if(Build.VERSION.SDK_INT>=29)liveRecordingMonitor();runOnMainSync(activity::finish);
            nativeCall("release",new Class<?>[]{long.class},0L);pass("permission-denied AudioRecord failure and explicitly stopped emulator microphone capture (silence)");
            result.putString("stream","PASS Android offline voice native acceptance\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("stream","FAIL Android voice at "+stage+": "+failure.getClass().getSimpleName()+" "+failure.getMessage()+"\n");finish(Activity.RESULT_CANCELED,result);}
    }
    private void liveRecordingMonitor()throws Exception{
        Class<?> type=Class.forName("org.zapime.android.VoiceRecordingMonitor",true,getTargetContext().getClassLoader());
        Method create=type.getDeclaredMethod("create",AudioRecord.class,Runnable.class),check=type.getDeclaredMethod("check"),close=type.getDeclaredMethod("close");create.setAccessible(true);check.setAccessible(true);close.setAccessible(true);
        int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,6400));Object monitor=null;AtomicBoolean silenced=new AtomicBoolean();short[] pcm=new short[160];
        try{
            require(recorder.getState()==AudioRecord.STATE_INITIALIZED,"live monitor recorder unavailable");monitor=create.invoke(null,recorder,(Runnable)()->silenced.set(true));recorder.startRecording();
            AudioRecordingConfiguration configuration=null;long deadline=SystemClock.elapsedRealtime()+5000;int samples=0;
            while(SystemClock.elapsedRealtime()<deadline){check.invoke(monitor);configuration=recorder.getActiveRecordingConfiguration();int count=recorder.read(pcm,0,pcm.length,AudioRecord.READ_NON_BLOCKING);require(count>=0,"live monitor PCM read");samples+=count;java.util.Arrays.fill(pcm,(short)0);if(configuration!=null&&samples>0)break;Thread.sleep(10);}
            require(configuration!=null&&configuration.getClientAudioSessionId()==recorder.getAudioSessionId(),"missing own active recording configuration");require(!configuration.isClientSilenced()&&!silenced.get(),"foreground test microphone policy unexpectedly silenced");require(samples>0,"no live monitor PCM");
        }finally{java.util.Arrays.fill(pcm,(short)0);try{if(monitor!=null)close.invoke(monitor);}finally{try{recorder.stop();}catch(RuntimeException ignored){}recorder.release();}}
        pass("API29+ production recording monitor with actual own active unsilenced configuration and emulator PCM (silence); no policy override");
    }
    private void capture(boolean permitted)throws Exception{
        ClassLoader loader=getTargetContext().getClassLoader();Class<?> capture=Class.forName("org.zapime.android.VoiceCapture",true,loader),listener=Class.forName("org.zapime.android.VoiceCapture$Listener",true,loader);
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> terminal=new AtomicReference<>();AtomicReference<Object> instance=new AtomicReference<>();AtomicBoolean heard=new AtomicBoolean();
        Method stop=capture.getDeclaredMethod("stop");stop.setAccessible(true);
        Object callback=Proxy.newProxyInstance(loader,new Class<?>[]{listener},(proxy,method,args)->{if(method.getName().equals("update")){String state=(String)args[0];if(state.equals("recording")&&heard.compareAndSet(false,true)){new Handler(Looper.getMainLooper()).postDelayed(()->{try{stop.invoke(instance.get());}catch(Exception e){terminal.set("stop_error");done.countDown();}},350);}if(state.equals("result")||state.equals("empty")||state.equals("error")||state.equals("microphone_error")||state.equals("microphone_silenced")){terminal.set(state);done.countDown();}}return null;});
        Constructor<?> ctor=capture.getDeclaredConstructor(Context.class,listener);ctor.setAccessible(true);Object value=ctor.newInstance(getTargetContext(),callback);instance.set(value);Method start=capture.getDeclaredMethod("start");start.setAccessible(true);start.invoke(value);
        require(done.await(90,TimeUnit.SECONDS),"microphone timeout");Method cancel=capture.getDeclaredMethod("cancel");cancel.setAccessible(true);cancel.invoke(value);
        require(permitted?heard.get()&&(terminal.get().equals("empty")||terminal.get().equals("result")):"microphone_error".equals(terminal.get())&&!heard.get(),"microphone permission/stop state");
    }
}
