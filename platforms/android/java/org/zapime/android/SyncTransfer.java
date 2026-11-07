// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Explicit manual transport only. Holds no database transaction across HTTPS,
 * never borrows an input session, and never persists an enabled/upload marker. */
final class SyncTransfer {
    private static final long ROUND_BUDGET_MS=90_000;
    private static final Set<String> RETENTION=new HashSet<>(Arrays.asList("saved","not_eligible","failed"));
    static final class RejectedPage extends IOException {
        final String retention;
        RejectedPage(String retention){super("sync_page_rejected");this.retention=retention;}
    }
    private static void fields(JSONObject object,String... expected)throws IOException {
        Set<String> actual=new HashSet<>();java.util.Iterator<String> keys=object.keys();while(keys.hasNext())actual.add(keys.next());
        if(!actual.equals(new HashSet<>(Arrays.asList(expected))))throw new IOException("sync_native_response");
    }
    private static JSONObject reply(String raw,int limit)throws IOException {
        if(raw==null||raw.length()==0||raw.length()>limit)throw new IOException("sync_native_response");
        try{
            JSONObject object=new JSONObject(raw);
            if(object.has("error")){
                if("sync_client_page_rejected".equals(object.optString("error"))){
                    fields(object,"error","cause","retention","retained_id","retention_error");
                    Object retained=object.get("retention"),cause=object.get("cause"),id=object.get("retained_id"),error=object.get("retention_error");
                    if(!(retained instanceof String)||!RETENTION.contains(retained)||!(cause instanceof String)||!((String)cause).matches("[a-z0-9_]{1,96}"))throw new IOException("sync_native_response");
                    if("saved".equals(retained)){
                        if(!(id instanceof String)||!((String)id).matches("[0-9a-f]{64}")||error!=JSONObject.NULL)throw new IOException("sync_native_response");
                    }else if("not_eligible".equals(retained)){
                        if(id!=JSONObject.NULL||error!=JSONObject.NULL)throw new IOException("sync_native_response");
                    }else if(id!=JSONObject.NULL||!(error instanceof String)||!((String)error).matches("[a-z0-9_]{1,96}"))throw new IOException("sync_native_response");
                    throw new RejectedPage((String)retained);
                }
                fields(object,"error");
                if("sync_client_record_too_large".equals(object.opt("error")))throw new IOException("sync_client_record_too_large");
                throw new IOException("sync_native_rejected");
            }
            return object;
        }catch(IOException fixed){throw fixed;}
        catch(Exception malformed){throw new IOException("sync_native_response");}
    }
    private static long number(JSONObject object,String name)throws IOException {
        Object value=object.opt(name);
        if(!(value instanceof Integer)&&!(value instanceof Long))throw new IOException("sync_native_response");
        long number=((Number)value).longValue();if(number<0)throw new IOException("sync_native_response");return number;
    }
    private static int count(JSONObject object,String name)throws IOException {
        long value=number(object,name);if(value>SyncRound.PAGE_LIMIT)throw new IOException("sync_native_response");return (int)value;
    }
    static SyncRound.Result run(Context context,SyncKeyRegistration.Selection selected,SyncHttp.Cancellation cancellation)throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("sync_transfer_requires_worker");
        if(context==null||selected==null||selected.account==null||selected.account.equals(new UUID(0,0))||cancellation==null)throw new IOException("sync_transfer_invalid");
        final UUID expected=selected.account;
        cancellation.check();
        try(SyncConnection snapshot=SyncConnectionStore.load(context)){
            if(snapshot==null||!expected.equals(snapshot.account)||!snapshot.device.equals(selected.device)||!snapshot.server.equals(selected.server))throw new IOException("sync_connection_changed");
            final SyncKeyRegistration.Descriptor local=SyncKeyRegistration.preparedLocal(context,snapshot,cancellation);
            if(!local.same(selected.local))throw new IOException("sync_key_local_changed");
            final byte[] material=SyncSecrets.load(context,expected);
            try{
                if(material==null)throw new IOException("sync_material_missing");
                String descriptor=NativeCore.syncKeyDescriptor(expected.toString(),material);
                if(descriptor==null||descriptor.length()>1024||!local.same(SyncKeyRegistration.parseDescriptor(descriptor.getBytes(StandardCharsets.UTF_8))))throw new IOException("sync_key_local_changed");
                final String root=new File(context.getFilesDir(),"zapime").getAbsolutePath(),account=expected.toString();
                final long began=SystemClock.elapsedRealtime();
                return SyncRound.run(new SyncRound.Backend(){
                    public void check()throws IOException {
                        cancellation.check();if(SystemClock.elapsedRealtime()-began>=ROUND_BUDGET_MS)throw new IOException("sync_round_budget");
                        if(!local.same(SyncKeyRegistration.preparedLocal(context,snapshot,cancellation)))throw new IOException("sync_key_local_changed");
                    }
                    public void verifyIdentity()throws IOException {
                        check();SyncKeyRegistration.Result remote=SyncKeyRegistration.inspect(context,selected,cancellation);check();
                        if(!remote.matchesLocal||!local.same(remote.local))throw new SyncHttp.Failure("key_conflict");
                    }
                    public SyncRound.Page pull()throws IOException {
                        check();JSONObject cursor=reply(NativeCore.syncPullCursor(root,account,material),512);fields(cursor,"cursor");long after=number(cursor,"cursor");
                        check();byte[] response=null;
                        try{
                            try{response=snapshot.pull(after,SyncRound.PAGE_LIMIT,cancellation);}finally{check();}
                            // The JNI only returns head/cursor after the complete
                            // ciphertext page passed authentication and commit.
                            JSONObject applied=reply(NativeCore.applySyncPage(root,account,material,after,response),1024);
                            fields(applied,"cursor","head","applied","echoed","unchanged");
                            return new SyncRound.Page(number(applied,"cursor"),number(applied,"head"),count(applied,"applied"),count(applied,"echoed"),count(applied,"unchanged"));
                        }finally{if(response!=null)Arrays.fill(response,(byte)0);}
                    }
                    public byte[] stage()throws IOException {
                        check();JSONObject staged=reply(NativeCore.stageSyncUpload(root,account,material),110_000);
                        Object pending=staged.opt("pending");if(!(pending instanceof Boolean))throw new IOException("sync_native_response");
                        if(!(Boolean)pending){fields(staged,"pending");return null;}
                        fields(staged,"pending","wire");Object wire=staged.opt("wire");if(!(wire instanceof String))throw new IOException("sync_native_response");
                        byte[] frozen=((String)wire).getBytes(StandardCharsets.UTF_8);
                        if(frozen.length==0||frozen.length>100_000){Arrays.fill(frozen,(byte)0);throw new IOException("sync_native_response");}return frozen;
                    }
                    public void pushAndAcknowledge(byte[] frozen)throws IOException {
                        check();byte[] response=null;
                        try{
                            // stage() returned the actual persisted immutable
                            // bytes. No HTTP retry or JSON reserialization here.
                            try{response=snapshot.push(frozen,cancellation);}finally{check();}
                            JSONObject ack=reply(NativeCore.ackSyncUpload(root,account,snapshot.device.toString(),material,frozen,response),512);
                            fields(ack,"acknowledged");if(!Boolean.TRUE.equals(ack.opt("acknowledged")))throw new IOException("sync_native_response");
                        }finally{if(response!=null)Arrays.fill(response,(byte)0);}
                    }
                });
            }finally{if(material!=null)Arrays.fill(material,(byte)0);}
        }catch(IOException fixed){throw fixed;}
        catch(RuntimeException|LinkageError unavailable){throw new IOException("sync_transfer_unavailable");}
    }
    private SyncTransfer(){}
}
