// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.*;
import java.net.URI;
import java.nio.charset.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.json.JSONObject;

/** Device authorization only. Contains no E2EE key and is never a personal-data archive. */
final class SyncConnection implements AutoCloseable {
    final URI server;
    final UUID account,device;
    final String expires,role;
    private final char[] token;
    private boolean closed;
    private SyncConnection(URI server,UUID account,UUID device,char[] token,String expires,String role){
        this.server=server;this.account=account;this.device=device;this.token=token;this.expires=expires;this.role=role;
    }
    private static UUID id(String text)throws SyncHttp.Failure {
        try{UUID value=UUID.fromString(text);if(!value.toString().equals(text)||value.equals(new UUID(0,0)))throw new IllegalArgumentException();return value;}
        catch(RuntimeException invalid){throw new SyncHttp.Failure("credential");}
    }
    private static String expiry(String text)throws SyncHttp.Failure {
        try{if(text.length()>64)throw new IllegalArgumentException();OffsetDateTime.parse(text).toInstant();return text;}
        catch(RuntimeException invalid){throw new SyncHttp.Failure("credential");}
    }
    private static Map<String,String> object(byte[] wire,Set<String> fields,Set<String> numbers)throws SyncHttp.Failure {
        if(wire==null||wire.length==0||wire.length>4096)throw new SyncHttp.Failure("response");
        Map<String,String> values=new HashMap<>();
        CharsetDecoder utf8=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(JsonReader reader=new JsonReader(new InputStreamReader(new ByteArrayInputStream(wire),utf8))){
            reader.setLenient(false);reader.beginObject();
            while(reader.hasNext()){
                String key=reader.nextName();
                if(!fields.contains(key)||values.containsKey(key)||reader.peek()!=(numbers.contains(key)?JsonToken.NUMBER:JsonToken.STRING))throw new IOException();
                values.put(key,reader.nextString());
            }
            reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT||!values.keySet().equals(fields))throw new IOException();return values;
        }catch(IOException|RuntimeException invalid){throw new SyncHttp.Failure("response");}
    }
    private static Set<String> fields(String... values){return new HashSet<>(Arrays.asList(values));}
    private static SyncConnection create(URI server,Map<String,String> values,String role)throws SyncHttp.Failure {
        char[] secret=values.remove("token").toCharArray();
        try{SyncHttp.token(secret);return new SyncConnection(server,id(values.get("account_id")),id(values.get("device_id")),secret,expiry(values.get("expires_at")),role);}
        catch(SyncHttp.Failure invalid){Arrays.fill(secret,'\0');throw invalid;}
    }
    static SyncConnection imported(String origin,String json)throws SyncHttp.Failure {
        URI server=SyncHttp.origin(origin);
        if(json==null||json.length()>4096)throw new SyncHttp.Failure("credential");
        byte[] wire=json.getBytes(StandardCharsets.UTF_8);
        try{return create(server,object(wire,fields("account_id","device_id","token","expires_at"),Collections.emptySet()),"");}
        finally{Arrays.fill(wire,(byte)0);}
    }
    SyncConnection verify(byte[] response)throws SyncHttp.Failure {
        if(closed)throw new SyncHttp.Failure("credential");
        Map<String,String> values=object(response,fields("protocol","account_id","device_id","role","expires_at"),fields("protocol"));
        if(!"1".equals(values.get("protocol"))||!account.equals(id(values.get("account_id")))||!device.equals(id(values.get("device_id"))))throw new SyncHttp.Failure("identity");
        String role=values.get("role");if(!role.equals("owner")&&!role.equals("member"))throw new SyncHttp.Failure("identity");
        return new SyncConnection(server,account,device,token.clone(),expiry(values.get("expires_at")),role);
    }
    byte[] inspect(SyncHttp.Cancellation cancellation)throws SyncHttp.Failure {
        if(closed)throw new SyncHttp.Failure("credential");return SyncHttp.session(server,token,cancellation);
    }
    byte[] encode()throws SyncHttp.Failure {
        if(closed||role.isEmpty())throw new SyncHttp.Failure("credential");
        try{return new JSONObject().put("version",1).put("server",server.toString()).put("account_id",account.toString()).put("device_id",device.toString())
                .put("token",new String(token)).put("expires_at",expires).put("role",role).toString().getBytes(StandardCharsets.UTF_8);}
        catch(Exception invalid){throw new SyncHttp.Failure("credential");}
    }
    static SyncConnection stored(byte[] wire)throws SyncHttp.Failure {
        Map<String,String> values=object(wire,fields("version","server","account_id","device_id","token","expires_at","role"),fields("version"));
        if(!"1".equals(values.get("version"))||!("owner".equals(values.get("role"))||"member".equals(values.get("role"))))throw new SyncHttp.Failure("credential");
        return create(SyncHttp.origin(values.get("server")),values,values.get("role"));
    }
    String description(){return "服务器："+server+"\n账号："+account+"\n设备："+device+"\n角色："+(role.equals("owner")?"管理员设备":"成员设备")+"\n凭据到期："+expires;}
    @Override public void close(){closed=true;Arrays.fill(token,'\0');}
    @Override public String toString(){return "SyncConnection{protected}";}
}
