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
    private final AtomicBoolean cancelled=new AtomicBoolean(),stopping=new AtomicBoolean(),captured=new AtomicBoolean();
    private final ArrayBlockingQueue<short[]> audio=new ArrayBlockingQueue<>(602);
    private volatile long handle;
    private volatile String captureFailure;
    private volatile boolean recording;
    private long lastPartial;
    private boolean loaded;
    VoiceCapture(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}
    void start(){VoiceWorker.EXECUTOR.execute(this::run);}
    void stop(){if(recording)stopping.set(true);else cancel();}
    void cancel(){cancelled.set(true);stopping.set(true);long id=handle;if(id!=0)NativeVoice.cancel(id);}
    private void update(String state,String text){main.post(()->{if(!cancelled.get())listener.update(state,text);});}
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
                short[] chunk=audio.poll(100,TimeUnit.MILLISECONDS);
                if(chunk!=null){try{String partial=result(NativeVoice.accept(handle,chunk,chunk.length)).optString("text");long now=SystemClock.uptimeMillis();if(now-lastPartial>=100){lastPartial=now;update("recording",partial);}}finally{Arrays.fill(chunk,(short)0);}}
                else if(captured.get())break;
            }
            if(cancelled.get())return;if(captureFailure!=null)throw new IOException("microphone_failure");
            update("decoding","");String text=result(NativeVoice.finish(handle)).getString("text");
            if(!cancelled.get())update(text.trim().isEmpty()?"empty":"result",text);
        } catch(Exception|LinkageError failure){if(!cancelled.get())update(captureFailure!=null?"microphone_error":"error","");}
        finally {
            stopping.set(true);
            if(capture!=null){boolean interrupted=false;while(capture.isAlive())try{capture.join(200);}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
            short[] pending;while((pending=audio.poll())!=null)Arrays.fill(pending,(short)0);
            long id=handle;if(id!=0){NativeVoice.close(id);handle=0;}
            if(loaded)VoiceWorker.EXECUTOR.schedule(()->NativeVoice.release(120000),121,TimeUnit.SECONDS);
        }
    }
    private void capture(){
        AudioRecord recorder=null;short[] block=new short[CHUNK];int filled=0,total=0;long progress=SystemClock.uptimeMillis();
        try {
            int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)throw new IOException();
            recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,CHUNK*4));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED||cancelled.get())throw new IOException();
            recorder.startRecording();if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException();
            recording=true;long started=SystemClock.elapsedRealtime();update("recording","");
            while(!stopping.get()&&!cancelled.get()&&total<MAX_SAMPLES&&SystemClock.elapsedRealtime()-started<60000){
                int count=recorder.read(block,filled,Math.min(CHUNK-filled,MAX_SAMPLES-total),AudioRecord.READ_NON_BLOCKING);
                if(count<0)throw new IOException();
                if(count==0){if(SystemClock.uptimeMillis()-progress>3000)throw new IOException();Thread.sleep(10);continue;}
                progress=SystemClock.uptimeMillis();filled+=count;total+=count;
                if(filled==CHUNK){if(!audio.offer(block))throw new IOException();block=new short[CHUNK];filled=0;}
            }
            if(filled>0&&!cancelled.get()&&!audio.offer(Arrays.copyOf(block,filled)))throw new IOException();
        } catch(Exception failure){if(!cancelled.get())captureFailure="microphone_unavailable";}
        finally {
            recording=false;Arrays.fill(block,(short)0);
            if(recorder!=null){try{recorder.stop();}catch(RuntimeException ignored){}try{recorder.release();}catch(RuntimeException failure){if(!cancelled.get())captureFailure="microphone_unavailable";}}
            captured.set(true);
        }
    }
}
