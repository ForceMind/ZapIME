// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline descriptor/parser/JNI/local-precondition acceptance only. This never
 * invokes a prepared HTTP operation and does not establish server CAS success.
 */
final class SyncKeyRegistrationProbe {
    private static final Class<?>[] NONE=new Class<?>[0];
    private static Object invoke(Class<?> type,String name,Class<?>[] signature,Object... args)throws Exception {
        return SyncAccountProbe.call(type,null,name,signature,args);
    }
    private static void reject(SyncAccountProbe.Task<?> work)throws Exception {
        boolean failed=false;try{work.run();}catch(IOException expected){failed=true;}
        SyncAccountProbe.check(failed,"offline key parser accepted invalid synthetic input");
    }
    private static void rejectCode(SyncAccountProbe.Task<?> work,String fixed)throws Exception {
        boolean failed=false;try{work.run();}catch(IOException expected){failed=fixed.equals(expected.getMessage());}
        SyncAccountProbe.check(failed,"offline key local guard did not return expected fixed code");
    }
    private static byte[] utf8(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String repeat(String value,int count){StringBuilder text=new StringBuilder();for(int n=0;n<count;n++)text.append(value);return text.toString();}
    private static String descriptor(UUID key,String sha)throws Exception {
        return new JSONObject().put("version",1).put("key_id",key.toString()).put("material_sha256",sha).toString();
    }
    private static String response(UUID account,String state,String descriptor)throws Exception {
        return new JSONObject().put("protocol",1).put("account_id",account.toString()).put("state",state)
                .put("descriptor",descriptor==null?JSONObject.NULL:new JSONObject(descriptor))
                .put("registered_by",descriptor==null?JSONObject.NULL:UUID.randomUUID().toString())
                .put("registered_at",descriptor==null?JSONObject.NULL:"2026-11-06T22:00:00.123456789Z").toString();
    }
    private static Object parseDescriptor(Class<?> bridge,byte[] wire)throws Exception {
        return invoke(bridge,"parseDescriptor",new Class<?>[]{byte[].class},wire);
    }
    private static Object parse(Class<?> bridge,byte[] wire,UUID account,Object local)throws Exception {
        Class<?> descriptor=Class.forName("org.zapime.android.SyncKeyRegistration$Descriptor",true,bridge.getClassLoader());
        return invoke(bridge,"parse",new Class<?>[]{byte[].class,UUID.class,descriptor},wire,account,local);
    }
    private static boolean same(Object a,Object b)throws Exception {
        return (Boolean)SyncAccountProbe.call(a.getClass(),a,"same",new Class<?>[]{a.getClass()},b);
    }
    private static Object cancellation(Class<?> type)throws Exception {
        Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);return constructor.newInstance();
    }
    private static Object request(Class<?> bridge,Class<?> cancelType,String method,Context context,UUID account,Object cancel)throws Exception {
        return invoke(bridge,method,new Class<?>[]{Context.class,UUID.class,cancelType},context,account,cancel);
    }
    private static Object requestSelected(Class<?> bridge,Class<?> cancelType,String method,Context context,Object selected,Object cancel)throws Exception {
        Class<?> selection=Class.forName("org.zapime.android.SyncKeyRegistration$Selection",true,bridge.getClassLoader());
        return invoke(bridge,method,new Class<?>[]{Context.class,selection,cancelType},context,selected,cancel);
    }
    private static Object selection(Class<?> bridge,UUID account,UUID device,URI server,Object descriptor)throws Exception {
        Class<?> type=Class.forName("org.zapime.android.SyncKeyRegistration$Selection",true,bridge.getClassLoader());
        Constructor<?> constructor=type.getDeclaredConstructor(UUID.class,UUID.class,URI.class,descriptor.getClass());
        constructor.setAccessible(true);return constructor.newInstance(account,device,server,descriptor);
    }
    private static final class Profile {
        final UUID account=UUID.randomUUID();
        final Context context;
        final File root,materialFile;
        final String preferences="sync-key-probe-"+UUID.randomUUID()+"-";
        Profile(Context base,File directory,File template)throws Exception {
            SyncAccountProbe.check(directory.mkdir(),"fresh key descriptor fixture directory");
            File noBackup=new File(directory,"no-backup");SyncAccountProbe.check(noBackup.mkdir(),"fresh key descriptor vault directory");
            root=new File(directory,"zapime");materialFile=new File(noBackup,"sync-material-"+account+".bin");
            context=new ContextWrapper(base){
                @Override public File getFilesDir(){return directory;}
                @Override public File getNoBackupFilesDir(){return noBackup;}
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(preferences+name,mode);}
            };
            if(template!=null){File database=new File(root,"data/personal.sqlite3");SyncAccountProbe.check(database.getParentFile().mkdirs(),"fresh key descriptor database directory");Files.copy(template.toPath(),database.toPath());}
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            for(String purpose:new String[]{"material","connection"})SyncAccountProbe.check(!keys.containsAlias("org.zapime.android.sync."+purpose+".v1."+account),"random key descriptor fixture alias exists");
        }
    }
    private static File template(Context base,File directory,Class<?> core)throws Exception {
        SyncAccountProbe.check(directory.mkdir(),"key descriptor template directory");
        Context context=new ContextWrapper(base){@Override public File getFilesDir(){return directory;}};
        Class<?> installer=Class.forName("org.zapime.android.ResourceInstaller",true,base.getClassLoader());
        invoke(installer,"prepare",new Class<?>[]{Context.class},context);
        File root=new File(directory,"zapime");long handle=(Long)invoke(core,"open",new Class<?>[]{String.class,boolean.class},root.toString(),false);
        SyncAccountProbe.check(handle!=0,"key descriptor template native initialization");invoke(core,"close",new Class<?>[]{long.class},handle);
        File database=new File(root,"data/personal.sqlite3");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.toString(),null,SQLiteDatabase.OPEN_READWRITE);Cursor rows=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){
            SyncAccountProbe.check(rows.moveToFirst()&&rows.getInt(0)==0,"key descriptor template checkpoint busy");
        }
        return database;
    }
    private static void credential(Class<?> model,Class<?> store,Profile profile)throws Exception {
        byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);
        String token="zap1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(random);Arrays.fill(random,(byte)0);
        UUID device=UUID.randomUUID();JSONObject input=new JSONObject().put("account_id",profile.account.toString()).put("device_id",device.toString()).put("token",token).put("expires_at","2099-01-01T00:00:00Z");
        Object imported=invoke(model,"imported",new Class<?>[]{String.class,String.class},"https://synthetic.invalid",input.toString()),verified=null;
        try{
            JSONObject identity=new JSONObject().put("protocol",1).put("account_id",profile.account.toString()).put("device_id",device.toString()).put("role","owner").put("expires_at","2099-01-01T00:00:00Z");
            verified=SyncAccountProbe.call(model,imported,"verify",new Class<?>[]{byte[].class},utf8(identity.toString()));
            invoke(store,"save",new Class<?>[]{Context.class,model},profile.context,verified);
        }finally{SyncAccountProbe.close(model,imported);SyncAccountProbe.close(model,verified);}
    }
    private static String sha256(byte[] bytes)throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);
        try{char[] digits="0123456789abcdef".toCharArray();StringBuilder result=new StringBuilder(64);for(byte value:hash){result.append(digits[(value&255)>>>4]);result.append(digits[value&15]);}return result.toString();}
        finally{Arrays.fill(hash,(byte)0);}
    }
    private static Throwable append(Throwable previous,Throwable next){if(previous==null)return next;previous.addSuppressed(next);return previous;}

    static void run(Instrumentation instrumentation,File output)throws Exception {
        Context base=instrumentation.getTargetContext();ClassLoader loader=base.getClassLoader();
        Class<?> bridge=Class.forName("org.zapime.android.SyncKeyRegistration",true,loader),core=Class.forName("org.zapime.android.NativeCore",true,loader),
                preparation=Class.forName("org.zapime.android.SyncPreparation",true,loader),vault=Class.forName("org.zapime.android.SyncSecrets",true,loader),
                model=Class.forName("org.zapime.android.SyncConnection",true,loader),store=Class.forName("org.zapime.android.SyncConnectionStore",true,loader),
                cancelType=Class.forName("org.zapime.android.SyncHttp$Cancellation",true,loader);
        File fixture=new File(base.getCacheDir(),"sync-key-registration-"+UUID.randomUUID());SyncAccountProbe.check(fixture.mkdir(),"fresh descriptor fixture");
        List<Profile> profiles=new ArrayList<>();List<byte[]> sensitive=new ArrayList<>();JSONArray cases=new JSONArray();String stage="descriptor_roundtrip";Throwable operationFailure=null;
        boolean nativeVerified=false,preparedVerified=false;
        try{
            UUID account=UUID.fromString("11111111-1111-4111-a111-111111111111"),key=UUID.fromString("22222222-2222-4222-b222-222222222222");String sha=repeat("a",64),good=descriptor(key,sha);Object local=parseDescriptor(bridge,utf8(good));
            byte[] emitted=(byte[])SyncAccountProbe.call(local.getClass(),local,"wire",NONE);
            SyncAccountProbe.check(same(local,parseDescriptor(bridge,emitted))&&key.equals(SyncAccountProbe.field(local,"keyId"))&&sha.equals(SyncAccountProbe.field(local,"materialSha256")),"public descriptor roundtrip mismatch");SyncAccountProbe.passed(cases,stage);
            stage="descriptor_schema";
            for(String bad:new String[]{"null","[]","{}",good+" {}",good.replace("\"version\":1","\"version\":1,\"version\":1"),good.replace("\"version\":1","\"version\":1,\"unknown\":0")})reject(()->parseDescriptor(bridge,utf8(bad)));
            for(Object version:new Object[]{0,2,-1,"1",true,JSONObject.NULL,1.5}){String bad=new JSONObject(good).put("version",version).toString();reject(()->parseDescriptor(bridge,utf8(bad)));}
            for(String number:new String[]{"1.0","1e0"})reject(()->parseDescriptor(bridge,utf8(good.replace("\"version\":1","\"version\":"+number))));
            for(String missing:new String[]{"version","key_id","material_sha256"}){JSONObject bad=new JSONObject(good);bad.remove(missing);reject(()->parseDescriptor(bridge,utf8(bad.toString())));}
            SyncAccountProbe.passed(cases,stage);
            stage="descriptor_uuid_hash";
            for(Object invalid:new Object[]{JSONObject.NULL,1,true,new JSONObject(),"invalid","00000000-0000-0000-0000-000000000000",key.toString().toUpperCase(java.util.Locale.ROOT)}){String bad=new JSONObject(good).put("key_id",invalid).toString();reject(()->parseDescriptor(bridge,utf8(bad)));}
            for(Object invalid:new Object[]{JSONObject.NULL,1,true,repeat("A",64),repeat("g",64),repeat("a",63),repeat("a",65),repeat("中",64)}){String bad=new JSONObject(good).put("material_sha256",invalid).toString();reject(()->parseDescriptor(bridge,utf8(bad)));}
            SyncAccountProbe.check(!same(local,parseDescriptor(bridge,utf8(descriptor(UUID.randomUUID(),sha))))&&!same(local,parseDescriptor(bridge,utf8(descriptor(key,repeat("b",64))))),"different public descriptor accepted as same");SyncAccountProbe.passed(cases,stage);
            stage="response_states";
            String registered=response(account,"registered",good);
            for(String state:new String[]{"empty","legacy","registered"}){
                Object result=parse(bridge,utf8(response(account,state,state.equals("registered")?good:null)),account,local);
                SyncAccountProbe.check(state.equals(SyncAccountProbe.field(result,"state"))&&((Boolean)SyncAccountProbe.field(result,"matchesLocal"))==state.equals("registered"),"response state or descriptor match mismatch");
            }
            Object conflict=parse(bridge,utf8(response(account,"registered",descriptor(UUID.randomUUID(),repeat("b",64)))),account,local);
            SyncAccountProbe.check(!(Boolean)SyncAccountProbe.field(conflict,"matchesLocal"),"different registered key reported matching");SyncAccountProbe.passed(cases,stage);
            stage="response_schema";
            for(String bad:new String[]{"null","[]","{}",registered+" {}",registered.replace("\"protocol\":1","\"protocol\":1,\"protocol\":1"),registered.replace("\"protocol\":1","\"protocol\":1,\"unknown\":false"),registered.replace("\"version\":1","\"version\":1,\"version\":1")})reject(()->parse(bridge,utf8(bad),account,local));
            for(Object version:new Object[]{0,2,-1,"1",true,JSONObject.NULL,1.5}){String bad=new JSONObject(registered).put("protocol",version).toString();reject(()->parse(bridge,utf8(bad),account,local));}
            for(String number:new String[]{"1.0","1e0"})reject(()->parse(bridge,utf8(registered.replace("\"protocol\":1","\"protocol\":"+number)),account,local));
            for(String missing:new String[]{"protocol","account_id","state","descriptor","registered_by","registered_at"}){JSONObject bad=new JSONObject(registered);bad.remove(missing);reject(()->parse(bridge,utf8(bad.toString()),account,local));}
            for(Object invalid:new Object[]{"other",1,true,JSONObject.NULL}){String bad=new JSONObject(registered).put("state",invalid).toString();reject(()->parse(bridge,utf8(bad),account,local));}SyncAccountProbe.passed(cases,stage);
            stage="response_account_and_nulls";
            for(Object invalid:new Object[]{UUID.randomUUID().toString(),"00000000-0000-0000-0000-000000000000",account.toString().toUpperCase(java.util.Locale.ROOT),JSONObject.NULL,1}){String bad=new JSONObject(registered).put("account_id",invalid).toString();reject(()->parse(bridge,utf8(bad),account,local));}
            reject(()->parse(bridge,utf8(registered),null,local));reject(()->parse(bridge,utf8(registered),new UUID(0,0),local));reject(()->parse(bridge,utf8(registered),account,null));
            for(String state:new String[]{"empty","legacy"})for(String field:new String[]{"descriptor","registered_by","registered_at"}){
                Object value=field.equals("descriptor")?new JSONObject(good):field.equals("registered_by")?UUID.randomUUID().toString():"2026-11-06T22:00:00Z";
                String bad=new JSONObject(response(account,state,null)).put(field,value).toString();reject(()->parse(bridge,utf8(bad),account,local));
            }
            for(String field:new String[]{"descriptor","registered_by","registered_at"}){String bad=new JSONObject(registered).put(field,JSONObject.NULL).toString();reject(()->parse(bridge,utf8(bad),account,local));}
            for(Object invalid:new Object[]{"00000000-0000-0000-0000-000000000000",1,true,new JSONObject()}){String bad=new JSONObject(registered).put("registered_by",invalid).toString();reject(()->parse(bridge,utf8(bad),account,local));}SyncAccountProbe.passed(cases,stage);
            stage="response_timestamp_utf8_bounds";
            for(Object invalid:new Object[]{"2026-02-30T22:00:00Z","2026-11-06T22:00Z","2026-11-06 22:00:00Z","2026-11-06T22:00:00","2026-11-06T22:00:00+25:00","2026-11-06T22:00:00.1234567890Z",1,true,new JSONObject()}){String bad=new JSONObject(registered).put("registered_at",invalid).toString();reject(()->parse(bridge,utf8(bad),account,local));}
            for(byte[] bad:new byte[][]{null,new byte[0],new byte[]{(byte)0xc3,0x28},new byte[2049]})reject(()->parse(bridge,bad,account,local));
            for(byte[] bad:new byte[][]{null,new byte[0],new byte[]{(byte)0xc3,0x28},new byte[1025]})reject(()->parseDescriptor(bridge,bad));
            SyncAccountProbe.check(same(local,parseDescriptor(bridge,utf8(good+repeat(" ",1024-utf8(good).length)))),"descriptor exact byte limit rejected");
            parse(bridge,utf8(registered+repeat(" ",2048-utf8(registered).length)),account,local);SyncAccountProbe.passed(cases,stage);
            stage="fixture_setup";File template=template(base,new File(fixture,"template"),core);Profile source=new Profile(base,new File(fixture,"source"),template);profiles.add(source);credential(model,store,source);
            stage="unprepared_before_network";byte[] before=Files.readAllBytes(new File(source.root,"data/personal.sqlite3").toPath());
            for(String method:new String[]{"inspect","register"})rejectCode(()->request(bridge,cancelType,method,source.context,source.account,cancellation(cancelType)),"sync_key_requires_prepared");
            SyncAccountProbe.check(!source.materialFile.exists()&&Arrays.equals(before,Files.readAllBytes(new File(source.root,"data/personal.sqlite3").toPath())),"unprepared network guard mutated local profile");SyncAccountProbe.passed(cases,stage);
            stage="prepared_fixture";Object prepared=invoke(preparation,"create",new Class<?>[]{Context.class,UUID.class},source.context,source.account);SyncAccountProbe.check((Boolean)SyncAccountProbe.field(prepared,"prepared"),"real prepared fixture unavailable");
            byte[] material=(byte[])invoke(vault,"load",new Class<?>[]{Context.class,UUID.class},source.context,source.account);sensitive.add(material);
            stage="native_descriptor_digest";SyncAccountProbe.check(material!=null&&material.length==72,"real typed material length mismatch");
            String nativeReply=(String)invoke(core,"syncKeyDescriptor",new Class<?>[]{String.class,byte[].class},source.account.toString(),material);JSONObject nativeObject=new JSONObject(nativeReply);
            SyncAccountProbe.check(nativeObject.length()==3&&nativeObject.getInt("version")==1&&nativeObject.getString("material_sha256").equals(sha256(material)),"native descriptor did not expose only exact public digest");
            Object nativeDescriptor=parseDescriptor(bridge,utf8(nativeReply));SyncAccountProbe.check(SyncAccountProbe.field(nativeDescriptor,"keyId").equals(SyncAccountProbe.field(prepared,"keyId")),"native descriptor key identity mismatch");nativeVerified=true;SyncAccountProbe.passed(cases,stage);
            stage="native_descriptor_negative_inputs";
            for(byte[] bad:new byte[][]{null,new byte[0],new byte[71],new byte[73],new byte[1025]}){
                String raw=(String)invoke(core,"syncKeyDescriptor",new Class<?>[]{String.class,byte[].class},source.account.toString(),bad);reject(()->parseDescriptor(bridge,raw==null?null:utf8(raw)));
            }
            String wrong=(String)invoke(core,"syncKeyDescriptor",new Class<?>[]{String.class,byte[].class},UUID.randomUUID().toString(),material);reject(()->parseDescriptor(bridge,wrong==null?null:utf8(wrong)));SyncAccountProbe.passed(cases,stage);
            stage="prepared_local_identity";Object connection=invoke(store,"load",new Class<?>[]{Context.class},source.context);
            try{
                Object current=invoke(bridge,"current",new Class<?>[]{Context.class,model,cancelType},source.context,connection,cancellation(cancelType));SyncAccountProbe.check(same(nativeDescriptor,current),"helper did not verify current protected material identity");
                UUID device=(UUID)SyncAccountProbe.field(connection,"device");URI server=(URI)SyncAccountProbe.field(connection,"server");
                UUID otherDevice=UUID.randomUUID();while(otherDevice.equals(device))otherDevice=UUID.randomUUID();
                String digest=(String)SyncAccountProbe.field(nativeDescriptor,"materialSha256");
                String changedDigest=(digest.startsWith("a")?"b":"a")+digest.substring(1);
                Object wrongLocal=parseDescriptor(bridge,utf8(descriptor((UUID)SyncAccountProbe.field(nativeDescriptor,"keyId"),changedDigest)));
                Object[] changed={selection(bridge,source.account,device,new URI("https://other.synthetic.invalid"),nativeDescriptor),
                        selection(bridge,source.account,otherDevice,server,nativeDescriptor),selection(bridge,source.account,device,server,wrongLocal)};
                byte[] vaultBefore=Files.readAllBytes(source.materialFile.toPath());sensitive.add(vaultBefore);
                for(int n=0;n<changed.length;n++){
                    final Object selected=changed[n];final String fixed=n==2?"sync_key_local_changed":"sync_connection_changed";
                    for(String method:new String[]{"inspect","register"})rejectCode(()->requestSelected(bridge,cancelType,method,source.context,selected,cancellation(cancelType)),fixed);
                }
                SyncAccountProbe.check(Arrays.equals(vaultBefore,Files.readAllBytes(source.materialFile.toPath())),"changed confirmation snapshot replaced protected material");
            }
            finally{SyncAccountProbe.close(model,connection);}preparedVerified=true;SyncAccountProbe.passed(cases,stage);
            stage="worker_before_io";Context poison=new ContextWrapper(base){@Override public SharedPreferences getSharedPreferences(String name,int mode){throw new AssertionError("unexpected local IO");}@Override public File getFilesDir(){throw new AssertionError("unexpected local IO");}};
            SyncAccountProbe.main(instrumentation,()->{
                for(String method:new String[]{"inspect","register"}){
                    rejectCode(()->request(bridge,cancelType,method,poison,source.account,cancellation(cancelType)),"sync_key_requires_worker");
                    rejectCode(()->requestSelected(bridge,cancelType,method,poison,null,cancellation(cancelType)),"sync_key_requires_worker");
                }
                return null;
            });SyncAccountProbe.passed(cases,stage);
            stage="missing_connection_before_network";Profile absent=new Profile(base,new File(fixture,"absent"),null);profiles.add(absent);
            for(String method:new String[]{"inspect","register"})rejectCode(()->request(bridge,cancelType,method,absent.context,absent.account,cancellation(cancelType)),"sync_connection_missing");
            SyncAccountProbe.check(!absent.root.exists()&&!absent.materialFile.exists(),"missing connection guard created profile or material");SyncAccountProbe.passed(cases,stage);
            stage="precancel_before_io";
            Object cancelled=cancellation(cancelType);SyncAccountProbe.call(cancelType,cancelled,"cancel",NONE);
            for(String method:new String[]{"inspect","register"})rejectCode(()->request(bridge,cancelType,method,poison,source.account,cancelled),"sync_cancelled");
            for(String method:new String[]{"inspect","register"})rejectCode(()->request(bridge,cancelType,method,poison,source.account,null),"sync_key_invalid_request");SyncAccountProbe.passed(cases,stage);
        }catch(Throwable error){operationFailure=error;cases.put(new JSONObject().put("id",stage).put("passed",false).put("failure_type",error.getClass().getSimpleName()));throw new Exception("synthetic offline key descriptor case: "+stage,error);}
        finally{
            Throwable cleanup=null;for(byte[] bytes:sensitive)if(bytes!=null)Arrays.fill(bytes,(byte)0);
            for(Profile profile:profiles){
                try{invoke(store,"forget",new Class<?>[]{Context.class},profile.context);}catch(Throwable error){cleanup=append(cleanup,error);}
                try{invoke(vault,"forgetConnection",new Class<?>[]{Context.class,UUID.class},profile.context,profile.account);invoke(vault,"forget",new Class<?>[]{Context.class,UUID.class},profile.context,profile.account);}catch(Throwable error){cleanup=append(cleanup,error);}
                try{base.deleteSharedPreferences(profile.preferences+"sync_connection");}catch(Throwable error){cleanup=append(cleanup,error);}
            }
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture.toPath())){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);}catch(Throwable error){cleanup=append(cleanup,error);}
            JSONObject report=new JSONObject().put("schema_version",1).put("status",operationFailure==null&&cleanup==null?"PASS":"FAIL").put("synthetic",true).put("api_level",Build.VERSION.SDK_INT).put("cases",cases)
                    .put("cleanup_completed",cleanup==null).put("actual_native_descriptor",nativeVerified).put("actual_keystore_prepared",preparedVerified).put("material_bytes",72).put("descriptor_public_fields",3)
                    .put("android_https","NOT RUN").put("server_cas","NOT RUN").put("remote_success_tested",false).put("secret_or_code_logged",false)
                    .put("scope","Offline strict Android JsonReader parser, real stateless descriptor JNI and independently prepared Android Keystore/SQLite fixture. Public helper calls only exercise pre-I/O/pre-network rejection; prepared helper is checked through its local-only current seam. No Android HTTPS, server registration/CAS, data synchronization, provider UI or phone acceptance.");
            if(cleanup!=null)report.put("cleanup_failure_type",cleanup.getClass().getSimpleName());Files.write(new File(output,"sync-key-registration-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            if(cleanup!=null){if(operationFailure!=null)operationFailure.addSuppressed(cleanup);else throw new Exception("offline key descriptor fixture cleanup failed",cleanup);}
        }
    }
    private SyncKeyRegistrationProbe(){}
}
