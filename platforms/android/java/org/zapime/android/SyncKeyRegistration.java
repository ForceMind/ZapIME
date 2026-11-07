// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Explicit public key-identity inspection/first registration only.
 * Does not modify material, database state or personal records, and never runs
 * automatically. A descriptor is metadata, not proof of root-key possession,
 * device pairing, or permission to start data synchronization.
 */
final class SyncKeyRegistration {
    /** Public choice captured by the UI before confirmation. No bearer/root. */
    static final class Selection {
        final UUID account,device;
        final URI server;
        final Descriptor local;
        Selection(UUID account,UUID device,URI server,Descriptor local){this.account=account;this.device=device;this.server=server;this.local=local;}
    }
    static final class Descriptor {
        final UUID keyId;
        final String materialSha256;
        private Descriptor(UUID keyId,String materialSha256){this.keyId=keyId;this.materialSha256=materialSha256;}
        boolean same(Descriptor other){return other!=null&&keyId.equals(other.keyId)&&materialSha256.equals(other.materialSha256);}
        byte[] wire(){return ("{\"version\":1,\"key_id\":\""+keyId+"\",\"material_sha256\":\""+materialSha256+"\"}").getBytes(StandardCharsets.US_ASCII);}
    }
    static final class Result {
        final UUID account,keyId,registeredBy;
        final String state,materialSha256,registeredAt;
        final Descriptor descriptor,local;
        final boolean matchesLocal;
        private Result(UUID account,String state,Descriptor descriptor,UUID registeredBy,String registeredAt,Descriptor local){
            this.account=account;this.state=state;this.descriptor=descriptor;this.registeredBy=registeredBy;this.registeredAt=registeredAt;this.local=local;
            this.keyId=descriptor==null?null:descriptor.keyId;this.materialSha256=descriptor==null?null:descriptor.materialSha256;
            matchesLocal="registered".equals(state)&&local.same(descriptor);
        }
    }
    private static SyncHttp.Failure invalid(){return new SyncHttp.Failure("key_response");}
    private static Set<String> fields(String... values){return new HashSet<>(Arrays.asList(values));}
    private static UUID id(String text)throws SyncHttp.Failure {
        try{UUID value=UUID.fromString(text);if(value.equals(new UUID(0,0))||!value.toString().equals(text))throw new IllegalArgumentException();return value;}
        catch(RuntimeException invalid){throw invalid();}
    }
    private static String text(JsonReader reader)throws IOException {
        if(reader.peek()!=JsonToken.STRING)throw invalid();return reader.nextString();
    }
    private static UUID nullableId(JsonReader reader)throws IOException {
        if(reader.peek()==JsonToken.NULL){reader.nextNull();return null;}return id(text(reader));
    }
    private static String timestamp(JsonReader reader)throws IOException {
        if(reader.peek()==JsonToken.NULL){reader.nextNull();return null;}
        String value=text(reader);
        try{
            if(value.length()>40||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})"))throw new IllegalArgumentException();
            OffsetDateTime.parse(value);return value;
        }catch(RuntimeException malformed){throw invalid();}
    }
    private static void one(JsonReader reader)throws IOException {
        if(reader.peek()!=JsonToken.NUMBER||!reader.nextString().equals("1"))throw invalid();
    }
    private static Descriptor descriptor(JsonReader reader)throws IOException {
        if(reader.peek()!=JsonToken.BEGIN_OBJECT)throw invalid();reader.beginObject();Set<String> seen=new HashSet<>();UUID key=null;String sha=null;
        while(reader.hasNext()){
            String name=reader.nextName();if(!seen.add(name))throw invalid();
            switch(name){
                case "version":one(reader);break;
                case "key_id":key=id(text(reader));break;
                case "material_sha256":sha=text(reader);if(!sha.matches("[0-9a-f]{64}"))throw invalid();break;
                default:throw invalid();
            }
        }
        reader.endObject();if(!seen.equals(fields("version","key_id","material_sha256"))||key==null||sha==null)throw invalid();return new Descriptor(key,sha);
    }
    private static JsonReader reader(byte[] wire,int maximum)throws SyncHttp.Failure {
        if(wire==null||wire.length==0||wire.length>maximum)throw invalid();
        JsonReader reader=new JsonReader(new InputStreamReader(new ByteArrayInputStream(wire),StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)));
        reader.setLenient(false);return reader;
    }
    /** Offline parser seam; no Context, native calls, secret bytes or network. */
    static Descriptor parseDescriptor(byte[] wire)throws IOException {
        try(JsonReader reader=reader(wire,1024)){
            Descriptor result=descriptor(reader);if(reader.peek()!=JsonToken.END_DOCUMENT)throw invalid();return result;
        }catch(SyncHttp.Failure fixed){throw fixed;}
        catch(IOException|RuntimeException malformed){throw invalid();}
    }
    /** Every response field is required, including explicit nulls for an empty or
     * legacy account. Duplicate/unknown fields and coercions are rejected.
     */
    static Result parse(byte[] wire,UUID expected,Descriptor local)throws IOException {
        if(expected==null||expected.equals(new UUID(0,0))||local==null)throw invalid();
        try(JsonReader reader=reader(wire,2048)){
            if(reader.peek()!=JsonToken.BEGIN_OBJECT)throw invalid();reader.beginObject();Set<String> seen=new HashSet<>();UUID account=null,by=null;String state=null,at=null;Descriptor descriptor=null;
            while(reader.hasNext()){
                String name=reader.nextName();if(!seen.add(name))throw invalid();
                switch(name){
                    case "protocol":one(reader);break;
                    case "account_id":account=id(text(reader));break;
                    case "state":state=text(reader);if(!state.equals("empty")&&!state.equals("legacy")&&!state.equals("registered"))throw invalid();break;
                    case "descriptor":if(reader.peek()==JsonToken.NULL)reader.nextNull();else descriptor=descriptor(reader);break;
                    case "registered_by":by=nullableId(reader);break;
                    case "registered_at":at=timestamp(reader);break;
                    default:throw invalid();
                }
            }
            reader.endObject();
            if(reader.peek()!=JsonToken.END_DOCUMENT||!seen.equals(fields("protocol","account_id","state","descriptor","registered_by","registered_at"))||!expected.equals(account))throw invalid();
            boolean registered="registered".equals(state);
            if(registered?(descriptor==null||by==null||at==null):(descriptor!=null||by!=null||at!=null))throw invalid();
            return new Result(account,state,descriptor,by,at,local);
        }catch(SyncHttp.Failure fixed){throw fixed;}
        catch(IOException|RuntimeException malformed){throw invalid();}
    }
    private static void worker()throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("sync_key_requires_worker");
    }
    private static SyncConnection connection(Context context,UUID expected)throws IOException {
        if(context==null||expected==null||expected.equals(new UUID(0,0)))throw new IOException("sync_connection_changed");
        SyncConnection current=SyncConnectionStore.load(context);
        if(current==null)throw new IOException("sync_connection_missing");
        if(!current.account.equals(expected)){current.close();throw new IOException("sync_connection_changed");}return current;
    }
    private static void sameConnection(Context context,SyncConnection expected)throws IOException {
        try(SyncConnection current=connection(context,expected.account)){
            if(!current.matchesSnapshot(expected))throw new IOException("sync_connection_changed");
        }
    }
    /** Verify the exact loaded bytes against the database fingerprint. The
     * earlier inspect snapshot alone cannot authenticate a later vault read.
     */
    private static void loadedPrepared(Context context,UUID account,Descriptor descriptor,byte[] material)throws IOException {
        String root=new java.io.File(context.getFilesDir(),"zapime").getAbsolutePath();
        String raw=NativeCore.syncWorkspaceStatus(root,account.toString(),material);byte[] bytes=null;
        try{
            if(raw==null||raw.length()>512)throw invalid();bytes=raw.getBytes(StandardCharsets.UTF_8);
            try(JsonReader reader=reader(bytes,512)){
                if(reader.peek()!=JsonToken.BEGIN_OBJECT)throw invalid();reader.beginObject();Set<String> seen=new HashSet<>();
                while(reader.hasNext()){
                    String name=reader.nextName();if(!seen.add(name))throw invalid();
                    switch(name){
                        case "initialized":case "material_present":case "prepared":if(reader.peek()!=JsonToken.BOOLEAN||!reader.nextBoolean())throw invalid();break;
                        case "account_id":if(!account.equals(id(text(reader))))throw invalid();break;
                        case "key_id":if(!descriptor.keyId.equals(id(text(reader))))throw invalid();break;
                        default:throw invalid();
                    }
                }
                reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT||!seen.equals(fields("initialized","account_id","key_id","material_present","prepared")))throw invalid();
            }
        }catch(IOException|RuntimeException malformed){throw new IOException("sync_key_local_changed");}
        finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}
    }
    private static Descriptor current(Context context,SyncConnection snapshot,SyncHttp.Cancellation cancellation)throws IOException {
        cancellation.check();sameConnection(context,snapshot);
        SyncPreparation.State state=SyncPreparation.inspect(context);
        if(!snapshot.account.equals(state.connectedAccount)||!snapshot.account.equals(state.account)||!state.prepared||state.keyId==null)throw new IOException("sync_key_requires_prepared");
        byte[] material=SyncSecrets.load(context,snapshot.account),descriptor=null;
        try{
            if(material==null)throw new IOException("sync_material_missing");
            String nativeReply=NativeCore.syncKeyDescriptor(snapshot.account.toString(),material);
            if(nativeReply==null||nativeReply.length()>1024)throw new IOException("sync_key_local_unavailable");
            descriptor=nativeReply.getBytes(StandardCharsets.UTF_8);Descriptor result=parseDescriptor(descriptor);
            if(!result.keyId.equals(state.keyId))throw new IOException("sync_key_local_changed");
            loadedPrepared(context,snapshot.account,result,material);
            cancellation.check();sameConnection(context,snapshot);return result;
        }catch(LinkageError unavailable){throw new IOException("sync_key_local_unavailable");}
        finally{if(material!=null)Arrays.fill(material,(byte)0);if(descriptor!=null)Arrays.fill(descriptor,(byte)0);}
    }
    private static void sameLocal(Context context,SyncConnection snapshot,Descriptor local,SyncHttp.Cancellation cancellation)throws IOException {
        if(!local.same(current(context,snapshot,cancellation)))throw new IOException("sync_key_local_changed");
    }
    /** Read-only safety seam for the explicit transfer coordinator. No HTTP. */
    static Descriptor preparedLocal(Context context,SyncConnection snapshot,SyncHttp.Cancellation cancellation)throws IOException {
        worker();if(snapshot==null||cancellation==null)throw new IOException("sync_key_invalid_request");
        try{return current(context,snapshot,cancellation);}
        catch(IOException fixed){throw fixed;}
        catch(RuntimeException|LinkageError unavailable){throw new IOException("sync_key_local_unavailable");}
    }
    private static Result exchange(Context context,SyncConnection snapshot,Descriptor local,byte[] registration,SyncHttp.Cancellation cancellation)throws IOException {
        sameLocal(context,snapshot,local,cancellation);byte[] response=null;
        try{
            // No database/vault/connection monitor or SQLite transaction is held
            // during the network operation. Recheck even an unsuccessful HTTP
            // attempt; a late response cannot approve a changed local identity.
            try{response=snapshot.keyDescriptor(registration,cancellation);}
            finally{sameLocal(context,snapshot,local,cancellation);}
            return parse(response,snapshot.account,local);
        }finally{if(response!=null)Arrays.fill(response,(byte)0);}
    }
    private static void selected(SyncConnection snapshot,Descriptor local,Selection selection)throws IOException {
        if(selection==null)return;
        if(!snapshot.account.equals(selection.account)||!snapshot.device.equals(selection.device)||!snapshot.server.equals(selection.server))throw new IOException("sync_connection_changed");
        if(!local.same(selection.local))throw new IOException("sync_key_local_changed");
    }
    static Result inspect(Context context,UUID expected,SyncHttp.Cancellation cancellation)throws IOException {
        return inspect(context,expected,null,cancellation);
    }
    static Result inspect(Context context,Selection expected,SyncHttp.Cancellation cancellation)throws IOException {
        worker();if(expected==null)throw new IOException("sync_connection_changed");return inspect(context,expected.account,expected,cancellation);
    }
    private static Result inspect(Context context,UUID expected,Selection selection,SyncHttp.Cancellation cancellation)throws IOException {
        worker();if(cancellation==null)throw new IOException("sync_key_invalid_request");cancellation.check();
        try(SyncConnection snapshot=connection(context,expected)){
            Descriptor local=current(context,snapshot,cancellation);selected(snapshot,local,selection);return exchange(context,snapshot,local,null,cancellation);
        }catch(IOException fixed){throw fixed;}
        catch(RuntimeException|LinkageError unavailable){throw new IOException("sync_key_local_unavailable");}
    }
    static Result register(Context context,UUID expected,SyncHttp.Cancellation cancellation)throws IOException {
        return register(context,expected,null,cancellation);
    }
    static Result register(Context context,Selection expected,SyncHttp.Cancellation cancellation)throws IOException {
        worker();if(expected==null)throw new IOException("sync_connection_changed");return register(context,expected.account,expected,cancellation);
    }
    private static Result register(Context context,UUID expected,Selection selection,SyncHttp.Cancellation cancellation)throws IOException {
        worker();if(cancellation==null)throw new IOException("sync_key_invalid_request");cancellation.check();
        try(SyncConnection snapshot=connection(context,expected)){
            Descriptor local=current(context,snapshot,cancellation);selected(snapshot,local,selection);Result before=exchange(context,snapshot,local,null,cancellation);
            if(before.matchesLocal)return before;
            if(!"empty".equals(before.state))throw new SyncHttp.Failure("key_conflict");
            // Stored role is only a conservative local hint. The server rechecks
            // actual token, role, revocation and account state in its transaction.
            if(!"owner".equals(snapshot.role))throw new SyncHttp.Failure("forbidden");
            byte[] request=local.wire();
            try{
                Result result=exchange(context,snapshot,local,request,cancellation);
                if(!result.matchesLocal)throw invalid();return result;
            }finally{Arrays.fill(request,(byte)0);}
        }catch(IOException fixed){throw fixed;}
        catch(RuntimeException|LinkageError unavailable){throw new IOException("sync_key_local_unavailable");}
    }
    private SyncKeyRegistration(){}
}
