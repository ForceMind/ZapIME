// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONObject;

/** Explicit device-local preparation only. No HTTP, automatic upload or global
 * account-key registration. All calls belong on the independent sync worker.
 * Secret material loaded/created here is cleared in finally; successful recovery
 * output/codeOut and restore input buffers remain the caller's responsibility.
 */
final class SyncPreparation {
    static final int MAX_RECOVERY=16*1024;
    private static final int MAX_MATERIAL=64*1024;
    static final class State {
        final UUID connectedAccount,account,keyId;
        final boolean initialized,materialPresent,prepared;
        private State(UUID connectedAccount,UUID account,UUID keyId,boolean initialized,boolean materialPresent,boolean prepared){
            this.connectedAccount=connectedAccount;this.account=account;this.keyId=keyId;
            this.initialized=initialized;this.materialPresent=materialPresent;this.prepared=prepared;
        }
    }
    private static void worker()throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("sync_preparation_requires_worker");
    }
    private static UUID id(Object raw)throws IOException {
        try{
            if(!(raw instanceof String))throw new IllegalArgumentException();
            UUID id=UUID.fromString((String)raw);
            if(id.equals(new UUID(0,0))||!id.toString().equals(raw))throw new IllegalArgumentException();return id;
        }catch(RuntimeException invalid){throw new IOException("sync_preparation_failed");}
    }
    private static JSONObject reply(String raw)throws IOException {
        try{
            if(raw==null||raw.length()>512)throw new IllegalArgumentException();JSONObject result=new JSONObject(raw);
            if(result.has("error")){
                Object error=result.get("error");
                if(result.length()!=1||!(error instanceof String)||!((String)error).matches("[a-z_]{1,64}"))throw new IllegalArgumentException();
                throw new IOException((String)error);
            }
            return result;
        }catch(IOException fixed){throw fixed;}
        catch(Exception invalid){throw new IOException("sync_preparation_failed");}
    }
    private static String root(Context context){return new File(context.getFilesDir(),"zapime").getAbsolutePath();}
    private static void material(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>MAX_MATERIAL)throw new IOException("sync_preparation_failed");
    }
    private static UUID materialKey(UUID account,byte[] bytes)throws IOException {
        material(bytes);
        try{
            JSONObject result=reply(NativeCore.syncMaterialInfo(account.toString(),bytes));
            if(result.length()!=2||!account.equals(id(result.get("account_id"))))throw new IOException("sync_preparation_failed");
            return id(result.get("key_id"));
        }catch(IOException fixed){throw fixed;}
        catch(Exception|LinkageError invalid){throw new IOException("sync_preparation_failed");}
    }
    private static State status(Context context,UUID account,byte[] bytes)throws IOException {
        try{
            JSONObject result=reply(NativeCore.syncWorkspaceStatus(root(context),account.toString(),bytes));
            if(result.length()!=5||!result.has("account_id")||!result.has("key_id")||!(result.get("initialized") instanceof Boolean)||!(result.get("material_present") instanceof Boolean)||!(result.get("prepared") instanceof Boolean))throw new IOException("sync_preparation_failed");
            UUID bound=result.isNull("account_id")?null:id(result.get("account_id"));
            UUID key=result.isNull("key_id")?null:id(result.get("key_id"));
            boolean initialized=result.getBoolean("initialized"),present=result.getBoolean("material_present"),prepared=result.getBoolean("prepared");
            if((bound!=null&&!bound.equals(account))||present!=(bytes!=null)||prepared&&(!initialized||bound==null||key==null||!present))throw new IOException("sync_preparation_failed");
            return new State(account,bound,key,initialized,present,prepared);
        }catch(IOException fixed){throw fixed;}
        catch(Exception|LinkageError invalid){throw new IOException("sync_preparation_failed");}
    }
    private static SyncConnection connection(Context context,UUID expected,boolean requiredExpected)throws IOException {
        if(requiredExpected&&(expected==null||expected.equals(new UUID(0,0))))throw new IOException("sync_connection_changed");
        SyncConnection saved=SyncConnectionStore.load(context);
        if(saved==null)throw new IOException("sync_connection_missing");
        if(expected!=null&&!saved.account.equals(expected)){saved.close();throw new IOException("sync_connection_changed");}
        return saved;
    }
    private static void check(Context context,UUID account)throws IOException {
        try(SyncConnection ignored=connection(context,account,true)){SyncDatabase.check(context,account);}
    }
    static State inspect(Context context)throws IOException {
        worker();synchronized(SyncPreparation.class){
            try(SyncConnection saved=connection(context,null,false)){
                SyncDatabase.check(context,saved.account);byte[] bytes=SyncSecrets.load(context,saved.account);
                try{if(bytes!=null)materialKey(saved.account,bytes);return status(context,saved.account,bytes);}
                finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}
            }
        }
    }
    private static State install(Context context,UUID account,byte[] bytes)throws IOException {
        UUID key=materialKey(account,bytes);check(context,account);
        // Incoming material must match any existing database identity/fingerprint
        // before even a create-only vault write is attempted.
        State before=status(context,account,bytes);
        if(!before.initialized)throw new IOException("sync_workspace_uninitialized");
        SyncSecrets.saveIfAbsent(context,account,bytes);
        byte[] committed=SyncSecrets.load(context,account);
        try{
            if(committed==null||!MessageDigest.isEqual(bytes,committed))throw new IOException("sync_preparation_failed");
            check(context,account);
            JSONObject response=reply(NativeCore.prepareSync(root(context),account.toString(),committed));
            // Re-read actual persisted state; a returned success cannot replace
            // the identity checks or a failed database/vault readback.
            if(response.length()!=5)throw new IOException("sync_preparation_failed");
            State after=status(context,account,committed);
            if(!after.prepared||!key.equals(after.keyId))throw new IOException("sync_preparation_failed");return after;
        }catch(IOException fixed){throw fixed;}
        catch(Exception|LinkageError invalid){throw new IOException("sync_preparation_failed");}
        finally{if(committed!=null)Arrays.fill(committed,(byte)0);}
    }
    static State create(Context context,UUID expected)throws IOException {
        worker();synchronized(SyncPreparation.class){
            try(SyncConnection saved=connection(context,expected,true)){
                SyncDatabase.check(context,saved.account);byte[] bytes=SyncSecrets.load(context,saved.account);
                try{
                    State before=status(context,saved.account,bytes);
                    if(!before.initialized)throw new IOException("sync_workspace_uninitialized");
                    if(bytes==null){
                        if(before.account!=null||before.keyId!=null)throw new IOException("sync_material_missing");
                        if(!"owner".equals(saved.role))throw new IOException("sync_preparation_owner");
                        bytes=NativeCore.newSyncMaterial(saved.account.toString());material(bytes);
                    }
                    return install(context,saved.account,bytes);
                }catch(IOException fixed){throw fixed;}
                catch(RuntimeException|LinkageError invalid){throw new IOException("sync_preparation_failed");}
                finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}
            }
        }
    }
    static State restore(Context context,UUID expected,byte[] code,byte[] encrypted)throws IOException {
        worker();synchronized(SyncPreparation.class){
            if(code==null||code.length!=43||encrypted==null||encrypted.length==0||encrypted.length>MAX_RECOVERY)throw new IOException("sync_recovery_invalid");
            try(SyncConnection saved=connection(context,expected,true)){
                SyncDatabase.check(context,saved.account);byte[] bytes=null;
                try{
                    bytes=NativeCore.restoreSyncRecovery(saved.account.toString(),code,encrypted);
                    if(bytes==null)throw new IOException("sync_recovery_invalid");return install(context,saved.account,bytes);
                }catch(IOException fixed){throw fixed;}
                catch(RuntimeException|LinkageError invalid){throw new IOException("sync_preparation_failed");}
                finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}
            }
        }
    }
    static byte[] recovery(Context context,UUID expected,byte[] codeOut)throws IOException {
        worker();synchronized(SyncPreparation.class){
            if(codeOut==null||codeOut.length!=43)throw new IOException("sync_recovery_invalid");
            Arrays.fill(codeOut,(byte)0);boolean success=false;
            try(SyncConnection saved=connection(context,expected,true)){
                SyncDatabase.check(context,saved.account);byte[] bytes=SyncSecrets.load(context,saved.account);
                try{
                    if(bytes==null)throw new IOException("sync_material_missing");materialKey(saved.account,bytes);
                    if(!status(context,saved.account,bytes).prepared)throw new IOException("sync_preparation_failed");
                    byte[] encrypted=NativeCore.createSyncRecovery(saved.account.toString(),bytes,codeOut);
                    if(encrypted==null||encrypted.length==0||encrypted.length>MAX_RECOVERY)throw new IOException("sync_recovery_invalid");
                    for(byte c:codeOut)if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='_'||c=='-'))throw new IOException("sync_recovery_invalid");
                    success=true;return encrypted;
                }finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}
            }catch(IOException fixed){throw fixed;}
            catch(RuntimeException|LinkageError invalid){throw new IOException("sync_preparation_failed");}
            finally{if(!success)Arrays.fill(codeOut,(byte)0);}
        }
    }
    private SyncPreparation(){}
}
