// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;

/** Explicit identity/public-key descriptor requests only. Platform TLS and hostname verification stay unchanged. */
final class SyncHttp {
    static final int MAX_PUSH_BYTES=100000,MAX_ACK_BYTES=100000,MAX_PULL_BYTES=10*1024*1024;
    static final class Failure extends IOException {
        final String code;
        Failure(String code){super("sync_"+code);this.code=code;}
    }
    static final class Cancellation {
        private final AtomicBoolean cancelled=new AtomicBoolean();
        void cancel(){cancelled.set(true);}
        void check()throws Failure{if(cancelled.get()||Thread.currentThread().isInterrupted())throw new Failure("cancelled");}
    }
    static URI origin(String value)throws Failure {
        try{
            if(value==null||value.length()>2048||!value.equals(value.trim()))throw new IllegalArgumentException();
            URI uri=new URI(value);String path=uri.getRawPath();
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null
                    ||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawAuthority().contains("%")
                    ||!(path==null||path.isEmpty()||path.equals("/"))||uri.getPort()==0||uri.getPort()>65535||uri.getPort()< -1)throw new IllegalArgumentException();
            return new URI("https",null,uri.getHost().toLowerCase(Locale.ROOT),uri.getPort()==443?-1:uri.getPort(),null,null,null);
        }catch(URISyntaxException|IllegalArgumentException invalid){throw new Failure("server");}
    }
    static void token(char[] value)throws Failure {
        if(value==null||value.length!=48)throw new Failure("credential");
        String text=new String(value);byte[] decoded=null;
        try{
            if(!text.startsWith("zap1_"))throw new IllegalArgumentException();
            String raw=text.substring(5);decoded=Base64.getUrlDecoder().decode(raw);
            if(decoded.length!=32||!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(raw))throw new IllegalArgumentException();
        }catch(IllegalArgumentException invalid){throw new Failure("credential");}
        finally{if(decoded!=null)Arrays.fill(decoded,(byte)0);}
    }
    private static int timeout(long deadline,Cancellation cancellation)throws Failure {
        cancellation.check();long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
        if(remaining<=0)throw new Failure("timeout");return (int)Math.max(1,Math.min(5000,remaining));
    }
    static byte[] session(URI server,char[] credential,Cancellation cancellation)throws Failure {
        return request(server,credential,cancellation,"/v1/session","GET",null,4096,null,false);
    }
    /** Only the normalized public descriptor encoding is permitted in a PUT body. */
    static byte[] keyDescriptor(URI server,char[] credential,byte[] registration,Cancellation cancellation)throws Failure {
        if(registration!=null&&(registration.length==0||registration.length>1024))throw new Failure("key_request");
        byte[] body=registration==null?null:registration.clone();
        try{
            if(body!=null){
                if(body.length==0||body.length>1024)throw new Failure("key_request");
                String wire=new String(body,StandardCharsets.US_ASCII);
                java.util.regex.Matcher shape=java.util.regex.Pattern.compile("\\{\"version\":1,\"key_id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\",\"material_sha256\":\"[0-9a-f]{64}\"\\}").matcher(wire);
                if(!shape.matches()||shape.group(1).equals("00000000-0000-0000-0000-000000000000"))throw new Failure("key_request");
            }
            return request(server,credential,cancellation,"/v1/key-descriptor",body==null?"GET":"PUT",body,2048,"key_conflict",false);
        }finally{if(body!=null)Arrays.fill(body,(byte)0);}
    }
    /** Caller must supply the exact durable staged ciphertext request. No JSON
     * parsing, decryption, normalization or application retry occurs here.
     */
    static byte[] push(URI server,char[] credential,byte[] frozen,Cancellation cancellation)throws Failure {
        if(frozen==null||frozen.length==0||frozen.length>MAX_PUSH_BYTES)throw new Failure("transfer_request");
        byte[] body=frozen.clone();
        try{return request(server,credential,cancellation,"/v1/changes","POST",body,MAX_ACK_BYTES,"revision_conflict",true);}
        finally{Arrays.fill(body,(byte)0);}
    }
    static byte[] pull(URI server,char[] credential,long after,int limit,Cancellation cancellation)throws Failure {
        if(after<0||limit<1||limit>100)throw new Failure("transfer_request");
        return request(server,credential,cancellation,"/v1/changes?after="+after+"&limit="+limit,"GET",null,MAX_PULL_BYTES,"revision_conflict",true);
    }
    private static byte[] request(URI server,char[] credential,Cancellation cancellation,String path,String method,byte[] body,int maximum,String conflict,boolean transfer)throws Failure {
        if(server==null||!server.equals(origin(server.toString()))||cancellation==null)throw new Failure("server");
        token(credential);cancellation.check();HttpsURLConnection connection=null;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        try{
            connection=(HttpsURLConnection)server.resolve(path).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setConnectTimeout(timeout(deadline,cancellation));connection.setReadTimeout(timeout(deadline,cancellation));
            connection.setRequestMethod(method);connection.setRequestProperty("Accept","application/json");
            connection.setRequestProperty("Authorization","Bearer "+new String(credential));
            if(body!=null){
                connection.setDoOutput(true);connection.setFixedLengthStreamingMode(body.length);connection.setRequestProperty("Content-Type","application/json");
                cancellation.check();try(OutputStream out=connection.getOutputStream()){cancellation.check();out.write(body);out.flush();}
            }
            // Refresh the read budget after connect/body output. DNS and output
            // writes are still platform I/O; cancellation is cooperative.
            connection.setReadTimeout(timeout(deadline,cancellation));
            int status=connection.getResponseCode();cancellation.check();
            if(status!=200){
                if(status>=300&&status<400)throw new Failure("redirect");
                if(conflict!=null&&status==409)throw new Failure(conflict);
                if(transfer&&status==413)throw new Failure("payload_too_large");
                if(transfer&&status==507)throw new Failure("storage_full");
                throw new Failure(status==401?"unauthorized":status==403?"forbidden":status==404?"unsupported":status==429?"limited":"http");
            }
            String type=connection.getContentType();
            if(type==null||!type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw new Failure("response");
            long length=connection.getContentLengthLong();if(length>maximum)throw new Failure("response");
            ByteArrayOutputStream out=new ByteArrayOutputStream((int)Math.max(32,Math.min(length,maximum)));byte[] buffer=new byte[8192];
            try(InputStream in=connection.getInputStream()){
                while(true){connection.setReadTimeout(timeout(deadline,cancellation));int count=in.read(buffer);if(count<0)break;
                    if(out.size()+count>maximum)throw new Failure("response");out.write(buffer,0,count);
                }
            }finally{Arrays.fill(buffer,(byte)0);}
            cancellation.check();if(out.size()==0)throw new Failure("response");return out.toByteArray();
        }catch(Failure known){throw known;}
        catch(SSLException tls){cancellation.check();throw new Failure("tls");}
        catch(IOException|RuntimeException unavailable){cancellation.check();throw new Failure("network");}
        finally{if(connection!=null)connection.disconnect();}
    }
    private SyncHttp(){}
}
