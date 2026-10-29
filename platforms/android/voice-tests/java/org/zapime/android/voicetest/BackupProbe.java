// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import android.text.InputType;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Synthetic snapshots, actual JNI and a guarded system ACTION_CREATE_DOCUMENT flow. */
final class BackupProbe {
    static final class Profile {
        final Context context;final File root;final long handle;
        Profile(Context base,File directory,Class<?> core,Class<?> installer)throws Exception{
            SyncAccountProbe.check(directory.mkdirs(),"fresh backup profile");
            context=new ContextWrapper(base){@Override public File getFilesDir(){return directory;}};root=new File(directory,"zapime");
            SyncAccountProbe.call(installer,null,"prepare",new Class<?>[]{Context.class},context);
            handle=(Long)SyncAccountProbe.call(core,null,"open",new Class<?>[]{String.class,boolean.class},root.toString(),false);SyncAccountProbe.check(handle!=0,"backup fixture native open");
        }
    }
    static JSONObject request(Class<?> core,long handle,JSONObject input)throws Exception{
        JSONObject result=new JSONObject((String)SyncAccountProbe.call(core,null,"request",new Class<?>[]{long.class,String.class},handle,input.toString()));SyncAccountProbe.check(!result.has("error"),"backup fixture native request");return result;
    }
    static byte[] archive(Class<?> core,Profile p)throws Exception{return request(core,p.handle,new JSONObject().put("op","personal_export")).getString("archive").getBytes(StandardCharsets.UTF_8);}
    static byte[] create(Class<?> bridge,Context context,byte[] code)throws Exception{return (byte[])SyncAccountProbe.call(bridge,null,"create",new Class<?>[]{Context.class,byte[].class},context,code);}
    static int restore(Class<?> bridge,Context context,byte[] code,byte[] encrypted)throws Exception{return (Integer)SyncAccountProbe.call(bridge,null,"restore",new Class<?>[]{Context.class,byte[].class,byte[].class},context,code,encrypted);}
    static void saveOnce(UiAutomation automation,File output)throws Exception{
        JSONArray shape=new JSONArray();
        for(int attempt=0;attempt<200;attempt++){
            AccessibilityNodeInfo root=automation.getRootInActiveWindow();
            if(root!=null&&root.getPackageName()!=null&&root.getPackageName().toString().contains("documentsui")){
                ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();queue.add(root);
                while(!queue.isEmpty()){
                    AccessibilityNodeInfo node=queue.remove();if(shape.length()<600)shape.put(new JSONObject().put("class",String.valueOf(node.getClassName())).put("id",String.valueOf(node.getViewIdResourceName())).put("enabled",node.isEnabled()).put("clickable",node.isClickable()));String id=node.getViewIdResourceName(),label=node.getText()==null?"":node.getText().toString();
                    if(node.isEnabled()&&((id!=null&&(id.endsWith(":id/button1")||id.endsWith(":id/action_menu_save")))||label.equalsIgnoreCase("save")||label.equals("保存"))){
                        AccessibilityNodeInfo target=node;for(int n=0;n<5&&target!=null&&!target.isClickable();n++)target=target.getParent();
                        if(target!=null&&target.isClickable()){SyncAccountProbe.check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK),"system document save action rejected");return;}
                    }
                    for(int n=0;n<node.getChildCount();n++){AccessibilityNodeInfo child=node.getChild(n);if(child!=null)queue.add(child);}
                }
            }
            Thread.sleep(100);
        }
        Files.write(new File(output,"backup-provider-ui-shape.json").toPath(),shape.toString(2).getBytes(StandardCharsets.UTF_8));
        throw new IOException("system document save control not observed");
    }
    static void confirmCopy(UiAutomation automation)throws Exception{
        for(int attempt=0;attempt<100;attempt++){
            AccessibilityNodeInfo root=automation.getRootInActiveWindow();
            if(root!=null&&"org.zapime.android".contentEquals(root.getPackageName()==null?"":root.getPackageName())){
                ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();queue.add(root);
                while(!queue.isEmpty()){
                    AccessibilityNodeInfo node=queue.remove();String id=node.getViewIdResourceName();
                    if(node.isEnabled()&&node.isClickable()&&((id!=null&&id.endsWith(":id/button1"))||"复制恢复码".contentEquals(node.getText()==null?"":node.getText()))){SyncAccountProbe.check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK),"explicit copy confirmation rejected");return;}
                    for(int n=0;n<node.getChildCount();n++){AccessibilityNodeInfo child=node.getChild(n);if(child!=null)queue.add(child);}
                }
            }
            Thread.sleep(100);
        }
        throw new IOException("explicit copy confirmation not observed");
    }
    static void run(Instrumentation i,File output,boolean dedicatedCI)throws Exception{
        SyncAccountProbe.check(dedicatedCI,"system document fixture requires explicit dedicated CI argument");
        Context base=i.getTargetContext();ClassLoader loader=base.getClassLoader();Class<?> core=Class.forName("org.zapime.android.NativeCore",true,loader),bridge=Class.forName("org.zapime.android.LocalBackup",true,loader),installer=Class.forName("org.zapime.android.ResourceInstaller",true,loader);
        File fixture=new File(base.getCacheDir(),"local-backup-"+UUID.randomUUID());SyncAccountProbe.check(fixture.mkdirs(),"fresh backup root");List<Profile> profiles=new ArrayList<>();JSONArray cases=new JSONArray();String stage="snapshot";byte[] code=new byte[43],encrypted=null,safCode=null,safCipher=null;Activity activity=null;Uri created=null;long primary=0;Exception operationFailure=null;boolean documentDeleted=false;
        try{
            Profile source=new Profile(base,new File(fixture,"source"),core,installer);profiles.add(source);
            for(int n=0;n<2;n++)request(core,source.handle,new JSONObject().put("op","personal_edit").put("id",UUID.randomUUID().toString()).put("operation_id",UUID.randomUUID().toString()).put("expected_revision",0).put("content",new JSONObject().put("type","favorite").put("title","synthetic").put("text","本机备份测试 ☃ "+n)).put("deleted",n==1));
            byte[] expected=archive(core,source);encrypted=create(bridge,source.context,code);SyncAccountProbe.check(encrypted.length>0&&encrypted.length<=6*1024*1024&&new String(code,StandardCharsets.US_ASCII).matches("[A-Za-z0-9_-]{43}"),"native backup envelope/code boundary");
            SyncAccountProbe.check(!new String(encrypted,StandardCharsets.UTF_8).contains("本机备份测试"),"backup plaintext escaped encryption");SyncAccountProbe.passed(cases,"native_snapshot");stage="authentication";
            Profile target=new Profile(base,new File(fixture,"target"),core,installer);profiles.add(target);byte[] before=archive(core,target);byte[] wrong=new byte[43];Arrays.fill(wrong,(byte)'A');final byte[] original=encrypted;
            SyncAccountProbe.reject(()->restore(bridge,target.context,wrong,original));SyncAccountProbe.check(Arrays.equals(before,archive(core,target)),"wrong code changed data");
            byte[] tampered=encrypted.clone();tampered[tampered.length/2]^=1;SyncAccountProbe.reject(()->restore(bridge,target.context,code,tampered));SyncAccountProbe.check(Arrays.equals(before,archive(core,target)),"tampered file changed data");
            SyncAccountProbe.check(restore(bridge,target.context,code,encrypted)==2&&Arrays.equals(expected,archive(core,target)),"actual JNI restore differs");JSONObject refreshed=request(core,target.handle,new JSONObject().put("op","personal_reload"));SyncAccountProbe.check(refreshed.getJSONArray("records").length()==1,"retained native settings session did not reload restored active records");SyncAccountProbe.passed(cases,"authentication_and_recovery");stage="existing state";
            SyncAccountProbe.reject(()->restore(bridge,target.context,code,original));SyncAccountProbe.check(Arrays.equals(expected,archive(core,target)),"repeat restore changed existing records");
            Profile bound=new Profile(base,new File(fixture,"bound"),core,installer);profiles.add(bound);String account=UUID.randomUUID().toString();try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(bound.root,"data/personal.sqlite3").toString(),null,SQLiteDatabase.OPEN_READWRITE)){db.execSQL("INSERT INTO metadata VALUES('sync_profile',?)",new Object[]{account});}
            SyncAccountProbe.reject(()->restore(bridge,bound.context,code,original));SyncAccountProbe.check(new JSONObject((String)SyncAccountProbe.call(core,null,"syncProfile",new Class<?>[]{String.class},bound.root.toString())).getString("account_id").equals(account),"restore changed bound identity");SyncAccountProbe.passed(cases,"nonempty_bound_preserved");stage="worker";
            SyncAccountProbe.main(i,()->{boolean refused=false;try{create(bridge,source.context,new byte[43]);}catch(IOException e){refused="backup_requires_worker".equals(e.getMessage());}SyncAccountProbe.check(refused,"backup main worker guard reason");return null;});
            SyncAccountProbe.check(SyncAccountProbe.call(core,null,"createLocalBackup",new Class<?>[]{String.class,byte[].class},source.root.toString(),new byte[42])==null,"JNI code out length ignored");SyncAccountProbe.passed(cases,"worker_and_limits");stage="sensitive page";
            activity=i.startActivitySync(new Intent().setClassName(base,"org.zapime.android.BackupActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity page=activity;
            SyncAccountProbe.main(i,()->{SyncAccountProbe.check((page.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"backup screen capture allowed");for(String field:new String[]{"confirmCode","restoreCode"}){EditText input=(EditText)SyncAccountProbe.field(page,field);SyncAccountProbe.check(!input.isSaveEnabled()&&(input.getInputType()&InputType.TYPE_MASK_VARIATION)==InputType.TYPE_TEXT_VARIATION_PASSWORD&&(input.getImeOptions()&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0&&input.getImportantForAutofill()==View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,"backup sensitive input policy");}return null;});SyncAccountProbe.passed(cases,"sensitive_activity");stage="system SAF export";
            primary=(Long)SyncAccountProbe.call(core,null,"open",new Class<?>[]{String.class,boolean.class},new File(base.getFilesDir(),"zapime").toString(),false);SyncAccountProbe.check(primary!=0,"main CI profile open");
            byte[] expectedMain=request(core,primary,new JSONObject().put("op","personal_export")).getString("archive").getBytes(StandardCharsets.UTF_8);
            SyncAccountProbe.main(i,()->{((Button)SyncAccountProbe.field(page,"create")).performClick();return null;});saveOnce(i.getUiAutomation(),output);
            for(int n=0;n<300;n++){if(SyncAccountProbe.main(i,()->SyncAccountProbe.field(page,"expectedCode")!=null))break;if(n==299)throw new IOException("SAF encrypted export did not complete");Thread.sleep(100);}
            safCode=SyncAccountProbe.main(i,()->((byte[])SyncAccountProbe.field(page,"expectedCode")).clone());created=SyncAccountProbe.main(i,()->(Uri)SyncAccountProbe.field(page,"lastExportUri"));SyncAccountProbe.check(created!=null,"no actual provider URI");
            try(InputStream in=base.getContentResolver().openInputStream(created)){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){SyncAccountProbe.check(bytes.size()+n<=6*1024*1024,"SAF encrypted file oversized");bytes.write(buffer,0,n);}safCipher=bytes.toByteArray();}
            Profile safTarget=new Profile(base,new File(fixture,"saf-target"),core,installer);profiles.add(safTarget);restore(bridge,safTarget.context,safCode,safCipher);SyncAccountProbe.check(Arrays.equals(expectedMain,archive(core,safTarget)),"actual SAF export did not recover exact data");
            final String confirmation=new String(safCode,StandardCharsets.US_ASCII);stage="explicit copy";
            SyncAccountProbe.main(i,()->{((Button)SyncAccountProbe.field(page,"copyCode")).performClick();return null;});confirmCopy(i.getUiAutomation());
            SyncAccountProbe.main(i,()->{ClipboardManager clipboard=(ClipboardManager)page.getSystemService(Context.CLIPBOARD_SERVICE);ClipData clip=clipboard.getPrimaryClip();SyncAccountProbe.check(clip!=null&&clip.getItemCount()==1&&confirmation.contentEquals(clip.getItemAt(0).getText())&&clip.getDescription().getExtras()!=null&&clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE",false),"explicit copy missing sensitive policy");clipboard.setPrimaryClip(ClipData.newPlainText("synthetic unrelated","synthetic replacement"));((Button)SyncAccountProbe.field(page,"clearClipboard")).performClick();SyncAccountProbe.check("synthetic replacement".contentEquals(clipboard.getPrimaryClip().getItemAt(0).getText()),"clear erased newer clipboard");((Button)SyncAccountProbe.field(page,"copyCode")).performClick();return null;});confirmCopy(i.getUiAutomation());
            SyncAccountProbe.main(i,()->{((Button)SyncAccountProbe.field(page,"clearClipboard")).performClick();ClipboardManager clipboard=(ClipboardManager)page.getSystemService(Context.CLIPBOARD_SERVICE);SyncAccountProbe.check(!clipboard.hasPrimaryClip(),"explicit clear retained copied recovery code");return null;});SyncAccountProbe.passed(cases,"explicit_sensitive_copy_and_clear");
            SyncAccountProbe.main(i,()->{((EditText)SyncAccountProbe.field(page,"confirmCode")).setText(confirmation);((Button)SyncAccountProbe.field(page,"confirm")).performClick();SyncAccountProbe.check(SyncAccountProbe.field(page,"expectedCode")==null&&((TextView)SyncAccountProbe.field(page,"recovery")).length()==0,"code confirmation retained secrets");return null;});SyncAccountProbe.passed(cases,"system_saf_export_and_recovery");stage="fixture document cleanup";
            SyncAccountProbe.check(DocumentsContract.deleteDocument(base.getContentResolver(),created),"synthetic SAF document cleanup failed");created=null;documentDeleted=true;stage="stop cleanup";
            SyncAccountProbe.main(i,()->{((EditText)SyncAccountProbe.field(page,"restoreCode")).setText(confirmation);page.finish();return null;});
            for(int n=0;n<100;n++){if(SyncAccountProbe.main(i,()->((EditText)SyncAccountProbe.field(page,"restoreCode")).length()==0))break;if(n==99)throw new IOException("backup onStop retained code input");Thread.sleep(50);}SyncAccountProbe.passed(cases,"stop_clears_sensitive_state");
        }catch(Throwable error){cases.put(new JSONObject().put("id",stage).put("passed",false).put("failure_type",error.getClass().getSimpleName()));operationFailure=new Exception("synthetic encrypted backup case: "+stage,error);throw operationFailure;}
        finally{
            Throwable cleanup=null;
            // ACTION_CREATE_DOCUMENT grants belong to the Activity lifetime.
            // Delete only this test's document before finish revokes that grant.
            try{if(!documentDeleted&&created==null&&activity!=null){final Activity page=activity;created=SyncAccountProbe.main(i,()->(Uri)SyncAccountProbe.field(page,"lastExportUri"));}if(created!=null)SyncAccountProbe.check(DocumentsContract.deleteDocument(base.getContentResolver(),created),"synthetic SAF document cleanup failed");}catch(Throwable error){cleanup=cleanupFailure(cleanup,error);}
            try{if(activity!=null){final Activity page=activity;SyncAccountProbe.main(i,()->{page.finish();return null;});i.waitForIdleSync();}}catch(Throwable error){cleanup=cleanupFailure(cleanup,error);}
            try{if(primary!=0)SyncAccountProbe.call(core,null,"close",new Class<?>[]{long.class},primary);}catch(Throwable error){cleanup=cleanupFailure(cleanup,error);}
            for(Profile p:profiles)try{SyncAccountProbe.call(core,null,"close",new Class<?>[]{long.class},p.handle);}catch(Throwable error){cleanup=cleanupFailure(cleanup,error);}
            Arrays.fill(code,(byte)0);if(encrypted!=null)Arrays.fill(encrypted,(byte)0);if(safCode!=null)Arrays.fill(safCode,(byte)0);if(safCipher!=null)Arrays.fill(safCipher,(byte)0);
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture.toPath())){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}catch(Throwable error){cleanup=cleanupFailure(cleanup,error);}
            JSONObject report=new JSONObject().put("synthetic",true).put("api_level",Build.VERSION.SDK_INT).put("cases",cases).put("cleanup_completed",cleanup==null).put("scope","Actual JNI snapshot/authentication/fresh fixture restore, bounded file boundary, secure Activity lifecycle, real system ACTION_CREATE_DOCUMENT export and exact recovery of its bytes into a separate empty fixture. Not positive SAF import UI, phone execution, sync keys or cloud account recovery.");
            if(cleanup!=null)report.put("cleanup_failure_type",cleanup.getClass().getSimpleName());
            Files.write(new File(output,"local-backup-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            if(cleanup!=null){if(operationFailure!=null)operationFailure.addSuppressed(cleanup);else throw new Exception("synthetic backup fixture cleanup failed",cleanup);}
        }
    }
    private static Throwable cleanupFailure(Throwable first,Throwable next){if(first==null)return next;first.addSuppressed(next);return first;}
    private BackupProbe(){}
}
