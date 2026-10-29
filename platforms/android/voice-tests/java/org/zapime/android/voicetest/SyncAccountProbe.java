// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;
import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Synthetic authorization JSON/local vault/UI only; never calls a real server. */
final class SyncAccountProbe {
    interface Task<T>{T run()throws Exception;}
    static <T>T main(Instrumentation i,Task<T> work)throws Exception{
        AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        i.runOnMainSync(()->{try{value.set(work.run());}catch(Throwable e){error.set(e);}});
        if(error.get()!=null)throw new Exception("synthetic account main task",error.get());return value.get();
    }
    static Object call(Class<?> type,Object owner,String name,Class<?>[] signature,Object... args)throws Exception{
        Method method=type.getDeclaredMethod(name,signature);method.setAccessible(true);
        try{return method.invoke(owner,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}
    }
    static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static void check(boolean ok,String code){if(!ok)throw new AssertionError(code);}
    static void close(Class<?> type,Object value)throws Exception{if(value!=null)call(type,value,"close",new Class<?>[0]);}
    static void reject(Task<?> task)throws Exception{boolean failed=false;try{task.run();}catch(IOException expected){failed=true;}check(failed,"invalid account data accepted");}
    static void passed(JSONArray cases,String id)throws Exception{cases.put(new JSONObject().put("id",id).put("passed",true));}
    static void run(Instrumentation i,File output)throws Exception{
        Context context=i.getTargetContext();SharedPreferences prefs=context.getSharedPreferences("sync_connection",Context.MODE_PRIVATE);
        check(!prefs.contains("active_account"),"account test requires clean ephemeral profile");
        ClassLoader loader=context.getClassLoader();Class<?> model=Class.forName("org.zapime.android.SyncConnection",true,loader),vault=Class.forName("org.zapime.android.SyncSecrets",true,loader),store=Class.forName("org.zapime.android.SyncConnectionStore",true,loader);
        UUID account=UUID.randomUUID(),device=UUID.randomUUID();byte[] root=new byte[32];new java.security.SecureRandom().nextBytes(root);
        byte[] tokenBytes=new byte[32];new java.security.SecureRandom().nextBytes(tokenBytes);String token="zap1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);Arrays.fill(tokenBytes,(byte)0);
        JSONObject imported=new JSONObject().put("account_id",account.toString()).put("device_id",device.toString()).put("token",token).put("expires_at","2099-01-01T00:00:00Z");
        JSONObject session=new JSONObject().put("protocol",1).put("account_id",account.toString()).put("device_id",device.toString()).put("role","owner").put("expires_at","2099-01-01T00:00:00Z");
        Object draft=null,verified=null;JSONArray cases=new JSONArray();String stage="schema";
        try{
            draft=call(model,null,"imported",new Class<?>[]{String.class,String.class},"https://synthetic.example",imported.toString());final Object candidate=draft;
            for(String invalid:new String[]{"http://synthetic.example","https://user:pass@synthetic.example","https://synthetic.example/path"})reject(()->call(model,null,"imported",new Class<?>[]{String.class,String.class},invalid,imported.toString()));
            reject(()->call(model,null,"imported",new Class<?>[]{String.class,String.class},"https://synthetic.example",imported.toString().replace("\"token\":","\"unexpected\":true,\"token\":")));
            reject(()->call(model,null,"imported",new Class<?>[]{String.class,String.class},"https://synthetic.example",imported.toString().replace("\"device_id\":", "\"account_id\":\""+account+"\",\"device_id\":")));
            passed(cases,"strict_schema");stage="identity";
            verified=call(model,draft,"verify",new Class<?>[]{byte[].class},session.toString().getBytes(StandardCharsets.UTF_8));
            for(String key:new String[]{"account_id","device_id","role","protocol","expires_at"}){
                JSONObject bad=new JSONObject(session.toString());bad.put(key,key.equals("protocol")?2:key.equals("role")?"admin":key.equals("expires_at")?"bad-time":UUID.randomUUID().toString());
                byte[] wire=bad.toString().getBytes(StandardCharsets.UTF_8);reject(()->call(model,candidate,"verify",new Class<?>[]{byte[].class},wire));
            }
            check(!call(model,verified,"description",new Class<?>[0]).toString().contains(token),"description exposed credential");passed(cases,"identity_binding");
            stage="vault namespaces";
            call(vault,null,"save",new Class<?>[]{Context.class,UUID.class,byte[].class},context,account,root);
            byte[] material=(byte[])call(model,verified,"encode",new Class<?>[0]);
            try{call(vault,null,"saveConnection",new Class<?>[]{Context.class,UUID.class,byte[].class},context,account,material);}finally{Arrays.fill(material,(byte)0);}
            File rootFile=new File(context.getNoBackupFilesDir(),"sync-material-"+account+".bin"),connectionFile=new File(context.getNoBackupFilesDir(),"sync-connection-"+account+".bin");
            byte[] rootCipher=Files.readAllBytes(rootFile.toPath()),connectionCipher=Files.readAllBytes(connectionFile.toPath());
            check(!new String(connectionCipher,StandardCharsets.ISO_8859_1).contains(token),"plaintext credential on disk");
            Files.write(connectionFile.toPath(),rootCipher);reject(()->call(vault,null,"loadConnection",new Class<?>[]{Context.class,UUID.class},context,account));Files.write(connectionFile.toPath(),connectionCipher);
            Files.write(rootFile.toPath(),connectionCipher);reject(()->call(vault,null,"load",new Class<?>[]{Context.class,UUID.class},context,account));Files.write(rootFile.toPath(),rootCipher);passed(cases,"keystore_namespace");
            stage="store";call(store,null,"save",new Class<?>[]{Context.class,model},context,verified);
            Object loaded=call(store,null,"load",new Class<?>[]{Context.class},context);
            try{check(field(loaded,"account").equals(account)&&field(loaded,"device").equals(device),"saved identity changed");}finally{close(model,loaded);}
            Object otherDraft=call(model,null,"imported",new Class<?>[]{String.class,String.class},"https://other-synthetic.example",imported.toString()),other=null;
            try{other=call(model,otherDraft,"verify",new Class<?>[]{byte[].class},session.toString().getBytes(StandardCharsets.UTF_8));final Object wrong=other;reject(()->call(store,null,"save",new Class<?>[]{Context.class,model},context,wrong));}
            finally{close(model,otherDraft);close(model,other);}
            call(store,null,"forget",new Class<?>[]{Context.class},context);check(call(store,null,"load",new Class<?>[]{Context.class},context)==null,"local removal left active connection");
            byte[] preserved=(byte[])call(vault,null,"load",new Class<?>[]{Context.class,UUID.class},context,account);try{check(Arrays.equals(root,preserved),"connection removal deleted E2EE material");}finally{Arrays.fill(preserved,(byte)0);}passed(cases,"store_roundtrip_switch");
            stage="main worker guard";main(i,()->{reject(()->call(store,null,"load",new Class<?>[]{Context.class},context));return null;});passed(cases,"worker_guard");
            stage="pre-cancel";Class<?> cancellation=Class.forName("org.zapime.android.SyncHttp$Cancellation",true,loader);Constructor<?> ctor=cancellation.getDeclaredConstructor();ctor.setAccessible(true);Object stopped=ctor.newInstance();call(cancellation,stopped,"cancel",new Class<?>[0]);
            reject(()->call(model,candidate,"inspect",new Class<?>[]{cancellation},stopped));passed(cases,"pre_cancel");
            stage="secure activity";
            Activity activity=i.startActivitySync(new Intent().setClassName(context,"org.zapime.android.SyncActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try{
                for(int n=0;n<100;n++){boolean ready=main(i,()->((TextView)field(activity,"status")).getText().toString().startsWith("尚未保存连接"));if(ready)break;if(n==99)throw new AssertionError("account activity local load timeout");Thread.sleep(50);}
                main(i,()->{
                    EditText input=(EditText)field(activity,"credential");
                    check((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"credential screen can be captured");
                    check((input.getInputType()&InputType.TYPE_MASK_VARIATION)==InputType.TYPE_TEXT_VARIATION_PASSWORD&&!input.isSaveEnabled(),"credential field state/privacy");
                    check((input.getImeOptions()&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0,"credential field allowed learning");
                    check(input.getImportantForAutofill()==View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,"credential autofill allowed");
                    input.setText(imported.toString());activity.finish();return null;
                });i.waitForIdleSync();
                for(int n=0;n<100;n++){if(main(i,()->((EditText)field(activity,"credential")).length()==0))break;if(n==99)throw new AssertionError("stopped activity retained credential entry");Thread.sleep(50);}
            }finally{main(i,()->{activity.finish();return null;});}
            passed(cases,"secure_activity_lifecycle");
        }catch(Throwable error){cases.put(new JSONObject().put("id",stage).put("passed",false).put("failure_type",error.getClass().getSimpleName()));throw new Exception("synthetic account case: "+stage,error);}
        finally{
            close(model,draft);close(model,verified);Arrays.fill(root,(byte)0);
            call(store,null,"forget",new Class<?>[]{Context.class},context);call(vault,null,"forgetConnection",new Class<?>[]{Context.class,UUID.class},context,account);call(vault,null,"forget",new Class<?>[]{Context.class,UUID.class},context,account);
            Files.write(new File(output,"sync-connection-report.json").toPath(),new JSONObject().put("synthetic",true).put("api_level",Build.VERSION.SDK_INT).put("cases",cases).put("scope","Real Android JSON validation, distinct Keystore namespaces, local connection store and secure Activity lifecycle; synthetic identities, no Android HTTPS success or user server deployment").toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private SyncAccountProbe(){}
}
