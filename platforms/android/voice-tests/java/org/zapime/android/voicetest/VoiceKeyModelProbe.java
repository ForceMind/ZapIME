// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.os.*;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Real official-model SAF import and JNI decoding, only on a fresh dedicated CI emulator. */
final class VoiceKeyModelProbe {
    static final String MODEL="wechat-offline-20260710220526250";
    static final String PACKAGE_SHA="b3729fbe928b25740cc9cd781537175b1db4a445bd78bf464267c0c4633e0863";
    static final String DICT="dict.decoder.utf8.txt", XNET="embed_140m_kld43wh_test_78000_1_merge_4.kv.conv1d.dyQu.onnx.xnet";
    static final Class<?>[] NONE=new Class<?>[0];
    static Object call(Class<?> type,String name,Class<?>[] signature,Object...args)throws Exception{return SyncAccountProbe.call(type,null,name,signature,args);}
    static JSONObject reply(Object bytes)throws Exception{SyncAccountProbe.check(bytes!=null,"VoiceKey null JNI response");return new JSONObject(new String((byte[])bytes,StandardCharsets.UTF_8));}
    static JSONObject ok(Object bytes)throws Exception{JSONObject result=reply(bytes);if(result.has("error")){String code=result.optString("error","");throw new AssertionError("VoiceKey JNI error "+(code.matches("[a-z_]{1,80}")?code:"unclassified_native_error"));}SyncAccountProbe.check("voicekey-wechat".equals(result.getString("engine_id")),"wrong native voice engine");return result;}
    static String sha(byte[] bytes)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder text=new StringBuilder();for(byte b:digest)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
    static String fileSha(File file)throws Exception{MessageDigest h=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)h.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:h.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    static JSONObject textEvidence(String text)throws Exception{return new JSONObject().put("recognized_codepoints",text.codePointCount(0,text.length())).put("hypothesis_sha256",sha(text.getBytes(StandardCharsets.UTF_8)));}
    static List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root){List<AccessibilityNodeInfo> nodes=new ArrayList<>();if(root==null)return nodes;ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();queue.add(root);while(!queue.isEmpty()&&nodes.size()<1000){AccessibilityNodeInfo n=queue.remove();nodes.add(n);for(int k=0;k<n.getChildCount();k++){AccessibilityNodeInfo c=n.getChild(k);if(c!=null)queue.add(c);}}return nodes;}
    static boolean click(AccessibilityNodeInfo node){for(int n=0;n<6&&node!=null;n++,node=node.getParent())if(node.isEnabled()&&node.isClickable())return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);return false;}
    static AccessibilityNodeInfo exactDocument(UiAutomation automation,String filename){
        AccessibilityNodeInfo root=automation.getRootInActiveWindow();if(root==null||!String.valueOf(root.getPackageName()).contains("documentsui"))return null;
        for(AccessibilityNodeInfo n:nodes(root))if(filename.contentEquals(n.getText()==null?"":n.getText())&&n.isVisibleToUser()&&n.isEnabled())return n;return null;
    }
    static void providerShape(UiAutomation automation,File output)throws Exception{
        JSONArray shape=new JSONArray();for(AccessibilityNodeInfo n:nodes(automation.getRootInActiveWindow())){Rect b=new Rect();n.getBoundsInScreen(b);shape.put(new JSONObject().put("class",String.valueOf(n.getClassName())).put("id",String.valueOf(n.getViewIdResourceName())).put("visible",n.isVisibleToUser()).put("enabled",n.isEnabled()).put("clickable",n.isClickable()).put("bounds",b.toShortString()));}
        Files.write(new File(output,"voicekey-provider-ui-shape.json").toPath(),shape.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    static MotionEvent fingerEvent(long down,long when,int action,float x,float y){
        MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=x;coords.y=y;coords.pressure=1;coords.size=1;
        MotionEvent event=MotionEvent.obtain(down,when,action,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        SyncAccountProbe.check(event.getPointerCount()==1&&event.getPointerId(0)==0&&event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER&&event.getSource()==InputDevice.SOURCE_TOUCHSCREEN,"document event must be one real finger touchscreen pointer");return event;
    }
    static void providerScreenshot(UiAutomation automation,File output,String name)throws Exception{
        Bitmap image=automation.takeScreenshot();SyncAccountProbe.check(image!=null,"dedicated CI provider screenshot unavailable");
        try(OutputStream out=new FileOutputStream(new File(output,name))){SyncAccountProbe.check(image.compress(Bitmap.CompressFormat.PNG,100,out),"provider screenshot encoding failed");}finally{image.recycle();}
    }
    static String selectOfficialDocument(UiAutomation automation,String filename,File output,JSONObject report)throws Exception{
        boolean rootsOpened=false,downloadsOpened=false;Rect previous=null;long stableSince=-1;
        for(int attempt=0;attempt<150;attempt++){
            AccessibilityNodeInfo named=exactDocument(automation,filename);
            if(named!=null){Rect now=new Rect();named.getBoundsInScreen(now);
                if(!now.isEmpty()&&now.equals(previous)&&SystemClock.uptimeMillis()-stableSince>=200){
                    // Reacquire immediately before the one physical selection. An
                    // acknowledged down/up is never followed by a blind retry.
                    providerScreenshot(automation,output,"voicekey-provider-before.png");AccessibilityNodeInfo fresh=exactDocument(automation,filename);Rect bounds=new Rect();if(fresh!=null)fresh.getBoundsInScreen(bounds);
                    if(fresh!=null&&bounds.equals(now)){
                        report.put("provider_touch_point",new JSONArray().put(bounds.centerX()).put(bounds.centerY())).put("provider_selected_filename_bounds",new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom)).put("provider_pointer_id",0).put("provider_pointer_count",1).put("provider_tool_type",MotionEvent.TOOL_TYPE_FINGER).put("provider_event_source",InputDevice.SOURCE_TOUCHSCREEN);
                        long when=SystemClock.uptimeMillis();MotionEvent down=fingerEvent(when,when,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY());boolean sent;
                        try{sent=automation.injectInputEvent(down,true);}finally{down.recycle();}SyncAccountProbe.check(sent,"official document physical down rejected");Thread.sleep(50);
                        MotionEvent up=fingerEvent(when,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY());try{sent=automation.injectInputEvent(up,true);}finally{up.recycle();}SyncAccountProbe.check(sent,"official document physical up rejected");report.put("provider_physical_selection_count",1);Thread.sleep(200);providerScreenshot(automation,output,"voicekey-provider-after.png");return "stable_observed_filename_physical_touch";
                    }
                }
                if(previous==null||!previous.equals(now)){previous=now;stableSince=SystemClock.uptimeMillis();}
            }else{
                previous=null;stableSince=-1;AccessibilityNodeInfo root=automation.getRootInActiveWindow();
                if(root!=null&&String.valueOf(root.getPackageName()).contains("documentsui")){
                    List<AccessibilityNodeInfo> visible=nodes(root);
                    if(!downloadsOpened)for(AccessibilityNodeInfo n:visible){String text=String.valueOf(n.getText());if(n.isVisibleToUser()&&(text.equals("Downloads")||text.equals("下载"))&&click(n)){downloadsOpened=true;break;}}
                    if(!downloadsOpened&&!rootsOpened)for(AccessibilityNodeInfo n:visible){String desc=String.valueOf(n.getContentDescription());if(n.isVisibleToUser()&&(desc.equals("Show roots")||desc.equals("显示根目录")||desc.equals("显示导航抽屉"))){SyncAccountProbe.check(click(n),"document roots action rejected");rootsOpened=true;break;}}
                }
            }
            Thread.sleep(200);
        }
        providerShape(automation,output);throw new IOException("official model filename geometry did not stabilize");
    }
    static void awaitActualImport(Instrumentation i,Activity page,Class<?> models,File output,JSONObject report)throws Exception{
        long returning=SystemClock.elapsedRealtime()+30000;boolean providerExited=false;
        while(SystemClock.elapsedRealtime()<returning){
            AccessibilityNodeInfo root=i.getUiAutomation().getRootInActiveWindow();String pkg=root==null?"":String.valueOf(root.getPackageName());
            boolean busy=SyncAccountProbe.main(i,()->(Boolean)SyncAccountProbe.field(page,"busy"));
            if(busy)report.put("import_busy_observed",true);
            if("org.zapime.android".equals(pkg)){providerExited=true;report.put("provider_exit_observed",true);break;}
            Thread.sleep(100);
        }
        if(!providerExited){providerShape(i.getUiAutomation(),output);throw new IOException("official document provider did not return after one selection");}
        long deadline=SystemClock.elapsedRealtime()+180000;
        while(SystemClock.elapsedRealtime()<deadline){
            boolean busy=SyncAccountProbe.main(i,()->(Boolean)SyncAccountProbe.field(page,"busy"));
            if(busy)report.put("import_busy_observed",true);
            String status=SyncAccountProbe.main(i,()->((android.widget.TextView)SyncAccountProbe.field(page,"voiceKeyStatus")).getText().toString());
            if(!busy&&(Boolean)call(models,"installed",new Class<?>[]{Context.class},i.getTargetContext())&&status.startsWith("VoiceKey 模型已校验安装")){report.put("import_complete_status_observed",true);return;}
            if(!busy&&status.startsWith("VoiceKey 导入失败"))throw new IOException("official package SAF import rejected");
            Thread.sleep(100);
        }
        providerShape(i.getUiAutomation(),output);throw new IOException("official package SAF import timeout");
    }
    static long start(Class<?> nativeVoice,File model)throws Exception{
        long h=(Long)call(nativeVoice,"allocate",NONE);SyncAccountProbe.check(h>0,"VoiceKey model allocation");
        try{SyncAccountProbe.check(ok(call(nativeVoice,"start",new Class<?>[]{long.class,byte[].class,byte[].class},h,model.getAbsolutePath().getBytes(StandardCharsets.UTF_8),new byte[0])).optBoolean("ready"),"VoiceKey model not ready");return h;}
        catch(Throwable e){call(nativeVoice,"close",new Class<?>[]{long.class},h);throw e;}
    }
    static JSONObject accept(Class<?> nativeVoice,long h,short[] pcm,int count)throws Exception{
        JSONObject result=ok(call(nativeVoice,"accept",new Class<?>[]{long.class,short[].class,int.class},h,pcm,count));
        SyncAccountProbe.check(result.getBoolean("partial_updated")==result.has("text"),"absent partial must omit text, preserving preview");return result;
    }
    static void emptyCases(Class<?> nativeVoice,File model,JSONArray cases)throws Exception{
        for(String id:new String[]{"no_samples","all_zero","ignored_tail","positive_lsb","negative_lsb","cancelled_empty"}){
            long h=start(nativeVoice,model);boolean nonzero=id.equals("positive_lsb")||id.equals("negative_lsb");int count=0;
            try{
                if(id.equals("cancelled_empty")){call(nativeVoice,"cancel",new Class<?>[]{long.class},h);SyncAccountProbe.check("cancelled".equals(reply(call(nativeVoice,"finish",new Class<?>[]{long.class},h)).optString("error")),"cancelled empty VoiceKey finish succeeded");cases.put(new JSONObject().put("id",id).put("passed",true).put("accepted_samples",0));continue;}
                if(!id.equals("no_samples")){
                    short[] pcm=id.equals("ignored_tail")?new short[]{0,0,17}:id.equals("positive_lsb")?new short[]{1,0,0}:id.equals("negative_lsb")?new short[]{-1,0,0}:new short[1600];
                    count=id.equals("ignored_tail")?2:id.endsWith("lsb")?1:pcm.length;
                    JSONObject partial=accept(nativeVoice,h,pcm,count);SyncAccountProbe.check(partial.getBoolean("has_nonzero_pcm")==nonzero,"VoiceKey valid-prefix nonzero evidence incorrect");if(!nonzero)SyncAccountProbe.check(partial.optString("text","").isEmpty(),"empty VoiceKey partial hallucinated");
                }
                if(id.equals("no_samples"))for(int budget:new int[]{0,30001})SyncAccountProbe.check("invalid_timeout".equals(reply(call(nativeVoice,"finish",new Class<?>[]{long.class,int.class},h,budget)).optString("error")),"live invalid finish budget accepted");
                JSONObject result=ok(call(nativeVoice,"finish",new Class<?>[]{long.class},h));String text=result.getString("text");if(!nonzero)SyncAccountProbe.check(text.isEmpty(),"empty VoiceKey final hallucinated");
                cases.put(textEvidence(text).put("id",id).put("passed",true).put("accepted_samples",count).put("expected_nonzero_pcm",nonzero).put("invalid_finish_budget_session_preserved",id.equals("no_samples")));
            }finally{SyncAccountProbe.check((Boolean)call(nativeVoice,"close",new Class<?>[]{long.class},h),"VoiceKey empty-case worker did not exit");}
        }
        long h=start(nativeVoice,model);
        try{
            short[] valid=new short[1600];valid[0]=17;
            SyncAccountProbe.check(accept(nativeVoice,h,valid,valid.length).getBoolean("has_nonzero_pcm"),"cancel case lacks accepted nonzero PCM");
            AtomicReference<Throwable> error=new AtomicReference<>();
            Thread cancellation=new Thread(()->{try{call(nativeVoice,"cancel",new Class<?>[]{long.class},h);}catch(Throwable e){error.set(e);}},"voicekey-model-cross-thread-cancel");
            cancellation.start();cancellation.join(5000);SyncAccountProbe.check(!cancellation.isAlive()&&error.get()==null,"cross-thread VoiceKey cancellation did not return");
            SyncAccountProbe.check("cancelled".equals(reply(call(nativeVoice,"finish",new Class<?>[]{long.class},h)).optString("error")),"accepted nonzero PCM bypassed cancellation");
        }finally{SyncAccountProbe.check((Boolean)call(nativeVoice,"close",new Class<?>[]{long.class},h),"cancelled real-model worker did not exit");}
        cases.put(new JSONObject().put("id","cancelled_nonzero_cross_thread").put("passed",true).put("accepted_samples",1600).put("physical_worker_exit_confirmed",true).put("scope","Cross-thread cancellation after accepted PCM and actual worker exit; not proof of interrupting every blocking inference instruction."));
    }
    static String independentDecode(Instrumentation i,Class<?> nativeVoice,File model,short[] samples,JSONObject report)throws Exception{
        Class<?> type=Class.forName("org.zapime.android.VoicePcmQueue",true,i.getTargetContext().getClassLoader());java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object queue=ctor.newInstance();
        BlockingQueue<?> backing=(BlockingQueue<?>)SyncAccountProbe.field(queue,"chunks");int capacity=backing.size()+backing.remainingCapacity();SyncAccountProbe.check(capacity==602,"production PCM queue capacity changed");
        AtomicBoolean cancelled=new AtomicBoolean();AtomicReference<Throwable> producerFailure=new AtomicReference<>();AtomicLong eof=new AtomicLong(-1),firstFrame=new AtomicLong(-1),produced=new AtomicLong(),maxLateness=new AtomicLong();
        long handle=start(nativeVoice,model),began=SystemClock.elapsedRealtime();final long owned=handle;Thread producer=new Thread(()->{
            try{
                int offset=0;
                while(offset<samples.length+371&&!cancelled.get()){
                    int count=offset<samples.length?Math.min(1600,samples.length-offset):371;short[] block=offset<samples.length?Arrays.copyOfRange(samples,offset,offset+count):new short[371];
                    long due=began+((offset+count)*1000L+15999)/16000;
                    while(!cancelled.get()){long remaining=due-SystemClock.elapsedRealtime();if(remaining<=0)break;Thread.sleep(Math.min(remaining,20));}
                    if(cancelled.get())break;
                    long now=SystemClock.elapsedRealtime();maxLateness.set(Math.max(maxLateness.get(),now-due));if(firstFrame.get()<0)firstFrame.set(now-began);
                    SyncAccountProbe.check((Boolean)SyncAccountProbe.call(type,queue,"offer",new Class<?>[]{short[].class},(Object)block),"production synthetic PCM queue full");offset+=count;produced.set(offset);
                }
                if(!cancelled.get())eof.set(SystemClock.elapsedRealtime());
            }catch(Throwable error){producerFailure.set(error);cancelled.set(true);try{call(nativeVoice,"cancel",new Class<?>[]{long.class},owned);}catch(Exception ignored){}}
            finally{try{SyncAccountProbe.call(type,queue,"finish",NONE);}catch(Throwable error){producerFailure.compareAndSet(null,error);cancelled.set(true);}}
        },"voicekey-absolute-clock-producer");
        int accepted=0,partials=0;long firstPartial=-1;String preview="";Throwable failed=null;
        report.put("independent_producer",true).put("producer_clock","monotonic_absolute_deadlines").put("production_pcm_queue_reused",true).put("production_pcm_queue_capacity",capacity).put("accepted_samples",0).put("partial_updates",0).put("first_nonempty_partial_ms",JSONObject.NULL).put("trailing_zero_samples",371).put("synthetic_decode_stage","independent paced feed");
        try{
            producer.start();
            while(!cancelled.get()){
                if(eof.get()>0)SyncAccountProbe.check(SystemClock.elapsedRealtime()-eof.get()<30000,"VoiceKey producer EOF deadline exceeded during drain");
                short[] block=(short[])SyncAccountProbe.call(type,queue,"next",new Class<?>[]{AtomicBoolean.class},cancelled);if(block==null)break;
                report.put("last_attempt_offset",accepted);JSONObject partial=accept(nativeVoice,handle,block,block.length);accepted+=block.length;report.put("accepted_samples",accepted);
                if(partial.optBoolean("partial_updated")){preview=partial.getString("text");partials++;if(firstPartial<0&&!preview.isEmpty())firstPartial=SystemClock.elapsedRealtime()-began;}
                report.put("partial_updates",partials).put("first_nonempty_partial_ms",firstPartial<0?JSONObject.NULL:firstPartial).put("last_partial",textEvidence(preview));
            }
            SyncAccountProbe.check(producerFailure.get()==null&&!cancelled.get(),"independent PCM producer failed");producer.join(2000);SyncAccountProbe.check(!producer.isAlive(),"independent PCM producer did not exit");
            SyncAccountProbe.check(produced.get()==samples.length+371&&accepted==produced.get(),"independent PCM samples dropped");SyncAccountProbe.check(firstPartial>=0,"VoiceKey supplied no nonempty streaming preview before finish");
            long drain=SystemClock.elapsedRealtime()-eof.get(),remaining=30000-drain;SyncAccountProbe.check(eof.get()>0&&remaining>0,"VoiceKey producer EOF final deadline exhausted");report.put("remaining_final_timeout_ms",remaining).put("drain_after_producer_eof_ms",drain).put("synthetic_decode_stage","native final");
            long finishing=SystemClock.elapsedRealtime();String decoded=ok(call(nativeVoice,"finish",new Class<?>[]{long.class,int.class},handle,(int)remaining)).getString("text");SyncAccountProbe.check(SystemClock.elapsedRealtime()-eof.get()<=30000,"VoiceKey final exceeded shared producer EOF deadline");SyncAccountProbe.check(!decoded.trim().isEmpty(),"VoiceKey synthetic fixture decoded empty");
            long finishedAt=SystemClock.elapsedRealtime();report.put("model_decode_executed",true).put("result",textEvidence(decoded)).put("finish_call_ms",finishedAt-finishing).put("stop_to_final_ms",finishedAt-eof.get()).put("producer_eof_to_final_ms",finishedAt-eof.get()).put("paced_feed_and_finish_ms",finishedAt-began);return decoded;
        }catch(Throwable error){failed=error;throw error;}
        finally{
            cancelled.set(true);try{call(nativeVoice,"cancel",new Class<?>[]{long.class},handle);}catch(Exception ignored){}producer.interrupt();boolean interrupted=false;long joinUntil=SystemClock.elapsedRealtime()+2000;
            while(producer.isAlive()&&SystemClock.elapsedRealtime()<joinUntil)try{producer.join(Math.min(100,Math.max(1,joinUntil-SystemClock.elapsedRealtime())));}catch(InterruptedException error){interrupted=true;}
            Throwable cleanup=null;
            if(producer.isAlive())cleanup=new AssertionError("independent PCM producer cleanup timeout");
            try{SyncAccountProbe.check((Boolean)call(nativeVoice,"close",new Class<?>[]{long.class},handle),"independent decode worker did not exit");}catch(Throwable error){if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error);}
            try{SyncAccountProbe.call(type,queue,"clear",NONE);}catch(Throwable error){if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error);}
            report.put("produced_samples",produced.get()).put("producer_first_frame_ms",firstFrame.get()).put("producer_epoch_elapsed_realtime_ms",began).put("producer_eof_elapsed_realtime_ms",eof.get()<0?JSONObject.NULL:eof.get()).put("producer_eof_elapsed_ms",eof.get()<0?JSONObject.NULL:eof.get()-began).put("producer_max_lateness_ms",maxLateness.get()).put("producer_failure",producerFailure.get()!=null).put("producer_exited",!producer.isAlive()).put("producer_cleanup_completed",cleanup==null);
            if(interrupted)Thread.currentThread().interrupt();
            if(cleanup!=null){if(failed!=null)failed.addSuppressed(cleanup);else throw new Exception("independent producer cleanup failed",cleanup);}
        }
    }
    static void run(Instrumentation i,File output,String packageFilename)throws Exception{
        SyncAccountProbe.check(packageFilename!=null&&packageFilename.matches("ZapIME-VoiceKey-[0-9]+-[0-9]+\\.apk"),"dedicated numeric CI official package required");
        Context context=i.getTargetContext();ClassLoader loader=context.getClassLoader();Class<?> models=Class.forName("org.zapime.android.VoiceKeyModels",true,loader),xasr=Class.forName("org.zapime.android.VoiceModels",true,loader),prefs=Class.forName("org.zapime.android.VoiceEnginePreferences",true,loader);
        JSONArray cases=new JSONArray();JSONObject report=new JSONObject().put("schema_version",1).put("status","FAIL").put("engine_id","voicekey").put("native_engine_id","voicekey-wechat").put("model_id",MODEL).put("model_package_sha256",PACKAGE_SHA).put("api_level",Build.VERSION.SDK_INT).put("synthetic",true).put("model_decode_executed",false).put("cases",cases).put("scope","Real system ACTION_OPEN_DOCUMENT import into clean dedicated CI profile, selected VoiceKey model only, actual JNI synthetic PCM, and separate real Editable/production VoiceCommitTransaction probe. Not natural speech, phone/ARM performance or physical confirmation in a system IME. Separate UI instrumentation covers emulator AudioRecord/IME lifecycle.");
        Activity activity=null;Class<?> nativeVoice=null;long h=0;String stage="fresh model-only profile";Throwable failure=null;
        try{
            SyncAccountProbe.check(!(Boolean)call(xasr,"installed",new Class<?>[]{Context.class},context)&&!(Boolean)call(xasr,"recoverable",new Class<?>[]{Context.class},context),"VoiceKey-only test requires fresh CI profile without X-ASR");
            SyncAccountProbe.check(!(Boolean)call(models,"installed",new Class<?>[]{Context.class},context)&&!(Boolean)call(models,"recoverable",new Class<?>[]{Context.class},context),"VoiceKey model must be imported through actual SAF first");SyncAccountProbe.passed(cases,"fresh_voicekey_only_profile");
            stage="system SAF import";UiAutomation automation=i.getUiAutomation();AccessibilityServiceInfo info=automation.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;automation.setServiceInfo(info);
            activity=i.startActivitySync(new Intent().setClassName(context,"org.zapime.android.VoiceSettingsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity page=activity;
            stage="SAF launch actual settings control";SyncAccountProbe.main(i,()->{SyncAccountProbe.check(((Button)SyncAccountProbe.field(page,"chooseVoiceKey")).performClick(),"actual VoiceKey import control rejected");return null;});
            stage="SAF observed official filename selection";report.put("provider_selection_method",selectOfficialDocument(automation,packageFilename,output,report));
            stage="SAF import completion";
            awaitActualImport(i,page,models,output,report);
            stage="SAF imported model fixed verification";File model=(File)call(models,"verified",new Class<?>[]{Context.class,BooleanSupplier.class},context,(BooleanSupplier)()->false);SyncAccountProbe.check(model.getName().equals(MODEL),"wrong immutable VoiceKey model version");
            SyncAccountProbe.check(new File(model,DICT).length()==129706&&fileSha(new File(model,DICT)).equals("08a16004cfcf5b9f1668e055f3100de9b8a44f88541819def2bbd56bfd42f484"),"VoiceKey dictionary differs");
            SyncAccountProbe.check(new File(model,XNET).length()==135646496&&fileSha(new File(model,XNET)).equals("0674250a848ce3eb91024b3c0a6eda61b91ea45b2c40e4900130995e89cb9033"),"VoiceKey network differs");SyncAccountProbe.passed(cases,"actual_saf_fixed_model_import");
            stage="actual engine setting";SyncAccountProbe.main(i,()->{Button use=(Button)SyncAccountProbe.field(page,"useVoiceKey");SyncAccountProbe.check(use.isEnabled()&&use.performClick(),"VoiceKey selection disabled");return null;});
            for(int n=0;n<600;n++){if("voicekey".equals(call(prefs,"selected",new Class<?>[]{Context.class},context)))break;if(n==599)throw new IOException("VoiceKey selection did not complete");Thread.sleep(100);}
            SyncAccountProbe.check(!(Boolean)call(xasr,"installed",new Class<?>[]{Context.class},context)&&!(Boolean)call(xasr,"recoverable",new Class<?>[]{Context.class},context),"X-ASR unexpectedly installed");report.put("selected_engine","voicekey").put("xasr_model_present",false);SyncAccountProbe.passed(cases,"voicekey_selected_without_xasr");
            stage="JNI PCM evidence";nativeVoice=Class.forName("org.zapime.android.NativeVoiceKey",true,loader);emptyCases(nativeVoice,model,cases);
            stage="actual Android stop watchdog idle-worker cancellation";VoiceKeyWatchdogProbe.run(i,nativeVoice,model,cases,output);
            stage="actual synthetic decode";byte[] pcm;try(InputStream input=i.getContext().getAssets().open("voice-fixtures/synthetic.pcm");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){SyncAccountProbe.check(bytes.size()+n<=1920000,"synthetic PCM exceeded bound");bytes.write(b,0,n);}pcm=bytes.toByteArray();}
            SyncAccountProbe.check(pcm.length>0&&pcm.length%2==0,"synthetic PCM missing");short[] samples=new short[pcm.length/2];ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);report.put("fixture_sha256",sha(pcm)).put("audio_samples",samples.length);
            stage="synthetic independent producer decode";String decoded=independentDecode(i,nativeVoice,model,samples,report);SyncAccountProbe.passed(cases,"actual_jni_synthetic_decode_and_tail");
            stage="actual confirmation transaction";VoiceCommitProbe.run(i,decoded,output);SyncAccountProbe.passed(cases,"actual_editable_production_confirmation");
            SyncAccountProbe.check((Boolean)call(nativeVoice,"releaseCache",NONE),"VoiceKey model cache still busy after all sessions");SyncAccountProbe.passed(cases,"idle_release_after_real_model");report.put("status","PASS");
        }catch(Throwable e){failure=e;Throwable cause=e;for(int depth=0;depth<5&&cause.getCause()!=null;depth++)cause=cause.getCause();JSONObject failed=new JSONObject().put("id",stage).put("passed",false).put("failure_type",cause.getClass().getSimpleName());String reason=cause.getMessage();if((cause instanceof AssertionError||cause instanceof IOException)&&reason!=null&&reason.matches("[A-Za-z0-9 _+.,();-]{1,160}")){failed.put("failure_reason",reason);if(reason.startsWith("VoiceKey JNI error "))report.put("native_error_code",reason.substring(19));}cases.put(failed);throw new Exception("VoiceKey official-model acceptance: "+stage,e);}
        finally{
            Throwable cleanup=null;
            try{if(h!=0&&nativeVoice!=null)SyncAccountProbe.check((Boolean)call(nativeVoice,"close",new Class<?>[]{long.class},h),"VoiceKey failed decode worker did not exit");}catch(Throwable e){cleanup=e;}
            try{if(activity!=null){final Activity page=activity;SyncAccountProbe.main(i,()->{page.finish();return null;});i.waitForIdleSync();}}catch(Throwable e){if(cleanup==null)cleanup=e;else cleanup.addSuppressed(e);}
            report.put("cleanup_completed",cleanup==null&&report.optBoolean("producer_cleanup_completed",true)).put("voicekey_selected_for_following_ime_probe","voicekey".equals(call(prefs,"selected",new Class<?>[]{Context.class},context)));
            if(cleanup!=null){report.put("status","FAIL").put("cleanup_failure_type",cleanup.getClass().getSimpleName());if(failure!=null)failure.addSuppressed(cleanup);}
            Files.write(new File(output,"voicekey-model-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));if(cleanup!=null&&failure==null)throw new Exception("VoiceKey model test cleanup failed",cleanup);
        }
    }
    private VoiceKeyModelProbe(){}
}
