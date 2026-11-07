// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real host TLS with synthetic ciphertext only. Transport bounds do not prove
 * staging, valid AEAD, authenticated ACK application or a real server sync.
 */
public final class SyncTransferHttpSmoke {
    private static int passed;
    private interface Action {void run()throws Exception;}
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static void fails(String code,Action action)throws Exception {
        try{action.run();throw new AssertionError("expected transfer rejection");}
        catch(SyncHttp.Failure failure){require(code.equals(failure.code),"wrong transfer error code");require(failure.getCause()==null&&!failure.getMessage().contains("zap1_")&&!failure.getMessage().contains("synthetic server detail"),"transfer failure leaked server body or credential");}++passed;
    }
    private static byte[] padded(byte[] prefix,int size){byte[] bytes=new byte[size];Arrays.fill(bytes,(byte)' ');System.arraycopy(prefix,0,bytes,0,prefix.length);return bytes;}
    public static void main(String[] args)throws Exception {
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(Paths.get(args[0]))){keys.load(input,"changeit".toCharArray());}
        KeyManagerFactory managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,"changeit".toCharArray());SSLContext serverTls=SSLContext.getInstance("TLS");serverTls.init(managers.getKeyManagers(),null,null);
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("fixture",keys.getCertificate("fixture"));TrustManagerFactory trusted=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trusted.init(trust);SSLContext clientTls=SSLContext.getInstance("TLS");clientTls.init(null,trusted.getTrustManagers(),null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(serverTls));ExecutorService pool=Executors.newCachedThreadPool();server.setExecutor(pool);
        AtomicReference<String> mode=new AtomicReference<>("push");AtomicInteger requests=new AtomicInteger(),leaks=new AtomicInteger(),closed=new AtomicInteger();AtomicReference<Throwable> handlerFailure=new AtomicReference<>();
        AtomicReference<CountDownLatch> entered=new AtomicReference<>(new CountDownLatch(1)),release=new AtomicReference<>(new CountDownLatch(1));
        char[] token=("zap1_"+new String(new char[43]).replace('\0','A')).toCharArray();
        // Deliberately noncanonical valid JSON whitespace/order: transport must
        // send the original frozen bytes instead of parsing/re-serializing them.
        byte[] frozen=(" \n{\"record_id\":\"11111111-1111-4111-8111-111111111111\", \"operation_id\":\"22222222-2222-4222-8222-222222222222\",\"key_id\":\"33333333-3333-4333-8333-333333333333\",\"kind\":4,\"base_revision\":0,\"deleted\":false,\"envelope\":{\"version\":1,\"nonce\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"ciphertext\":\"BBBBBBBBBBBBBBBBBBBBBBBB\"}}\n ").getBytes(StandardCharsets.US_ASCII);
        byte[] frozenBefore=frozen.clone();AtomicReference<byte[]> expectedFrozen=new AtomicReference<>(frozenBefore);
        byte[] ack="{\"revision\":1,\"synthetic_transport_only\":true}".getBytes(StandardCharsets.UTF_8),page="{\"changes\":[],\"next_cursor\":0,\"head\":0}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/",exchange->{
            try{
                if(!exchange.getRequestURI().getPath().equals("/v1/changes")){leaks.incrementAndGet();exchange.sendResponseHeaders(500,-1);return;}
                requests.incrementAndGet();String value=mode.get();boolean push=value.startsWith("push");
                require(exchange.getRequestMethod().equals(push?"POST":"GET"),"wrong transfer method");
                require(("Bearer "+new String(token)).equals(exchange.getRequestHeaders().getFirst("Authorization")),"missing transfer authorization");
                require("application/json".equals(exchange.getRequestHeaders().getFirst("Accept")),"wrong transfer accept header");
                require(push?exchange.getRequestURI().getRawQuery()==null:(value.equals("pull-max-cursor")?("after="+Long.MAX_VALUE+"&limit=100").equals(exchange.getRequestURI().getRawQuery()):"after=0&limit=1".equals(exchange.getRequestURI().getRawQuery())),"caller URL or wrong cursor query reached transport");
                byte[] body;
                try(InputStream input=exchange.getRequestBody();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){require(bytes.size()+count<=SyncHttp.MAX_PUSH_BYTES,"outgoing push exceeded limit");bytes.write(buffer,0,count);}body=bytes.toByteArray();
                }
                if(push){require(Arrays.equals(expectedFrozen.get(),body),"frozen ciphertext bytes changed");require("application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type")),"POST content type changed");require(Integer.toString(body.length).equals(exchange.getRequestHeaders().getFirst("Content-Length")),"POST not fixed length");}
                else require(body.length==0,"pull carried a request body");
                if(value.endsWith("redirect")){exchange.getResponseHeaders().set("Location","https://localhost:"+server.getAddress().getPort()+"/leak");exchange.sendResponseHeaders(307,-1);return;}
                int status=value.contains("status-")?Integer.parseInt(value.substring(value.indexOf("status-")+7)):value.endsWith("error-held")?409:200;
                exchange.getResponseHeaders().set("Content-Type",value.endsWith("mime")?"text/html":"application/json; charset=utf-8");
                if(value.endsWith("empty")){exchange.sendResponseHeaders(200,-1);return;}
                int maximum=push?SyncHttp.MAX_ACK_BYTES:SyncHttp.MAX_PULL_BYTES;byte[] prefix=push?ack:page;
                if(status!=200)prefix="synthetic server detail must not be read or retained".getBytes(StandardCharsets.UTF_8);
                if(value.endsWith("error-held")){
                    exchange.sendResponseHeaders(status,4096);exchange.getResponseBody().write(prefix);exchange.getResponseBody().flush();entered.get().countDown();release.get().await(10,TimeUnit.SECONDS);return;
                }
                if(value.endsWith("large-header")){exchange.sendResponseHeaders(200,maximum+1);return;}
                if(value.endsWith("chunked-large")){
                    exchange.sendResponseHeaders(200,0);byte[] block=new byte[8192];Arrays.fill(block,(byte)' ');
                    for(int sent=0;sent<maximum+1;){int count=Math.min(block.length,maximum+1-sent);exchange.getResponseBody().write(block,0,count);sent+=count;}return;
                }
                byte[] result=value.endsWith("exact-limit")?padded(prefix,maximum):prefix;
                exchange.sendResponseHeaders(status,result.length);
                if(value.endsWith("cancel")){entered.get().countDown();release.get().await(10,TimeUnit.SECONDS);}
                exchange.getResponseBody().write(result);
            }catch(IOException clientClosed){/* the client intentionally closes bounded/error responses */}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            catch(Throwable failure){handlerFailure.compareAndSet(null,failure);}
            finally{exchange.close();closed.incrementAndGet();}
        });server.start();
        SSLSocketFactory previous=HttpsURLConnection.getDefaultSSLSocketFactory();HostnameVerifier verifier=HttpsURLConnection.getDefaultHostnameVerifier();URI origin=SyncHttp.origin("https://localhost:"+server.getAddress().getPort());
        try{
            fails("tls",()->SyncHttp.push(origin,token,frozen,new SyncHttp.Cancellation()));HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.getSocketFactory());
            mode.set("push");require(Arrays.equals(ack,SyncHttp.push(origin,token,frozen,new SyncHttp.Cancellation()))&&Arrays.equals(frozenBefore,frozen),"frozen push or ACK changed");++passed;
            mode.set("pull");require(Arrays.equals(page,SyncHttp.pull(origin,token,0,1,new SyncHttp.Cancellation())),"pull bytes changed");++passed;
            mode.set("pull-max-cursor");require(Arrays.equals(page,SyncHttp.pull(origin,token,Long.MAX_VALUE,100,new SyncHttp.Cancellation())),"maximum cursor/limit rejected");++passed;
            fails("tls",()->SyncHttp.pull(SyncHttp.origin("https://127.0.0.1:"+server.getAddress().getPort()),token,0,1,new SyncHttp.Cancellation()));
            int before=requests.get();
            for(byte[] invalid:new byte[][]{null,new byte[0],new byte[SyncHttp.MAX_PUSH_BYTES+1]}){fails("transfer_request",()->SyncHttp.push(origin,token,invalid,new SyncHttp.Cancellation()));require(requests.get()==before,"oversized push went online");}
            for(long after:new long[]{-1,Long.MIN_VALUE}){fails("transfer_request",()->SyncHttp.pull(origin,token,after,1,new SyncHttp.Cancellation()));require(requests.get()==before,"negative pull cursor went online");}
            for(int limit:new int[]{0,-1,101,Integer.MAX_VALUE}){fails("transfer_request",()->SyncHttp.pull(origin,token,0,limit,new SyncHttp.Cancellation()));require(requests.get()==before,"invalid pull limit went online");}
            fails("server",()->SyncHttp.push(URI.create(origin+"/path"),token,frozen,new SyncHttp.Cancellation()));fails("server",()->SyncHttp.pull(URI.create(origin+"?after=7"),token,0,1,new SyncHttp.Cancellation()));require(requests.get()==before,"non-origin transfer leaked authorization");
            SyncHttp.Cancellation stopped=new SyncHttp.Cancellation();stopped.cancel();fails("cancelled",()->SyncHttp.push(origin,token,frozen,stopped));fails("cancelled",()->SyncHttp.pull(origin,token,0,1,stopped));require(requests.get()==before,"pre-cancelled transfer went online");
            for(String value:new String[]{"push-redirect","pull-redirect","push-mime","pull-mime","push-large-header","pull-large-header","push-chunked-large","pull-chunked-large","push-empty","pull-empty","push-status-401","pull-status-403","pull-status-404","push-status-409","pull-status-409","push-status-413","pull-status-429","push-status-507","push-status-503"}){
                mode.set(value);String code=value.endsWith("redirect")?"redirect":value.endsWith("401")?"unauthorized":value.endsWith("403")?"forbidden":value.endsWith("404")?"unsupported":value.endsWith("409")?"revision_conflict":value.endsWith("413")?"payload_too_large":value.endsWith("429")?"limited":value.endsWith("507")?"storage_full":value.endsWith("503")?"http":"response";
                int count=requests.get();fails(code,()->{if(value.startsWith("push"))SyncHttp.push(origin,token,frozen,new SyncHttp.Cancellation());else SyncHttp.pull(origin,token,0,1,new SyncHttp.Cancellation());});require(requests.get()==count+1&&leaks.get()==0,"transfer automatically retried or followed redirect");
            }
            mode.set("push");byte[] largest=padded(frozen,SyncHttp.MAX_PUSH_BYTES);expectedFrozen.set(largest);
            try{require(Arrays.equals(ack,SyncHttp.push(origin,token,largest,new SyncHttp.Cancellation())),"exact maximum frozen request rejected");++passed;}finally{expectedFrozen.set(frozenBefore);Arrays.fill(largest,(byte)0);}
            for(String value:new String[]{"push-exact-limit","pull-exact-limit"}){
                mode.set(value);byte[] result=value.startsWith("push")?SyncHttp.push(origin,token,frozen,new SyncHttp.Cancellation()):SyncHttp.pull(origin,token,0,1,new SyncHttp.Cancellation());
                try{require(result.length==(value.startsWith("push")?SyncHttp.MAX_ACK_BYTES:SyncHttp.MAX_PULL_BYTES),"exact response budget changed");++passed;}finally{Arrays.fill(result,(byte)0);}
            }
            for(String value:new String[]{"push-cancel","pull-cancel"}){
                mode.set(value);entered.set(new CountDownLatch(1));release.set(new CountDownLatch(1));SyncHttp.Cancellation during=new SyncHttp.Cancellation();
                Future<?> task=pool.submit(()->{try{fails("cancelled",()->{if(value.startsWith("push"))SyncHttp.push(origin,token,frozen,during);else SyncHttp.pull(origin,token,0,1,during);});}catch(Exception failure){throw new RuntimeException(failure);}});
                require(entered.get().await(5,TimeUnit.SECONDS),"transfer cancellation fixture missing");during.cancel();release.get().countDown();task.get(6,TimeUnit.SECONDS);
            }
            mode.set("push-error-held");entered.set(new CountDownLatch(1));release.set(new CountDownLatch(1));
            Future<?> rejected=pool.submit(()->{try{fails("revision_conflict",()->SyncHttp.push(origin,token,frozen,new SyncHttp.Cancellation()));}catch(Exception failure){throw new RuntimeException(failure);}});
            require(entered.get().await(5,TimeUnit.SECONDS),"held error-body fixture missing");
            try{rejected.get(4,TimeUnit.SECONDS);}finally{release.get().countDown();}
            require(HttpsURLConnection.getDefaultHostnameVerifier()==verifier&&leaks.get()==0&&handlerFailure.get()==null,"transfer test altered TLS or failed server assertions");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(closed.get()<requests.get()&&System.nanoTime()<deadline)Thread.sleep(10);require(closed.get()==requests.get(),"test transfer response streams were not closed");
            Path report=Paths.get(args[1]);Files.createDirectories(report.getParent());Files.write(report,("{\"passed\":true,\"checks\":"+passed+",\"redirect_target_requests\":"+leaks.get()+",\"closed_server_exchanges\":"+closed.get()+",\"push_max_bytes\":"+SyncHttp.MAX_PUSH_BYTES+",\"ack_max_bytes\":"+SyncHttp.MAX_ACK_BYTES+",\"pull_max_bytes\":"+SyncHttp.MAX_PULL_BYTES+",\"held_error_body_not_read_to_completion\":true,\"scope\":\"Actual host-JVM TLS and production frozen-byte push/bounded pull only, with fixture CA trusted in this JVM. Cancellation test releases server I/O; no immediate disconnect, DNS/write hard deadline, valid AEAD, ACK application, Android HTTPS or real server synchronization claim.\"}\n").getBytes(StandardCharsets.UTF_8));
            System.out.println("PASS SyncHttp frozen ciphertext transfer TLS: checks="+passed);
        }finally{HttpsURLConnection.setDefaultSSLSocketFactory(previous);release.get().countDown();Arrays.fill(token,'\0');Arrays.fill(frozen,(byte)0);Arrays.fill(frozenBefore,(byte)0);server.stop(0);pool.shutdownNow();}
    }
}
