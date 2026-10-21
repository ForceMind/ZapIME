// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.test;
import android.app.*;
import android.os.*;
import android.content.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.inputmethod.BaseInputConnection;
import android.text.Editable;
import java.io.*;

public final class SmokeInstrumentation extends Instrumentation {
    private UiAutomation automation;
    private String stage="launch";
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private AccessibilityNodeInfo walk(AccessibilityNodeInfo node,String text) { return walk(node,text,false); }
    private AccessibilityNodeInfo walk(AccessibilityNodeInfo node,String text,boolean button) {
        if(node==null)return null;
        if((!button || "android.widget.Button".contentEquals(node.getClassName()==null?"":node.getClassName())) && (text.contentEquals(node.getText()==null?"":node.getText()) || text.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())))return node;
        for(int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo found=walk(node.getChild(i),text,button); if(found!=null)return found; }
        return null;
    }
    private AccessibilityNodeInfo find(String text) { return find(text,false); }
    private AccessibilityNodeInfo find(String text,boolean button) {
        for(AccessibilityWindowInfo window:automation.getWindows()) { AccessibilityNodeInfo found=walk(window.getRoot(),text,button); if(found!=null)return found; }
        return walk(automation.getRootInActiveWindow(),text,button);
    }
    private AccessibilityNodeInfo waitFor(String text) throws Exception { return waitFor(text,false); }
    private AccessibilityNodeInfo waitFor(String text,boolean button) throws Exception {
        for(int i=0;i<150;i++) {
            if(find("System UI isn't responding")!=null) { AccessibilityNodeInfo wait=find("Wait");if(wait!=null)wait.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
            AccessibilityNodeInfo node=find(text,button); if(node!=null)return node; Thread.sleep(100);
        }
        throw new Exception("Missing UI node: "+text);
    }
    private void click(String text) throws Exception {
        for(int attempt=0;attempt<30;attempt++) {
            // 键盘/候选必须是真正的 Button，不能误点同文的编辑器内容或提示。
            AccessibilityNodeInfo node=waitFor(text,!text.startsWith("probe-"));
            Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            if(node.isVisibleToUser() && bounds.width()>0 && bounds.height()>0) {
                long now=SystemClock.uptimeMillis();
                MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0);
                MotionEvent up=MotionEvent.obtain(now,now+50,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0);
                down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                boolean sent=automation.injectInputEvent(down,true) && automation.injectInputEvent(up,true);down.recycle();up.recycle();
                if(sent) { Thread.sleep(200);return; }
            } else if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {Thread.sleep(200);return;}
            // 候选更新会替换节点；重新获取，而不是点击已经脱离窗口的节点。
            Thread.sleep(100);
        }
        throw new Exception("UI click rejected: "+text);
    }
    private void requireEditor(String expected,String phase) throws Exception {
        stage=phase;
        for(int i=0;i<100;i++) { AccessibilityNodeInfo editor=find("probe-normal"); if(editor!=null && expected.contentEquals(editor.getText()==null?"":editor.getText()))return; Thread.sleep(100); }
        AccessibilityNodeInfo editor=find("probe-normal");
        int length=editor==null?-1:(editor.getText()==null?0:editor.getText().length());
        throw new Exception("Editor content assertion failed at "+phase+" expectedLength="+expected.length()+" actualLength="+length);
    }
    private static final class EditorState {
        String text, committed;
        boolean composing;
    }
    private EditorState editorState() throws Exception {
        final EditorState[] state=new EditorState[1];
        // Read our independent test Activity's real Editable atomically on its UI thread.
        // This observes the InputConnection result; it does not inject text or call the core.
        runOnMainSync(() -> {
            ProbeActivity activity=ProbeActivity.current.get();
            if(activity==null)return;
            Editable editable=activity.normal.getText();
            EditorState value=new EditorState(); value.text=editable.toString();
            int start=BaseInputConnection.getComposingSpanStart(editable);
            int end=BaseInputConnection.getComposingSpanEnd(editable);
            value.composing=start>=0 && end>=start;
            value.committed=value.composing?value.text.substring(0,start)+value.text.substring(end):value.text;
            state[0]=value;
        });
        if(state[0]==null)throw new Exception("Independent test editor unavailable");
        return state[0];
    }
    private void chooseCandidate(String text,String expected,String phase) throws Exception {
        stage=phase;
        String before=editorState().committed;
        for(int attempt=0;attempt<10;attempt++) {
            // A candidate refresh can move a different word under old screen coordinates.
            // Invoke this exact Button instead; detached or obsolete generations may reject.
            AccessibilityNodeInfo node=waitFor(text,true);
            if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                for(int i=0;i<20;i++) {
                    EditorState current=editorState();
                    if(!current.composing && current.text.equals(expected)) { requireEditor(expected,phase);return; }
                    if(!current.committed.equals(before))throw new Exception("Candidate changed editor unexpectedly at "+phase+" expectedLength="+expected.length()+" actualLength="+current.committed.length());
                    Thread.sleep(50);
                }
            }
            Thread.sleep(100);
        }
        requireEditor(expected,phase);
    }
    private void screenshot() {
        try { Bitmap bitmap=automation.takeScreenshot(); if(bitmap!=null)try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"smoke.png"))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); } } catch(Exception ignored) {}
    }
    private void setText(String label,String value) throws Exception {
        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        if(!waitFor(label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))throw new Exception("Set text rejected");
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            automation=getUiAutomation(); AccessibilityServiceInfo info=automation.getServiceInfo(); info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS; automation.setServiceInfo(info);
            Intent intent=new Intent(); intent.setClassName("org.zapime.android.test","org.zapime.android.test.ProbeActivity"); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(intent);
            click("probe-normal"); waitFor("⚡ 闪电 · 离线");
            for(char c:"nihao".toCharArray())click(String.valueOf(c));
            chooseCandidate("你好","你好","pinyin_commit");
            click("中/英"); for(char c:"qwe".toCharArray())click(String.valueOf(c)); requireEditor("你好qwe","english_append");
            click("⌫"); requireEditor("你好qw","backspace");
            click("probe-password"); waitFor("隐私输入 · 不学习"); click("a"); click("b");
            click("probe-normal"); waitFor("⚡ 闪电 · 离线");
            for(char c:"nihao".toCharArray())click(String.valueOf(c));chooseCandidate("你好","你好qw你好","focus_return");
            Intent manager=new Intent();manager.setClassName("org.zapime.android","org.zapime.android.MainActivity");manager.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getContext().startActivity(manager);click("个人资料 · 离线管理与备份");
            setText("personal-code","wx");setText("personal-text","ZapIME test snippet");click("保存到本机");waitFor("wx\nZapIME test snippet");
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);getContext().startActivity(intent);click("probe-normal");waitFor("⚡ 闪电 · 离线");click("w");click("x");click("空格");requireEditor("ZapIME test snippet","saved_shortcut");
            screenshot(); result.putString("stream","PASS ZapIME native keyboard: offline pinyin, real commit, English, backspace, private field, focus isolation and saved shortcut UI\n"); finish(Activity.RESULT_OK,result);
        } catch(Throwable error) { screenshot(); result.putString("stream","FAIL ZapIME native keyboard at "+stage+": "+error.getClass().getSimpleName()+": "+error.getMessage()+"\n"); finish(Activity.RESULT_CANCELED,result); }
    }
}
