// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual loopback TLS on the host JVM; fixture trust changes are test-only. */
public final class SyncHttpSmoke {
    static int passed;
    static void require(boolean value,String code){if(!value)throw new AssertionError(code);}
    interface Action{void run()throws Exception;}
    static void fails(String code,Action action)throws Exception{
        try{action.run();throw new AssertionError("expected rejection: "+code);}catch(SyncHttp.Failure error){require(code.equals(error.code),"unexpected failure code");require(error.getCause()==null&&!error.getMessage().contains("zap1_"),"error retained credential");}++passed;
    }
    public static void main(String[] args)throws Exception{
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(Paths.get(args[0]))){keys.load(in,"changeit".toCharArray());}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"changeit".toCharArray());
        SSLContext serverTls=SSLContext.getInstance("TLS");serverTls.init(km.getKeyManagers(),null,null);
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("fixture",keys.getCertificate("fixture"));
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        SSLContext clientTls=SSLContext.getInstance("TLS");clientTls.init(null,tm.getTrustManagers(),null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        ExecutorService pool=Executors.newCachedThreadPool();server.setExecutor(pool);
        AtomicReference<String> mode=new AtomicReference<>("ok");AtomicInteger requests=new AtomicInteger(),leaks=new AtomicInteger();AtomicReference<Throwable> handlerFailure=new AtomicReference<>();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final char[] token=("zap1_"+new String(new char[43]).replace('\0','A')).toCharArray();
        server.createContext("/",exchange->{
            try{
                if(!exchange.getRequestURI().getPath().equals("/v1/session")){leaks.incrementAndGet();exchange.sendResponseHeaders(500,-1);return;}
                requests.incrementAndGet();require(exchange.getRequestMethod().equals("GET"),"unexpected method");
                require(("Bearer "+new String(token)).equals(exchange.getRequestHeaders().getFirst("Authorization")),"missing authorization");
                String value=mode.get();byte[] body="{\"protocol\":1}".getBytes(StandardCharsets.UTF_8);int status=200;
                exchange.getResponseHeaders().set("Content-Type",value.equals("mime")?"text/html":"application/json; charset=utf-8");
                if(value.equals("redirect")){exchange.getResponseHeaders().set("Location","https://localhost:"+server.getAddress().getPort()+"/leak");exchange.sendResponseHeaders(302,-1);return;}
                if(value.startsWith("status-"))status=Integer.parseInt(value.substring(7));
                if(value.equals("empty")){exchange.sendResponseHeaders(200,-1);return;}
                if(value.equals("large")||value.equals("chunked"))body=new byte[4097];
                exchange.sendResponseHeaders(status,value.equals("chunked")||value.equals("trickle")?0:body.length);
                if(value.equals("cancel")){entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
                if(value.equals("trickle")){
                    for(int i=0;i<100;i++){exchange.getResponseBody().write(' ');exchange.getResponseBody().flush();try{Thread.sleep(200);}catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}}
                }else exchange.getResponseBody().write(body);
            }catch(IOException clientClosed){/* cancellation, timeout and bounded-body tests close the stream */}
            catch(Throwable error){handlerFailure.set(error);}
            finally{exchange.close();}
        });server.start();
        SSLSocketFactory previous=HttpsURLConnection.getDefaultSSLSocketFactory();HostnameVerifier verifier=HttpsURLConnection.getDefaultHostnameVerifier();
        URI origin=SyncHttp.origin("https://localhost:"+server.getAddress().getPort());
        try{
            require(SyncHttp.origin("HTTPS://EXAMPLE.COM:443/").toString().equals("https://example.com"),"origin normalization");++passed;
            require(SyncHttp.origin("https://[::1]:443/").toString().equals("https://[::1]"),"IPv6 origin normalization");++passed;
            fails("server",()->SyncHttp.origin("https://[fe80::1%25lo0]"));
            for(String invalid:new String[]{"http://example.com","https://u:p@example.com","https://example.com/a","https://example.com?x=y","https://example.com#x"," https://example.com","https://example.com:0","https://example.com:65536","https://example.com/%2e%2e","https://example.com:bad"})fails("server",()->SyncHttp.origin(invalid));
            fails("credential",()->SyncHttp.token(("zap1_"+new String(new char[42]).replace('\0','A')+"B").toCharArray()));
            fails("credential",()->SyncHttp.token("zap1_\r\n".toCharArray()));
            fails("tls",()->SyncHttp.session(origin,token,new SyncHttp.Cancellation()));
            HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.getSocketFactory());
            byte[] result=SyncHttp.session(origin,token,new SyncHttp.Cancellation());require(new String(result,StandardCharsets.UTF_8).equals("{\"protocol\":1}"),"TLS response changed");++passed;
            fails("tls",()->SyncHttp.session(SyncHttp.origin("https://127.0.0.1:"+server.getAddress().getPort()),token,new SyncHttp.Cancellation()));
            for(String value:new String[]{"redirect","mime","large","chunked","empty","status-401","status-403","status-404","status-429","status-503"}){
                mode.set(value);String code=value.equals("redirect")?"redirect":value.equals("status-401")?"unauthorized":value.equals("status-403")?"forbidden":value.equals("status-404")?"unsupported":value.equals("status-429")?"limited":value.equals("status-503")?"http":"response";
                int before=requests.get();fails(code,()->SyncHttp.session(origin,token,new SyncHttp.Cancellation()));require(requests.get()==before+1&&leaks.get()==0,"redirect or application retry");
            }
            SyncHttp.Cancellation cancelled=new SyncHttp.Cancellation();cancelled.cancel();int before=requests.get();fails("cancelled",()->SyncHttp.session(origin,token,cancelled));require(requests.get()==before,"cancelled request went online");
            mode.set("cancel");SyncHttp.Cancellation during=new SyncHttp.Cancellation();Future<?> task=pool.submit(()->{try{fails("cancelled",()->SyncHttp.session(origin,token,during));}catch(Exception e){throw new RuntimeException(e);}});
            require(entered.await(5,TimeUnit.SECONDS),"cancellation fixture unavailable");during.cancel();release.countDown();task.get(6,TimeUnit.SECONDS);
            mode.set("trickle");long began=System.nanoTime();
            try{SyncHttp.session(origin,token,new SyncHttp.Cancellation());throw new AssertionError("unbounded trickle");}catch(SyncHttp.Failure expected){require(expected.code.equals("timeout")||expected.code.equals("network"),"wrong deadline failure");}
            require(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-began)<17,"absolute read deadline failed");++passed;
            require(handlerFailure.get()==null,"HTTPS fixture assertion failed");require(HttpsURLConnection.getDefaultHostnameVerifier()==verifier,"hostname verifier changed");
            Path report=Paths.get(args[1]);Files.createDirectories(report.getParent());
            Files.write(report,("{\"passed\":true,\"checks\":"+passed+",\"redirect_target_requests\":"+leaks.get()+",\"scope\":\"Actual host-JVM loopback TLS and production SyncHttp, fixture CA trusted only in this test JVM; not Android TLS, user server deployment or data synchronization\"}\n").getBytes(StandardCharsets.UTF_8));
            System.out.println("PASS SyncHttp loopback TLS: trusted/untrusted CA, hostname, redirect, bounded body/deadline, cancellation and no application retry; checks="+passed);
        }finally{HttpsURLConnection.setDefaultSSLSocketFactory(previous);release.countDown();Arrays.fill(token,'\0');server.stop(0);pool.shutdownNow();}
    }
}
