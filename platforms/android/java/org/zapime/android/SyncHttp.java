// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;

/** Explicit identity inspection only. Uses platform TLS/hostname verification unchanged. */
final class SyncHttp {
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
        if(server==null||!server.equals(origin(server.toString()))||cancellation==null)throw new Failure("server");
        token(credential);cancellation.check();HttpsURLConnection connection=null;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        try{
            connection=(HttpsURLConnection)server.resolve("/v1/session").toURL().openConnection();
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setConnectTimeout(timeout(deadline,cancellation));connection.setReadTimeout(timeout(deadline,cancellation));
            connection.setRequestMethod("GET");connection.setRequestProperty("Accept","application/json");
            connection.setRequestProperty("Authorization","Bearer "+new String(credential));
            int status=connection.getResponseCode();cancellation.check();
            if(status!=200){
                if(status>=300&&status<400)throw new Failure("redirect");
                throw new Failure(status==401?"unauthorized":status==403?"forbidden":status==404?"unsupported":status==429?"limited":"http");
            }
            String type=connection.getContentType();
            if(type==null||!type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw new Failure("response");
            long length=connection.getContentLengthLong();if(length>4096)throw new Failure("response");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[1024];
            try(InputStream in=connection.getInputStream()){
                while(true){connection.setReadTimeout(timeout(deadline,cancellation));int count=in.read(buffer);if(count<0)break;
                    if(out.size()+count>4096)throw new Failure("response");out.write(buffer,0,count);
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
