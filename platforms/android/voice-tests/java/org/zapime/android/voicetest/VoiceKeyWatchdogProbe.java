// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual Android timer and native idle worker; capture state is controlled explicitly. */
final class VoiceKeyWatchdogProbe {
    private static final Class<?>[] NONE=new Class<?>[0];
    private static void set(Object owner,String name,Object value)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    static void run(Instrumentation instrumentation,Class<?> nativeVoice,File model,JSONArray cases,File output)throws Exception{
        JSONObject evidence=new JSONObject().put("id","actual_android_stop_watchdog").put("passed",false)
                .put("synthetic",true).put("engine_id","voicekey").put("native_engine_id","voicekey-wechat")
                .put("model_id",VoiceKeyModelProbe.MODEL).put("model_package_sha256",VoiceKeyModelProbe.PACKAGE_SHA)
                .put("scope","Controlled VoiceCapture handle/recording fields, actual production stop() and 30-second Android main Handler timer, plus real model-backed native idle-worker cancellation/close. No capture.run(), AudioRecord, active matrix inference, system IME or natural speech.")
                .put("controlled_capture_state",true).put("real_android_timer",true).put("audio_record_executed",false)
                .put("matrix_inference_executed",false).put("system_ime_executed",false);
        AtomicReference<Object> capture=new AtomicReference<>();
        AtomicReference<Throwable> callbackError=new AtomicReference<>();
        AtomicInteger callbacks=new AtomicInteger();AtomicLong stoppedAt=new AtomicLong(-1),callbackElapsed=new AtomicLong(-1);
        CountDownLatch timedOut=new CountDownLatch(1);long handle=0;Throwable failure=null;
        try{
            ClassLoader loader=instrumentation.getTargetContext().getClassLoader();
            Class<?> captureType=Class.forName("org.zapime.android.VoiceCapture",true,loader);
            Class<?> engineType=Class.forName("org.zapime.android.VoiceEngineSession",true,loader);
            Class<?> listenerType=Class.forName("org.zapime.android.VoiceCapture$Listener",true,loader);
            Constructor<?> engineConstructor=engineType.getDeclaredConstructor(String.class);engineConstructor.setAccessible(true);
            Constructor<?> captureConstructor=captureType.getDeclaredConstructor(Context.class,engineType,listenerType);captureConstructor.setAccessible(true);
            Object listener=Proxy.newProxyInstance(loader,new Class<?>[]{listenerType},(proxy,method,args)->{
                if(method.getName().equals("update")){
                    try{
                        callbackElapsed.set(SystemClock.elapsedRealtime()-stoppedAt.get());callbacks.incrementAndGet();
                        SyncAccountProbe.check(Looper.myLooper()==Looper.getMainLooper(),"watchdog callback left Android main thread");
                        SyncAccountProbe.check("timeout".equals(args[0])&&"".equals(args[1]),"watchdog published a non-timeout or nonempty result");
                    }catch(Throwable error){callbackError.set(error);}finally{timedOut.countDown();}
                }
                return null;
            });
            handle=VoiceKeyModelProbe.start(nativeVoice,model);final long ownedHandle=handle;
            evidence.put("real_model_native_session_started",true);
            SyncAccountProbe.main(instrumentation,()->{
                Object value=captureConstructor.newInstance(instrumentation.getTargetContext(),engineConstructor.newInstance("voicekey"),listener);
                capture.set(value);set(value,"handle",ownedHandle);set(value,"recording",true);
                stoppedAt.set(SystemClock.elapsedRealtime());
                SyncAccountProbe.call(captureType,value,"stop",NONE);
                // Idempotence must not arm another timer or reset the deadline.
                SyncAccountProbe.call(captureType,value,"stop",NONE);return null;
            });
            SyncAccountProbe.check(timedOut.await(40,TimeUnit.SECONDS),"actual Android stop watchdog did not fire within 40 seconds");
            if(callbackError.get()!=null)throw new Exception("actual watchdog callback failed",callbackError.get());
            SyncAccountProbe.check(callbacks.get()==1,"actual stop watchdog emitted repeated callbacks");
            SyncAccountProbe.check(callbackElapsed.get()>=30000&&callbackElapsed.get()<=40000,"actual watchdog elapsed time outside contract");
            int remaining=SyncAccountProbe.main(instrumentation,()->{
                Object budget=SyncAccountProbe.field(capture.get(),"stopBudget");
                return (Integer)SyncAccountProbe.call(budget.getClass(),budget,"remainingMillis",NONE);
            });
            SyncAccountProbe.check(remaining==0,"actual stop budget did not expire");
            JSONObject rejected=VoiceKeyModelProbe.reply(VoiceKeyModelProbe.call(nativeVoice,"finish",new Class<?>[]{long.class},handle));
            SyncAccountProbe.check("cancelled".equals(rejected.optString("error")),"actual watchdog did not cancel the real native session");
            evidence.put("callback_elapsed_ms",callbackElapsed.get()).put("remaining_budget_ms",remaining)
                    .put("callback_on_main_thread",true).put("callback_count",callbacks.get())
                    .put("timeout_result_empty",true).put("native_finish_rejected_cancelled",true);
        }catch(Throwable error){failure=error;evidence.put("failure_type",error.getClass().getSimpleName());}
        finally{
            Throwable cleanup=null;
            try{
                if(capture.get()!=null)SyncAccountProbe.main(instrumentation,()->{
                    Object value=capture.get();SyncAccountProbe.call(value.getClass(),value,"cancel",NONE);
                    set(value,"handle",0L);set(value,"recording",false);return null;
                });
            }catch(Throwable error){cleanup=error;}
            try{
                if(handle!=0){
                    boolean closed=(Boolean)VoiceKeyModelProbe.call(nativeVoice,"close",new Class<?>[]{long.class},handle);
                    SyncAccountProbe.check(closed,"watchdog native idle worker did not physically exit");
                    evidence.put("physical_idle_worker_exit_confirmed",true);
                }
            }catch(Throwable error){if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error);}
            evidence.put("cleanup_completed",cleanup==null);
            if(cleanup!=null){evidence.put("cleanup_failure_type",cleanup.getClass().getSimpleName());if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
            evidence.put("passed",failure==null);cases.put(evidence);
            Files.write(new File(output,"voicekey-watchdog-report.json").toPath(),evidence.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        if(failure!=null)throw new Exception("actual Android VoiceKey stop watchdog acceptance",failure);
    }
    private VoiceKeyWatchdogProbe(){}
}
