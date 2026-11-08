// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONObject;

/** API28-only real HTTPS fixture using unchanged platform TLS verification.
 * The host owns one ephemeral emulator CA installation and actual Go/PG server.
 * This probe never installs trust, changes hostname verification, or touches
 * the main profile. Two private profiles share this test process and device.
 */
final class SyncHttpsProbe {
    private static final int MAX_BOOTSTRAP=8192;
    private static final String TLS_SCOPE="One API28 temporary CI emulator, production SyncHttp and unchanged platform TLS defaults; a valid dummy credential must fail specifically as tls before fixture CA installation or after its removal in a fresh process. No profile, server data exchange, public-PKI, phone or R2 acceptance.";
    private static final String ROUND_SCOPE="One API28 temporary CI emulator with a temporary host-installed system CA, two isolated profiles, production Android HTTPS and actual Go/PostgreSQL service. Local same-root recovery, bidirectional synthetic favorites and strict hostname rejection; not two devices, stock root-store/public-PKI, R2, IME input, pairing or phone acceptance.";
    private static final class Classes {
        final Class<?> core,connection,store,preparation,vault,registration,descriptor,selection,cancellation,transfer;
        Classes(ClassLoader loader)throws Exception {
            core=Class.forName("org.zapime.android.NativeCore",true,loader);connection=Class.forName("org.zapime.android.SyncConnection",true,loader);store=Class.forName("org.zapime.android.SyncConnectionStore",true,loader);
            preparation=Class.forName("org.zapime.android.SyncPreparation",true,loader);vault=Class.forName("org.zapime.android.SyncSecrets",true,loader);registration=Class.forName("org.zapime.android.SyncKeyRegistration",true,loader);
            descriptor=Class.forName("org.zapime.android.SyncKeyRegistration$Descriptor",true,loader);selection=Class.forName("org.zapime.android.SyncKeyRegistration$Selection",true,loader);cancellation=Class.forName("org.zapime.android.SyncHttp$Cancellation",true,loader);transfer=Class.forName("org.zapime.android.SyncTransfer",true,loader);
        }
    }
    private static final class Profile {
        final Context context;
        final File directory,root,database;
        final String prefix;
        final UUID account;
        Profile(Context base,Classes types,File directory,UUID account)throws Exception {
            this.directory=directory;this.account=account;root=new File(directory,"zapime");database=new File(root,"data/personal.sqlite3");prefix="sync-https-"+UUID.randomUUID()+"-";
            check(directory.mkdir(),"fresh HTTPS fixture directory");File noBackup=new File(directory,"no-backup");check(noBackup.mkdir(),"fresh HTTPS noBackup directory");
            context=new ContextWrapper(base){
                @Override public File getFilesDir(){return directory;}
                @Override public File getNoBackupFilesDir(){return noBackup;}
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(prefix+name,mode);}
            };
            check(!context.getSharedPreferences("sync_connection",Context.MODE_PRIVATE).contains("active_account"),"independent HTTPS connection index");
            Class<?> installer=Class.forName("org.zapime.android.ResourceInstaller",true,base.getClassLoader());invoke(installer,"prepare",new Class<?>[]{Context.class},context);
            long handle=(Long)invoke(types.core,"open",new Class<?>[]{String.class,boolean.class},root.getAbsolutePath(),false);check(handle!=0,"native fresh HTTPS database initialization");invoke(types.core,"close",new Class<?>[]{long.class},handle);
            check(count(this,"SELECT count(*) FROM records")==0&&count(this,"PRAGMA user_version")==2,"empty full-v2 HTTPS profile");
        }
    }
    private static final class Bootstrap {
        final Map<String,String> owner,member;
        Bootstrap(Map<String,String> owner,Map<String,String> member){this.owner=owner;this.member=member;}
        void clear(){owner.clear();member.clear();}
    }
    private static Object invoke(Class<?> type,String method,Class<?>[] signature,Object...args)throws Exception{return SyncAccountProbe.call(type,null,method,signature,args);}
    private static void check(boolean value,String reason){SyncAccountProbe.check(value,reason);}
    private static Object cancellation(Class<?> type)throws Exception{Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);return constructor.newInstance();}
    private static int number(Object object,String field)throws Exception{return ((Number)SyncAccountProbe.field(object,field)).intValue();}
    private static long revision(Object object,String field)throws Exception{return ((Number)SyncAccountProbe.field(object,field)).longValue();}
    private static String alias(UUID account,String purpose){return "org.zapime.android.sync."+purpose+".v1."+account;}
    private static KeyStore keys()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);return store;}
    private static UUID id(String value)throws Exception{UUID parsed=UUID.fromString(value);check(!parsed.equals(new UUID(0,0))&&parsed.toString().equals(value),"canonical nonzero HTTPS fixture identity");return parsed;}
    private static Set<String> fields(String... values){return new HashSet<>(Arrays.asList(values));}
    private static Map<String,String> credential(JsonReader reader)throws Exception {
        check(reader.peek()==JsonToken.BEGIN_OBJECT,"bootstrap credential object");reader.beginObject();Map<String,String> values=new HashMap<>();
        while(reader.hasNext()){
            String name=reader.nextName();check(fields("account_id","device_id","token","expires_at").contains(name)&&!values.containsKey(name)&&reader.peek()==JsonToken.STRING,"strict bootstrap credential fields");
            String value=reader.nextString();check(value.length()<=256,"bounded bootstrap credential value");values.put(name,value);
        }
        reader.endObject();check(values.keySet().equals(fields("account_id","device_id","token","expires_at")),"complete bootstrap credential");return values;
    }
    private static Bootstrap parseBootstrap(byte[] body)throws Exception {
        try(JsonReader reader=new JsonReader(new InputStreamReader(new ByteArrayInputStream(body),StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))){
            reader.setLenient(false);check(reader.peek()==JsonToken.BEGIN_OBJECT,"bootstrap outer object");reader.beginObject();Set<String> seen=new HashSet<>();Map<String,String> owner=null,member=null;
            while(reader.hasNext()){String name=reader.nextName();check(seen.add(name)&&(name.equals("owner")||name.equals("member")),"strict bootstrap outer fields");if(name.equals("owner"))owner=credential(reader);else member=credential(reader);}
            reader.endObject();check(reader.peek()==JsonToken.END_DOCUMENT&&seen.equals(fields("owner","member"))&&owner!=null&&member!=null,"complete bootstrap document");return new Bootstrap(owner,member);
        }
    }
    private static byte[] bootstrap(URI origin,String path)throws Exception {
        check(path!=null&&path.matches("/ci/bootstrap/[0-9a-f]{16,128}"),"bootstrap must be an isolated HTTPS route");
        HttpsURLConnection connection=null;byte[] body=new byte[MAX_BOOTSTRAP+1];
        try{
            connection=(HttpsURLConnection)origin.resolve(path).toURL().openConnection();connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);connection.setRequestProperty("Accept","application/json");
            check(connection.getResponseCode()==200,"real provision bootstrap HTTPS response");String content=connection.getContentType();check(content!=null&&content.split(";",2)[0].trim().equalsIgnoreCase("application/json"),"bootstrap JSON response type");check(connection.getContentLengthLong()<=MAX_BOOTSTRAP,"bootstrap response size");
            int used=0;try(InputStream input=connection.getInputStream()){
                while(true){int count=input.read(body,used,body.length-used);if(count<0)break;used+=count;check(used<=MAX_BOOTSTRAP,"bounded bootstrap stream");}
            }
            check(used>0,"nonempty bootstrap response");return Arrays.copyOf(body,used);
        }finally{Arrays.fill(body,(byte)0);if(connection!=null)connection.disconnect();}
    }
    private static void requireTls(Class<?> http,Class<?> cancel,URI origin)throws Exception {
        char[] token=("zap1_"+"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").toCharArray();Object request=cancellation(cancel);boolean tls=false;
        try{
            invoke(http,"token",new Class<?>[]{char[].class},(Object)token);
            try{byte[] unexpected=(byte[])invoke(http,"session",new Class<?>[]{URI.class,char[].class,cancel},origin,token,request);if(unexpected!=null)Arrays.fill(unexpected,(byte)0);}
            catch(IOException failure){tls=failure.getClass().getName().equals("org.zapime.android.SyncHttp$Failure")&&"tls".equals(SyncAccountProbe.field(failure,"code"));}
            check(tls,"production TLS failure must be tls rather than network or authorization");
        }finally{Arrays.fill(token,'\0');SyncAccountProbe.call(cancel,request,"cancel",new Class<?>[0]);}
    }
    private static void connect(Classes types,Profile profile,Map<String,String> credential,String origin,String expectedRole)throws Exception {
        Object draft=null,verified=null;byte[] response=null;
        try{
            JSONObject json=new JSONObject();for(Map.Entry<String,String> entry:credential.entrySet())json.put(entry.getKey(),entry.getValue());
            draft=invoke(types.connection,"imported",new Class<?>[]{String.class,String.class},origin,json.toString());Object request=cancellation(types.cancellation);
            response=(byte[])SyncAccountProbe.call(types.connection,draft,"inspect",new Class<?>[]{types.cancellation},request);
            verified=SyncAccountProbe.call(types.connection,draft,"verify",new Class<?>[]{byte[].class},(Object)response);
            check(profile.account.equals(SyncAccountProbe.field(verified,"account"))&&expectedRole.equals(SyncAccountProbe.field(verified,"role")),"actual server-verified role and account");
            invoke(types.store,"save",new Class<?>[]{Context.class,types.connection},profile.context,verified);
        }finally{if(response!=null)Arrays.fill(response,(byte)0);SyncAccountProbe.close(types.connection,draft);SyncAccountProbe.close(types.connection,verified);}
    }
    private static Object selected(Classes types,Profile profile)throws Exception {
        Object connection=invoke(types.store,"load",new Class<?>[]{Context.class},profile.context);
        try{
            check(connection!=null,"actual saved HTTPS connection");Object local=invoke(types.registration,"preparedLocal",new Class<?>[]{Context.class,types.connection,types.cancellation},profile.context,connection,cancellation(types.cancellation));
            Constructor<?> constructor=types.selection.getDeclaredConstructor(UUID.class,UUID.class,URI.class,types.descriptor);constructor.setAccessible(true);
            return constructor.newInstance(SyncAccountProbe.field(connection,"account"),SyncAccountProbe.field(connection,"device"),SyncAccountProbe.field(connection,"server"),local);
        }finally{SyncAccountProbe.close(types.connection,connection);}
    }
    private static Object round(Classes types,Profile profile,Object selection)throws Exception{return invoke(types.transfer,"run",new Class<?>[]{Context.class,types.selection,types.cancellation},profile.context,selection,cancellation(types.cancellation));}
    private static long count(Profile profile,String query,String...args)throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(profile.database.toString(),null,SQLiteDatabase.OPEN_READONLY);Cursor result=db.rawQuery(query,args)){check(result.moveToFirst(),"HTTPS fixture database query");return result.getLong(0);}
    }
    private static String text(Profile profile,UUID record)throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(profile.database.toString(),null,SQLiteDatabase.OPEN_READONLY);Cursor result=db.rawQuery("SELECT body FROM records WHERE id=?",new String[]{record.toString()})){check(result.moveToFirst(),"actual downloaded favorite exists");return new JSONObject(result.getString(0)).getJSONObject("content").getString("text");}
    }
    private static void edit(Classes types,Profile profile,UUID record,String text)throws Exception {
        long handle=(Long)invoke(types.core,"open",new Class<?>[]{String.class,boolean.class},profile.root.getAbsolutePath(),false);check(handle!=0,"independent native edit session");
        try{
            JSONObject command=new JSONObject().put("op","personal_edit").put("id",record.toString()).put("operation_id",UUID.randomUUID().toString()).put("expected_revision",0).put("content",new JSONObject().put("type","favorite").put("title","synthetic HTTPS fixture").put("text",text));
            String reply=(String)invoke(types.core,"request",new Class<?>[]{long.class,String.class},handle,command.toString());check(reply!=null&&reply.length()<10_000&&!new JSONObject(reply).has("error"),"actual synthetic favorite edit accepted");
        }finally{invoke(types.core,"close",new Class<?>[]{long.class},handle);}
    }
    private static void emptyPending(Classes types,Profile profile)throws Exception {
        byte[] material=(byte[])invoke(types.vault,"load",new Class<?>[]{Context.class,UUID.class},profile.context,profile.account);
        try{
            JSONObject reply=new JSONObject((String)invoke(types.core,"stageSyncUpload",new Class<?>[]{String.class,String.class,byte[].class},profile.root.getAbsolutePath(),profile.account.toString(),material));
            check(reply.length()==1&&Boolean.FALSE.equals(reply.opt("pending"))&&count(profile,"SELECT count(*) FROM operations WHERE pending=1")==0,"download and completed round must not echo new upload");
        }finally{if(material!=null)Arrays.fill(material,(byte)0);}
    }
    private static void serverHead(Classes types,Profile profile)throws Exception {
        Object connection=invoke(types.store,"load",new Class<?>[]{Context.class},profile.context);byte[] response=null;
        try{
            response=(byte[])SyncAccountProbe.call(types.connection,connection,"pull",new Class<?>[]{long.class,int.class,types.cancellation},2L,100,cancellation(types.cancellation));JSONObject page=new JSONObject(new String(response,StandardCharsets.UTF_8));
            check(page.length()==3&&page.getLong("head")==2&&page.getLong("next_cursor")==2&&page.getJSONArray("changes").length()==0,"actual Go server head is two");
        }finally{if(response!=null)Arrays.fill(response,(byte)0);SyncAccountProbe.close(types.connection,connection);}
    }
    private static void delete(Path root)throws Exception{if(!Files.exists(root))return;try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);}}
    static void run(Instrumentation instrumentation,File output,String phase,String origin,String badOrigin,String bootstrapPath)throws Exception {
        check(Build.VERSION.SDK_INT==28&&fields("before","roundtrip","after").contains(phase),"HTTPS fixture is restricted to API28 phases");Class<?> worker=Class.forName("org.zapime.android.SyncWorker",true,instrumentation.getTargetContext().getClassLoader());Field field=worker.getDeclaredField("EXECUTOR");field.setAccessible(true);ExecutorService executor=(ExecutorService)field.get(null);
        Future<Exception> work=executor.submit(()->{try{execute(instrumentation,output,phase,origin,badOrigin,bootstrapPath);return null;}catch(Exception safe){return safe;}});
        try{Exception failure=work.get(240,TimeUnit.SECONDS);if(failure!=null)throw failure;}
        catch(java.util.concurrent.TimeoutException timeout){work.cancel(true);throw new Exception("synthetic Android HTTPS worker timeout; cleanup not proven");}
        catch(InterruptedException interrupted){work.cancel(true);Thread.currentThread().interrupt();throw new Exception("synthetic Android HTTPS waiter interrupted; cleanup not proven");}
    }
    private static void execute(Instrumentation instrumentation,File output,String phase,String originText,String badOriginText,String bootstrapPath)throws Exception {
        Context base=instrumentation.getTargetContext();ClassLoader loader=base.getClassLoader();Class<?> http=Class.forName("org.zapime.android.SyncHttp",true,loader),cancel=Class.forName("org.zapime.android.SyncHttp$Cancellation",true,loader);
        SSLSocketFactory factory=HttpsURLConnection.getDefaultSSLSocketFactory();HostnameVerifier verifier=HttpsURLConnection.getDefaultHostnameVerifier();
        JSONArray cases=new JSONArray();List<Profile> profiles=new ArrayList<>();List<byte[]> buffers=new ArrayList<>();File directory=null;UUID account=null;Classes types=null;boolean aliasesOwned=false;Throwable operationFailure=null;String stage="production_worker_platform_defaults";Bootstrap bootstrap=null;
        int uploaded=0;boolean workerVerified=false,defaultsUnchanged=false;long head=-1;JSONArray cursors=new JSONArray();
        try{
            workerVerified=Thread.currentThread().getName().equals("zapime-sync-control");check(workerVerified,"actual production SyncWorker thread");
            URI origin=(URI)invoke(http,"origin",new Class<?>[]{String.class},originText);
            if(!phase.equals("roundtrip")){
                stage="untrusted_ca_rejected_as_tls";requireTls(http,cancel,origin);SyncAccountProbe.passed(cases,stage);
            }else{
                types=new Classes(loader);stage="strict_https_bootstrap";byte[] body=bootstrap(origin,bootstrapPath);buffers.add(body);bootstrap=parseBootstrap(body);
                account=id(bootstrap.owner.get("account_id"));check(account.equals(id(bootstrap.member.get("account_id")))&&!id(bootstrap.owner.get("device_id")).equals(id(bootstrap.member.get("device_id"))),"actual owner/member credentials have one account and distinct devices");
                KeyStore before=keys();check(!before.containsAlias(alias(account,"material"))&&!before.containsAlias(alias(account,"connection")),"fresh random account aliases must not preexist");aliasesOwned=true;SyncAccountProbe.passed(cases,stage);
                stage="actual_owner_member_session_and_vault";directory=new File(base.getCacheDir(),"sync-https-"+UUID.randomUUID());check(directory.mkdir(),"fresh HTTPS fixture parent");
                Profile owner=new Profile(base,types,new File(directory,"owner"),account);profiles.add(owner);Profile member=new Profile(base,types,new File(directory,"member"),account);profiles.add(member);
                connect(types,owner,bootstrap.owner,origin.toString(),"owner");connect(types,member,bootstrap.member,origin.toString(),"member");bootstrap.clear();bootstrap=null;SyncAccountProbe.passed(cases,stage);
                stage="same_root_local_recovery";
                Object created=invoke(types.preparation,"create",new Class<?>[]{Context.class,UUID.class},owner.context,account);check(Boolean.TRUE.equals(SyncAccountProbe.field(created,"prepared")),"owner material prepared on sync worker");
                byte[] code=new byte[43];buffers.add(code);byte[] encrypted=(byte[])invoke(types.preparation,"recovery",new Class<?>[]{Context.class,UUID.class,byte[].class},owner.context,account,code);buffers.add(encrypted);
                Object restored=invoke(types.preparation,"restore",new Class<?>[]{Context.class,UUID.class,byte[].class,byte[].class},member.context,account,code,encrypted);
                byte[] first=(byte[])invoke(types.vault,"load",new Class<?>[]{Context.class,UUID.class},owner.context,account),second=(byte[])invoke(types.vault,"load",new Class<?>[]{Context.class,UUID.class},member.context,account);buffers.add(first);buffers.add(second);
                check(first!=null&&first.length==72&&Arrays.equals(first,second)&&Boolean.TRUE.equals(SyncAccountProbe.field(restored,"prepared")),"local encrypted recovery yields same actual root");SyncAccountProbe.passed(cases,stage);
                stage="owner_register_member_inspect";Object ownerSelection=selected(types,owner),memberSelection=selected(types,member);
                Object registered=invoke(types.registration,"register",new Class<?>[]{Context.class,types.selection,types.cancellation},owner.context,ownerSelection,cancellation(cancel));
                Object inspected=invoke(types.registration,"inspect",new Class<?>[]{Context.class,types.selection,types.cancellation},member.context,memberSelection,cancellation(cancel));
                check(Boolean.TRUE.equals(SyncAccountProbe.field(registered,"matchesLocal"))&&Boolean.TRUE.equals(SyncAccountProbe.field(inspected,"matchesLocal"))&&SyncAccountProbe.field(registered,"keyId").equals(SyncAccountProbe.field(inspected,"keyId")),"actual registered account key matches both local roots");SyncAccountProbe.passed(cases,stage);
                stage="trusted_ca_wrong_san_rejected";URI bad=(URI)invoke(http,"origin",new Class<?>[]{String.class},badOriginText);check(!origin.equals(bad),"wrong-SAN fixture must be separate origin");requireTls(http,cancel,bad);SyncAccountProbe.passed(cases,stage);
                stage="owner_upload_actual_https";final String ownerText="HTTPS真实合成资料甲",memberText="HTTPS真实合成资料乙";UUID ownerRecord=UUID.randomUUID(),memberRecord=UUID.randomUUID();
                edit(types,owner,ownerRecord,ownerText);Object ownerRound=round(types,owner,ownerSelection);uploaded+=number(ownerRound,"uploaded");check(number(ownerRound,"uploaded")==1&&revision(ownerRound,"cursor")==1&&revision(ownerRound,"head")==1,"owner real upload acknowledgement and self echo");SyncAccountProbe.passed(cases,stage);
                stage="member_download_decrypted_no_echo";Object memberRound=round(types,member,memberSelection);uploaded+=number(memberRound,"uploaded");check(number(memberRound,"uploaded")==0&&number(memberRound,"applied")==1&&text(member,ownerRecord).equals(ownerText),"member actual HTTPS ciphertext decrypts to native original");emptyPending(types,member);SyncAccountProbe.passed(cases,stage);
                stage="member_upload_owner_download_actual_https";edit(types,member,memberRecord,memberText);Object memberUpload=round(types,member,memberSelection);uploaded+=number(memberUpload,"uploaded");Object ownerDownload=round(types,owner,ownerSelection);uploaded+=number(ownerDownload,"uploaded");
                check(number(memberUpload,"uploaded")==1&&number(ownerDownload,"uploaded")==0&&number(ownerDownload,"applied")==1&&text(owner,memberRecord).equals(memberText)&&text(member,ownerRecord).equals(ownerText),"actual bidirectional HTTPS and AEAD favorite transfer");SyncAccountProbe.passed(cases,stage);
                stage="final_cursors_head_and_pending";emptyPending(types,owner);emptyPending(types,member);serverHead(types,owner);serverHead(types,member);
                for(Profile profile:profiles){check(count(profile,"SELECT count(*) FROM records")==2&&count(profile,"SELECT count(*) FROM sync_received")==2&&count(profile,"SELECT count(*) FROM sync_uploads WHERE ack_revision IS NOT NULL")==1,"each isolated profile has exactly two records and one confirmed local upload");long cursor=count(profile,"SELECT CAST(value AS INTEGER) FROM metadata WHERE key='sync_cursor'");check(cursor==2,"both authenticated pull cursors reach two");cursors.put(cursor);}
                check(uploaded==2,"exactly two real server operations uploaded");head=2;SyncAccountProbe.passed(cases,stage);
            }
            stage="production_worker_platform_defaults";defaultsUnchanged=factory==HttpsURLConnection.getDefaultSSLSocketFactory()&&verifier==HttpsURLConnection.getDefaultHostnameVerifier();check(defaultsUnchanged,"platform TLS factory and hostname verifier must remain unchanged");SyncAccountProbe.passed(cases,"production_worker_platform_defaults");
        }catch(Throwable error){
            Exception safe=new Exception("synthetic Android HTTPS case: "+stage+" ("+error.getClass().getSimpleName()+")");safe.setStackTrace(error.getStackTrace());operationFailure=safe;cases.put(new JSONObject().put("id",stage).put("passed",false).put("failure_type",error.getClass().getSimpleName()));throw safe;
        }finally{
            Throwable cleanup=null;if(bootstrap!=null)bootstrap.clear();for(byte[] value:buffers)if(value!=null)Arrays.fill(value,(byte)0);
            // Both Contexts use one account-scoped Android Keystore alias. Clear
            // both indexes first, then directly remove only owned files/aliases;
            // never call Store.forget, whose load could need the other's key.
            for(Profile profile:profiles){try{check(profile.context.getSharedPreferences("sync_connection",Context.MODE_PRIVATE).edit().clear().commit(),"owned HTTPS index cleanup committed");base.deleteSharedPreferences(profile.prefix+"sync_connection");File preferences=new File(new File(base.getApplicationInfo().dataDir,"shared_prefs"),profile.prefix+"sync_connection.xml");check(!preferences.exists()&&!new File(preferences+".bak").exists(),"owned HTTPS preference files fully removed");}catch(Throwable error){if(cleanup==null)cleanup=error;}}
            if(types!=null&&aliasesOwned&&account!=null){
                for(Profile profile:profiles){
                    try{invoke(types.vault,"forgetConnection",new Class<?>[]{Context.class,UUID.class},profile.context,account);}catch(Throwable error){if(cleanup==null)cleanup=error;}
                    try{invoke(types.vault,"forget",new Class<?>[]{Context.class,UUID.class},profile.context,account);}catch(Throwable error){if(cleanup==null)cleanup=error;}
                }
                try{KeyStore after=keys();check(!after.containsAlias(alias(account,"material"))&&!after.containsAlias(alias(account,"connection")),"owned HTTPS aliases fully removed");}catch(Throwable error){if(cleanup==null)cleanup=error;}
            }
            if(directory!=null)try{delete(directory.toPath());}catch(Throwable error){if(cleanup==null)cleanup=error;}
            defaultsUnchanged=factory==HttpsURLConnection.getDefaultSSLSocketFactory()&&verifier==HttpsURLConnection.getDefaultHostnameVerifier();
            boolean roundtrip=phase.equals("roundtrip"),success=operationFailure==null&&cleanup==null&&cases.length()==(roundtrip?10:2);
            JSONObject report=new JSONObject().put("schema_version",1).put("status",success?"PASS":"FAIL").put("synthetic",true).put("api_level",Build.VERSION.SDK_INT).put("phase",phase).put("cases",cases)
                    .put("cleanup_completed",cleanup==null).put("worker_name_verified",workerVerified).put("platform_defaults_unchanged",defaultsUnchanged).put("secret_content_or_ciphertext_logged",false)
                    .put("scope",roundtrip?ROUND_SCOPE:TLS_SCOPE).put("android_https",success?(roundtrip?"PASS_FIXTURE_CA":"TLS_REJECTED"):"NOT PROVEN")
                    .put("server_exchange",success?(roundtrip?"actual_go_postgresql":"not_attempted_untrusted_tls"):"NOT PROVEN").put("profiles",roundtrip?profiles.size():0).put("server_head",head<0?JSONObject.NULL:head).put("final_cursors",cursors).put("uploaded_operations",uploaded).put("tls_failure_code",success?"tls":"NOT PROVEN");
            if(cleanup!=null)report.put("cleanup_failure_type",cleanup.getClass().getSimpleName());Files.write(new File(output,"sync-https-"+phase+"-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            if(cleanup!=null){Exception safe=new Exception("synthetic Android HTTPS cleanup failed ("+cleanup.getClass().getSimpleName()+")");if(operationFailure!=null)operationFailure.addSuppressed(safe);else throw safe;}
        }
    }
    private SyncHttpsProbe(){}
}
