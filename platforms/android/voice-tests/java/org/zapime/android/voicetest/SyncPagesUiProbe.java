// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real protected Activities and their actual queued work; no successful HTTP
 * or active-network cancellation is simulated. Main fixture must be freshly
 * absent on the guarded CI emulator, and is removed only while still owned.
 */
final class SyncPagesUiProbe {
    private static final String ORIGIN="https://sync-pages.synthetic.invalid";
    private static final String ACTIVATION="production_View_performClick_and_AlertDialog_buttons";
    private static final String CANCEL_SCOPE="actual_sync_worker_queued_cancel_before_work_io";
    private static final String SCOPE="Actual non-exported Android Activities on a freshly owned prepared main fixture; local selection and resume seam, normal View.performClick and AlertDialog negative/positive controls, actual SyncWorker queued cancellation before work I/O, stopped-generation rejection, unchanged business/frozen/cursor/vault state and exact fixture cleanup. No physical input, active HTTPS interruption, successful network registration/transfer, phone or user data acceptance.";
    private static final class Trace {
        volatile String step="case_body",failed;
    }
    private static final class Page {
        final Activity activity;
        final boolean key;
        final String resultField,buttonField,title,positive;
        int queuedGeneration;
        boolean queuedCancelled,stoppedCleared,lateRejected;
        Page(Activity activity,boolean key){this.activity=activity;this.key=key;resultField=key?"remoteStatus":"result";buttonField=key?"register":"synchronize";title=key?"首次登记这套密钥？":"向这台服务器同步资料？";positive=key?"明确登记":"同步本轮";}
    }
    private static final class Blocker {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final ExecutorService executor;
        Blocker(ExecutorService executor)throws Exception {
            this.executor=executor;
            executor.execute(()->{entered.countDown();try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("sync page worker fixture timed out");}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
            check(entered.await(5,TimeUnit.SECONDS),"actual sync worker did not enter blocker");
        }
        void drain(Instrumentation instrumentation)throws Exception {
            release.countDown();executor.submit(()->{}).get(15,TimeUnit.SECONDS);instrumentation.waitForIdleSync();
        }
    }
    private static void check(boolean condition,String reason){SyncAccountProbe.check(condition,reason);}
    private static Object call(Class<?> type,String name,Class<?>[] signature,Object...args)throws Exception{return SyncAccountProbe.call(type,null,name,signature,args);}
    private static void await(SyncAccountProbe.Task<Boolean> predicate,String reason)throws Exception {
        long deadline=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<deadline){if(predicate.run())return;Thread.sleep(50);}throw new IOException(reason);
    }
    private static JSONObject request(Class<?> core,long handle,JSONObject request)throws Exception {
        JSONObject result=new JSONObject((String)call(core,"request",new Class<?>[]{long.class,String.class},handle,request.toString()));check(!result.has("error"),"native synthetic pages fixture request failed");return result;
    }
    private static void saveConnection(Context context,Class<?> model,Class<?> store,UUID account,UUID device)throws Exception {
        byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);String token="zap1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(random);Arrays.fill(random,(byte)0);
        JSONObject credential=new JSONObject().put("account_id",account.toString()).put("device_id",device.toString()).put("token",token).put("expires_at","2099-01-01T00:00:00Z");
        Object draft=call(model,"imported",new Class<?>[]{String.class,String.class},ORIGIN,credential.toString()),verified=null;
        try{
            JSONObject response=new JSONObject().put("protocol",1).put("account_id",account.toString()).put("device_id",device.toString()).put("role","owner").put("expires_at","2099-01-01T00:00:00Z");
            verified=SyncAccountProbe.call(model,draft,"verify",new Class<?>[]{byte[].class},response.toString().getBytes(StandardCharsets.UTF_8));call(store,"save",new Class<?>[]{Context.class,model},context,verified);
        }finally{SyncAccountProbe.close(model,draft);SyncAccountProbe.close(model,verified);}
    }
    private static byte[] snapshot(File database)throws Exception {
        JSONArray tables=new JSONArray();
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.toString(),null,SQLiteDatabase.OPEN_READONLY)){
            for(String name:new String[]{"records","metadata","operations","sync_queue","sync_uploads","sync_versions","sync_received","sync_retained_pulls"}){
                JSONArray rows=new JSONArray();try(Cursor cursor=db.rawQuery("SELECT * FROM "+name+" ORDER BY 1",null)){
                    while(cursor.moveToNext()){JSONArray row=new JSONArray();for(int n=0;n<cursor.getColumnCount();n++)row.put(cursor.isNull(n)?JSONObject.NULL:cursor.getString(n));rows.put(row);}
                }tables.put(new JSONObject().put("table",name).put("rows",rows));
            }
        }return tables.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static boolean containsText(View view,String expected){
        if(view instanceof TextView&&expected.contentEquals(((TextView)view).getText()))return true;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++)if(containsText(group.getChildAt(n),expected))return true;}return false;
    }
    private static String result(Instrumentation instrumentation,Page page)throws Exception{return SyncAccountProbe.main(instrumentation,()->((TextView)SyncAccountProbe.field(page.activity,page.resultField)).getText().toString());}
    private static void localSelection(Instrumentation instrumentation,Page page,UUID account,UUID device,UUID key,String digest)throws Exception {
        await(()->SyncAccountProbe.main(instrumentation,()->(Boolean)SyncAccountProbe.field(page.activity,"active")&&!(Boolean)SyncAccountProbe.field(page.activity,"busy")&&SyncAccountProbe.field(page.activity,"selection")!=null),"sync page did not finish local-only load");
        SyncAccountProbe.main(instrumentation,()->{
            Object selected=SyncAccountProbe.field(page.activity,"selection"),local=SyncAccountProbe.field(selected,"local");
            check(account.equals(SyncAccountProbe.field(selected,"account"))&&device.equals(SyncAccountProbe.field(selected,"device"))&&URI.create(ORIGIN).equals(SyncAccountProbe.field(selected,"server")),"sync page selected another public connection");
            check(key.equals(SyncAccountProbe.field(local,"keyId"))&&digest.equals(SyncAccountProbe.field(local,"materialSha256")),"sync page selected another local descriptor");
            check((page.activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"sync page permits screenshots");
            for(String name:new String[]{"localStatus",page.resultField})check(!((TextView)SyncAccountProbe.field(page.activity,name)).isSaveEnabled(),"sync page saves status view state");
            String status=((TextView)SyncAccountProbe.field(page.activity,page.resultField)).getText().toString();
            check(status.contains("尚未联网")&&(page.key?SyncAccountProbe.field(page.activity,"remote")==null:true),"opening sync page did not remain on local-only seam");
            String shown=((TextView)SyncAccountProbe.field(page.activity,"localStatus")).getText().toString();check(shown.contains(ORIGIN)&&shown.contains(account.toString())&&shown.contains(key.toString())&&shown.contains(digest),"sync page does not show selected target identity");return null;
        });
    }
    private static void resumeLocal(Instrumentation instrumentation,Context context,Page page,UUID account,UUID device,UUID key,String digest,List<Activity> activities)throws Exception {
        int before=SyncAccountProbe.main(instrumentation,()->(Integer)SyncAccountProbe.field(page.activity,"generation"));
        Activity cover=instrumentation.startActivitySync(new Intent().setClassName(context,"org.zapime.android.SyncPreparationActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activities.add(cover);
        await(()->SyncAccountProbe.main(instrumentation,()->(Boolean)SyncAccountProbe.field(cover,"active")&&!(Boolean)SyncAccountProbe.field(cover,"busy")),"local preparation cover did not load");
        SyncAccountProbe.main(instrumentation,()->{cover.finish();return null;});instrumentation.waitForIdleSync();localSelection(instrumentation,page,account,device,key,digest);
        check(SyncAccountProbe.main(instrumentation,()->(Integer)SyncAccountProbe.field(page.activity,"generation"))>before,"actual sync page resume retained old generation");
    }
    private static AlertDialog dialog(Instrumentation instrumentation,Page page,UUID account,UUID key,String digest)throws Exception {
        return SyncAccountProbe.main(instrumentation,()->{
            Button control=(Button)SyncAccountProbe.field(page.activity,page.buttonField);check(control.isEnabled()&&control.performClick(),"normal sync page action refused");
            AlertDialog dialog=(AlertDialog)SyncAccountProbe.field(page.activity,"confirmation");check(dialog!=null&&dialog.isShowing(),"normal sync confirmation not shown");
            check((dialog.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0&&containsText(dialog.getWindow().getDecorView(),page.title),"sync confirmation title or privacy differs");
            TextView message=dialog.findViewById(android.R.id.message);check(message!=null,"sync confirmation message missing");String shown=message.getText().toString();
            check(shown.contains(ORIGIN)&&shown.contains(account.toString())&&shown.contains(key.toString())&&shown.contains(digest),"sync confirmation lost displayed selection binding");
            check(page.positive.contentEquals(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText())&&"取消".contentEquals(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText()),"sync confirmation controls differ");return dialog;
        });
    }
    private static void negative(Instrumentation instrumentation,Page page,UUID account,UUID key,String digest,ThreadPoolExecutor executor)throws Exception {
        executor.submit(()->{}).get(5,TimeUnit.SECONDS);instrumentation.waitForIdleSync();long before=executor.getTaskCount();String status=result(instrumentation,page);Object selected=SyncAccountProbe.main(instrumentation,()->SyncAccountProbe.field(page.activity,"selection"));
        AlertDialog dialog=dialog(instrumentation,page,account,key,digest);SyncAccountProbe.main(instrumentation,()->{check(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(),"normal negative dialog click refused");return null;});instrumentation.waitForIdleSync();
        check(executor.getTaskCount()==before,"negative sync confirmation submitted background work");
        SyncAccountProbe.main(instrumentation,()->{check(!(Boolean)SyncAccountProbe.field(page.activity,"busy")&&SyncAccountProbe.field(page.activity,"cancellation")==null&&SyncAccountProbe.field(page.activity,"confirmation")==null&&SyncAccountProbe.field(page.activity,"selection")==selected,"negative sync confirmation mutated active selection");return null;});
        check(status.equals(result(instrumentation,page)),"negative sync confirmation changed request status");
    }
    private static void queuedCancel(Instrumentation instrumentation,Page page,UUID account,UUID key,String digest,ThreadPoolExecutor executor,Trace trace)throws Exception {
        trace.failed=null;trace.step="queued_worker_blocker";Blocker blocker=new Blocker(executor);Object cancelled=null;String stopped=null;Throwable original=null;
        try{
            trace.step="queued_confirmation_dialog";
            AlertDialog dialog=dialog(instrumentation,page,account,key,digest);
            trace.step="queued_positive_click";
            SyncAccountProbe.main(instrumentation,()->{check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(),"normal positive dialog click refused");return null;});
            // AlertController dispatches its normal positive listener through a
            // Handler. Observe its actual work only after that click is delivered.
            trace.step="queued_positive_dispatch";instrumentation.waitForIdleSync();
            trace.step="queued_request_capture";
            cancelled=SyncAccountProbe.main(instrumentation,()->{Object request=SyncAccountProbe.field(page.activity,"cancellation");check(request!=null&&(Boolean)SyncAccountProbe.field(page.activity,"busy")&&executor.getQueue().size()==1,"positive sync request not queued behind actual worker");page.queuedGeneration=(Integer)SyncAccountProbe.field(page.activity,"generation");page.activity.finish();return request;});
            trace.step="queued_stop_invalidation";
            await(()->SyncAccountProbe.main(instrumentation,()->!(Boolean)SyncAccountProbe.field(page.activity,"active")&&SyncAccountProbe.field(page.activity,"selection")==null&&SyncAccountProbe.field(page.activity,"cancellation")==null&&SyncAccountProbe.field(page.activity,"confirmation")==null&&(Integer)SyncAccountProbe.field(page.activity,"generation")>page.queuedGeneration),"stopped sync page retained selection or queued request");
            page.stoppedCleared=true;stopped=result(instrumentation,page);boolean rejected=false;
            trace.step="queued_cancellation_check";
            try{SyncAccountProbe.call(cancelled.getClass(),cancelled,"check",new Class<?>[0]);}catch(IOException expected){rejected="sync_cancelled".equals(expected.getMessage());}check(rejected,"actual queued cancellation token did not stop before work I/O");page.queuedCancelled=true;
        }catch(Exception|Error error){original=error;trace.failed=trace.step;throw error;}
        finally{
            try{trace.step="queued_worker_drain";blocker.drain(instrumentation);}
            catch(Exception|Error error){if(original!=null)original.addSuppressed(error);else{trace.failed=trace.step;throw error;}}
        }
        trace.step="queued_late_result_rejected";
        check(stopped!=null&&stopped.equals(result(instrumentation,page)),"stopped sync page accepted its late real worker callback");page.lateRejected=true;
    }
    private static Throwable add(Throwable previous,Throwable next){if(previous==null)return next;previous.addSuppressed(next);return previous;}
    static void run(Instrumentation instrumentation,File output,String dedicatedCI)throws Exception {
        check(dedicatedCI!=null&&dedicatedCI.matches("[0-9]+-[0-9]+"),"numeric dedicated sync pages CI required");
        Context context=instrumentation.getTargetContext();ClassLoader loader=context.getClassLoader();
        Class<?> core=Class.forName("org.zapime.android.NativeCore",true,loader),vault=Class.forName("org.zapime.android.SyncSecrets",true,loader),model=Class.forName("org.zapime.android.SyncConnection",true,loader),store=Class.forName("org.zapime.android.SyncConnectionStore",true,loader),preparation=Class.forName("org.zapime.android.SyncPreparation",true,loader),installer=Class.forName("org.zapime.android.ResourceInstaller",true,loader),worker=Class.forName("org.zapime.android.SyncWorker",true,loader);
        java.lang.reflect.Field field=worker.getDeclaredField("EXECUTOR");field.setAccessible(true);Object rawWorker=field.get(null);check(rawWorker instanceof ThreadPoolExecutor,"actual bounded SyncWorker required");ThreadPoolExecutor executor=(ThreadPoolExecutor)rawWorker;
        File root=new File(context.getFilesDir(),"zapime"),database=new File(root,"data/personal.sqlite3");UUID account=UUID.randomUUID(),device=UUID.randomUUID();
        List<Activity> activities=new ArrayList<>();List<Page> pages=new ArrayList<>();JSONArray cases=new JSONArray();Trace trace=new Trace();String stage="fresh_main_prepared_fixture";Throwable operationFailure=null;boolean owned=false;long handle=0;
        byte[] material=null,before=null,materialCipher=null,connectionCipher=null;
        JSONObject report=new JSONObject().put("schema_version",1).put("status","FAIL").put("synthetic",true).put("api_level",Build.VERSION.SDK_INT).put("cases",cases).put("scope",SCOPE)
                .put("button_activation_method",ACTIVATION).put("cancellation_scope",CANCEL_SCOPE).put("android_https","NOT RUN").put("active_https_interruption_tested",false).put("physical_input_tested",false).put("network_success_tested",false).put("secret_content_or_code_logged",false);
        try{
            check(!Files.exists(root.toPath(),LinkOption.NOFOLLOW_LINKS)&&context.getSharedPreferences("sync_connection",Context.MODE_PRIVATE).getAll().isEmpty(),"sync pages require absent main root and empty connection index");
            Object existing=call(store,"load",new Class<?>[]{Context.class},context);try{check(existing==null,"sync pages cannot adopt an existing connection");}finally{SyncAccountProbe.close(model,existing);}
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            for(String purpose:new String[]{"material","connection"}){
                check(!keys.containsAlias("org.zapime.android.sync."+purpose+".v1."+account),"random sync pages alias exists");
                for(String suffix:new String[]{"",".bak",".new"})check(!Files.exists(new File(context.getNoBackupFilesDir(),"sync-"+purpose+"-"+account+".bin"+suffix).toPath(),LinkOption.NOFOLLOW_LINKS),"random sync pages vault file exists");
            }
            owned=true;call(installer,"prepare",new Class<?>[]{Context.class},context);handle=(Long)call(core,"open",new Class<?>[]{String.class,boolean.class},root.toString(),false);check(handle!=0,"sync pages production database initialization failed");
            request(core,handle,new JSONObject().put("op","personal_edit").put("id",UUID.randomUUID().toString()).put("operation_id",UUID.randomUUID().toString()).put("expected_revision",0).put("content",new JSONObject().put("type","favorite").put("title","synthetic pages").put("text","页面生命周期合成资料")));
            call(core,"close",new Class<?>[]{long.class},handle);handle=0;saveConnection(context,model,store,account,device);call(preparation,"create",new Class<?>[]{Context.class,UUID.class},context,account);
            material=(byte[])call(vault,"load",new Class<?>[]{Context.class,UUID.class},context,account);check(material!=null,"sync pages prepared material missing");
            JSONObject descriptor=new JSONObject((String)call(core,"syncKeyDescriptor",new Class<?>[]{String.class,byte[].class},account.toString(),material));UUID key=UUID.fromString(descriptor.getString("key_id"));String digest=descriptor.getString("material_sha256");
            JSONObject frozen=new JSONObject((String)call(core,"stageSyncUpload",new Class<?>[]{String.class,String.class,byte[].class},root.toString(),account.toString(),material));check(!frozen.has("error")&&Boolean.TRUE.equals(frozen.opt("pending"))&&frozen.opt("wire") instanceof String,"sync pages require real frozen pending operation");
            before=snapshot(database);materialCipher=Files.readAllBytes(new File(context.getNoBackupFilesDir(),"sync-material-"+account+".bin").toPath());connectionCipher=Files.readAllBytes(new File(context.getNoBackupFilesDir(),"sync-connection-"+account+".bin").toPath());SyncAccountProbe.passed(cases,stage);
            for(boolean keyPage:new boolean[]{true,false}){
                String className="org.zapime.android."+(keyPage?"SyncKeyRegistrationActivity":"SyncTransferActivity");ActivityInfo info=context.getPackageManager().getActivityInfo(new ComponentName(context,className),0);check(!info.exported,"sync page is externally exported");
                Activity activity=instrumentation.startActivitySync(new Intent().setClassName(context,className).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activities.add(activity);Page page=new Page(activity,keyPage);pages.add(page);
                stage=keyPage?"key_page_secure_local_selection":"transfer_page_secure_local_selection";localSelection(instrumentation,page,account,device,key,digest);resumeLocal(instrumentation,context,page,account,device,key,digest,activities);SyncAccountProbe.passed(cases,stage);
                stage=keyPage?"key_confirmation_negative_cancel":"transfer_confirmation_negative_cancel";negative(instrumentation,page,account,key,digest,executor);SyncAccountProbe.passed(cases,stage);
                stage=keyPage?"key_queued_cancel_before_io":"transfer_queued_cancel_before_io";queuedCancel(instrumentation,page,account,key,digest,executor,trace);SyncAccountProbe.passed(cases,stage);
            }
            stage="stopped_selection_and_generation_invalidated";check(pages.size()==2,"both sync pages not exercised");for(Page page:pages)check(page.stoppedCleared&&page.queuedCancelled&&page.lateRejected,"sync page lifecycle proof incomplete");SyncAccountProbe.passed(cases,stage);
            stage="prepared_business_and_vault_preserved";check(Arrays.equals(before,snapshot(database))&&Arrays.equals(materialCipher,Files.readAllBytes(new File(context.getNoBackupFilesDir(),"sync-material-"+account+".bin").toPath()))&&Arrays.equals(connectionCipher,Files.readAllBytes(new File(context.getNoBackupFilesDir(),"sync-connection-"+account+".bin").toPath())),"cancelled sync pages changed business, frozen rows, cursor or protected vault");SyncAccountProbe.passed(cases,stage);
            report.put("actual_activities",true).put("manifest_nonexported",true).put("secure_window_flags",true).put("displayed_selection_matches_local",true).put("resume_local_only_seam_verified",true).put("negative_dialog_cancel_no_task",true).put("actual_sync_worker_queued_cancel",true).put("captured_cancellation_checked",true).put("late_result_rejected",true).put("business_queue_frozen_cursor_preserved",true).put("vault_ciphertext_preserved",true).put("queued_cancelled_pages",pages.size());
        }catch(Throwable error){operationFailure=error;report.put("failure_substep",stage.endsWith("queued_cancel_before_io")?(trace.failed==null?trace.step:trace.failed):"case_body");cases.put(new JSONObject().put("id",stage).put("passed",false).put("failure_type",error.getClass().getSimpleName()));throw new Exception("synthetic sync pages UI case: "+stage,error);}
        finally{
            Throwable cleanup=null;
            for(Activity activity:activities)try{SyncAccountProbe.main(instrumentation,()->{activity.finish();return null;});}catch(Throwable error){cleanup=add(cleanup,error);}
            try{executor.submit(()->{}).get(15,TimeUnit.SECONDS);instrumentation.waitForIdleSync();}catch(Throwable error){cleanup=add(cleanup,error);}
            try{if(handle!=0)call(core,"close",new Class<?>[]{long.class},handle);}catch(Throwable error){cleanup=add(cleanup,error);}
            if(owned){
                boolean rootStillOwned=true;
                try{
                    Object current=call(store,"load",new Class<?>[]{Context.class},context);try{check(current==null||account.equals(SyncAccountProbe.field(current,"account")),"sync pages fixture account changed before cleanup");}finally{SyncAccountProbe.close(model,current);}
                    JSONObject profile=new JSONObject((String)call(core,"syncProfile",new Class<?>[]{String.class},root.toString()));check(!profile.has("error")&&(profile.isNull("account_id")||account.toString().equals(profile.getString("account_id"))),"sync pages fixture database ownership changed");
                    call(store,"forget",new Class<?>[]{Context.class},context);
                }catch(Throwable error){rootStillOwned=false;cleanup=add(cleanup,error);}
                try{call(vault,"forgetConnection",new Class<?>[]{Context.class,UUID.class},context,account);call(vault,"forget",new Class<?>[]{Context.class,UUID.class},context,account);}catch(Throwable error){cleanup=add(cleanup,error);}
                try{if(rootStillOwned&&root.exists())try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);}}catch(Throwable error){cleanup=add(cleanup,error);}
                try{check(rootStillOwned&&!Files.exists(root.toPath(),LinkOption.NOFOLLOW_LINKS)&&context.getSharedPreferences("sync_connection",Context.MODE_PRIVATE).getAll().isEmpty(),"sync pages owned cleanup left main fixture");}catch(Throwable error){cleanup=add(cleanup,error);}
            }
            for(byte[] bytes:new byte[][]{material,before,materialCipher,connectionCipher})if(bytes!=null)Arrays.fill(bytes,(byte)0);
            report.put("fresh_fixture_owned",owned).put("cleanup_completed",cleanup==null);
            if(cleanup==null&&operationFailure==null){SyncAccountProbe.passed(cases,"owned_main_fixture_cleanup");report.put("status","PASS");}
            if(cleanup!=null){report.put("cleanup_failure_type",cleanup.getClass().getSimpleName());if(operationFailure!=null)operationFailure.addSuppressed(cleanup);else report.put("failure_substep","owned_fixture_cleanup");}
            Files.write(new File(output,"sync-pages-ui-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            if(cleanup!=null&&operationFailure==null)throw new Exception("sync pages fixture cleanup failed",cleanup);
        }
    }
    private SyncPagesUiProbe(){}
}
