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

/** Actual host-JVM loopback TLS for the public descriptor endpoint, not Android
 * TLS, server authorization/database semantics or a real account registration.
 */
public final class SyncKeyHttpSmoke {
    private static int passed;
    private interface Action {void run()throws Exception;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void fails(String code,Action action)throws Exception {
        try{action.run();throw new AssertionError("expected public key request rejection");}
        catch(SyncHttp.Failure failure){require(code.equals(failure.code),"unexpected descriptor failure code");require(failure.getCause()==null&&!failure.getMessage().contains("zap1_"),"descriptor failure exposed credential");}++passed;
    }
    public static void main(String[] args)throws Exception {
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(Paths.get(args[0]))){keys.load(input,"changeit".toCharArray());}
        KeyManagerFactory managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,"changeit".toCharArray());
        SSLContext serverTls=SSLContext.getInstance("TLS");serverTls.init(managers.getKeyManagers(),null,null);
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("fixture",keys.getCertificate("fixture"));
        TrustManagerFactory trusted=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trusted.init(trust);
        SSLContext clientTls=SSLContext.getInstance("TLS");clientTls.init(null,trusted.getTrustManagers(),null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        ExecutorService pool=Executors.newCachedThreadPool();server.setExecutor(pool);
        AtomicReference<String> mode=new AtomicReference<>("get");AtomicReference<Throwable> handlerFailure=new AtomicReference<>();AtomicInteger requests=new AtomicInteger(),leaks=new AtomicInteger(),puts=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        char[] token=("zap1_"+new String(new char[43]).replace('\0','A')).toCharArray();
        byte[] registration=("{\"version\":1,\"key_id\":\"11111111-1111-4111-8111-111111111111\",\"material_sha256\":\""+new String(new char[64]).replace('\0','b')+"\"}").getBytes(StandardCharsets.US_ASCII);
        byte[] response="{\"protocol\":1,\"account_id\":\"22222222-2222-4222-8222-222222222222\",\"state\":\"empty\",\"descriptor\":null,\"registered_by\":null,\"registered_at\":null}".getBytes(StandardCharsets.UTF_8);
        byte[] registeredResponse=("{\"protocol\":1,\"account_id\":\"22222222-2222-4222-8222-222222222222\",\"state\":\"registered\",\"descriptor\":"+new String(registration,StandardCharsets.US_ASCII)+",\"registered_by\":\"33333333-3333-4333-8333-333333333333\",\"registered_at\":\"2026-11-06T12:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
        server.createContext("/",exchange->{
            try{
                if(!exchange.getRequestURI().getPath().equals("/v1/key-descriptor")||exchange.getRequestURI().getRawQuery()!=null){leaks.incrementAndGet();exchange.sendResponseHeaders(500,-1);return;}
                requests.incrementAndGet();String value=mode.get();boolean put=value.startsWith("put");
                require(exchange.getRequestMethod().equals(put?"PUT":"GET"),"descriptor HTTP method changed");
                require(("Bearer "+new String(token)).equals(exchange.getRequestHeaders().getFirst("Authorization")),"descriptor request omitted authorization");
                require("application/json".equals(exchange.getRequestHeaders().getFirst("Accept")),"descriptor accept header changed");
                byte[] body;
                try(InputStream input=exchange.getRequestBody();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    byte[] block=new byte[256];int count;while((count=input.read(block))!=-1){require(bytes.size()+count<=1024,"outgoing descriptor exceeded 1024");bytes.write(block,0,count);}body=bytes.toByteArray();
                }
                if(put){
                    puts.incrementAndGet();require(Arrays.equals(registration,body),"PUT body is not the exact public descriptor");
                    require("application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type")),"PUT content type changed");
                    require(Integer.toString(registration.length).equals(exchange.getRequestHeaders().getFirst("Content-Length")),"PUT not fixed length");
                }else require(body.length==0,"GET carried descriptor material");
                if(value.contains("redirect")){exchange.getResponseHeaders().set("Location","https://localhost:"+server.getAddress().getPort()+"/leak");exchange.sendResponseHeaders(307,-1);return;}
                int status=value.startsWith("status-")?Integer.parseInt(value.substring(7)):value.equals("put-status-503")?503:200;
                if(value.equals("empty")){exchange.sendResponseHeaders(200,-1);return;}
                exchange.getResponseHeaders().set("Content-Type",value.equals("mime")?"text/html":"application/json; charset=utf-8");
                byte[] result=value.equals("large")||value.equals("chunked")?new byte[2049]:put?registeredResponse:response;
                exchange.sendResponseHeaders(status,value.equals("chunked")?0:result.length);
                if(value.equals("cancel")){entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
                exchange.getResponseBody().write(result);
            }catch(IOException clientClosed){/* bounded response and cancellation intentionally close the stream */}
            catch(Throwable failure){handlerFailure.compareAndSet(null,failure);}
            finally{exchange.close();}
        });server.start();
        SSLSocketFactory previous=HttpsURLConnection.getDefaultSSLSocketFactory();HostnameVerifier verifier=HttpsURLConnection.getDefaultHostnameVerifier();URI origin=SyncHttp.origin("https://localhost:"+server.getAddress().getPort());
        try{
            fails("tls",()->SyncHttp.keyDescriptor(origin,token,null,new SyncHttp.Cancellation()));
            HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.getSocketFactory());
            require(Arrays.equals(response,SyncHttp.keyDescriptor(origin,token,null,new SyncHttp.Cancellation())),"public descriptor GET response changed");++passed;
            mode.set("put");require(Arrays.equals(registeredResponse,SyncHttp.keyDescriptor(origin,token,registration,new SyncHttp.Cancellation()))&&puts.get()==1,"public descriptor PUT response changed");++passed;
            fails("tls",()->SyncHttp.keyDescriptor(SyncHttp.origin("https://127.0.0.1:"+server.getAddress().getPort()),token,null,new SyncHttp.Cancellation()));
            for(String value:new String[]{"redirect","put-redirect","mime","large","chunked","empty","status-401","status-403","status-404","status-409","status-429","status-503","put-status-503"}){
                mode.set(value);String code=value.contains("redirect")?"redirect":value.equals("status-401")?"unauthorized":value.equals("status-403")?"forbidden":value.equals("status-404")?"unsupported":value.equals("status-409")?"key_conflict":value.equals("status-429")?"limited":value.endsWith("503")?"http":"response";
                int before=requests.get();fails(code,()->SyncHttp.keyDescriptor(origin,token,value.startsWith("put")?registration:null,new SyncHttp.Cancellation()));require(requests.get()==before+1&&leaks.get()==0,"descriptor redirect or application retry occurred");
            }
            int before=requests.get();
            for(byte[] invalid:new byte[][]{new byte[0],new byte[1025],"{\"root\":\"forbidden\"}".getBytes(StandardCharsets.US_ASCII),new String(registration,StandardCharsets.US_ASCII).replace("11111111-1111-4111-8111-111111111111","00000000-0000-0000-0000-000000000000").getBytes(StandardCharsets.US_ASCII),new String(registration,StandardCharsets.US_ASCII).replace("version\":1","version\":1,\"version\":1").getBytes(StandardCharsets.US_ASCII)}){
                fails("key_request",()->SyncHttp.keyDescriptor(origin,token,invalid,new SyncHttp.Cancellation()));require(requests.get()==before,"invalid descriptor went online");
            }
            SyncHttp.Cancellation stopped=new SyncHttp.Cancellation();stopped.cancel();fails("cancelled",()->SyncHttp.keyDescriptor(origin,token,null,stopped));fails("cancelled",()->SyncHttp.keyDescriptor(origin,token,registration,stopped));require(requests.get()==before,"pre-cancelled descriptor went online");
            fails("server",()->SyncHttp.keyDescriptor(URI.create(origin.toString()+"/path"),token,null,new SyncHttp.Cancellation()));require(requests.get()==before,"non-origin descriptor leaked authorization");
            mode.set("cancel");SyncHttp.Cancellation during=new SyncHttp.Cancellation();Future<?> task=pool.submit(()->{try{fails("cancelled",()->SyncHttp.keyDescriptor(origin,token,null,during));}catch(Exception failure){throw new RuntimeException(failure);}});
            require(entered.await(5,TimeUnit.SECONDS),"descriptor cancellation fixture not entered");during.cancel();release.countDown();task.get(6,TimeUnit.SECONDS);
            require(leaks.get()==0&&handlerFailure.get()==null,"descriptor test server failed");require(HttpsURLConnection.getDefaultHostnameVerifier()==verifier,"descriptor changed hostname verification");
            Path report=Paths.get(args[1]);Files.createDirectories(report.getParent());Files.write(report,("{\"passed\":true,\"checks\":"+passed+",\"redirect_target_requests\":"+leaks.get()+",\"public_descriptor_put_requests\":"+puts.get()+",\"scope\":\"Actual host-JVM loopback TLS and production SyncHttp public descriptor GET/PUT only; fixture CA trusted only in this JVM, original session deadline tested separately; not Android HTTPS, parser/JNI, real server authorization or data synchronization\"}\n").getBytes(StandardCharsets.UTF_8));
            System.out.println("PASS SyncHttp public descriptor loopback TLS: checks="+passed);
        }finally{HttpsURLConnection.setDefaultSSLSocketFactory(previous);release.countDown();Arrays.fill(token,'\0');Arrays.fill(registration,(byte)0);server.stop(0);pool.shutdownNow();}
    }
}
