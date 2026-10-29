// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;
import android.app.Instrumentation;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Real JNI read-only metadata/credential guard on a separate synthetic fixture. */
final class SyncProfileProbe {
    static void run(Instrumentation i,File output)throws Exception {
        Context base=i.getTargetContext();String prefix="profile-guard-"+UUID.randomUUID();
        File fixture=new File(base.getCacheDir(),prefix);SyncAccountProbe.check(!fixture.exists()&&fixture.mkdirs(),"fresh profile fixture");
        Context context=new ContextWrapper(base){
            @Override public File getFilesDir(){return fixture;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(prefix+"-"+name,mode);}
        };
        ClassLoader loader=base.getClassLoader();Class<?> core=Class.forName("org.zapime.android.NativeCore",true,loader),guard=Class.forName("org.zapime.android.SyncDatabase",true,loader),model=Class.forName("org.zapime.android.SyncConnection",true,loader),store=Class.forName("org.zapime.android.SyncConnectionStore",true,loader);
        File root=new File(fixture,"zapime"),database=new File(root,"data/personal.sqlite3");
        UUID account=UUID.randomUUID(),other=UUID.randomUUID(),device=UUID.randomUUID();JSONArray cases=new JSONArray();String stage="absent";Object a=null,b=null;
        try {
            JSONObject absent=new JSONObject((String)SyncAccountProbe.call(core,null,"syncProfile",new Class<?>[]{String.class},root.getAbsolutePath()));
            SyncAccountProbe.check(!absent.getBoolean("initialized")&&absent.isNull("account_id")&&!root.exists(),"read created database directories");
            SyncAccountProbe.passed(cases,"absent_no_create");stage="canonical";
            SyncAccountProbe.check(database.getParentFile().mkdirs(),"fixture database directory");
            try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(database,null)){
                db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");db.execSQL("PRAGMA user_version=2");
            }
            SyncAccountProbe.call(guard,null,"check",new Class<?>[]{Context.class,UUID.class},context,account);
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.toString(),null,SQLiteDatabase.OPEN_READWRITE)){
                db.execSQL("INSERT INTO metadata VALUES('sync_profile',?)",new Object[]{account.toString()});
            }
            byte[] before=Files.readAllBytes(database.toPath());
            SyncAccountProbe.call(guard,null,"check",new Class<?>[]{Context.class,UUID.class},context,account);
            JSONObject bound=new JSONObject((String)SyncAccountProbe.call(core,null,"syncProfile",new Class<?>[]{String.class},root.getAbsolutePath()));
            SyncAccountProbe.check(bound.getBoolean("initialized")&&bound.getString("account_id").equals(account.toString()),"native profile mismatch");
            SyncAccountProbe.check(Arrays.equals(before,Files.readAllBytes(database.toPath())),"read changed primary database bytes");
            SyncAccountProbe.passed(cases,"canonical_binding");stage="cross account";
            a=connection(model,account,device);b=connection(model,other,device);
            SyncAccountProbe.call(store,null,"save",new Class<?>[]{Context.class,model},context,a);
            File cipher=new File(base.getNoBackupFilesDir(),"sync-connection-"+account+".bin");byte[] saved=Files.readAllBytes(cipher.toPath());final Object wrong=b;
            boolean rejected=false;
            try{SyncAccountProbe.call(store,null,"save",new Class<?>[]{Context.class,model},context,wrong);}catch(IOException error){rejected="sync_connection_profile".equals(error.getMessage());}
            SyncAccountProbe.check(rejected&&Arrays.equals(saved,Files.readAllBytes(cipher.toPath())),"cross-account save replaced vault");
            Object loaded=SyncAccountProbe.call(store,null,"load",new Class<?>[]{Context.class},context);
            try{SyncAccountProbe.check(SyncAccountProbe.field(loaded,"account").equals(account),"cross-account save changed index");}finally{SyncAccountProbe.close(model,loaded);}
            SyncAccountProbe.call(store,null,"forget",new Class<?>[]{Context.class},context);
            boolean afterRemoval=false;
            try{SyncAccountProbe.call(store,null,"save",new Class<?>[]{Context.class,model},context,wrong);}catch(IOException error){afterRemoval="sync_connection_profile".equals(error.getMessage());}
            SyncAccountProbe.check(afterRemoval&&!context.getSharedPreferences("sync_connection",0).contains("active_account"),"removal bypassed database binding");
            SyncAccountProbe.passed(cases,"cross_account_preserves_vault");stage="invalid state";
            for(String invalid:new String[]{"bad-profile","00000000-0000-0000-0000-000000000000","AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"}){
                try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.toString(),null,SQLiteDatabase.OPEN_READWRITE)){db.execSQL("UPDATE metadata SET value=? WHERE key='sync_profile'",new Object[]{invalid});}
                SyncAccountProbe.reject(()->SyncAccountProbe.call(guard,null,"check",new Class<?>[]{Context.class,UUID.class},context,account));
            }
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.toString(),null,SQLiteDatabase.OPEN_READWRITE)){db.execSQL("PRAGMA user_version=3");}
            SyncAccountProbe.reject(()->SyncAccountProbe.call(guard,null,"check",new Class<?>[]{Context.class,UUID.class},context,account));
            SyncAccountProbe.check(!new JSONObject((String)SyncAccountProbe.call(core,null,"syncProfile",new Class<?>[]{String.class},root.getAbsolutePath())).has("initialized"),"unknown schema treated as unbound");
            SyncAccountProbe.passed(cases,"invalid_future_fail_closed");stage="worker";
            SyncAccountProbe.main(i,()->{SyncAccountProbe.reject(()->SyncAccountProbe.call(guard,null,"check",new Class<?>[]{Context.class,UUID.class},context,account));return null;});
            SyncAccountProbe.passed(cases,"worker_guard");
        }catch(Throwable error){cases.put(new JSONObject().put("id",stage).put("passed",false).put("failure_type",error.getClass().getSimpleName()));throw new Exception("synthetic profile guard case: "+stage,error);}
        finally {
            SyncAccountProbe.close(model,a);SyncAccountProbe.close(model,b);SyncAccountProbe.call(store,null,"forget",new Class<?>[]{Context.class},context);
            base.deleteSharedPreferences(prefix+"-sync_connection");
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture.toPath())){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}
            Files.write(new File(output,"sync-profile-report.json").toPath(),new JSONObject().put("synthetic",true).put("api_level",Build.VERSION.SDK_INT).put("cases",cases).put("scope","Real stateless JNI on isolated SQLite v2 metadata fixture, no-create/read-only profile inspection and actual credential-store guard; full Store/history preservation tested separately in Rust. No automatic binding, data sync or Android HTTPS success.").toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static Object connection(Class<?> model,UUID account,UUID device)throws Exception {
        byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);String token="zap1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(random);Arrays.fill(random,(byte)0);
        JSONObject credential=new JSONObject().put("account_id",account.toString()).put("device_id",device.toString()).put("token",token).put("expires_at","2099-01-01T00:00:00Z");
        Object draft=SyncAccountProbe.call(model,null,"imported",new Class<?>[]{String.class,String.class},"https://synthetic.example",credential.toString());
        try{return SyncAccountProbe.call(model,draft,"verify",new Class<?>[]{byte[].class},new JSONObject().put("protocol",1).put("account_id",account.toString()).put("device_id",device.toString()).put("role","owner").put("expires_at","2099-01-01T00:00:00Z").toString().getBytes(StandardCharsets.UTF_8));}
        finally{SyncAccountProbe.close(model,draft);}
    }
    private SyncProfileProbe(){}
}
