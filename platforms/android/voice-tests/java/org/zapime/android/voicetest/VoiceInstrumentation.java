// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;
import android.app.*;
import android.content.*;
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
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            nativeVoice=Class.forName("org.zapime.android.NativeVoice",true,getTargetContext().getClassLoader());models=Class.forName("org.zapime.android.VoiceModels",true,getTargetContext().getClassLoader());
            fixtures=new File(getTargetContext().getExternalFilesDir(null),"voice-fixtures");
            stage="model boundary";rejectImport("../tokens.txt");rejectImport("msime-model.json");rejectImport("tokens.txt");
            try(InputStream in=new FileInputStream(new File(fixtures,"model.zip"))){importZip(in,()->false);}File model=verified();
            byte[] manifest=Files.readAllBytes(new File(model,"msime-model.json").toPath());rejectImport("tokens.txt");require(verified().equals(model),"invalid import replaced model");
            boolean cancelled=false;try(InputStream in=new FileInputStream(new File(fixtures,"model.zip"))){try{importZip(in,()->true);}catch(InvocationTargetException e){cancelled=true;}}require(cancelled,"cancelled import accepted");require(verified().equals(model),"cancel damaged installed model");
            Files.write(new File(model,"msime-model.json").toPath(),bytes("{}"));boolean tampered=false;try{verified();}catch(InvocationTargetException e){tampered=true;}finally{Files.write(new File(model,"msime-model.json").toPath(),manifest);}require(tampered,"tampered manifest accepted");verified();pass("verified import, traversal/config/checksum/cancellation rejection and preservation");
            stage="native ownership";long[] handles=new long[4];for(int i=0;i<4;i++){handles[i]=allocate();require(handles[i]!=0,"allocation");}require(allocate()==0,"unbounded handles");for(long h:handles)require(close(h),"close allocated handle");
            long handle=allocate();final long ownerHandle=handle;AtomicReference<Throwable> wrongFailure=new AtomicReference<>();Thread wrong=new Thread(()->{try{require(!close(ownerHandle),"wrong owner closed handle");require("invalid_owner".equals(accept(ownerHandle,new short[1],1).optString("error")),"wrong owner accepted PCM");}catch(Throwable e){wrongFailure.set(e);}});wrong.start();wrong.join();require(wrongFailure.get()==null,"wrong-owner boundary");
            require(start(handle,new File(getTargetContext().getFilesDir(),"missing-model")).has("error"),"missing model accepted");nativeCall("cancel",new Class<?>[]{long.class},handle);require("cancelled".equals(start(handle,model).optString("error")),"cancel before load ignored");require(close(handle)&&!close(handle),"stale handle close");pass("bounded handles, wrong-thread/stale ownership and load cancellation");
            stage="real native synthetic recognition";handle=allocate();require(model.getAbsolutePath().equals(model.getCanonicalPath()),"model path not canonical");JSONObject started=start(handle,model);require(started.optBoolean("ready"),"native model load: "+started.optString("error"));require("invalid_audio".equals(accept(handle,new short[1601],1601).optString("error")),"oversized PCM accepted");require("invalid_audio".equals(accept(handle,new short[1],2).optString("error")),"PCM count overrun accepted");
            int samples=0;try(InputStream in=new FileInputStream(new File(fixtures,"synthetic.pcm"))){byte[] buffer=new byte[3200];int n;while((n=in.read(buffer))!=-1){require(n>0&&n%2==0,"invalid synthetic PCM");short[] pcm=new short[n/2];ByteBuffer.wrap(buffer,0,n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);require(!accept(handle,pcm,pcm.length).has("error"),"native stream decode");samples+=pcm.length;}}
            String text=json(nativeCall("finish",new Class<?>[]{long.class},handle)).getString("text");for(String fragment:new String[]{"你好","离线语音输入测试","三点开会"})require(text.contains(fragment),"synthetic recognition fragment missing");require(samples>16000&&samples<16000*60,"fixture duration");require("invalid_session".equals(json(nativeCall("finish",new Class<?>[]{long.class},handle)).optString("error")),"finished session reused");require(close(handle),"close decoded session");pass("actual Android JNI streaming recognition of synthetic Mandarin; no transcript logged");
            stage="duration and cancellation";handle=allocate();require(start(handle,model).optBoolean("ready"),"native reload");short[] silence=new short[1600];for(int i=0;i<600;i++)require(!accept(handle,silence,silence.length).has("error"),"bounded silence stream");require("audio_limit".equals(accept(handle,new short[1],1).optString("error")),"60 second sample bound ignored");nativeCall("cancel",new Class<?>[]{long.class},handle);require("cancelled".equals(json(nativeCall("finish",new Class<?>[]{long.class},handle)).optString("error")),"decode cancellation ignored");require(close(handle),"close cancelled decode");pass("actual 60 second PCM bound and cancellation");
            stage="microphone lifecycle";Activity activity=startActivitySync(new Intent().setClassName(getTargetContext(),"org.zapime.android.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            capture(false);getUiAutomation().grantRuntimePermission("org.zapime.android",android.Manifest.permission.RECORD_AUDIO);capture(true);runOnMainSync(activity::finish);
            nativeCall("release",new Class<?>[]{long.class},0L);pass("permission-denied AudioRecord failure and explicitly stopped emulator microphone capture (silence)");
            result.putString("stream","PASS Android offline voice native acceptance\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("stream","FAIL Android voice at "+stage+": "+failure.getClass().getSimpleName()+" "+failure.getMessage()+"\n");finish(Activity.RESULT_CANCELED,result);}
    }
    private void capture(boolean permitted)throws Exception{
        ClassLoader loader=getTargetContext().getClassLoader();Class<?> capture=Class.forName("org.zapime.android.VoiceCapture",true,loader),listener=Class.forName("org.zapime.android.VoiceCapture$Listener",true,loader);
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> terminal=new AtomicReference<>();AtomicReference<Object> instance=new AtomicReference<>();AtomicBoolean heard=new AtomicBoolean();
        Method stop=capture.getDeclaredMethod("stop");stop.setAccessible(true);
        Object callback=Proxy.newProxyInstance(loader,new Class<?>[]{listener},(proxy,method,args)->{if(method.getName().equals("update")){String state=(String)args[0];if(state.equals("recording")&&heard.compareAndSet(false,true)){new Handler(Looper.getMainLooper()).postDelayed(()->{try{stop.invoke(instance.get());}catch(Exception e){terminal.set("stop_error");done.countDown();}},350);}if(state.equals("result")||state.equals("empty")||state.equals("error")||state.equals("microphone_error")){terminal.set(state);done.countDown();}}return null;});
        Constructor<?> ctor=capture.getDeclaredConstructor(Context.class,listener);ctor.setAccessible(true);Object value=ctor.newInstance(getTargetContext(),callback);instance.set(value);Method start=capture.getDeclaredMethod("start");start.setAccessible(true);start.invoke(value);
        require(done.await(90,TimeUnit.SECONDS),"microphone timeout");Method cancel=capture.getDeclaredMethod("cancel");cancel.setAccessible(true);cancel.invoke(value);
        require(permitted?heard.get()&&(terminal.get().equals("empty")||terminal.get().equals("result")):"microphone_error".equals(terminal.get())&&!heard.get(),"microphone permission/stop state");
    }
}
