// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.content.*;
import android.graphics.Rect;
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
import java.util.concurrent.atomic.AtomicReference;
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
    static String selectDocumentOnce(UiAutomation automation,AccessibilityNodeInfo filename)throws Exception{
        SyncAccountProbe.check(filename.isVisibleToUser()&&filename.isEnabled(),"official filename not visible and enabled");
        AccessibilityNodeInfo target=filename;
        for(int depth=0;depth<6&&target!=null;depth++,target=target.getParent())if(target.isEnabled()&&target.isClickable()){
            SyncAccountProbe.check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK),"official document accessibility action rejected");return "accessibility_action";
        }
        // API28 DocumentsUI uses a RecyclerView gesture handler: its real file
        // item may expose no clickable ancestor. Touch the freshly observed
        // filename once rather than manufacturing a provider URI or bypassing SAF.
        Rect bounds=new Rect();filename.getBoundsInScreen(bounds);SyncAccountProbe.check(!bounds.isEmpty(),"official filename has no visible bounds");
        long when=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(when,when,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0);down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean sent;try{sent=automation.injectInputEvent(down,true);}finally{down.recycle();}SyncAccountProbe.check(sent,"official document physical down rejected");Thread.sleep(50);
        MotionEvent up=MotionEvent.obtain(when,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{sent=automation.injectInputEvent(up,true);}finally{up.recycle();}SyncAccountProbe.check(sent,"official document physical up rejected");return "touch_observed_filename";
    }
    static String selectOfficialDocument(UiAutomation automation,String filename,File output)throws Exception{
        boolean rootsOpened=false,downloadsOpened=false;JSONArray shape=new JSONArray();
        for(int attempt=0;attempt<300;attempt++){
            AccessibilityNodeInfo root=automation.getRootInActiveWindow();
            if(root!=null&&String.valueOf(root.getPackageName()).contains("documentsui")){
                List<AccessibilityNodeInfo> nodes=nodes(root);
                for(AccessibilityNodeInfo n:nodes)if(filename.contentEquals(n.getText()==null?"":n.getText())&&n.isVisibleToUser())return selectDocumentOnce(automation,n);
                // Navigate only controls observed in the real provider tree. Once
                // an action is acknowledged, never repeat it as a blind retry.
                if(!downloadsOpened){for(AccessibilityNodeInfo n:nodes){String text=String.valueOf(n.getText());if(text.equals("Downloads")||text.equals("下载")){if(click(n)){downloadsOpened=true;break;}}}}
                if(!downloadsOpened&&!rootsOpened){for(AccessibilityNodeInfo n:nodes){String desc=String.valueOf(n.getContentDescription());if(desc.equals("Show roots")||desc.equals("显示根目录")||desc.equals("显示导航抽屉")){SyncAccountProbe.check(click(n),"document roots action rejected");rootsOpened=true;break;}}}
                if(attempt==299)for(AccessibilityNodeInfo n:nodes)shape.put(new JSONObject().put("class",String.valueOf(n.getClassName())).put("id",String.valueOf(n.getViewIdResourceName())).put("clickable",n.isClickable()).put("enabled",n.isEnabled()));
            }
            Thread.sleep(100);
        }
        Files.write(new File(output,"voicekey-provider-ui-shape.json").toPath(),shape.toString(2).getBytes(StandardCharsets.UTF_8));
        throw new IOException("official model filename not observed in system ACTION_OPEN_DOCUMENT");
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
                JSONObject result=ok(call(nativeVoice,"finish",new Class<?>[]{long.class},h));String text=result.getString("text");if(!nonzero)SyncAccountProbe.check(text.isEmpty(),"empty VoiceKey final hallucinated");
                cases.put(textEvidence(text).put("id",id).put("passed",true).put("accepted_samples",count).put("expected_nonzero_pcm",nonzero));
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
            stage="SAF observed official filename selection";report.put("provider_selection_method",selectOfficialDocument(automation,packageFilename,output));
            stage="SAF import completion";
            for(int n=0;n<1800;n++){boolean busy=SyncAccountProbe.main(i,()->(Boolean)SyncAccountProbe.field(page,"busy"));if(!busy&&(Boolean)call(models,"installed",new Class<?>[]{Context.class},context))break;if(!busy&&n>10)throw new IOException("official package SAF import rejected");if(n==1799)throw new IOException("official package SAF import timeout");Thread.sleep(100);}
            stage="SAF imported model fixed verification";File model=(File)call(models,"verified",new Class<?>[]{Context.class,BooleanSupplier.class},context,(BooleanSupplier)()->false);SyncAccountProbe.check(model.getName().equals(MODEL),"wrong immutable VoiceKey model version");
            SyncAccountProbe.check(new File(model,DICT).length()==129706&&fileSha(new File(model,DICT)).equals("08a16004cfcf5b9f1668e055f3100de9b8a44f88541819def2bbd56bfd42f484"),"VoiceKey dictionary differs");
            SyncAccountProbe.check(new File(model,XNET).length()==135646496&&fileSha(new File(model,XNET)).equals("0674250a848ce3eb91024b3c0a6eda61b91ea45b2c40e4900130995e89cb9033"),"VoiceKey network differs");SyncAccountProbe.passed(cases,"actual_saf_fixed_model_import");
            stage="actual engine setting";SyncAccountProbe.main(i,()->{Button use=(Button)SyncAccountProbe.field(page,"useVoiceKey");SyncAccountProbe.check(use.isEnabled()&&use.performClick(),"VoiceKey selection disabled");return null;});
            for(int n=0;n<600;n++){if("voicekey".equals(call(prefs,"selected",new Class<?>[]{Context.class},context)))break;if(n==599)throw new IOException("VoiceKey selection did not complete");Thread.sleep(100);}
            SyncAccountProbe.check(!(Boolean)call(xasr,"installed",new Class<?>[]{Context.class},context)&&!(Boolean)call(xasr,"recoverable",new Class<?>[]{Context.class},context),"X-ASR unexpectedly installed");report.put("selected_engine","voicekey").put("xasr_model_present",false);SyncAccountProbe.passed(cases,"voicekey_selected_without_xasr");
            stage="JNI PCM evidence";nativeVoice=Class.forName("org.zapime.android.NativeVoiceKey",true,loader);emptyCases(nativeVoice,model,cases);
            stage="actual synthetic decode";byte[] pcm;try(InputStream input=i.getContext().getAssets().open("voice-fixtures/synthetic.pcm");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){SyncAccountProbe.check(bytes.size()+n<=1920000,"synthetic PCM exceeded bound");bytes.write(b,0,n);}pcm=bytes.toByteArray();}
            SyncAccountProbe.check(pcm.length>0&&pcm.length%2==0,"synthetic PCM missing");short[] samples=new short[pcm.length/2];ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);report.put("fixture_sha256",sha(pcm)).put("audio_samples",samples.length);
            stage="synthetic native model start";h=start(nativeVoice,model);long began=SystemClock.elapsedRealtime();int partials=0;String preview="";long firstPartial=-1;report.put("accepted_samples",0).put("partial_updates",0).put("first_nonempty_partial_ms",JSONObject.NULL);
            stage="synthetic paced feed";
            for(int offset=0;offset<samples.length;offset+=1600){report.put("last_attempt_offset",offset);int count=Math.min(1600,samples.length-offset);short[] block=Arrays.copyOfRange(samples,offset,offset+count);JSONObject partial=accept(nativeVoice,h,block,count);report.put("accepted_samples",offset+count);if(partial.optBoolean("partial_updated")){preview=partial.getString("text");partials++;if(firstPartial<0&&!preview.isEmpty())firstPartial=SystemClock.elapsedRealtime()-began;}report.put("partial_updates",partials).put("first_nonempty_partial_ms",firstPartial<0?JSONObject.NULL:firstPartial).put("last_partial",textEvidence(preview));Thread.sleep(100);}
            // A real trailing block is fed even when shorter than a normal frame.
            stage="synthetic tail feed";JSONObject tail=accept(nativeVoice,h,new short[371],371);report.put("accepted_samples",samples.length+371);if(tail.optBoolean("partial_updated")){preview=tail.getString("text");partials++;if(firstPartial<0&&!preview.isEmpty())firstPartial=SystemClock.elapsedRealtime()-began;}
            report.put("partial_updates",partials).put("first_nonempty_partial_ms",firstPartial<0?JSONObject.NULL:firstPartial).put("last_partial",textEvidence(preview));stage="synthetic nonempty streaming preview";
            SyncAccountProbe.check(firstPartial>=0,"VoiceKey supplied no nonempty streaming preview before finish");
            stage="synthetic native final";long finishing=SystemClock.elapsedRealtime();String decoded=ok(call(nativeVoice,"finish",new Class<?>[]{long.class},h)).getString("text");SyncAccountProbe.check(!decoded.trim().isEmpty(),"VoiceKey synthetic fixture decoded empty; no fabricated transcript permitted");
            report.put("model_decode_executed",true).put("result",textEvidence(decoded)).put("last_partial",textEvidence(preview)).put("partial_updates",partials).put("first_nonempty_partial_ms",firstPartial<0?JSONObject.NULL:firstPartial).put("stop_to_final_ms",SystemClock.elapsedRealtime()-finishing).put("paced_feed_and_finish_ms",SystemClock.elapsedRealtime()-began);
            SyncAccountProbe.check((Boolean)call(nativeVoice,"close",new Class<?>[]{long.class},h),"VoiceKey synthetic worker did not exit");h=0;SyncAccountProbe.passed(cases,"actual_jni_synthetic_decode_and_tail");
            stage="actual confirmation transaction";VoiceCommitProbe.run(i,decoded,output);SyncAccountProbe.passed(cases,"actual_editable_production_confirmation");
            SyncAccountProbe.check((Boolean)call(nativeVoice,"releaseCache",NONE),"VoiceKey model cache still busy after all sessions");SyncAccountProbe.passed(cases,"idle_release_after_real_model");report.put("status","PASS");
        }catch(Throwable e){failure=e;Throwable cause=e;for(int depth=0;depth<5&&cause.getCause()!=null;depth++)cause=cause.getCause();JSONObject failed=new JSONObject().put("id",stage).put("passed",false).put("failure_type",cause.getClass().getSimpleName());String reason=cause.getMessage();if(cause instanceof AssertionError&&reason!=null&&reason.matches("[A-Za-z0-9 _+.,()-]{1,160}")){failed.put("failure_reason",reason);if(reason.startsWith("VoiceKey JNI error "))report.put("native_error_code",reason.substring(19));}cases.put(failed);throw new Exception("VoiceKey official-model acceptance: "+stage,e);}
        finally{
            Throwable cleanup=null;
            try{if(h!=0&&nativeVoice!=null)SyncAccountProbe.check((Boolean)call(nativeVoice,"close",new Class<?>[]{long.class},h),"VoiceKey failed decode worker did not exit");}catch(Throwable e){cleanup=e;}
            try{if(activity!=null){final Activity page=activity;SyncAccountProbe.main(i,()->{page.finish();return null;});i.waitForIdleSync();}}catch(Throwable e){if(cleanup==null)cleanup=e;else cleanup.addSuppressed(e);}
            report.put("cleanup_completed",cleanup==null).put("voicekey_selected_for_following_ime_probe","voicekey".equals(call(prefs,"selected",new Class<?>[]{Context.class},context)));
            if(cleanup!=null){report.put("status","FAIL").put("cleanup_failure_type",cleanup.getClass().getSimpleName());if(failure!=null)failure.addSuppressed(cleanup);}
            Files.write(new File(output,"voicekey-model-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));if(cleanup!=null&&failure==null)throw new Exception("VoiceKey model test cleanup failed",cleanup);
        }
    }
    private VoiceKeyModelProbe(){}
}
