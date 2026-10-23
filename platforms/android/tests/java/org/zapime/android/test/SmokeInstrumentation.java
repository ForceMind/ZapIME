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
        if(node==null || !node.refresh())return null;
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
    private void screenshot(String name) throws Exception {
        Thread.sleep(250);Bitmap bitmap=automation.takeScreenshot();if(bitmap==null)throw new IOException("Screenshot unavailable: "+name);
        try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Screenshot write failed");}finally{bitmap.recycle();}
    }
    private void dumpNode(AccessibilityNodeInfo node,Writer out,int depth) throws IOException {
        if(node==null||depth>30||!node.refresh())return;Rect bounds=new Rect();node.getBoundsInScreen(bounds);
        out.write(depth+" | "+node.getClassName()+" | "+node.getText()+" | "+node.getContentDescription()+" | "+node.isVisibleToUser()+" | "+bounds+"\n");
        for(int i=0;i<node.getChildCount();i++)dumpNode(node.getChild(i),out,depth+1);
    }
    private void dumpHierarchy(){try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"hierarchy.txt")),"UTF-8")){for(AccessibilityWindowInfo window:automation.getWindows())dumpNode(window.getRoot(),out,0);}catch(Exception ignored){}}
    private void setText(String label,String value) throws Exception {
        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        if(!waitFor(label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))throw new Exception("Set text rejected");
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            automation=getUiAutomation(); AccessibilityServiceInfo info=automation.getServiceInfo(); info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS; automation.setServiceInfo(info);
            Intent intent=new Intent(); intent.setClassName("org.zapime.android.test","org.zapime.android.test.ProbeActivity"); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(intent);
            click("probe-normal"); waitFor("⚡ 闪电 · 离线");screenshot("keyboard.png");click("工具");waitFor("返回键盘",true);screenshot("tools.png");click("返回键盘");
            for(char c:"nihao".toCharArray())click(String.valueOf(c));
            waitFor("你好",true);screenshot("candidates.png");chooseCandidate("你好","你好","pinyin_commit");
            click("中/英"); for(char c:"qwe".toCharArray())click(String.valueOf(c)); requireEditor("你好qwe","english_append");
            click("⌫"); requireEditor("你好qw","backspace");
            click("⇧");click("a");requireEditor("你好qwA","shift_uppercase");click("⌫");
            click("123");click("1");click("2");click("3");requireEditor("你好qw123","number_panel");screenshot("numbers.png");click("⌫");click("⌫");click("⌫");click("返回");requireEditor("你好qw","number_return");
            click("probe-password"); waitFor("隐私输入 · 不学习"); click("a"); click("b");
            click("probe-normal"); waitFor("⚡ 闪电 · 离线");
            for(char c:"nihao".toCharArray())click(String.valueOf(c));chooseCandidate("你好","你好qw你好","focus_return");click("符号");click("？");requireEditor("你好qw你好？","symbol_panel");click("⌫");click("返回");requireEditor("你好qw你好","symbol_return");
            Intent manager=new Intent();manager.setClassName("org.zapime.android","org.zapime.android.MainActivity");manager.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getContext().startActivity(manager);waitFor("个人资料 · 离线管理与备份",true);screenshot("home.png");click("个人资料 · 离线管理与备份");
            setText("personal-code","wx");setText("personal-text","ZapIME test snippet");click("保存到本机");waitFor("wx\nZapIME test snippet");screenshot("personal.png");
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);getContext().startActivity(intent);click("probe-normal");waitFor("⚡ 闪电 · 离线");click("w");click("x");click("空格");requireEditor("ZapIME test snippet","saved_shortcut");
            stage="nine_key";click("切换九键");waitFor("九键 6",true);click("九键 6");click("九键 4");click("九键读音 ni");click("九键 4");click("九键 2");click("九键 6");waitFor("你好",true);screenshot("nine-key.png");chooseCandidate("你好","ZapIME test snippet你好","nine_key_commit");
            click("九键 6");click("⌫");requireEditor("ZapIME test snippet你好","nine_key_backspace");
            click("中/英");click("q");requireEditor("ZapIME test snippet你好q","nine_to_english");click("⌫");click("中/英");waitFor("九键 9",true);click("九键 9");click("九键 9");click("空格");requireEditor("ZapIME test snippet你好ZapIME test snippet","nine_key_shortcut");
            click("123");click("1");requireEditor("ZapIME test snippet你好ZapIME test snippet1","nine_numeric_literal");click("⌫");click("返回");
            click("probe-password");waitFor("隐私输入 · 不学习");click("a");click("probe-normal");waitFor("⚡ 闪电 · 离线");waitFor("九键 6",true);click("切换全键");waitFor("q",true);
            screenshot("smoke.png"); result.putString("stream","PASS ZapIME native keyboard: offline pinyin, real commit, English, backspace, private field, focus isolation, shift, numeric/symbol panels, tools, saved shortcut UI, nine-key spelling/commit/backspace/shortcut/numeric/privacy and layout switching\n"); finish(Activity.RESULT_OK,result);
        } catch(Throwable error) { dumpHierarchy();try{screenshot("smoke.png");}catch(Exception ignored){} result.putString("stream","FAIL ZapIME native keyboard at "+stage+": "+error.getClass().getSimpleName()+": "+error.getMessage()+"\n"); finish(Activity.RESULT_CANCELED,result); }
    }
}
