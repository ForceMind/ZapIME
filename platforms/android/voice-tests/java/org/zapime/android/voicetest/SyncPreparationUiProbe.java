// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real create/open DocumentsUI, only after a fresh dedicated CI profile guard.
 * Uses synthetic credentials and normal Activity controls. Never logs key/code bytes.
 */
final class SyncPreparationUiProbe {
    private static final int MAX_RECOVERY=16*1024;
    private static final Class<?>[] NONE=new Class<?>[0];
    private static Object call(Class<?> type,String name,Class<?>[] signature,Object... args)throws Exception {
        return SyncAccountProbe.call(type,null,name,signature,args);
    }
    private static void await(SyncAccountProbe.Task<Boolean> ready,String failure)throws Exception {
        long deadline=SystemClock.elapsedRealtime()+45000;
        while(SystemClock.elapsedRealtime()<deadline){if(ready.run())return;Thread.sleep(100);}
        throw new IOException(failure);
    }
    private static boolean ready(Instrumentation instrumentation,Activity page)throws Exception {
        return SyncAccountProbe.main(instrumentation,()->(Boolean)SyncAccountProbe.field(page,"active")&&!(Boolean)SyncAccountProbe.field(page,"busy"));
    }
    private static void button(Instrumentation instrumentation,Activity page,String field)throws Exception {
        SyncAccountProbe.main(instrumentation,()->{
            Button button=(Button)SyncAccountProbe.field(page,field);
            SyncAccountProbe.check(button.isEnabled()&&button.performClick(),"normal preparation control rejected");return null;
        });
    }
    private static void confirmDialog(UiAutomation automation,String title,String label)throws Exception {
        for(int attempt=0;attempt<150;attempt++){
            AccessibilityNodeInfo root=automation.getRootInActiveWindow();
            if(root!=null&&"org.zapime.android".contentEquals(root.getPackageName()==null?"":root.getPackageName())){
                List<AccessibilityNodeInfo> nodes=VoiceKeyModelProbe.nodes(root);boolean exactTitle=false;
                for(AccessibilityNodeInfo node:nodes)if(title.contentEquals(node.getText()==null?"":node.getText()))exactTitle=true;
                if(exactTitle)for(AccessibilityNodeInfo node:nodes){
                    if(node.isEnabled()&&node.isVisibleToUser()&&label.contentEquals(node.getText()==null?"":node.getText())){
                        SyncAccountProbe.check(VoiceKeyModelProbe.click(node),"normal preparation confirmation rejected");return;
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new IOException("normal preparation confirmation not observed");
    }
    private static String filename(Context context,Uri uri)throws Exception {
        try(Cursor result=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            SyncAccountProbe.check(result!=null&&result.moveToFirst(),"created provider filename unavailable");
            String name=result.getString(0);SyncAccountProbe.check(name!=null&&name.matches("ZapIME-sync-key-[0-9]+\\.zapime-key"),"unexpected generated provider filename");return name;
        }
    }
    private static byte[] read(Context context,Uri uri)throws Exception {
        try(InputStream input=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            SyncAccountProbe.check(input!=null,"actual recovery provider stream missing");byte[] buffer=new byte[1024];
            try{int count;while((count=input.read(buffer))!=-1){SyncAccountProbe.check(output.size()+count<=MAX_RECOVERY,"actual recovery file exceeds bound");output.write(buffer,0,count);}}
            finally{Arrays.fill(buffer,(byte)0);}
            byte[] bytes=output.toByteArray();SyncAccountProbe.check(bytes.length>0,"actual recovery file empty");return bytes;
        }
    }
    private static AccessibilityNodeInfo uniqueDocument(UiAutomation automation,String name)throws Exception {
        AccessibilityNodeInfo root=automation.getRootInActiveWindow();
        if(root==null||!String.valueOf(root.getPackageName()).contains("documentsui"))return null;
        AccessibilityNodeInfo found=null;Rect foundBounds=null;
        for(AccessibilityNodeInfo node:VoiceKeyModelProbe.nodes(root)){
            if(node.isVisibleToUser()&&node.isEnabled()&&name.contentEquals(node.getText()==null?"":node.getText())){
                Rect bounds=new Rect();node.getBoundsInScreen(bounds);if(bounds.isEmpty())continue;
                SyncAccountProbe.check(foundBounds==null||foundBounds.equals(bounds),"recovery filename is not unique in provider");found=node;foundBounds=bounds;
            }
        }
        return found;
    }
    private static Rect visibleRoots(UiAutomation automation)throws Exception {
        AccessibilityNodeInfo root=automation.getRootInActiveWindow();
        if(root==null||!String.valueOf(root.getPackageName()).contains("documentsui"))return null;
        Rect screen=new Rect();root.getBoundsInScreen(screen);
        for(AccessibilityNodeInfo node:VoiceKeyModelProbe.nodes(root)){
            String id=node.getViewIdResourceName();
            if(node.isVisibleToUser()&&id!=null&&(id.endsWith(":id/container_roots")||id.endsWith(":id/roots_list"))){
                Rect bounds=new Rect();node.getBoundsInScreen(bounds);
                if(bounds.intersect(screen)&&!bounds.isEmpty())return bounds;
            }
        }
        return null;
    }
    private static void observeProvider(UiAutomation automation,String name,SyncRecoveryTrace trace)throws Exception {
        AccessibilityNodeInfo root=automation.getRootInActiveWindow();
        String kind=root==null?"":String.valueOf(root.getPackageName());
        SyncRecoveryTrace.Window window=root==null?SyncRecoveryTrace.Window.NONE:
                kind.contains("documentsui")?SyncRecoveryTrace.Window.DOCUMENTS_UI:
                kind.equals("org.zapime.android")?SyncRecoveryTrace.Window.PRODUCT:SyncRecoveryTrace.Window.OTHER;
        // Read provider nodes only. Never inspect the sensitive product page.
        trace.observed(window,window==SyncRecoveryTrace.Window.DOCUMENTS_UI&&visibleRoots(automation)!=null,
                window==SyncRecoveryTrace.Window.DOCUMENTS_UI&&uniqueDocument(automation,name)!=null);
    }
    private static JSONObject diagnostic(SyncRecoveryTrace trace)throws Exception {
        return new JSONObject().put("substep",trace.stepCode()).put("active_window",trace.windowCode())
                .put("provider_roots_present",trace.rootsPresent).put("matching_filename_present",trace.filenamePresent)
                .put("observed_polls",trace.polls).put("navigation_close_attempts",trace.closeAttempts)
                .put("physical_selection_attempts",trace.touchAttempts);
    }
    private static void selectRecovery(UiAutomation automation,String name,File output,JSONObject report,SyncRecoveryTrace trace)throws Exception {
        boolean rootsOpened=false,downloadsOpened=false,closedCover=false;Rect previous=null;long stableSince=-1;
        report.put("provider_navigation_cover_detected",false).put("provider_navigation_close_count",0)
                .put("provider_occlusion_scope","identified_DocumentsUI_roots_navigation_only");
        for(int attempt=0;attempt<150;attempt++){
            trace.step=SyncRecoveryTrace.Step.FILENAME_POLL;observeProvider(automation,name,trace);
            AccessibilityNodeInfo named=uniqueDocument(automation,name);
            Rect navigation=visibleRoots(automation);
            if(closedCover&&navigation!=null){trace.step=SyncRecoveryTrace.Step.NAVIGATION_CLOSE_WAIT;previous=null;stableSince=-1;Thread.sleep(200);continue;}
            if(named!=null){
                Rect bounds=new Rect();named.getBoundsInScreen(bounds);
                // Visibility alone does not prove hit-test reachability: modern
                // DocumentsUI exposes the filename beneath an open drawer.
                if(navigation!=null&&navigation.contains(bounds.centerX(),bounds.centerY())){
                    SyncAccountProbe.check(!closedCover,"provider drawer still covers filename after one close");
                    trace.step=SyncRecoveryTrace.Step.NAVIGATION_SNAPSHOT;
                    VoiceKeyModelProbe.providerScreenshot(automation,output,"sync-key-provider-nav-before.png");
                    report.put("provider_navigation_cover_detected",true).put("provider_navigation_bounds",new JSONArray().put(navigation.left).put(navigation.top).put(navigation.right).put(navigation.bottom));
                    trace.closeAttempt();
                    SyncAccountProbe.check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK),"observed provider drawer close rejected");
                    closedCover=true;report.put("provider_navigation_close_count",1).put("provider_navigation_close_method","observed_cover_single_global_back");
                    trace.step=SyncRecoveryTrace.Step.NAVIGATION_CLOSE_WAIT;
                    previous=null;stableSince=-1;Thread.sleep(200);continue;
                }
                if(bounds.equals(previous)&&SystemClock.uptimeMillis()-stableSince>=200){
                    // Capture only the provider's public synthetic filename list;
                    // never capture the Activity while a recovery code is displayed.
                    trace.step=SyncRecoveryTrace.Step.FILE_SNAPSHOT;
                    VoiceKeyModelProbe.providerScreenshot(automation,output,"sync-key-provider-before.png");
                    AccessibilityNodeInfo fresh=uniqueDocument(automation,name);Rect now=new Rect();if(fresh!=null)fresh.getBoundsInScreen(now);
                    Rect finalNavigation=visibleRoots(automation);
                    if(fresh!=null&&now.equals(bounds)&&(!closedCover||finalNavigation==null)
                            &&(finalNavigation==null||!finalNavigation.contains(now.centerX(),now.centerY()))){
                        report.put("provider_touch_point",new JSONArray().put(now.centerX()).put(now.centerY()))
                                .put("provider_selected_filename_bounds",new JSONArray().put(now.left).put(now.top).put(now.right).put(now.bottom))
                                .put("provider_pointer_id",0).put("provider_pointer_count",1).put("provider_tool_type",MotionEvent.TOOL_TYPE_FINGER)
                                .put("provider_event_source",InputDevice.SOURCE_TOUCHSCREEN)
                                .put("provider_selection_unoccluded",true).put("provider_cover_closed_before_selection",closedCover);
                        trace.touchAttempt();long began=SystemClock.uptimeMillis();MotionEvent down=VoiceKeyModelProbe.fingerEvent(began,began,MotionEvent.ACTION_DOWN,now.centerX(),now.centerY());boolean sent;
                        try{sent=automation.injectInputEvent(down,true);}finally{down.recycle();}SyncAccountProbe.check(sent,"recovery provider finger down rejected");Thread.sleep(50);
                        trace.step=SyncRecoveryTrace.Step.TOUCH_UP;
                        MotionEvent up=VoiceKeyModelProbe.fingerEvent(began,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,now.centerX(),now.centerY());
                        try{sent=automation.injectInputEvent(up,true);}finally{up.recycle();}SyncAccountProbe.check(sent,"recovery provider finger up rejected");
                        report.put("provider_physical_selection_count",1).put("provider_selection_method","unique_stable_filename_single_finger");return;
                    }
                }
                if(previous==null||!previous.equals(bounds)){previous=bounds;stableSince=SystemClock.uptimeMillis();}
            }else{
                previous=null;stableSince=-1;AccessibilityNodeInfo root=automation.getRootInActiveWindow();
                if(root!=null&&String.valueOf(root.getPackageName()).contains("documentsui")){
                    List<AccessibilityNodeInfo> nodes=VoiceKeyModelProbe.nodes(root);
                    if(!closedCover&&!downloadsOpened)for(AccessibilityNodeInfo node:nodes){String text=String.valueOf(node.getText());if(node.isVisibleToUser()&&(text.equals("Downloads")||text.equals("下载"))&&VoiceKeyModelProbe.click(node)){downloadsOpened=true;break;}}
                    if(!closedCover&&!downloadsOpened&&!rootsOpened)for(AccessibilityNodeInfo node:nodes){String desc=String.valueOf(node.getContentDescription());if(node.isVisibleToUser()&&(desc.equals("Show roots")||desc.equals("显示根目录")||desc.equals("显示导航抽屉"))){SyncAccountProbe.check(VoiceKeyModelProbe.click(node),"recovery document roots rejected");rootsOpened=true;break;}}
                }
            }
            Thread.sleep(200);
        }
        throw new IOException("recovery provider filename geometry did not stabilize");
    }
    private static void enter(Instrumentation instrumentation,Activity page,String field,byte[] code)throws Exception {
        SyncAccountProbe.main(instrumentation,()->{
            EditText input=(EditText)SyncAccountProbe.field(page,field);input.setText("");
            for(byte value:code)input.getText().append((char)(value&255));
            SyncAccountProbe.check(input.length()==code.length,"synthetic recovery code entry rejected");return null;
        });
    }
    private static void saveCredential(Context context,Class<?> model,Class<?> store,UUID account)throws Exception {
        byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);
        String token="zap1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(secret);Arrays.fill(secret,(byte)0);UUID device=UUID.randomUUID();
        JSONObject credential=new JSONObject().put("account_id",account.toString()).put("device_id",device.toString()).put("token",token).put("expires_at","2099-01-01T00:00:00Z");
        Object draft=call(model,"imported",new Class<?>[]{String.class,String.class},"https://synthetic.invalid",credential.toString()),verified=null;
        try{
            JSONObject session=new JSONObject().put("protocol",1).put("account_id",account.toString()).put("device_id",device.toString()).put("role","owner").put("expires_at","2099-01-01T00:00:00Z");
            verified=SyncAccountProbe.call(model,draft,"verify",new Class<?>[]{byte[].class},session.toString().getBytes(StandardCharsets.UTF_8));
            call(store,"save",new Class<?>[]{Context.class,model},context,verified);
        }finally{SyncAccountProbe.close(model,draft);SyncAccountProbe.close(model,verified);}
    }
    private static Throwable append(Throwable current,Throwable next){if(current==null)return next;current.addSuppressed(next);return current;}
    static void run(Instrumentation instrumentation,File output,String dedicatedCI)throws Exception {
        SyncAccountProbe.check(dedicatedCI!=null&&dedicatedCI.matches("[0-9]+-[0-9]+"),"numeric dedicated sync preparation CI required");
        Context context=instrumentation.getTargetContext();ClassLoader loader=context.getClassLoader();
        Class<?> core=Class.forName("org.zapime.android.NativeCore",true,loader),vault=Class.forName("org.zapime.android.SyncSecrets",true,loader),model=Class.forName("org.zapime.android.SyncConnection",true,loader),store=Class.forName("org.zapime.android.SyncConnectionStore",true,loader),installer=Class.forName("org.zapime.android.ResourceInstaller",true,loader);
        File root=new File(context.getFilesDir(),"zapime"),database=new File(root,"data/personal.sqlite3");UUID account=UUID.randomUUID();
        JSONArray cases=new JSONArray();JSONObject report=new JSONObject().put("schema_version",1).put("status","FAIL").put("synthetic",true).put("api_level",Build.VERSION.SDK_INT).put("cases",cases)
                .put("scope","Fresh dedicated CI main profile only: normal preparation buttons/dialogs, actual ACTION_CREATE_DOCUMENT and ACTION_OPEN_DOCUMENT with one observed filename/finger selection, actual native key recovery and unchanged protected material. Synthetic owner identity verified locally without HTTP. EditText inputs are programmatic test input, not natural or physical typing. Does not prove account-wide key registration, data synchronization, phone behavior or human backup custody.")
                .put("actual_provider_create",false).put("actual_provider_open",false).put("actual_native_recovery",false).put("recovery_identity_unchanged",false).put("normal_buttons_and_dialogs_used",false).put("missing_material_restore_ui",false).put("secret_or_code_logged",false)
                .put("button_activation_method","production_View_performClick_and_visible_AlertDialog_confirmation");
        String stage="fresh main fixture";Throwable operationFailure=null;boolean owned=false,saveTriggered=false,documentDeleted=false;Activity activity=null;Uri document=null;long handle=0;
        SyncRecoveryTrace trace=new SyncRecoveryTrace();String recoveryName=null;
        byte[] originalMaterial=null,originalCipher=null,recoveryCode=null,encrypted=null,restored=null,shownBuffer=null;
        try{
            // Do not clear, adopt or bind an existing user's main database.
            SyncAccountProbe.check(!Files.exists(root.toPath(),LinkOption.NOFOLLOW_LINKS)&&!database.exists(),"UI probe requires absent main data and root");
            SyncAccountProbe.check(context.getSharedPreferences("sync_connection",Context.MODE_PRIVATE).getAll().isEmpty(),"UI probe requires empty connection index");
            Object existing=call(store,"load",new Class<?>[]{Context.class},context);
            try{SyncAccountProbe.check(existing==null,"UI probe requires no saved connection");}finally{SyncAccountProbe.close(model,existing);}
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            for(String purpose:new String[]{"material","connection"}){
                SyncAccountProbe.check(!keys.containsAlias("org.zapime.android.sync."+purpose+".v1."+account),"random UI fixture alias already exists");
                String filename="sync-"+purpose+"-"+account+".bin";
                for(String suffix:new String[]{"",".bak",".new"})SyncAccountProbe.check(!Files.exists(new File(context.getNoBackupFilesDir(),filename+suffix).toPath(),LinkOption.NOFOLLOW_LINKS),"random UI fixture vault already exists");
            }
            owned=true;call(installer,"prepare",new Class<?>[]{Context.class},context);
            handle=(Long)call(core,"open",new Class<?>[]{String.class,boolean.class},root.toString(),false);SyncAccountProbe.check(handle!=0&&database.isFile(),"UI fixture native initialization failed");
            call(core,"close",new Class<?>[]{long.class},handle);handle=0;saveCredential(context,model,store,account);SyncAccountProbe.passed(cases,"fresh_main_fixture");
            UiAutomation automation=instrumentation.getUiAutomation();AccessibilityServiceInfo info=automation.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;automation.setServiceInfo(info);
            activity=instrumentation.startActivitySync(new Intent().setClassName(context,"org.zapime.android.SyncPreparationActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity page=activity;
            await(()->ready(instrumentation,page)&&SyncAccountProbe.main(instrumentation,()->account.equals(SyncAccountProbe.field(page,"currentAccount"))),"UI fixture state did not load");
            stage="normal create confirmation";button(instrumentation,page,"create");confirmDialog(automation,"准备首次使用的同步密钥？","准备本机密钥");
            await(()->ready(instrumentation,page)&&SyncAccountProbe.main(instrumentation,()->(Boolean)SyncAccountProbe.field(page,"prepared")),"normal preparation creation did not complete");
            originalMaterial=(byte[])call(vault,"load",new Class<?>[]{Context.class,UUID.class},context,account);SyncAccountProbe.check(originalMaterial!=null,"normal creation omitted protected material");
            File materialFile=new File(context.getNoBackupFilesDir(),"sync-material-"+account+".bin");originalCipher=Files.readAllBytes(materialFile.toPath());
            JSONObject state=new JSONObject((String)call(core,"syncWorkspaceStatus",new Class<?>[]{String.class,String.class,byte[].class},root.toString(),account.toString(),originalMaterial));SyncAccountProbe.check(state.getBoolean("prepared"),"normal creation omitted database binding");SyncAccountProbe.passed(cases,"normal_create_confirmation");
            stage="actual SAF export";button(instrumentation,page,"export");saveTriggered=true;BackupProbe.saveOnce(automation,output);
            await(()->ready(instrumentation,page)&&SyncAccountProbe.main(instrumentation,()->SyncAccountProbe.field(page,"expectedCode")!=null),"actual recovery export did not complete");
            shownBuffer=SyncAccountProbe.main(instrumentation,()->(byte[])SyncAccountProbe.field(page,"expectedCode"));recoveryCode=shownBuffer.clone();
            document=SyncAccountProbe.main(instrumentation,()->(Uri)SyncAccountProbe.field(page,"lastExportUri"));SyncAccountProbe.check(document!=null&&recoveryCode.length==43,"actual recovery result URI or code missing");
            String name=filename(context,document);recoveryName=name;encrypted=read(context,document);report.put("actual_provider_create",true).put("encrypted_package_bytes",encrypted.length);SyncAccountProbe.passed(cases,"actual_saf_export");
            stage="native recovery identity";restored=(byte[])call(core,"restoreSyncRecovery",new Class<?>[]{String.class,byte[].class,byte[].class},account.toString(),recoveryCode,encrypted);
            SyncAccountProbe.check(restored!=null&&Arrays.equals(originalMaterial,restored),"actual provider export failed native identity recovery");report.put("actual_native_recovery",true);SyncAccountProbe.passed(cases,"actual_native_recovery_identity");
            stage="recovery code confirmation";enter(instrumentation,page,"confirmCode",recoveryCode);button(instrumentation,page,"confirm");
            SyncAccountProbe.check(SyncAccountProbe.main(instrumentation,()->SyncAccountProbe.field(page,"expectedCode")==null&&((TextView)SyncAccountProbe.field(page,"recovery")).length()==0),"normal recovery code confirmation retained display");
            for(byte value:shownBuffer)SyncAccountProbe.check(value==0,"normal code confirmation retained shown bytes");SyncAccountProbe.passed(cases,"recovery_code_confirmation");
            stage="actual SAF open";button(instrumentation,page,"choose");selectRecovery(automation,name,output,report,trace);
            trace.step=SyncRecoveryTrace.Step.PROVIDER_RETURN_WAIT;
            await(()->ready(instrumentation,page)&&SyncAccountProbe.main(instrumentation,()->SyncAccountProbe.field(page,"restoreUri")!=null),"actual recovery provider selection did not return");
            trace.step=SyncRecoveryTrace.Step.SELECTED_DOCUMENT_READ;
            Uri selected=SyncAccountProbe.main(instrumentation,()->(Uri)SyncAccountProbe.field(page,"restoreUri"));byte[] selectedBytes=read(context,selected);
            try{trace.step=SyncRecoveryTrace.Step.SELECTED_DOCUMENT_IDENTITY;SyncAccountProbe.check(name.equals(filename(context,selected))&&Arrays.equals(encrypted,selectedBytes),"actual selected document differs from exported file");}finally{Arrays.fill(selectedBytes,(byte)0);}
            report.put("actual_provider_open",true).put("provider_exit_observed",true);SyncAccountProbe.passed(cases,"actual_saf_open");
            stage="normal restore identity";enter(instrumentation,page,"restoreCode",recoveryCode);button(instrumentation,page,"restore");confirmDialog(automation,"导入已有同步密钥？","验证并导入");
            await(()->ready(instrumentation,page)&&SyncAccountProbe.main(instrumentation,()->((TextView)SyncAccountProbe.field(page,"status")).getText().toString().startsWith("同步密钥已验证并保存在本机。")),"normal provider restore did not complete");
            byte[] after=(byte[])call(vault,"load",new Class<?>[]{Context.class,UUID.class},context,account);
            try{SyncAccountProbe.check(Arrays.equals(originalMaterial,after)&&Arrays.equals(originalCipher,Files.readAllBytes(materialFile.toPath())),"normal restore changed existing material or vault");}finally{if(after!=null)Arrays.fill(after,(byte)0);}
            report.put("recovery_identity_unchanged",true).put("normal_buttons_and_dialogs_used",true);SyncAccountProbe.passed(cases,"normal_restore_identity_preserved");
            stage="missing material requires restore";Files.delete(materialFile.toPath());button(instrumentation,page,"reload");
            await(()->ready(instrumentation,page)&&SyncAccountProbe.main(instrumentation,()->(Boolean)SyncAccountProbe.field(page,"requiresRecovery")),"missing material recovery state not shown");
            SyncAccountProbe.check(SyncAccountProbe.main(instrumentation,()->!((Button)SyncAccountProbe.field(page,"create")).isEnabled()&&((Button)SyncAccountProbe.field(page,"choose")).isEnabled()&&((TextView)SyncAccountProbe.field(page,"status")).getText().toString().contains("不能创建新密钥")),"missing material offered creation or hid recovery");
            enter(instrumentation,page,"restoreCode",recoveryCode);button(instrumentation,page,"restore");confirmDialog(automation,"导入已有同步密钥？","验证并导入");
            await(()->ready(instrumentation,page)&&SyncAccountProbe.main(instrumentation,()->(Boolean)SyncAccountProbe.field(page,"prepared")&&!(Boolean)SyncAccountProbe.field(page,"requiresRecovery")),"missing material original-key restoration did not finish");
            byte[] recovered=(byte[])call(vault,"load",new Class<?>[]{Context.class,UUID.class},context,account);
            try{SyncAccountProbe.check(Arrays.equals(originalMaterial,recovered),"missing material restored a different key");}finally{if(recovered!=null)Arrays.fill(recovered,(byte)0);}
            report.put("missing_material_restore_ui",true);SyncAccountProbe.passed(cases,"missing_material_requires_restore");
            stage="provider cleanup before finish";SyncAccountProbe.check(DocumentsContract.deleteDocument(context.getContentResolver(),document),"new recovery provider document deletion failed");document=null;documentDeleted=true;SyncAccountProbe.passed(cases,"provider_file_deleted_before_finish");
            stage="stop clears code";enter(instrumentation,page,"restoreCode",recoveryCode);SyncAccountProbe.main(instrumentation,()->{page.finish();return null;});instrumentation.waitForIdleSync();
            await(()->SyncAccountProbe.main(instrumentation,()->((EditText)SyncAccountProbe.field(page,"restoreCode")).length()==0&&((EditText)SyncAccountProbe.field(page,"confirmCode")).length()==0&&((TextView)SyncAccountProbe.field(page,"recovery")).length()==0&&SyncAccountProbe.field(page,"expectedCode")==null),"stopped recovery Activity retained secrets");SyncAccountProbe.passed(cases,"stop_clears_recovery_state");report.put("status","PASS");
        }catch(Throwable error){operationFailure=error;
            // Keep arbitrary Throwable messages/causes out: they can contain a URI
            // or provider text. Enums and booleans distinguish where selection failed.
            if(stage.equals("actual SAF open")){
                try{observeProvider(instrumentation.getUiAutomation(),recoveryName,trace);}catch(Throwable ignored){}
                report.put("failure_diagnostic",diagnostic(trace));
            }
            cases.put(new JSONObject().put("id",stage).put("passed",false).put("failure_type",error.getClass().getSimpleName()));throw new Exception("synthetic sync preparation UI case: "+stage,error);}
        finally{
            Throwable cleanup=null;
            try{
                if(saveTriggered&&!documentDeleted&&document==null&&activity!=null){final Activity page=activity;document=SyncAccountProbe.main(instrumentation,()->(Uri)SyncAccountProbe.field(page,"lastExportUri"));}
                if(document!=null){SyncAccountProbe.check(DocumentsContract.deleteDocument(context.getContentResolver(),document),"new recovery provider document cleanup failed");documentDeleted=true;document=null;}
                if(saveTriggered)SyncAccountProbe.check(documentDeleted,"recovery document ownership URI unavailable for cleanup");
            }catch(Throwable error){cleanup=append(cleanup,error);}
            try{if(activity!=null){final Activity page=activity;SyncAccountProbe.main(instrumentation,()->{page.finish();return null;});instrumentation.waitForIdleSync();}}catch(Throwable error){cleanup=append(cleanup,error);}
            try{if(handle!=0)call(core,"close",new Class<?>[]{long.class},handle);}catch(Throwable error){cleanup=append(cleanup,error);}
            if(owned){
                boolean rootStillOwned=true;
                try{Object current=call(store,"load",new Class<?>[]{Context.class},context);try{SyncAccountProbe.check(current==null||account.equals(SyncAccountProbe.field(current,"account")),"UI fixture connection changed before cleanup");}finally{SyncAccountProbe.close(model,current);}call(store,"forget",new Class<?>[]{Context.class},context);}catch(Throwable error){rootStillOwned=false;cleanup=append(cleanup,error);}
                try{call(vault,"forgetConnection",new Class<?>[]{Context.class,UUID.class},context,account);call(vault,"forget",new Class<?>[]{Context.class,UUID.class},context,account);}catch(Throwable error){cleanup=append(cleanup,error);}
                try{if(rootStillOwned&&root.exists())try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);}}catch(Throwable error){cleanup=append(cleanup,error);}
                try{SyncAccountProbe.check(context.getSharedPreferences("sync_connection",Context.MODE_PRIVATE).getAll().isEmpty()&&!root.exists(),"owned UI fixture cleanup left index or root");}catch(Throwable error){cleanup=append(cleanup,error);}
            }
            for(byte[] bytes:new byte[][]{originalMaterial,originalCipher,recoveryCode,encrypted,restored,shownBuffer})if(bytes!=null)Arrays.fill(bytes,(byte)0);
            report.put("cleanup_completed",cleanup==null).put("provider_document_deleted",documentDeleted).put("fresh_fixture_owned",owned).put("code_entry_method","synthetic_programmatic_EditText_normal_confirmation");
            if(cleanup!=null){report.put("status","FAIL").put("cleanup_failure_type",cleanup.getClass().getSimpleName());if(operationFailure!=null)operationFailure.addSuppressed(cleanup);}
            Files.write(new File(output,"sync-preparation-ui-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            if(cleanup!=null&&operationFailure==null)throw new Exception("sync preparation UI fixture cleanup failed",cleanup);
        }
    }
    private SyncPreparationUiProbe(){}
}
