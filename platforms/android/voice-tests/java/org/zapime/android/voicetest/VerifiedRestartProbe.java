// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;
import android.app.Instrumentation;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.EditorInfo;
import java.util.function.BooleanSupplier;
import android.widget.EditText;
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
/** Real Editable helpers on a synthetic unattached Service, not the active IME. */
final class VerifiedRestartProbe {
    interface Task<T>{T run()throws Exception;}
    static <T>T main(Instrumentation i,Task<T> task)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        i.runOnMainSync(()->{try{result.set(task.run());}catch(Throwable e){error.set(e);}});
        if(error.get()!=null)throw new Exception("restart_method_probe",error.get());return result.get();
    }
    static Object call(Object o,String n,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}
    static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static void check(boolean ok){if(!ok)throw new AssertionError("restart_method_boundary");}
    static void run(Instrumentation i,File output)throws Exception{
        if(Build.VERSION.SDK_INT<31)return;
        ClassLoader loader=i.getTargetContext().getClassLoader();Class<?> type=Class.forName("org.zapime.android.ZapInputMethodService",true,loader);
        Object service=main(i,()->type.getDeclaredConstructor().newInstance());JSONArray cases=new JSONArray();
        try{
            Object echoes=field(service,"selectionEcho");
            for(boolean commit:new boolean[]{true,false}){
                main(i,()->{
                    EditText edit=new EditText(new ContextThemeWrapper(i.getTargetContext(),android.R.style.Theme_Material_Light_NoActionBar));edit.setText("前后");edit.setSelection(1);
                    Runnable finish=()->{try{
                        type.getMethod("onFinishInput").invoke(service);
                        call(echoes,"reset",new Class<?>[]{int.class,int.class},8,8);call(echoes,"compose",new Class<?>[]{int.class},2);call(echoes,"expect",new Class<?>[0]);
                    }catch(Exception e){throw new IllegalStateException(e);}};
                    BaseInputConnection connection=new BaseInputConnection(edit,true){
                        @Override public android.text.Editable getEditable(){return edit.getText();}
                        @Override public boolean commitText(CharSequence text,int cursor){boolean ok=super.commitText(text,cursor);finish.run();return ok;}
                        @Override public boolean setComposingText(CharSequence text,int cursor){boolean ok=super.setComposingText(text,cursor);finish.run();return ok;}
                    };
                    if(commit){Class<?> ticket=Class.forName("org.zapime.android.EnglishLearningSession$Ticket",true,loader);call(service,"trackCommit",new Class<?>[]{InputConnection.class,String.class,ticket},connection,"字",null);}
                    else call(service,"trackCompose",new Class<?>[]{InputConnection.class,String.class},connection,"字");
                    check(edit.getText().toString().equals("前字后"));check((Integer)call(echoes,"ownedCompositionEnd",new Class<?>[]{int.class},2)==10);return null;
                });
                cases.put(new JSONObject().put("id",commit?"commit_finish_reentry":"compose_finish_reentry").put("passed",true));
            }
            main(i,()->{
                EditText edit=new EditText(new ContextThemeWrapper(i.getTargetContext(),android.R.style.Theme_Material_Light_NoActionBar));edit.setText("前后");edit.setSelection(1);
                int epoch=(Integer)field(service,"epoch"),starts=(Integer)field(service,"inputStarts");
                BaseInputConnection connection=new BaseInputConnection(edit,true){
                    @Override public android.text.Editable getEditable(){return edit.getText();}
                    @Override public boolean beginBatchEdit(){try{type.getMethod("onFinishInput").invoke(service);}catch(Exception e){throw new IllegalStateException(e);}return true;}
                };
                connection.beginBatchEdit();boolean current=(Boolean)call(service,"applicationCurrent",new Class<?>[]{int.class,int.class,InputConnection.class},epoch,starts,null);
                if(current)connection.setComposingText("禁止旧写",1);connection.endBatchEdit();check(!current&&edit.getText().toString().equals("前后"));return null;
            });
            cases.put(new JSONObject().put("id","begin_batch_finish_guard").put("passed",true));
            main(i,()->{
                EditText edit=new EditText(new ContextThemeWrapper(i.getTargetContext(),android.R.style.Theme_Material_Light_NoActionBar));edit.setText("前后");edit.setSelection(0,1);
                BaseInputConnection connection=new BaseInputConnection(edit,true){
                    @Override public android.text.Editable getEditable(){return edit.getText();}
                    @Override public CharSequence getSelectedText(int flags){CharSequence selected=super.getSelectedText(flags);try{type.getMethod("onFinishInput").invoke(service);}catch(Exception e){throw new IllegalStateException(e);}return selected;}
                };
                call(service,"delete",new Class<?>[]{InputConnection.class},connection);
                check(edit.getText().toString().equals("前后"));return null;
            });
            cases.put(new JSONObject().put("id","delete_getter_finish_guard").put("passed",true));
            for(String phase:new String[]{"begin","region","end"}){
                main(i,()->{
                    EditText edit=new EditText(new ContextThemeWrapper(i.getTargetContext(),android.R.style.Theme_Material_Light_NoActionBar));edit.setText("前sh后");edit.setSelection(3);
                    int active=(Integer)field(service,"epoch"),starts=(Integer)field(service,"inputStarts"),lifecycle=(Integer)field(service,"inputLifecycle");
                    int[] calls=new int[3];
                    Runnable finish=()->{try{type.getMethod("onFinishInput").invoke(service);}catch(Exception e){throw new IllegalStateException(e);}};
                    BaseInputConnection editable=new BaseInputConnection(edit,true){
                        @Override public android.text.Editable getEditable(){return edit.getText();}
                    };
                    // Keep BaseInputConnection's internal nested batch calls on
                    // its own receiver; observe only the helper's public calls.
                    InputConnection connection=new InputConnectionWrapper(editable,false){
                        @Override public boolean beginBatchEdit(){++calls[0];boolean ok=super.beginBatchEdit();if(phase.equals("begin"))finish.run();return ok;}
                        @Override public boolean setComposingRegion(int start,int end){++calls[1];boolean ok=super.setComposingRegion(start,end);if(phase.equals("region"))finish.run();return ok;}
                        @Override public boolean endBatchEdit(){++calls[2];boolean ok=super.endBatchEdit();if(phase.equals("end"))finish.run();return ok;}
                    };
                    BooleanSupplier current=()->{try{return (Boolean)call(service,"inputStartCurrent",new Class<?>[]{int.class,int.class,int.class,InputConnection.class,EditorInfo.class},active,starts,lifecycle,null,null);}catch(Exception e){throw new IllegalStateException(e);}};
                    check(current.getAsBoolean());
                    boolean retained=(Boolean)call(service,"restoreRestartRegion",new Class<?>[]{InputConnection.class,int.class,int.class,BooleanSupplier.class},connection,1,3,current);
                    check(!retained&&!current.getAsBoolean()&&calls[0]==1&&calls[2]==1&&calls[1]==(phase.equals("begin")?0:1));
                    check(edit.getText().toString().equals("前sh后"));return null;
                });
                cases.put(new JSONObject().put("id","ordinary_"+phase+"_finish_reentry").put("passed",true));
            }
            Files.write(new File(output,"verified-restart-method-report.json").toPath(),new JSONObject().put("synthetic",true).put("api_level",Build.VERSION.SDK_INT).put("cases",cases)
                .put("scope","Independent Android Editable with production commit/compose/application and ordinary region-restore helpers under synthetic unattached Service finish reentry. Ordinary helper cases do not execute the full framework-bound retain/onStartInput path. Not active system IME, framework configuration restart, remote Binder proof or phone acceptance.").toString(2).getBytes(StandardCharsets.UTF_8));
        }finally{((ExecutorService)field(service,"worker")).shutdown();((ExecutorService)field(service,"reader")).shutdown();}
    }
}
