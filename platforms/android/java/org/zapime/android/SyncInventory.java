// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Explicit metadata-only local preview. No HTTP, staging or upload permission. */
final class SyncInventory {
    static final int PAGE_SIZE=40,MAX_ITEMS=100,MAX_WIRE=65536;
    static final class Item {
        final UUID id;
        final long revision;
        final int kind;
        final boolean deleted,pending,currentPending,history,currentHistory;
        final Long serverRevision;
        final String state;
        Item(UUID id,long revision,int kind,boolean deleted,String state,boolean pending,boolean currentPending,boolean history,boolean currentHistory,Long serverRevision){
            this.id=id;this.revision=revision;this.kind=kind;this.deleted=deleted;this.state=state;this.pending=pending;this.currentPending=currentPending;this.history=history;this.currentHistory=currentHistory;this.serverRevision=serverRevision;
        }
    }
    static final class Page {
        final List<Item> items;
        final UUID nextAfter;
        Page(List<Item> items,UUID nextAfter){this.items=Collections.unmodifiableList(items);this.nextAfter=nextAfter;}
    }
    static final class Result {
        final SyncKeyRegistration.Selection selection;
        final Page page;
        Result(SyncKeyRegistration.Selection selection,Page page){this.selection=selection;this.page=page;}
    }
    private static IOException invalid(){return new IOException("sync_inventory_response");}
    private static Set<String> fields(String... names){return new HashSet<>(Arrays.asList(names));}
    private static String text(JsonReader reader)throws IOException {
        if(reader.peek()!=JsonToken.STRING)throw invalid();return reader.nextString();
    }
    private static UUID id(String raw)throws IOException {
        try{UUID id=UUID.fromString(raw);if(id.equals(new UUID(0,0))||!id.toString().equals(raw))throw invalid();return id;}
        catch(IllegalArgumentException malformed){throw invalid();}
    }
    private static long positive(JsonReader reader)throws IOException {
        if(reader.peek()!=JsonToken.NUMBER)throw invalid();String raw=reader.nextString();
        if(!raw.matches("[1-9][0-9]{0,18}"))throw invalid();
        try{return Long.parseLong(raw);}catch(NumberFormatException malformed){throw invalid();}
    }
    private static boolean flag(JsonReader reader)throws IOException {
        if(reader.peek()!=JsonToken.BOOLEAN)throw invalid();return reader.nextBoolean();
    }
    private static Item item(JsonReader reader)throws IOException {
        if(reader.peek()!=JsonToken.BEGIN_OBJECT)throw invalid();reader.beginObject();Set<String> seen=new HashSet<>();
        UUID id=null;long revision=0;int kind=0;boolean deleted=false,pending=false,currentPending=false,history=false,currentHistory=false;Long known=null;String state=null;
        while(reader.hasNext()){
            String name=reader.nextName();if(!seen.add(name))throw invalid();
            switch(name){
                case "id":id=id(text(reader));break;
                case "local_revision":revision=positive(reader);break;
                case "kind":long value=positive(reader);if(value>5)throw invalid();kind=(int)value;break;
                case "deleted":deleted=flag(reader);break;
                case "state":state=text(reader);break;
                case "has_pending_local_operation":pending=flag(reader);break;
                case "current_snapshot_pending":currentPending=flag(reader);break;
                case "has_local_operation_history":history=flag(reader);break;
                case "current_snapshot_has_local_operation":currentHistory=flag(reader);break;
                case "known_server_revision":if(reader.peek()==JsonToken.NULL)reader.nextNull();else known=positive(reader);break;
                default:throw invalid();
            }
        }
        reader.endObject();
        if(!seen.equals(fields("id","local_revision","kind","deleted","state","has_pending_local_operation","current_snapshot_pending","has_local_operation_history","current_snapshot_has_local_operation","known_server_revision")))throw invalid();
        if((pending&&!history)||(currentPending&&(!pending||!currentHistory))||(currentHistory&&!history))throw invalid();
        String expected=pending?"pending_local_operation":known!=null?"server_version_known":history?"local_operation_history":"untracked";
        if(!expected.equals(state))throw invalid();
        return new Item(id,revision,kind,deleted,state,pending,currentPending,history,currentHistory,known);
    }
    /** Strict bounded wire contract. No unknown/duplicate fields or coercions. */
    static Page parse(String raw,UUID after,int limit)throws IOException {
        if(raw==null||raw.isEmpty()||raw.length()>MAX_WIRE||limit<1||limit>MAX_ITEMS||after!=null&&after.equals(new UUID(0,0)))throw invalid();
        try(JsonReader reader=new JsonReader(new StringReader(raw))){
            reader.setLenient(false);if(reader.peek()!=JsonToken.BEGIN_OBJECT)throw invalid();reader.beginObject();Set<String> seen=new HashSet<>();List<Item> rows=new ArrayList<>();UUID next=null;String error=null;
            while(reader.hasNext()){
                String name=reader.nextName();if(!seen.add(name))throw invalid();
                if(name.equals("items")){
                    if(reader.peek()!=JsonToken.BEGIN_ARRAY)throw invalid();reader.beginArray();String previous=after==null?null:after.toString();
                    while(reader.hasNext()){
                        if(rows.size()==limit)throw invalid();Item row=item(reader);
                        if(previous!=null&&previous.compareTo(row.id.toString())>=0)throw invalid();previous=row.id.toString();rows.add(row);
                    }reader.endArray();
                }else if(name.equals("next_after")){
                    if(reader.peek()==JsonToken.NULL)reader.nextNull();else next=id(text(reader));
                }else if(name.equals("error")){error=text(reader);if(!error.matches("[a-z0-9_]{1,96}"))throw invalid();}
                else throw invalid();
            }
            reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT)throw invalid();
            if(error!=null){if(!seen.equals(fields("error")))throw invalid();throw new IOException("sync_inventory_rejected");}
            if(!seen.equals(fields("items","next_after"))||next!=null&&(rows.size()!=limit||!next.equals(rows.get(rows.size()-1).id)))throw invalid();
            return new Page(rows,next);
        }catch(IOException known){throw known;}
        catch(RuntimeException malformed){throw invalid();}
    }
    static Result load(Context context,SyncKeyRegistration.Selection expected,UUID after,SyncHttp.Cancellation cancellation)throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("sync_inventory_requires_worker");
        if(context==null||cancellation==null||after!=null&&expected==null)throw new IOException("sync_inventory_invalid");
        cancellation.check();
        try(SyncConnection snapshot=SyncConnectionStore.load(context)){
            if(snapshot==null)throw new IOException("sync_connection_missing");
            SyncKeyRegistration.Descriptor local=SyncKeyRegistration.preparedLocal(context,snapshot,cancellation);
            if(expected!=null){
                if(!snapshot.account.equals(expected.account)||!snapshot.device.equals(expected.device)||!snapshot.server.equals(expected.server))throw new IOException("sync_connection_changed");
                if(!local.same(expected.local))throw new IOException("sync_key_local_changed");
            }
            byte[] material=SyncSecrets.load(context,snapshot.account);
            try{
                if(material==null)throw new IOException("sync_material_missing");
                String descriptor=NativeCore.syncKeyDescriptor(snapshot.account.toString(),material);
                if(descriptor==null||descriptor.length()>1024||!local.same(SyncKeyRegistration.parseDescriptor(descriptor.getBytes(StandardCharsets.UTF_8))))throw new IOException("sync_key_local_changed");
                cancellation.check();
                String root=new File(context.getFilesDir(),"zapime").getAbsolutePath();
                Page page=parse(NativeCore.syncInventoryPage(root,snapshot.account.toString(),material,after==null?null:after.toString(),PAGE_SIZE),after,PAGE_SIZE);
                cancellation.check();
                if(!local.same(SyncKeyRegistration.preparedLocal(context,snapshot,cancellation)))throw new IOException("sync_key_local_changed");
                cancellation.check();return new Result(new SyncKeyRegistration.Selection(snapshot.account,snapshot.device,snapshot.server,local),page);
            }finally{if(material!=null)Arrays.fill(material,(byte)0);}
        }catch(IOException known){throw known;}
        catch(RuntimeException|LinkageError unavailable){throw new IOException("sync_inventory_unavailable");}
    }
    private SyncInventory(){}
}
