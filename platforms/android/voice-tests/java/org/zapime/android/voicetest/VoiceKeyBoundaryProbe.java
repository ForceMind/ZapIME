// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;
import android.app.Instrumentation;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Actual optional-library JNI boundaries, without model weights or fabricated speech. */
final class VoiceKeyBoundaryProbe {
    static Object call(Class<?> type,String name,Class<?>[] signature,Object... args)throws Exception{return SyncAccountProbe.call(type,null,name,signature,args);}
    static JSONObject reply(Object bytes)throws Exception{SyncAccountProbe.check(bytes!=null,"VoiceKey null JNI reply");return new JSONObject(new String((byte[])bytes,StandardCharsets.UTF_8));}
    static void error(Object bytes)throws Exception{SyncAccountProbe.check(reply(bytes).has("error"),"VoiceKey invalid input accepted");}
    static void run(Instrumentation i,File output)throws Exception{
        Class<?> type=Class.forName("org.zapime.android.NativeVoiceKey",true,i.getTargetContext().getClassLoader());
        List<Long> handles=new ArrayList<>();JSONArray cases=new JSONArray();String stage="capacity";boolean timeoutChecked=false;
        try{
            for(int n=0;n<4;n++){long id=(Long)call(type,"allocate",new Class<?>[]{});SyncAccountProbe.check(id>0&&!handles.contains(id),"VoiceKey handle allocation");handles.add(id);}
            SyncAccountProbe.check((Long)call(type,"allocate",new Class<?>[]{})==0,"VoiceKey handle capacity ignored");SyncAccountProbe.passed(cases,"bounded_allocation");
            final long owner=handles.get(0);stage="foreign owner";AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread foreign=new Thread(()->{try{SyncAccountProbe.check(!(Boolean)call(type,"close",new Class<?>[]{long.class},owner),"foreign VoiceKey close");error(call(type,"accept",new Class<?>[]{long.class,short[].class,int.class},owner,new short[]{1},1));}catch(Throwable e){failure.set(e);}},"voicekey-foreign-owner");foreign.start();foreign.join(5000);SyncAccountProbe.check(!foreign.isAlive()&&failure.get()==null,"VoiceKey foreign ownership boundary");SyncAccountProbe.passed(cases,"foreign_owner");
            stage="invalid parameters";for(int count:new int[]{-1,0,2,16001})error(call(type,"accept",new Class<?>[]{long.class,short[].class,int.class},owner,new short[]{1},count));
            for(int budget:new int[]{0,-1,30001,Integer.MAX_VALUE})SyncAccountProbe.check("invalid_timeout".equals(reply(call(type,"finish",new Class<?>[]{long.class,int.class},owner,budget)).optString("error")),"VoiceKey finish budget contract ignored");timeoutChecked=true;
            error(call(type,"accept",new Class<?>[]{long.class,short[].class,int.class},owner,null,1));error(call(type,"finish",new Class<?>[]{long.class},owner));
            error(call(type,"start",new Class<?>[]{long.class,byte[].class,byte[].class},owner,new byte[0],new byte[0]));SyncAccountProbe.passed(cases,"invalid_parameters");
            stage="cancel and stale";long cancelled=handles.get(1);call(type,"cancel",new Class<?>[]{long.class},cancelled);call(type,"cancel",new Class<?>[]{long.class},cancelled);error(call(type,"finish",new Class<?>[]{long.class},cancelled));
            for(long id:handles)SyncAccountProbe.check((Boolean)call(type,"close",new Class<?>[]{long.class},id),"VoiceKey owned close failed");handles.clear();
            SyncAccountProbe.check(!(Boolean)call(type,"close",new Class<?>[]{long.class},owner),"stale close accepted");error(call(type,"accept",new Class<?>[]{long.class,short[].class,int.class},owner,new short[]{1},1));call(type,"cancel",new Class<?>[]{long.class},owner);SyncAccountProbe.passed(cases,"cancel_close_stale");
            stage="reclaim";long again=(Long)call(type,"allocate",new Class<?>[]{});SyncAccountProbe.check(again>owner,"VoiceKey slot not reclaimed");handles.add(again);SyncAccountProbe.check((Boolean)call(type,"close",new Class<?>[]{long.class},again),"reclaimed close");handles.clear();
            SyncAccountProbe.check((Boolean)call(type,"releaseCache",new Class<?>[]{}),"idle VoiceKey cache release");SyncAccountProbe.passed(cases,"reclaimed_idle_release");
        }catch(Throwable e){cases.put(new JSONObject().put("id",stage).put("passed",false).put("failure_type",e.getClass().getSimpleName()));throw new Exception("VoiceKey JNI boundary: "+stage,e);}
        finally{
            for(long id:handles)call(type,"close",new Class<?>[]{long.class},id);
            Files.write(new File(output,"voicekey-boundary-report.json").toPath(),new JSONObject().put("engine_id","voicekey").put("api_level",Build.VERSION.SDK_INT).put("synthetic",true).put("model_decode_executed",false).put("finish_timeout_contract_checked",timeoutChecked).put("cases",cases).put("scope","Actual VoiceKey JNI loading/ownership/invalid arguments/cancel/stale/idle cleanup only. No model, PCM decoding, AudioRecord, IME or natural speech acceptance.").toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private VoiceKeyBoundaryProbe(){}
}
