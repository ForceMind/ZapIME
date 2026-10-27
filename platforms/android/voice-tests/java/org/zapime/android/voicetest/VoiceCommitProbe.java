// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.app.Instrumentation;
import android.os.Build;
import android.os.Looper;
import android.text.Editable;
import android.view.ContextThemeWrapper;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Real Android Editable + production transaction, not an activated IME/microphone test. */
final class VoiceCommitProbe {
    private interface Task<T> { T run() throws Exception; }
    private static <T> T main(Instrumentation i, Task<T> task) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper()) return task.run();
        AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        i.runOnMainSync(()->{try{value.set(task.run());}catch(Throwable e){error.set(e);}});
        if(error.get()!=null) throw new Exception("synthetic confirmation main task",error.get());
        return value.get();
    }
    private static Object invoke(Object owner,String name,Class<?>[] types,Object... args) throws Exception {
        Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(owner,args);}catch(InvocationTargetException e){
            if(e.getCause() instanceof RuntimeException)throw (RuntimeException)e.getCause();
            throw e;
        }
    }
    private static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}

    private static final class Editor {
        final Instrumentation instrumentation;
        final EditText edit;
        final InputConnection connection;
        final int originCursor;
        int commits;
        boolean reject,throwCommit,nullBefore,nullAfter,oversize,throwContext;
        Runnable duringCommit;
        Editor(Instrumentation i,boolean repeated){
            instrumentation=i;
            edit=new EditText(new ContextThemeWrapper(i.getTargetContext(),android.R.style.Theme_Material_Light_NoActionBar));
            char[] chars=new char[600];Arrays.fill(chars,'a');edit.setText(repeated?new String(chars):"前后");
            originCursor=repeated?300:1;edit.setSelection(originCursor);
            connection=new BaseInputConnection(edit,true){
                @Override public Editable getEditable(){return edit.getText();}
                @Override public boolean commitText(CharSequence text,int cursor){
                    check(Looper.myLooper()==Looper.getMainLooper(),"editor write left main thread");++commits;
                    if(duringCommit!=null)duringCommit.run();
                    if(throwCommit)throw new IllegalStateException("synthetic_editor_unavailable");
                    return !reject&&super.commitText(text,cursor);
                }
                @Override public CharSequence getTextBeforeCursor(int n,int flags){
                    check(Looper.myLooper()!=Looper.getMainLooper()&&n==128,"context query thread/bound changed");
                    if(throwContext)throw new IllegalStateException("synthetic_context_unavailable");
                    if(nullBefore)return null;if(oversize)return new String(new char[129]);
                    try{return main(i,()->super.getTextBeforeCursor(n,flags));}catch(Exception e){throw new IllegalStateException("synthetic_context_dispatch",e);}
                }
                @Override public CharSequence getTextAfterCursor(int n,int flags){
                    check(Looper.myLooper()!=Looper.getMainLooper()&&n==128,"context query thread/bound changed");
                    if(nullAfter)return null;
                    try{return main(i,()->super.getTextAfterCursor(n,flags));}catch(Exception e){throw new IllegalStateException("synthetic_context_dispatch",e);}
                }
            };
        }
    }

    private final Instrumentation instrumentation;
    private final Constructor<?> editorConstructor,transactionConstructor;
    private final Class<?> editorType;
    private final Object service;
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private final JSONArray cases=new JSONArray();
    private VoiceCommitProbe(Instrumentation i) throws Exception {
        instrumentation=i;ClassLoader loader=i.getTargetContext().getClassLoader();
        Class<?> transaction=Class.forName("org.zapime.android.VoiceCommitTransaction",true,loader);
        editorType=Class.forName("org.zapime.android.VoiceCommitTransaction$Editor",true,loader);
        editorConstructor=editorType.getDeclaredConstructor(InputConnection.class,int.class,int.class,int.class,int.class,int.class,boolean.class);editorConstructor.setAccessible(true);
        transactionConstructor=transaction.getDeclaredConstructor(editorType,String.class,String.class,String.class);transactionConstructor.setAccessible(true);
        service=main(i,()->Class.forName("org.zapime.android.ZapInputMethodService",true,loader).getDeclaredConstructor().newInstance());
    }
    private Object state(InputConnection connection,int ticket,int epoch,int version,int start,int end,boolean allowed) throws Exception {
        return editorConstructor.newInstance(connection,ticket,epoch,version,start,end,allowed);
    }
    private String[] context(Editor editor) throws Exception {
        return reader.submit(()->(String[])invoke(service,"voiceContext",new Class<?>[]{InputConnection.class},editor.connection)).get(10,TimeUnit.SECONDS);
    }
    private String finish(Object transaction,Object current,String[] context,Runnable end) throws Exception {
        return invoke(transaction,"finish",new Class<?>[]{editorType,String[].class,Runnable.class},current,context,end).toString();
    }
    private void runCase(String id,String decoded) throws Exception {
        Editor editor=main(instrumentation,()->new Editor(instrumentation,id.equals("cursor_changed_identical_context")));
        String[] original=context(editor);check(original!=null,"initial synthetic context missing");
        Object origin=state(editor.connection,11,22,33,editor.originCursor,editor.originCursor,true);
        Object transaction=main(instrumentation,()->transactionConstructor.newInstance(origin,original[0],original[1],decoded));
        int ticket=11,epoch=22,version=33,start=editor.originCursor,end=start;
        InputConnection connection=editor.connection;boolean allowed=true;
        String expectedOutcome="INVALID";int expectedCommits=0;boolean inserted=false;
        switch(id){
        case "commit_once":case "reentrant_finish":case "worker_thread_rejected":
            expectedOutcome="COMMITTED";expectedCommits=1;inserted=true;break;
        case "cancelled_completion":main(instrumentation,()->{invoke(transaction,"cancel",new Class<?>[0]);return null;});expectedOutcome="ALREADY_FINISHED";break;
        case "connection_changed":connection=main(instrumentation,()->new Editor(instrumentation,false)).connection;break;
        case "ticket_changed":++ticket;break;
        case "epoch_changed":++epoch;break;
        case "selection_version_changed":++version;break;
        case "cursor_changed_identical_context":
            ++start;++end;final int moved=start;main(instrumentation,()->{editor.edit.setSelection(moved);return null;});break;
        case "selected_range":--start;final int rangeStart=start,rangeEnd=end;main(instrumentation,()->{editor.edit.setSelection(rangeStart,rangeEnd);return null;});break;
        case "unknown_selection":start=-1;end=-1;break;
        case "policy_excluded":allowed=false;break;
        case "before_changed":main(instrumentation,()->{editor.edit.getText().replace(0,1,"新");return null;});break;
        case "after_changed":main(instrumentation,()->{editor.edit.getText().replace(1,2,"新");return null;});break;
        case "null_before":editor.nullBefore=true;break;
        case "null_after":editor.nullAfter=true;break;
        case "oversize_context":editor.oversize=true;break;
        case "context_exception":editor.throwContext=true;break;
        case "editor_rejected":editor.reject=true;expectedOutcome="REJECTED";expectedCommits=1;break;
        case "editor_exception":editor.throwCommit=true;expectedOutcome="REJECTED";expectedCommits=1;break;
        default:throw new AssertionError("unknown confirmation case");
        }
        String unchanged=main(instrumentation,()->editor.edit.getText().toString());
        String expectedText=inserted?unchanged.substring(0,editor.originCursor)+decoded+unchanged.substring(editor.originCursor):unchanged;
        String[] checked=context(editor);
        if(id.equals("cursor_changed_identical_context"))check(Arrays.equals(original,checked),"repeated-text fixture must retain identical bounded context");
        Object current=state(connection,ticket,epoch,version,start,end,allowed);
        int[] ended={0};Runnable endSession=()->{
            ++ended[0];
            // A UI restoration can synchronously change focus/selection. If
            // restoration ever moves before commit, the real Editable would
            // receive the JNI text at the wrong position and this case fails.
            if(id.equals("commit_once"))editor.edit.setSelection(0);
            try{invoke(transaction,"cancel",new Class<?>[0]);}catch(Exception e){throw new IllegalStateException("synthetic_end_session",e);}
        };
        if(id.equals("worker_thread_rejected")){
            boolean rejected=reader.submit(()->{try{finish(transaction,current,checked,endSession);return false;}catch(IllegalStateException expected){return "voice_confirmation_requires_main".equals(expected.getMessage());}}).get(10,TimeUnit.SECONDS);
            check(rejected,"worker-thread confirmation was not rejected");
        }
        if(id.equals("reentrant_finish"))editor.duringCommit=()->{try{invoke(transaction,"cancel",new Class<?>[0]);check("ALREADY_FINISHED".equals(finish(transaction,current,checked,endSession)),"reentrant completion submitted twice");}catch(Exception e){throw new IllegalStateException("synthetic_reentrant_confirmation",e);}};
        String outcome=main(instrumentation,()->finish(transaction,current,checked,endSession));
        check(expectedOutcome.equals(outcome),"confirmation outcome mismatch");
        check("ALREADY_FINISHED".equals(main(instrumentation,()->finish(transaction,current,checked,endSession))),"completion was reusable");
        final int attempts=expectedCommits;
        main(instrumentation,()->{
            check(editor.commits==attempts,"commit attempt count mismatch");
            check(expectedText.contentEquals(editor.edit.getText()),"actual Editable mismatch");
            check(ended[0]==(id.equals("cancelled_completion")?0:1),"session completion repeated");
            for(String field:new String[]{"origin","before","after","text"}){Field f=transaction.getClass().getDeclaredField(field);f.setAccessible(true);check(f.get(transaction)==null,"terminal confirmation retained input");}
            return null;
        });
        cases.put(new JSONObject().put("id",id).put("outcome",outcome).put("commit_attempts",editor.commits).put("actual_editable_verified",true).put("passed",true));
    }
    static void run(Instrumentation instrumentation,String decoded,File output) throws Exception {
        check(decoded!=null&&!decoded.trim().isEmpty(),"real JNI result required");
        VoiceCommitProbe probe=new VoiceCommitProbe(instrumentation);
        String active="initialization";
        try{
            for(String id:new String[]{"commit_once","reentrant_finish","cancelled_completion","connection_changed","ticket_changed","epoch_changed","selection_version_changed","cursor_changed_identical_context","selected_range","unknown_selection","policy_excluded","before_changed","after_changed","null_before","null_after","oversize_context","context_exception","editor_rejected","editor_exception","worker_thread_rejected"}){
                active=id;probe.runCase(id,decoded);
            }
        }catch(Throwable error){
            probe.cases.put(new JSONObject().put("id",active).put("passed",false).put("failure_type",error.getClass().getSimpleName()));
            throw new Exception("synthetic production confirmation case: "+active,error);
        }finally{
            probe.reader.shutdownNow();
            // Only an unbound helper for the real product voiceContext method.
            // No Service lifecycle, framework fields or active IME are changed.
            for(String name:new String[]{"worker","reader"}){Field field=probe.service.getClass().getDeclaredField(name);field.setAccessible(true);((ExecutorService)field.get(probe.service)).shutdownNow();}
            JSONObject report=new JSONObject().put("synthetic",true).put("api_level",Build.VERSION.SDK_INT)
                    .put("actual_jni_decoded_text_used",true).put("recognized_codepoints",decoded.codePointCount(0,decoded.length()))
                    .put("cases",probe.cases).put("scope","Production confirmation transaction and bounded-context method with real Android Editable; same-UID synthetic harness, not activated IME, physical Confirm, microphone or natural-speech acceptance");
            Files.write(new File(output,"voice-commit-report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private VoiceCommitProbe(){throw new AssertionError();}
}
