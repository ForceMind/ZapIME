// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Android JNI encryption/storage/decryption on private synthetic fixtures.
 * Only the server's outer ACK/page fields are simulated. No HTTP, production
 * connection, model, credential, vault alias or main personal profile is used.
 */
final class SyncTransferProbe {
    private static final int MAX_ACK=100_000,MAX_PAGE=10*1024*1024;
    private static final String SCOPE="Actual Android stateless JNI and isolated existing-v2 SQLite; native synthetic edits, persistent frozen ciphertext, strict ACK, authenticated application, retained-page rollback and read-only inventory with strict host parsing. Server outer ACK/page metadata only is synthetic; no Android HTTPS, real server exchange, pairing, phone or Cloudflare acceptance.";
    private static final class Fixture {
        final Class<?> core;
        final File directory,root,database;
        final UUID account;
        Fixture(Context base,Class<?> core,File directory,File template,UUID account)throws Exception {
            this.core=core;this.directory=directory;this.account=account;root=new File(directory,"zapime");database=new File(root,"data/personal.sqlite3");
            check(directory.mkdir(),"fresh independent transfer fixture directory");
            Context context=new ContextWrapper(base){@Override public File getFilesDir(){return directory;}};
            Class<?> installer=Class.forName("org.zapime.android.ResourceInstaller",true,base.getClassLoader());
            invoke(installer,"prepare",new Class<?>[]{Context.class},context);
            if(template!=null){Files.createDirectories(database.getParentFile().toPath());check(!database.exists(),"transfer database must be newly owned");Files.copy(template.toPath(),database.toPath());}
        }
        String root(){return root.getAbsolutePath();}
    }
    private static final class Native {
        final Class<?> core;
        final List<byte[]> buffers=new ArrayList<>();
        Native(Class<?> core){this.core=core;}
        byte[] keep(byte[] value){if(value!=null)buffers.add(value);return value;}
        byte[] wire(JSONObject value){return keep(value.toString().getBytes(StandardCharsets.UTF_8));}
        JSONObject result(String method,Class<?>[] signature,Object...args)throws Exception {
            Object raw=invoke(core,method,signature,args);check(raw instanceof String&&((String)raw).length()<=110_000,"bounded transfer JNI response");return new JSONObject((String)raw);
        }
        JSONObject stage(Fixture fixture,UUID account,byte[] material)throws Exception {
            return result("stageSyncUpload",new Class<?>[]{String.class,String.class,byte[].class},fixture.root(),account.toString(),material);
        }
        byte[] frozen(Fixture fixture,byte[] material)throws Exception {
            JSONObject staged=stage(fixture,fixture.account,material);check(!staged.has("error")&&Boolean.TRUE.equals(staged.opt("pending"))&&staged.length()==2&&staged.opt("wire") instanceof String,"actual pending JNI wire");
            byte[] result=keep(staged.getString("wire").getBytes(StandardCharsets.UTF_8));check(result.length>0&&result.length<=MAX_ACK,"frozen request byte budget");return result;
        }
        JSONObject ack(Fixture fixture,UUID account,UUID device,byte[] material,byte[] expected,byte[] response)throws Exception {
            return result("ackSyncUpload",new Class<?>[]{String.class,String.class,String.class,byte[].class,byte[].class,byte[].class},fixture.root(),account.toString(),device.toString(),material,expected,response);
        }
        JSONObject cursor(Fixture fixture,UUID account,byte[] material)throws Exception {
            return result("syncPullCursor",new Class<?>[]{String.class,String.class,byte[].class},fixture.root(),account.toString(),material);
        }
        long cursor(Fixture fixture,byte[] material)throws Exception {JSONObject value=cursor(fixture,fixture.account,material);check(!value.has("error")&&value.length()==1,"cursor response shape");return value.getLong("cursor");}
        JSONObject apply(Fixture fixture,UUID account,byte[] material,long after,byte[] response)throws Exception {
            return result("applySyncPage",new Class<?>[]{String.class,String.class,byte[].class,long.class,byte[].class},fixture.root(),account.toString(),material,after,response);
        }
        JSONObject inventory(Fixture fixture,UUID account,byte[] material,String after,int limit)throws Exception {
            return result("syncInventoryPage",new Class<?>[]{String.class,String.class,byte[].class,String.class,int.class},fixture.root(),account.toString(),material,after,limit);
        }
        void clear(){for(byte[] value:buffers)Arrays.fill(value,(byte)0);buffers.clear();}
    }
    private static Object invoke(Class<?> core,String method,Class<?>[] signature,Object...args)throws Exception{return SyncAccountProbe.call(core,null,method,signature,args);}
    private static void check(boolean condition,String reason){SyncAccountProbe.check(condition,reason);}
    private static void rejected(JSONObject response){check(response.length()>=1&&response.opt("error") instanceof String&&((String)response.opt("error")).matches("[a-z_]{1,64}"),"JNI request must fail with fixed error");}
    private static void acknowledged(JSONObject response){check(response.length()==1&&Boolean.TRUE.equals(response.opt("acknowledged")),"actual JNI acknowledgement");}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static JSONObject change(UUID account,UUID device,JSONObject operation,long revision)throws Exception {
        return new JSONObject().put("account_id",account.toString()).put("device_id",device.toString()).put("revision",revision).put("operation",copy(operation));
    }
    private static JSONObject page(long after,JSONObject...changes)throws Exception {
        JSONArray rows=new JSONArray();for(JSONObject value:changes)rows.put(value);return new JSONObject().put("changes",rows).put("next_cursor",after+changes.length).put("head",after+changes.length);
    }
    private static void empty(JSONObject stage){check(stage.length()==1&&Boolean.FALSE.equals(stage.opt("pending")),"receiver must not create an upload echo");}
    private static void hostRetention(Class<?> transfer,JSONObject failure,boolean saved)throws Exception {
        boolean rejected=false;
        try{invoke(transfer,"reply",new Class<?>[]{String.class,int.class},failure.toString(),1024);}
        catch(IOException expected){
            boolean page=expected.getClass().getName().equals("org.zapime.android.SyncTransfer$RejectedPage");
            rejected=saved?page&&"saved".equals(SyncAccountProbe.field(expected,"retention")):!page&&"sync_native_response".equals(expected.getMessage());
        }
        check(rejected,saved?"actual retained JNI page must reach host saved outcome":"malformed retention must be rejected rather than claim saved");
    }
    private static SQLiteDatabase read(Fixture fixture){return SQLiteDatabase.openDatabase(fixture.database.toString(),null,SQLiteDatabase.OPEN_READONLY);}
    private static String scalar(Fixture fixture,String query,String...args)throws Exception {
        try(SQLiteDatabase db=read(fixture);Cursor cursor=db.rawQuery(query,args)){check(cursor.moveToFirst(),"synthetic database query must have a row");return cursor.isNull(0)?null:cursor.getString(0);}
    }
    private static long count(Fixture fixture,String query,String...args)throws Exception {return Long.parseLong(scalar(fixture,query,args));}
    private static JSONObject record(Fixture fixture,UUID id)throws Exception {return new JSONObject(scalar(fixture,"SELECT body FROM records WHERE id=?",id.toString()));}
    private static byte[] snapshot(Native nativeCore,Fixture fixture,boolean includeJournal)throws Exception {
        JSONArray tables=new JSONArray();
        try(SQLiteDatabase db=read(fixture)){
            for(String table:new String[]{"metadata","records","operations","sync_queue","sync_uploads","sync_versions","sync_received","sync_retained_pulls"}){
                if(table.equals("sync_retained_pulls")&&!includeJournal)continue;
                JSONArray rows=new JSONArray();try(Cursor cursor=db.rawQuery("SELECT * FROM "+table+" ORDER BY 1",null)){
                    while(cursor.moveToNext()){JSONArray row=new JSONArray();for(int n=0;n<cursor.getColumnCount();n++)row.put(cursor.isNull(n)?JSONObject.NULL:cursor.getString(n));rows.put(row);}
                }tables.put(new JSONObject().put("table",table).put("rows",rows));
            }
        }
        return nativeCore.keep(tables.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void same(Native nativeCore,Fixture fixture,byte[] before,boolean includeJournal){
        try{check(Arrays.equals(before,snapshot(nativeCore,fixture,includeJournal)),"rejected JNI operation changed persisted state");}
        catch(RuntimeException|Error failure){throw failure;}catch(Exception invalid){throw new AssertionError("could not compare synthetic persisted state");}
    }
    private static void edit(Fixture fixture,UUID id,UUID operation,long revision,String text)throws Exception {
        long handle=(Long)invoke(fixture.core,"open",new Class<?>[]{String.class,boolean.class},fixture.root(),false);check(handle!=0,"real native edit session open");
        try{
            JSONObject command=new JSONObject().put("op","personal_edit").put("id",id.toString()).put("operation_id",operation.toString()).put("expected_revision",revision)
                    .put("content",new JSONObject().put("type","favorite").put("title","synthetic transfer fixture").put("text",text));
            String raw=(String)invoke(fixture.core,"request",new Class<?>[]{long.class,String.class},handle,command.toString());
            check(raw!=null&&raw.length()<=10_000&&!new JSONObject(raw).has("error"),"real native personal edit accepted");
        }finally{invoke(fixture.core,"close",new Class<?>[]{long.class},handle);}
    }
    private static File template(Context base,Class<?> core,File directory,UUID account)throws Exception {
        Fixture template=new Fixture(base,core,directory,null,account);
        long handle=(Long)invoke(core,"open",new Class<?>[]{String.class,boolean.class},template.root(),false);check(handle!=0,"empty full-v2 native template initialization");invoke(core,"close",new Class<?>[]{long.class},handle);
        try(SQLiteDatabase database=SQLiteDatabase.openDatabase(template.database.toString(),null,SQLiteDatabase.OPEN_READWRITE);Cursor result=database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){
            check(result.moveToFirst()&&result.getInt(0)==0,"empty transfer template checkpoint");
        }
        check(count(template,"SELECT count(*) FROM records")==0&&count(template,"SELECT count(*) FROM operations")==0,"native transfer template must be empty");return template.database;
    }
    private static void prepare(Native nativeCore,Fixture fixture,byte[] material)throws Exception {
        JSONObject result=nativeCore.result("prepareSync",new Class<?>[]{String.class,String.class,byte[].class},fixture.root(),fixture.account.toString(),material);
        check(result.length()==5&&Boolean.TRUE.equals(result.opt("prepared"))&&fixture.account.toString().equals(result.opt("account_id")),"explicit same-material local binding");
    }
    private static void allReject(Native nativeCore,Fixture fixture,UUID account,UUID device,byte[] material,byte[] frozen,byte[] ack,byte[] page)throws Exception {
        rejected(nativeCore.stage(fixture,account,material));rejected(nativeCore.cursor(fixture,account,material));rejected(nativeCore.ack(fixture,account,device,material,frozen,ack));rejected(nativeCore.apply(fixture,account,material,0,page));
        rejected(nativeCore.inventory(fixture,account,material,null,40));
    }
    private static Object parseInventory(Class<?> inventory,String raw,UUID after,int limit)throws Exception {
        return invoke(inventory,"parse",new Class<?>[]{String.class,UUID.class,int.class},raw,after,limit);
    }
    private static void rejectsInventory(Class<?> inventory,String raw,UUID after,int limit)throws Exception {
        boolean rejected=false;try{parseInventory(inventory,raw,after,limit);}catch(IOException fixed){rejected=true;}
        check(rejected,"inventory parser accepted malformed/coerced metadata");
    }
    private static String archive(Native nativeCore,Fixture fixture)throws Exception {
        long handle=(Long)invoke(fixture.core,"open",new Class<?>[]{String.class,boolean.class},fixture.root(),false);check(handle!=0,"inventory synthetic archive session");
        try{return new JSONObject((String)invoke(fixture.core,"request",new Class<?>[]{long.class,String.class},handle,"{\"op\":\"personal_export\"}")).getString("archive");}
        finally{invoke(fixture.core,"close",new Class<?>[]{long.class},handle);}
    }
    static void run(Instrumentation instrumentation,File output)throws Exception {
        Context base=instrumentation.getTargetContext();Class<?> core=Class.forName("org.zapime.android.NativeCore",true,base.getClassLoader());Native nativeCore=new Native(core);
        File directory=new File(base.getCacheDir(),"sync-transfer-"+UUID.randomUUID());check(directory.mkdir(),"fresh private JNI transfer directory");
        JSONArray cases=new JSONArray();String stage="isolated_existing_v2_shared_material";Throwable operationFailure=null;
        try{
            UUID account=UUID.randomUUID(),serverDevice=UUID.randomUUID(),recordId=UUID.randomUUID(),operation1=UUID.randomUUID(),operation2=UUID.randomUUID();
            File template=template(base,core,new File(directory,"template"),account);
            Fixture source=new Fixture(base,core,new File(directory,"source"),template,account),target=new Fixture(base,core,new File(directory,"target"),template,account);
            byte[] material=nativeCore.keep((byte[])invoke(core,"newSyncMaterial",new Class<?>[]{String.class},account.toString()));check(material!=null&&material.length==72,"actual fixed native material");
            prepare(nativeCore,source,material);prepare(nativeCore,target,material);check(nativeCore.cursor(source,material)==0&&nativeCore.cursor(target,material)==0,"fresh independent pull cursors");SyncAccountProbe.passed(cases,stage);
            stage="native_edit_stage_frozen_retry";
            final String initial="同步传输合成原文甲",later="同步传输合成后续乙",third="同步传输合成第二条丙";
            edit(source,recordId,operation1,0,initial);byte[] frozen1=nativeCore.frozen(source,material),retry=nativeCore.frozen(source,material);
            JSONObject op1=new JSONObject(new String(frozen1,StandardCharsets.UTF_8));
            check(Arrays.equals(frozen1,retry)&&operation1.toString().equals(op1.opt("operation_id"))&&op1.getJSONObject("envelope").getString("ciphertext").length()>22,"real frozen wire and nonce survive new JNI connection");
            check(!new String(frozen1,StandardCharsets.UTF_8).contains(initial)&&scalar(source,"SELECT wire FROM sync_uploads WHERE operation_id=?",operation1.toString()).equals(new String(frozen1,StandardCharsets.UTF_8)),"actual persisted encrypted request only");
            check(count(source,"SELECT count(*) FROM sync_uploads")==1&&nativeCore.cursor(source,material)==0,"one actual freeze without pull advancement");SyncAccountProbe.passed(cases,stage);
            JSONObject ack1=change(account,serverDevice,op1,1);byte[] ack1Bytes=nativeCore.wire(ack1);
            stage="ack_identity_operation_revision_rejection";byte[] before=snapshot(nativeCore,source,true);
            for(String field:new String[]{"account_id","device_id","operation","revision"}){
                JSONObject invalid=copy(ack1);
                if(field.equals("operation")){JSONObject changed=copy(op1);changed.put("operation_id",UUID.randomUUID().toString());invalid.put(field,changed);}
                else invalid.put(field,field.equals("revision")?0:UUID.randomUUID().toString());
                rejected(nativeCore.ack(source,account,serverDevice,material,frozen1,nativeCore.wire(invalid)));same(nativeCore,source,before,true);
            }
            rejected(nativeCore.ack(source,account,UUID.randomUUID(),material,frozen1,ack1Bytes));same(nativeCore,source,before,true);SyncAccountProbe.passed(cases,stage);
            stage="strict_ack_json_shape_rejection";
            JSONObject unknown=copy(ack1).put("unexpected",true),wrongType=copy(ack1).put("revision","1");
            String duplicate="{\"account_id\":\""+account+"\","+ack1.toString().substring(1);
            for(byte[] invalid:new byte[][]{nativeCore.wire(unknown),nativeCore.wire(wrongType),nativeCore.keep(duplicate.getBytes(StandardCharsets.UTF_8)),nativeCore.keep((ack1+" {}").getBytes(StandardCharsets.UTF_8))}){
                rejected(nativeCore.ack(source,account,serverDevice,material,frozen1,invalid));same(nativeCore,source,before,true);
            }SyncAccountProbe.passed(cases,stage);
            stage="ack_requires_actual_stage";
            edit(source,recordId,operation2,1,later);byte[] laterBefore=snapshot(nativeCore,source,true);
            JSONObject unstaged=copy(op1).put("operation_id",operation2.toString());byte[] unstagedWire=nativeCore.wire(unstaged);
            rejected(nativeCore.ack(source,account,serverDevice,material,unstagedWire,nativeCore.wire(change(account,serverDevice,unstaged,2))));
            same(nativeCore,source,laterBefore,true);check(count(source,"SELECT count(*) FROM sync_uploads WHERE operation_id=?",operation2.toString())==0,"ACK cannot create staging row");SyncAccountProbe.passed(cases,stage);
            stage="ack_idempotent_preserves_cursor_and_later_edit";
            acknowledged(nativeCore.ack(source,account,serverDevice,material,frozen1,ack1Bytes));byte[] acknowledged=snapshot(nativeCore,source,true);
            acknowledged(nativeCore.ack(source,account,serverDevice,material,frozen1,ack1Bytes));same(nativeCore,source,acknowledged,true);
            check(nativeCore.cursor(source,material)==0&&record(source,recordId).getJSONObject("content").getString("text").equals(later)
                    &&count(source,"SELECT pending FROM operations WHERE id=?",operation2.toString())==1&&count(source,"SELECT ack_revision FROM sync_uploads WHERE operation_id=?",operation1.toString())==1,"ACK preserves later edit and independent pull cursor");SyncAccountProbe.passed(cases,stage);
            stage="authenticated_remote_apply_plaintext_no_echo";byte[] firstPage=nativeCore.wire(page(0,ack1));
            JSONObject applied=nativeCore.apply(target,account,material,0,firstPage);
            check(!applied.has("error")&&applied.length()==5&&applied.getLong("cursor")==1&&applied.getInt("applied")==1&&applied.getInt("echoed")==0&&nativeCore.cursor(target,material)==1,"actual JNI remote page application");
            check(record(target,recordId).getJSONObject("content").getString("text").equals(initial)&&count(target,"SELECT count(*) FROM operations")==0&&count(target,"SELECT count(*) FROM sync_received")==1,"actual decrypted synthetic text without local operation");empty(nativeCore.stage(target,account,material));SyncAccountProbe.passed(cases,stage);
            stage="authenticated_self_echo_no_duplicate";
            JSONObject echoed=nativeCore.apply(source,account,material,0,firstPage);
            check(!echoed.has("error")&&echoed.getLong("cursor")==1&&echoed.getInt("echoed")==1&&echoed.getInt("applied")==0&&count(source,"SELECT count(*) FROM records")==1
                    &&count(source,"SELECT count(*) FROM operations")==2&&record(source,recordId).getJSONObject("content").getString("text").equals(later)&&count(source,"SELECT pending FROM operations WHERE id=?",operation2.toString())==1,"self echo preserves later edit without new operation");SyncAccountProbe.passed(cases,stage);
            stage="bad_second_change_atomic_rollback_retained";
            byte[] frozen2=nativeCore.frozen(source,material);JSONObject op2=new JSONObject(new String(frozen2,StandardCharsets.UTF_8));check(op2.getLong("base_revision")==1,"later edit uses original acknowledged server revision");
            JSONObject ack2=change(account,serverDevice,op2,2);acknowledged(nativeCore.ack(source,account,serverDevice,material,frozen2,nativeCore.wire(ack2)));
            UUID anotherId=UUID.randomUUID(),operation3=UUID.randomUUID();edit(source,anotherId,operation3,0,third);byte[] frozen3=nativeCore.frozen(source,material);JSONObject op3=new JSONObject(new String(frozen3,StandardCharsets.UTF_8));
            JSONObject ack3=change(account,serverDevice,op3,3),bad3=copy(ack3);JSONObject badEnvelope=bad3.getJSONObject("operation").getJSONObject("envelope");String ciphertext=badEnvelope.getString("ciphertext");badEnvelope.put("ciphertext",(ciphertext.charAt(0)=='A'?"B":"A")+ciphertext.substring(1));
            byte[] badPage=nativeCore.wire(page(1,ack2,bad3)),validPage=nativeCore.wire(page(1,ack2,ack3)),targetBefore=snapshot(nativeCore,target,false);
            JSONObject failed=nativeCore.apply(target,account,material,1,badPage);rejected(failed);
            check(failed.length()==5&&"sync_client_page_rejected".equals(failed.opt("error"))&&"saved".equals(failed.opt("retention"))&&failed.opt("retained_id") instanceof String&&((String)failed.opt("retained_id")).matches("[0-9a-f]{64}")&&failed.isNull("retention_error"),"eligible failed page must actually be retained");same(nativeCore,target,targetBefore,false);
            Class<?> transfer=Class.forName("org.zapime.android.SyncTransfer",true,base.getClassLoader());hostRetention(transfer,failed,true);
            hostRetention(transfer,copy(failed).put("retained_id",JSONObject.NULL),false);hostRetention(transfer,copy(failed).put("retention","not_eligible"),false);
            hostRetention(transfer,copy(failed).put("retention_error","storage_error"),false);
            check(nativeCore.cursor(target,material)==1&&count(target,"SELECT count(*) FROM records")==1&&count(target,"SELECT count(*) FROM sync_retained_pulls")==1
                    &&count(target,"SELECT after_cursor FROM sync_retained_pulls")==1&&scalar(target,"SELECT wire FROM sync_retained_pulls").equals(new String(badPage,StandardCharsets.UTF_8)),"entire failed page rolled back and exact untrusted wire persisted");
            JSONObject repaired=nativeCore.apply(target,account,material,1,validPage);check(!repaired.has("error")&&repaired.getInt("applied")==2&&nativeCore.cursor(target,material)==3
                    &&record(target,recordId).getJSONObject("content").getString("text").equals(later)&&record(target,anotherId).getJSONObject("content").getString("text").equals(third),"original authenticated two-change page recovers without deleting failure journal");empty(nativeCore.stage(target,account,material));SyncAccountProbe.passed(cases,stage);
            stage="wrong_material_account_no_mutation";byte[] boundBefore=snapshot(nativeCore,target,true),wrongMaterial=nativeCore.keep(material.clone());wrongMaterial[wrongMaterial.length-1]^=1;
            allReject(nativeCore,target,account,serverDevice,wrongMaterial,frozen1,ack1Bytes,firstPage);same(nativeCore,target,boundBefore,true);
            allReject(nativeCore,target,UUID.randomUUID(),serverDevice,material,frozen1,ack1Bytes,firstPage);same(nativeCore,target,boundBefore,true);
            allReject(nativeCore,target,new UUID(0,0),serverDevice,material,frozen1,ack1Bytes,firstPage);same(nativeCore,target,boundBefore,true);SyncAccountProbe.passed(cases,stage);
            stage="negative_cursor_and_wire_budgets";
            rejected(nativeCore.apply(target,account,material,-1,firstPage));same(nativeCore,target,boundBefore,true);
            // JNI must reject null arrays/strings before GetArrayLength/string
            // access. A pending Java NPE/Throwable is a failure, never a pass.
            Class<?>[] three=new Class<?>[]{String.class,String.class,byte[].class};
            Class<?>[] ackSignature=new Class<?>[]{String.class,String.class,String.class,byte[].class,byte[].class,byte[].class};
            Class<?>[] applySignature=new Class<?>[]{String.class,String.class,byte[].class,long.class,byte[].class};
            for(String method:new String[]{"stageSyncUpload","syncPullCursor"}){
                rejected(nativeCore.result(method,three,target.root(),account.toString(),null));
                rejected(nativeCore.result(method,three,null,account.toString(),material));
                rejected(nativeCore.result(method,three,target.root(),null,material));
            }
            rejected(nativeCore.result("ackSyncUpload",ackSignature,target.root(),account.toString(),serverDevice.toString(),null,frozen1,ack1Bytes));
            rejected(nativeCore.result("ackSyncUpload",ackSignature,target.root(),account.toString(),serverDevice.toString(),material,null,ack1Bytes));
            rejected(nativeCore.result("ackSyncUpload",ackSignature,target.root(),account.toString(),serverDevice.toString(),material,frozen1,null));
            rejected(nativeCore.result("ackSyncUpload",ackSignature,null,account.toString(),serverDevice.toString(),material,frozen1,ack1Bytes));
            rejected(nativeCore.result("ackSyncUpload",ackSignature,target.root(),null,serverDevice.toString(),material,frozen1,ack1Bytes));
            rejected(nativeCore.result("ackSyncUpload",ackSignature,target.root(),account.toString(),null,material,frozen1,ack1Bytes));
            rejected(nativeCore.result("applySyncPage",applySignature,target.root(),account.toString(),null,3L,firstPage));
            rejected(nativeCore.result("applySyncPage",applySignature,target.root(),account.toString(),material,3L,null));
            rejected(nativeCore.result("applySyncPage",applySignature,null,account.toString(),material,3L,firstPage));
            rejected(nativeCore.result("applySyncPage",applySignature,target.root(),null,material,3L,firstPage));
            check(invoke(core,"newSyncMaterial",new Class<?>[]{String.class},(Object)null)==null,"null generation account must return defined null array");
            check(invoke(core,"createSyncRecovery",new Class<?>[]{String.class,byte[].class,byte[].class},account.toString(),material,null)==null,"null recovery output must return defined null array");
            check(invoke(core,"restoreSyncRecovery",new Class<?>[]{String.class,byte[].class,byte[].class},account.toString(),null,firstPage)==null,"null recovery code must return defined null array");
            same(nativeCore,target,boundBefore,true);
            byte[] oversizedAck=nativeCore.keep(new byte[MAX_ACK+1]),oversizedPage=nativeCore.keep(new byte[MAX_PAGE+1]),oversizedMaterial=nativeCore.keep(new byte[1025]);Arrays.fill(oversizedAck,(byte)' ');Arrays.fill(oversizedPage,(byte)' ');
            rejected(nativeCore.ack(target,account,serverDevice,material,frozen1,oversizedAck));rejected(nativeCore.ack(target,account,serverDevice,material,oversizedAck,ack1Bytes));
            rejected(nativeCore.apply(target,account,material,3,oversizedPage));rejected(nativeCore.stage(target,account,oversizedMaterial));same(nativeCore,target,boundBefore,true);SyncAccountProbe.passed(cases,stage);
            stage="missing_future_database_not_created_or_migrated";
            // This object has an owned parent but intentionally no resource or
            // data directory. The native API must never initialize it.
            File absentDirectory=new File(directory,"absent");check(absentDirectory.mkdir(),"independent absent parent");
            Fixture absent=new Fixture(base,core,new File(absentDirectory,"owned"),null,account);
            // ResourceInstaller created only resource directories; remove this
            // owned root so all four calls encounter a truly missing database.
            delete(absent.root.toPath());check(!absent.root.exists(),"owned absent root removed before boundary test");
            allReject(nativeCore,absent,account,serverDevice,material,frozen1,ack1Bytes,firstPage);check(!absent.root.exists(),"transfer must not create a missing root or database");
            Fixture future=new Fixture(base,core,new File(directory,"future"),template,account);
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(future.database.toString(),null,SQLiteDatabase.OPEN_READWRITE)){db.execSQL("PRAGMA user_version=3");}
            byte[] futureBefore=snapshot(nativeCore,future,true);allReject(nativeCore,future,account,serverDevice,material,frozen1,ack1Bytes,firstPage);same(nativeCore,future,futureBefore,true);
            check(count(future,"PRAGMA user_version")==3&&count(future,"SELECT count(*) FROM metadata WHERE key LIKE 'sync_%'")==0,"future database must not migrate or bind");SyncAccountProbe.passed(cases,stage);
            stage="readonly_inventory_pending_known_no_mutation";
            Class<?> inventory=Class.forName("org.zapime.android.SyncInventory",true,base.getClassLoader());
            byte[] sourceInventoryBefore=snapshot(nativeCore,source,true),targetInventoryBefore=snapshot(nativeCore,target,true);
            JSONObject sourcePage=nativeCore.inventory(source,account,material,null,40),targetPage=nativeCore.inventory(target,account,material,null,40);
            parseInventory(inventory,sourcePage.toString(),null,40);parseInventory(inventory,targetPage.toString(),null,40);
            boolean pendingSeen=false;
            for(int n=0;n<sourcePage.getJSONArray("items").length();n++){
                JSONObject item=sourcePage.getJSONArray("items").getJSONObject(n);
                if("pending_local_operation".equals(item.opt("state")))pendingSeen=true;
            }
            check(pendingSeen,"inventory must preserve local pending evidence");
            for(int n=0;n<targetPage.getJSONArray("items").length();n++){
                JSONObject item=targetPage.getJSONArray("items").getJSONObject(n);
                check("server_version_known".equals(item.opt("state"))&&!item.getBoolean("has_local_operation_history")&&!item.getBoolean("has_pending_local_operation"),"downloaded record became local upload/history");
            }
            check(!sourcePage.toString().contains(initial)&&!sourcePage.toString().contains(later)&&!targetPage.toString().contains(third),"inventory leaked synthetic content");
            same(nativeCore,source,sourceInventoryBefore,true);same(nativeCore,target,targetInventoryBefore,true);SyncAccountProbe.passed(cases,stage);
            stage="readonly_inventory_restored_pagination";
            Fixture collection=new Fixture(base,core,new File(directory,"collection"),template,account),restored=new Fixture(base,core,new File(directory,"restored"),template,account);
            long catalog=(Long)invoke(core,"open",new Class<?>[]{String.class,boolean.class},collection.root(),false);check(catalog!=0,"synthetic inventory collection initialization");
            try{for(int n=1;n<=43;n++){
                JSONObject edit=new JSONObject().put("op","personal_edit").put("id",new UUID(0,n).toString()).put("operation_id",UUID.randomUUID().toString()).put("expected_revision",0).put("deleted",n%7==0)
                        .put("content",new JSONObject().put("type","favorite").put("title","synthetic inventory").put("text","仅在夹具内的正文"));
                JSONObject response=new JSONObject((String)invoke(core,"request",new Class<?>[]{long.class,String.class},catalog,edit.toString()));check(!response.has("error"),"native synthetic inventory edit rejected");
            }}finally{invoke(core,"close",new Class<?>[]{long.class},catalog);}
            String archive=archive(nativeCore,collection);
            long imported=(Long)invoke(core,"open",new Class<?>[]{String.class,boolean.class},restored.root(),false);check(imported!=0,"native restored inventory initialization");
            try{JSONObject response=new JSONObject((String)invoke(core,"request",new Class<?>[]{long.class,String.class},imported,new JSONObject().put("op","personal_import").put("archive",archive).toString()));check(!response.has("error"),"actual records-only import failed");}
            finally{invoke(core,"close",new Class<?>[]{long.class},imported);}
            prepare(nativeCore,restored,material);byte[] restoredBefore=snapshot(nativeCore,restored,true);
            String after=null;int seen=0,deleted=0,pages=0;
            do{
                JSONObject value=nativeCore.inventory(restored,account,material,after,40);parseInventory(inventory,value.toString(),after==null?null:UUID.fromString(after),40);
                JSONArray items=value.getJSONArray("items");check(items.length()==(pages==0?40:3),"actual metadata page bound differs");
                for(int n=0;n<items.length();n++){
                    JSONObject item=items.getJSONObject(n);seen++;
                    check(new UUID(0,seen).toString().equals(item.getString("id"))&&"untracked".equals(item.opt("state"))&&!item.getBoolean("has_pending_local_operation")&&!item.getBoolean("has_local_operation_history")&&item.isNull("known_server_revision"),"restored evidence/ID/order was rewritten");
                    if(item.getBoolean("deleted"))deleted++;
                }
                after=value.isNull("next_after")?null:value.getString("next_after");pages++;
                check(!value.toString().contains("仅在夹具内的正文")&&pages<=2,"inventory leaks content or loops");
            }while(after!=null);
            check(seen==43&&deleted==6&&pages==2&&count(restored,"SELECT count(*) FROM operations")==0,"restored page count/tombstones/zero operations differ");same(nativeCore,restored,restoredBefore,true);SyncAccountProbe.passed(cases,stage);
            stage="readonly_inventory_input_and_parser_rejection";
            Class<?>[] inventorySignature={String.class,String.class,byte[].class,String.class,int.class};
            for(int limit:new int[]{-1,0,101,Integer.MAX_VALUE})rejected(nativeCore.inventory(restored,account,material,null,limit));
            for(String bad:new String[]{"",new UUID(0,0).toString(),"not-a-uuid","00000000-0000-0000-0000-00000000000A"})rejected(nativeCore.inventory(restored,account,material,bad,40));
            rejected(nativeCore.result("syncInventoryPage",inventorySignature,restored.root(),account.toString(),null,null,40));
            rejected(nativeCore.result("syncInventoryPage",inventorySignature,null,account.toString(),material,null,40));
            rejected(nativeCore.result("syncInventoryPage",inventorySignature,restored.root(),null,material,null,40));
            rejected(nativeCore.inventory(restored,account,wrongMaterial,null,40));rejected(nativeCore.inventory(restored,UUID.randomUUID(),material,null,40));
            JSONObject valid=nativeCore.inventory(restored,account,material,null,1);parseInventory(inventory,valid.toString(),null,1);
            String validText=valid.toString();
            rejectsInventory(inventory,"{\"items\":[],\"items\":[],\"next_after\":null}",null,1);
            rejectsInventory(inventory,validText+" {}",null,1);rejectsInventory(inventory,copy(valid).put("text","forbidden").toString(),null,1);
            rejectsInventory(inventory,validText.replace("\"local_revision\":1","\"local_revision\":1.0"),null,1);
            for(String field:new String[]{"kind","deleted","state","has_pending_local_operation","current_snapshot_pending","known_server_revision"}){
                JSONObject altered=copy(valid),item=altered.getJSONArray("items").getJSONObject(0);
                item.put(field,field.equals("state")?"server_version_known":field.equals("deleted")?"false":field.equals("known_server_revision")?0:field.equals("kind")?6:true);
                rejectsInventory(inventory,altered.toString(),null,1);
            }
            rejectsInventory(inventory,copy(valid).put("next_after",UUID.randomUUID().toString()).toString(),null,1);
            rejectsInventory(inventory,validText,UUID.fromString(valid.getJSONArray("items").getJSONObject(0).getString("id")),1);
            rejectsInventory(inventory,validText,null,0);same(nativeCore,restored,restoredBefore,true);SyncAccountProbe.passed(cases,stage);
        }catch(Throwable error){
            Exception safe=new Exception("synthetic sync transfer case: "+stage+" ("+error.getClass().getSimpleName()+")");safe.setStackTrace(error.getStackTrace());operationFailure=safe;
            cases.put(new JSONObject().put("id",stage).put("passed",false).put("failure_type",error.getClass().getSimpleName()));throw safe;
        }finally{
            nativeCore.clear();Throwable cleanup=null;try{delete(directory.toPath());}catch(Throwable error){cleanup=error;}
            boolean success=operationFailure==null&&cleanup==null&&cases.length()==15;
            JSONObject report=new JSONObject().put("schema_version",2).put("status",success?"PASS":"FAIL").put("synthetic",true).put("api_level",Build.VERSION.SDK_INT).put("cases",cases)
                    .put("cleanup_completed",cleanup==null).put("android_https","NOT RUN").put("server_exchange","synthetic_outer_only").put("scope",SCOPE)
                    .put("actual_native_edit",success).put("actual_jni_staging",success).put("actual_jni_acknowledgement",success).put("actual_jni_authenticated_apply",success)
                    .put("frozen_retry_identical",success).put("plaintext_verified_locally",success).put("no_upload_echo_on_receiver",success).put("bad_page_rolled_back",success)
                    .put("failed_page_retention",success?"saved":"NOT PROVEN").put("secret_content_or_ciphertext_logged",false).put("actual_jni_readonly_inventory",success);
            if(cleanup!=null)report.put("cleanup_failure_type",cleanup.getClass().getSimpleName());
            Files.write(new File(output,"sync-transfer-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            if(cleanup!=null){Exception safe=new Exception("synthetic sync transfer cleanup failed ("+cleanup.getClass().getSimpleName()+")");if(operationFailure!=null)operationFailure.addSuppressed(safe);else throw safe;}
        }
    }
    private static void delete(Path directory)throws Exception {
        if(!Files.exists(directory))return;
        try(java.util.stream.Stream<Path> paths=Files.walk(directory)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);}
    }
    private SyncTransferProbe(){}
}
