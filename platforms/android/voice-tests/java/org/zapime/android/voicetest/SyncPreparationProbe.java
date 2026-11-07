// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real stateless JNI + Android Keystore on independently named synthetic
 * database/vault fixtures. No HTTP, model recognition or global account setup.
 */
final class SyncPreparationProbe {
    private static final class Profile {
        final Context context;
        final File directory,root,materialFile;
        final String preferences;
        final UUID account;
        Profile(Context base,File directory,File template,UUID account)throws Exception {
            this.directory=directory;this.account=account;root=new File(directory,"zapime");
            SyncAccountProbe.check(directory.mkdirs(),"fresh sync preparation profile");
            File noBackup=new File(directory,"no-backup");SyncAccountProbe.check(noBackup.mkdir(),"private synthetic noBackup directory");
            preferences="sync-preparation-"+UUID.randomUUID()+"-";
            context=new ContextWrapper(base){
                @Override public File getFilesDir(){return directory;}
                @Override public File getNoBackupFilesDir(){return noBackup;}
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(preferences+name,mode);}
            };
            materialFile=new File(noBackup,"sync-material-"+account+".bin");
            if(template!=null){File database=new File(root,"data/personal.sqlite3");SyncAccountProbe.check(database.getParentFile().mkdirs(),"preparation fixture data directory");Files.copy(template.toPath(),database.toPath());}
        }
    }
    private static Object invoke(Class<?> bridge,String name,Class<?>[] signature,Object...args)throws Exception{return SyncAccountProbe.call(bridge,null,name,signature,args);}
    private static Object inspect(Class<?> bridge,Profile p)throws Exception{return invoke(bridge,"inspect",new Class<?>[]{Context.class},p.context);}
    private static Object create(Class<?> bridge,Profile p,UUID expected)throws Exception{return invoke(bridge,"create",new Class<?>[]{Context.class,UUID.class},p.context,expected);}
    private static Object restore(Class<?> bridge,Profile p,byte[] code,byte[] bytes)throws Exception{return invoke(bridge,"restore",new Class<?>[]{Context.class,UUID.class,byte[].class,byte[].class},p.context,p.account,code,bytes);}
    private static byte[] load(Class<?> vault,Profile p)throws Exception{return (byte[])invoke(vault,"load",new Class<?>[]{Context.class,UUID.class},p.context,p.account);}
    private static byte[] newMaterial(Class<?> core,UUID account)throws Exception{return (byte[])invoke(core,"newSyncMaterial",new Class<?>[]{String.class},account.toString());}
    private static UUID key(Object state)throws Exception{return (UUID)SyncAccountProbe.field(state,"keyId");}
    private static boolean prepared(Object state)throws Exception{return (Boolean)SyncAccountProbe.field(state,"prepared");}
    private static void rejectCode(SyncAccountProbe.Task<?> task,String code)throws Exception {
        boolean rejected=false;try{task.run();}catch(IOException expected){rejected=code.equals(expected.getMessage());}
        SyncAccountProbe.check(rejected,"operation did not reject with expected fixed code");
    }
    private static boolean contains(byte[] bytes,byte[] value){
        for(int i=0;i+value.length<=bytes.length;i++){boolean same=true;for(int n=0;n<value.length;n++)if(bytes[i+n]!=value[n]){same=false;break;}if(same)return true;}return false;
    }
    private static byte[] business(Profile p)throws Exception {
        JSONArray tables=new JSONArray();
        try(SQLiteDatabase database=SQLiteDatabase.openDatabase(new File(p.root,"data/personal.sqlite3").toString(),null,SQLiteDatabase.OPEN_READONLY)){
            for(String table:new String[]{"records","operations","sync_queue","sync_uploads","sync_versions","sync_received"}){
                JSONArray rows=new JSONArray();try(Cursor cursor=database.rawQuery("SELECT * FROM "+table+" ORDER BY 1",null)){
                    while(cursor.moveToNext()){JSONArray row=new JSONArray();for(int n=0;n<cursor.getColumnCount();n++)row.put(cursor.isNull(n)?JSONObject.NULL:cursor.getString(n));rows.put(row);}
                }tables.put(new JSONObject().put("table",table).put("rows",rows));
            }
        }
        return tables.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void credential(Class<?> model,Class<?> store,Profile p,String role)throws Exception {
        byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);
        String token="zap1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(secret);Arrays.fill(secret,(byte)0);
        UUID device=UUID.randomUUID();JSONObject credential=new JSONObject().put("account_id",p.account.toString()).put("device_id",device.toString()).put("token",token).put("expires_at","2099-01-01T00:00:00Z");
        Object draft=invoke(model,"imported",new Class<?>[]{String.class,String.class},"https://synthetic.example",credential.toString()),verified=null;
        try{
            JSONObject session=new JSONObject().put("protocol",1).put("account_id",p.account.toString()).put("device_id",device.toString()).put("role",role).put("expires_at","2099-01-01T00:00:00Z");
            verified=SyncAccountProbe.call(model,draft,"verify",new Class<?>[]{byte[].class},session.toString().getBytes(StandardCharsets.UTF_8));
            invoke(store,"save",new Class<?>[]{Context.class,model},p.context,verified);
        }finally{SyncAccountProbe.close(model,draft);SyncAccountProbe.close(model,verified);}
    }
    private static File template(Context base,File directory,Class<?> core)throws Exception {
        SyncAccountProbe.check(directory.mkdir(),"template directory");Context context=new ContextWrapper(base){@Override public File getFilesDir(){return directory;}};
        Class<?> installer=Class.forName("org.zapime.android.ResourceInstaller",true,base.getClassLoader());invoke(installer,"prepare",new Class<?>[]{Context.class},context);
        File root=new File(directory,"zapime");long handle=(Long)invoke(core,"open",new Class<?>[]{String.class,boolean.class},root.toString(),false);
        SyncAccountProbe.check(handle!=0,"preparation template native initialization");
        try{
            JSONObject edit=new JSONObject().put("op","personal_edit").put("id",UUID.randomUUID().toString()).put("operation_id",UUID.randomUUID().toString()).put("expected_revision",0)
                    .put("content",new JSONObject().put("type","favorite").put("title","synthetic preparation").put("text","同步准备合成资料"));
            JSONObject result=new JSONObject((String)invoke(core,"request",new Class<?>[]{long.class,String.class},handle,edit.toString()));SyncAccountProbe.check(!result.has("error"),"preparation template edit");
        }finally{invoke(core,"close",new Class<?>[]{long.class},handle);}
        File database=new File(root,"data/personal.sqlite3");
        // Copy a closed, explicitly checkpointed database, never a live WAL file.
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.toString(),null,SQLiteDatabase.OPEN_READWRITE);Cursor result=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){
            SyncAccountProbe.check(result.moveToFirst()&&result.getInt(0)==0,"preparation template checkpoint busy");
        }
        return database;
    }
    private static Throwable failure(Throwable current,Throwable next){if(current==null)return next;current.addSuppressed(next);return current;}
    static void run(Instrumentation instrumentation,File output)throws Exception {
        Context base=instrumentation.getTargetContext();ClassLoader loader=base.getClassLoader();
        Class<?> bridge=Class.forName("org.zapime.android.SyncPreparation",true,loader),core=Class.forName("org.zapime.android.NativeCore",true,loader),vault=Class.forName("org.zapime.android.SyncSecrets",true,loader),model=Class.forName("org.zapime.android.SyncConnection",true,loader),store=Class.forName("org.zapime.android.SyncConnectionStore",true,loader);
        File fixture=new File(base.getCacheDir(),"sync-preparation-"+UUID.randomUUID());SyncAccountProbe.check(fixture.mkdirs(),"fresh sync preparation fixture");
        List<Profile> profiles=new ArrayList<>();List<byte[]> sensitive=new ArrayList<>();JSONArray cases=new JSONArray();String stage="fresh create";Throwable operationFailure=null;Activity activity=null;
        try{
            File template=template(base,new File(fixture,"template"),core);UUID account=UUID.randomUUID();
            Profile source=new Profile(base,new File(fixture,"source"),template,account);profiles.add(source);credential(model,store,source,"owner");byte[] before=business(source);
            SyncAccountProbe.check(!prepared(inspect(bridge,source)),"fresh fixture reported prepared");Object created=create(bridge,source,account);SyncAccountProbe.check(prepared(created)&&key(created)!=null,"native preparation did not bind key");
            byte[] material=load(vault,source);sensitive.add(material);byte[] cipher=Files.readAllBytes(source.materialFile.toPath());
            SyncAccountProbe.check(material!=null&&!contains(cipher,material)&&Arrays.equals(before,business(source)),"preparation changed business rows or saved plaintext");SyncAccountProbe.passed(cases,"fresh_create_and_binding");
            stage="idempotent material";
            Object again=create(bridge,source,account);SyncAccountProbe.check(prepared(again)&&key(created).equals(key(again))&&Arrays.equals(cipher,Files.readAllBytes(source.materialFile.toPath()))&&Arrays.equals(before,business(source)),"idempotent preparation replaced material or rows");
            byte[] different=newMaterial(core,account);sensitive.add(different);final byte[] differentValue=different;
            SyncAccountProbe.reject(()->invoke(vault,"saveIfAbsent",new Class<?>[]{Context.class,UUID.class,byte[].class},source.context,account,differentValue));SyncAccountProbe.check(Arrays.equals(cipher,Files.readAllBytes(source.materialFile.toPath())),"create-only vault overwrote different key");
            byte[] tampered=cipher.clone();tampered[tampered.length-1]^=1;Files.write(source.materialFile.toPath(),tampered);
            try{SyncAccountProbe.reject(()->create(bridge,source,account));SyncAccountProbe.check(Arrays.equals(tampered,Files.readAllBytes(source.materialFile.toPath())),"unreadable material was replaced by initialization");}
            finally{Files.write(source.materialFile.toPath(),cipher);}
            SyncAccountProbe.check(prepared(inspect(bridge,source)),"exact fixture repair lost preparation");SyncAccountProbe.passed(cases,"create_only_idempotent");
            stage="export and authenticated restore";
            byte[] code=new byte[43];sensitive.add(code);byte[] encrypted=(byte[])invoke(bridge,"recovery",new Class<?>[]{Context.class,UUID.class,byte[].class},source.context,account,code);sensitive.add(encrypted);
            SyncAccountProbe.check(encrypted!=null&&encrypted.length>0&&encrypted.length<=16*1024&&new String(code,StandardCharsets.US_ASCII).matches("[A-Za-z0-9_-]{43}"),"bounded recovery output");
            Profile target=new Profile(base,new File(fixture,"target"),template,account);profiles.add(target);credential(model,store,target,"member");
            byte[] badCode=code.clone();sensitive.add(badCode);badCode[0]=(byte)(badCode[0]=='A'?'B':'A');
            SyncAccountProbe.reject(()->restore(bridge,target,badCode,encrypted));SyncAccountProbe.check(!target.materialFile.exists()&&!prepared(inspect(bridge,target)),"bad recovery mutated target");
            Object recovered=restore(bridge,target,code,encrypted);byte[] recoveredMaterial=load(vault,target);sensitive.add(recoveredMaterial);
            SyncAccountProbe.check(prepared(recovered)&&key(created).equals(key(recovered))&&Arrays.equals(material,recoveredMaterial)&&Arrays.equals(before,business(target)),"recovery did not restore same typed key");SyncAccountProbe.passed(cases,"authenticated_recovery_roundtrip");
            stage="bound vault loss";
            Files.delete(target.materialFile.toPath());rejectCode(()->create(bridge,target,account),"sync_material_missing");
            SyncAccountProbe.check(!target.materialFile.exists()&&!prepared(inspect(bridge,target)),"missing bound material silently recreated");
            SyncAccountProbe.check(prepared(restore(bridge,target,code,encrypted)),"same-key recovery did not repair missing local material");SyncAccountProbe.passed(cases,"bound_missing_material_requires_recovery");
            stage="orphan material resume";
            Profile orphan=new Profile(base,new File(fixture,"orphan"),template,account);profiles.add(orphan);credential(model,store,orphan,"member");
            invoke(vault,"saveIfAbsent",new Class<?>[]{Context.class,UUID.class,byte[].class},orphan.context,account,material);byte[] orphanCipher=Files.readAllBytes(orphan.materialFile.toPath());
            SyncAccountProbe.check(!prepared(inspect(bridge,orphan))&&prepared(create(bridge,orphan,account))&&Arrays.equals(orphanCipher,Files.readAllBytes(orphan.materialFile.toPath())),"orphan retry regenerated key");SyncAccountProbe.passed(cases,"vault_before_binding_retry");
            stage="fingerprint and mismatched key";
            byte[] rootChanged=material.clone();sensitive.add(rootChanged);rootChanged[rootChanged.length-1]^=1;
            byte[] variantCode=new byte[43];sensitive.add(variantCode);byte[] variant=(byte[])invoke(core,"createSyncRecovery",new Class<?>[]{String.class,byte[].class,byte[].class},account.toString(),rootChanged,variantCode);sensitive.add(variant);
            SyncAccountProbe.check(variant!=null,"same-id different-root recovery fixture creation");
            SyncAccountProbe.reject(()->restore(bridge,source,variantCode,variant));
            SyncAccountProbe.check(Arrays.equals(cipher,Files.readAllBytes(source.materialFile.toPath()))&&Arrays.equals(before,business(source)),"same key ID with other root replaced prepared identity");
            Profile occupied=new Profile(base,new File(fixture,"occupied"),template,account);profiles.add(occupied);credential(model,store,occupied,"owner");
            invoke(vault,"saveIfAbsent",new Class<?>[]{Context.class,UUID.class,byte[].class},occupied.context,account,different);byte[] occupiedCipher=Files.readAllBytes(occupied.materialFile.toPath());
            SyncAccountProbe.reject(()->restore(bridge,occupied,code,encrypted));SyncAccountProbe.check(Arrays.equals(occupiedCipher,Files.readAllBytes(occupied.materialFile.toPath()))&&!prepared(inspect(bridge,occupied)),"restore replaced existing unbound material");SyncAccountProbe.passed(cases,"different_material_and_fingerprint_preserved");
            stage="identity and role";
            rejectCode(()->create(bridge,source,UUID.randomUUID()),"sync_connection_changed");
            Profile newMember=new Profile(base,new File(fixture,"new-member"),template,account);profiles.add(newMember);credential(model,store,newMember,"member");
            rejectCode(()->create(bridge,newMember,account),"sync_preparation_owner");SyncAccountProbe.check(!newMember.materialFile.exists(),"member created initial root material");
            Profile wrongAccount=new Profile(base,new File(fixture,"other-account"),template,UUID.randomUUID());profiles.add(wrongAccount);credential(model,store,wrongAccount,"owner");
            SyncAccountProbe.reject(()->restore(bridge,wrongAccount,code,encrypted));SyncAccountProbe.check(!wrongAccount.materialFile.exists(),"cross-account recovery installed material");SyncAccountProbe.passed(cases,"connection_snapshot_and_member_policy");
            stage="database boundaries";
            Profile absent=new Profile(base,new File(fixture,"absent"),null,UUID.randomUUID());profiles.add(absent);credential(model,store,absent,"owner");
            SyncAccountProbe.reject(()->create(bridge,absent,absent.account));SyncAccountProbe.check(!absent.root.exists()&&!absent.materialFile.exists(),"prepare created absent database or material");
            Profile future=new Profile(base,new File(fixture,"future"),template,UUID.randomUUID());profiles.add(future);credential(model,store,future,"owner");
            try(SQLiteDatabase database=SQLiteDatabase.openDatabase(new File(future.root,"data/personal.sqlite3").toString(),null,SQLiteDatabase.OPEN_READWRITE)){database.execSQL("PRAGMA user_version=3");}
            SyncAccountProbe.reject(()->create(bridge,future,future.account));SyncAccountProbe.check(!future.materialFile.exists(),"future database installed material");SyncAccountProbe.passed(cases,"absent_future_database_fail_closed");
            stage="worker guard";
            CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean rejected=new AtomicBoolean();AtomicLong elapsed=new AtomicLong();
            Thread holder=new Thread(()->{synchronized(bridge){held.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}});
            try{holder.start();SyncAccountProbe.check(held.await(5,TimeUnit.SECONDS),"preparation contention fixture");SyncAccountProbe.main(instrumentation,()->{
                long start=SystemClock.uptimeMillis();try{inspect(bridge,source);}catch(IOException expected){rejected.set("sync_preparation_requires_worker".equals(expected.getMessage()));}elapsed.set(SystemClock.uptimeMillis()-start);return null;
            });SyncAccountProbe.check(rejected.get()&&elapsed.get()<1800,"preparation main guard blocked behind lock");}
            finally{release.countDown();holder.join(1000);}SyncAccountProbe.passed(cases,"worker_guard_before_lock");
            stage="connection removal";
            invoke(store,"forget",new Class<?>[]{Context.class},source.context);byte[] retained=load(vault,source);sensitive.add(retained);SyncAccountProbe.check(Arrays.equals(material,retained),"connection removal deleted prepared key");
            SyncAccountProbe.reject(()->create(bridge,source,account));credential(model,store,source,"owner");SyncAccountProbe.check(prepared(inspect(bridge,source)),"connection re-add lost preparation");SyncAccountProbe.passed(cases,"connection_removal_preserves_material");
            stage="secure activity lifecycle";
            activity=instrumentation.startActivitySync(new Intent().setClassName(base,"org.zapime.android.SyncPreparationActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity page=activity;
            for(int n=0;n<100;n++){if(SyncAccountProbe.main(instrumentation,()->!(Boolean)SyncAccountProbe.field(page,"busy")))break;if(n==99)throw new IOException("preparation Activity local load timeout");Thread.sleep(50);}
            byte[] shown=new byte[43];Arrays.fill(shown,(byte)'A');sensitive.add(shown);AtomicLong oldTicket=new AtomicLong();
            SyncAccountProbe.main(instrumentation,()->{
                SyncAccountProbe.check((page.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"preparation page permits screenshots");
                for(String name:new String[]{"confirmCode","restoreCode"}){
                    EditText input=(EditText)SyncAccountProbe.field(page,name);
                    SyncAccountProbe.check(!input.isSaveEnabled()&&(input.getInputType()&InputType.TYPE_MASK_VARIATION)==InputType.TYPE_TEXT_VARIATION_PASSWORD&&(input.getImeOptions()&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0&&input.getImportantForAutofill()==View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,"preparation sensitive input policy");
                    input.setText(new String(shown,StandardCharsets.US_ASCII));
                }
                java.lang.reflect.Field expected=page.getClass().getDeclaredField("expectedCode");expected.setAccessible(true);expected.set(page,shown);
                ((TextView)SyncAccountProbe.field(page,"recovery")).setText(new String(shown,StandardCharsets.US_ASCII));oldTicket.set((Integer)SyncAccountProbe.field(page,"generation"));page.finish();return null;
            });
            for(int n=0;n<100;n++){if(SyncAccountProbe.main(instrumentation,()->!(Boolean)SyncAccountProbe.field(page,"active")&&SyncAccountProbe.field(page,"expectedCode")==null&&((EditText)SyncAccountProbe.field(page,"confirmCode")).length()==0&&((EditText)SyncAccountProbe.field(page,"restoreCode")).length()==0&&((TextView)SyncAccountProbe.field(page,"recovery")).length()==0))break;if(n==99)throw new IOException("stopped preparation Activity retained secrets");Thread.sleep(50);}
            for(byte value:shown)SyncAccountProbe.check(value==0,"Activity did not clear actual shown recovery buffer");
            CountDownLatch callback=new CountDownLatch(1);AtomicBoolean admitted=new AtomicBoolean();
            ((Handler)SyncAccountProbe.field(page,"main")).post(()->{try{admitted.set((Boolean)SyncAccountProbe.call(page.getClass(),page,"current",new Class<?>[]{int.class},(int)oldTicket.get()));}catch(Exception invalid){admitted.set(true);}finally{callback.countDown();}});
            SyncAccountProbe.check(callback.await(5,TimeUnit.SECONDS)&&!admitted.get(),"stopped Activity accepted old generation callback");SyncAccountProbe.passed(cases,"secure_preparation_activity_lifecycle");
        }catch(Throwable error){operationFailure=error;cases.put(new JSONObject().put("id",stage).put("passed",false).put("failure_type",error.getClass().getSimpleName()));throw new Exception("synthetic sync preparation case: "+stage,error);}
        finally{
            Throwable cleanup=null;for(byte[] bytes:sensitive)if(bytes!=null)Arrays.fill(bytes,(byte)0);
            try{if(activity!=null){final Activity page=activity;SyncAccountProbe.main(instrumentation,()->{page.finish();return null;});}}catch(Throwable error){cleanup=failure(cleanup,error);}
            for(Profile p:profiles){
                try{invoke(store,"forget",new Class<?>[]{Context.class},p.context);}catch(Throwable error){cleanup=failure(cleanup,error);}
                try{invoke(vault,"forgetConnection",new Class<?>[]{Context.class,UUID.class},p.context,p.account);}catch(Throwable error){cleanup=failure(cleanup,error);}
                try{invoke(vault,"forget",new Class<?>[]{Context.class,UUID.class},p.context,p.account);}catch(Throwable error){cleanup=failure(cleanup,error);}
                try{base.deleteSharedPreferences(p.preferences+"sync_connection");}catch(Throwable error){cleanup=failure(cleanup,error);}
            }
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture.toPath())){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);}catch(Throwable error){cleanup=failure(cleanup,error);}
            JSONObject report=new JSONObject().put("synthetic",true).put("api_level",Build.VERSION.SDK_INT).put("cases",cases).put("cleanup_completed",cleanup==null)
                    .put("scope","Real stateless JNI and Android Keystore on isolated full-v2 synthetic database copies; create-only material, explicit account/key fingerprint binding, key recovery and lifecycle/identity boundaries. No HTTP, cloud-key registration, upload, cross-device data sync, provider UI or phone acceptance.");
            if(cleanup!=null)report.put("cleanup_failure_type",cleanup.getClass().getSimpleName());Files.write(new File(output,"sync-preparation-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            if(cleanup!=null){if(operationFailure!=null)operationFailure.addSuppressed(cleanup);else throw new Exception("sync preparation fixture cleanup failed",cleanup);}
        }
    }
    private SyncPreparationProbe(){}
}
