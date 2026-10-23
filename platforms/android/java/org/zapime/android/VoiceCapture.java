// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.media.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** PCM lives only in this bounded in-memory queue. Native ASR has exactly one owner. */
final class VoiceCapture {
    interface Listener {void update(String state,String text);}
    private static final int CHUNK=1600,MAX_SAMPLES=16000*60;
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled=new AtomicBoolean(),stopping=new AtomicBoolean();
    private final VoicePcmQueue audio=new VoicePcmQueue();
    private volatile long handle;
    private volatile String captureFailure;
    private volatile boolean recording;
    private long lastPartial;
    private boolean loaded;
    VoiceCapture(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}
    void start(){VoiceWorker.EXECUTOR.execute(this::run);}
    void stop(){if(recording)stopping.set(true);else cancel();}
    void cancel(){cancelled.set(true);stopping.set(true);long id=handle;if(id!=0)NativeVoice.cancel(id);}
    private void update(String state,String text){main.post(()->{if(!cancelled.get()&&(captureFailure==null||state.startsWith("microphone_")))listener.update(state,text);});}
    private synchronized void captureFailed(String reason){
        if(cancelled.get()||captureFailure!=null)return;
        captureFailure=reason;stopping.set(true);
        long id=handle;if(id!=0)NativeVoice.cancel(id);
    }
    private void checkCapture()throws IOException{if(captureFailure!=null)throw new IOException("microphone_failure");}
    private JSONObject result(byte[] bytes)throws Exception {
        JSONObject result=NativeVoice.result(bytes);if(result.has("error"))throw new IOException("voice_failure");return result;
    }
    private void run(){
        Thread capture=null;
        try {
            if(cancelled.get())return;update("loading","");
            File model=VoiceModels.verified(context,cancelled::get);if(cancelled.get())return;
            handle=NativeVoice.allocate();loaded=true;VoiceWorker.nativeLoaded=true;if(handle==0)throw new IOException("voice_capacity");
            if(cancelled.get()){NativeVoice.cancel(handle);return;}
            result(NativeVoice.start(handle,model.getAbsolutePath().getBytes(StandardCharsets.UTF_8),new File(context.getApplicationInfo().nativeLibraryDir,"libsherpa-onnx-c-api.so").getAbsolutePath().getBytes(StandardCharsets.UTF_8)));
            if(cancelled.get())return;
            capture=new Thread(this::capture,"zapime-microphone");capture.start();
            while(!cancelled.get()){
                short[] chunk=audio.next(cancelled);
                if(chunk==null)break;
                try{checkCapture();String partial=result(NativeVoice.accept(handle,chunk,chunk.length)).optString("text");long now=SystemClock.uptimeMillis();if(now-lastPartial>=100){lastPartial=now;update("recording",partial);}}finally{Arrays.fill(chunk,(short)0);}
            }
            if(cancelled.get())return;checkCapture();
            update("decoding","");String text=result(NativeVoice.finish(handle)).getString("text");
            checkCapture();
            if(!cancelled.get())update(text.trim().isEmpty()?"empty":"result",text);
        } catch(Exception|LinkageError failure){if(!cancelled.get())update(captureFailure!=null?captureFailure:"error","");}
        finally {
            stopping.set(true);
            if(capture!=null){boolean interrupted=false;while(capture.isAlive())try{capture.join(200);}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
            audio.clear();
            long id=handle;if(id!=0){NativeVoice.close(id);handle=0;}
            if(loaded)VoiceWorker.EXECUTOR.schedule(()->NativeVoice.release(120000),121,TimeUnit.SECONDS);
        }
    }
    private void capture(){
        AudioRecord recorder=null;VoiceRecordingMonitor monitor=null;short[] block=new short[CHUNK];int filled=0,total=0;long progress=SystemClock.uptimeMillis();
        try {
            int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)throw new IOException();
            recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,CHUNK*4));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED||cancelled.get())throw new IOException();
            monitor=VoiceRecordingMonitor.create(recorder,()->captureFailed("microphone_silenced"));
            recorder.startRecording();if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException();
            monitor.check();checkCapture();recording=true;long started=SystemClock.elapsedRealtime(),checked=started;update("recording","");
            while(!stopping.get()&&!cancelled.get()&&total<MAX_SAMPLES&&SystemClock.elapsedRealtime()-started<60000){
                // Initial configuration may be unavailable until capture is active.
                // Poll only on the capture thread, also covering delayed callbacks.
                long now=SystemClock.elapsedRealtime();if(now-checked>=500){checked=now;monitor.check();checkCapture();}
                int count=recorder.read(block,filled,Math.min(CHUNK-filled,MAX_SAMPLES-total),AudioRecord.READ_NON_BLOCKING);
                if(count<0)throw new IOException();
                if(count==0){if(SystemClock.uptimeMillis()-progress>3000)throw new IOException();Thread.sleep(10);continue;}
                progress=SystemClock.uptimeMillis();filled+=count;total+=count;
                if(filled==CHUNK){if(!audio.offer(block))throw new IOException();block=new short[CHUNK];filled=0;}
            }
            if(filled>0&&!cancelled.get()&&captureFailure==null&&!audio.offer(Arrays.copyOf(block,filled)))throw new IOException();
        } catch(Exception failure){captureFailed("microphone_error");}
        finally {
            recording=false;Arrays.fill(block,(short)0);
            if(monitor!=null)try{monitor.close();}catch(RuntimeException failure){captureFailed("microphone_error");}
            if(recorder!=null){try{recorder.stop();}catch(RuntimeException ignored){}try{recorder.release();}catch(RuntimeException failure){captureFailed("microphone_error");}}
            audio.finish();
        }
    }
}
