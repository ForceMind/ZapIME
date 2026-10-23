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
import android.view.KeyEvent;
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
    private void click(String text) throws Exception { click(text,!text.startsWith("probe-")); }
    private void click(String text,boolean button) throws Exception {
        for(int attempt=0;attempt<30;attempt++) {
            // 键盘/候选必须是真正的 Button，不能误点同文的编辑器内容或提示。
            AccessibilityNodeInfo node=waitFor(text,button);
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
        // Accessibility getText() may expose an empty EditText's hint as text.
        // Read the independent Activity's real Editable for every assertion.
        for(int i=0;i<100;i++){if(expected.equals(editorState().text))return;Thread.sleep(100);}
        throw new Exception("Editor content assertion failed at "+phase+" expectedLength="+expected.length()+" actualLength="+editorState().text.length());
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
    private AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node) {
        if(node==null)return null;if(node.isScrollable())return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=scrollable(node.getChild(i));if(found!=null)return found;}return null;
    }
    private void reveal(String text) throws Exception {
        for(int i=0;i<12;i++){AccessibilityNodeInfo node=find(text);if(node!=null&&node.isVisibleToUser())return;
            AccessibilityNodeInfo scroll=scrollable(automation.getRootInActiveWindow());if(scroll!=null)scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(200);}
        throw new Exception("Cannot reveal UI control: "+text);
    }
    private void touchAt(int action,long down,int x,int y) throws Exception {
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{if(!automation.injectInputEvent(event,true))throw new Exception("Touch injection rejected");}finally{event.recycle();}
    }
    private Rect bounds(String label) throws Exception {Rect r=new Rect();waitFor(label,true).getBoundsInScreen(r);return r;}
    private void repeatAcceptance() throws Exception {
        stage="repeat_delete";setText("probe-normal","abcdefghijklmnopqrstuvwxyz");Thread.sleep(250);
        Rect r=bounds("⌫");long down=SystemClock.uptimeMillis();touchAt(MotionEvent.ACTION_DOWN,down,r.centerX(),r.centerY());Thread.sleep(700);touchAt(MotionEvent.ACTION_CANCEL,down,r.centerX(),r.centerY());Thread.sleep(250);
        String after=editorState().text;if(after.length()>23||!"abcdefghijklmnopqrstuvwxyz".startsWith(after))throw new Exception("Long delete did not repeat safely");Thread.sleep(500);requireEditor(after,"cancel_stops_delete");
        down=SystemClock.uptimeMillis();touchAt(MotionEvent.ACTION_DOWN,down,r.centerX(),r.centerY());touchAt(MotionEvent.ACTION_MOVE,down,0,0);Thread.sleep(250);String moved=editorState().text;Thread.sleep(600);requireEditor(moved,"move_out_stops_delete");touchAt(MotionEvent.ACTION_UP,down,0,0);
        setText("probe-normal","abc😀");Thread.sleep(200);click("⌫");requireEditor("abc","delete_whole_unicode");
        for(String cluster:new String[]{"❤️","👍🏽","🇨🇳","👨‍👩‍👧‍👦","e\u0301"}){setText("probe-normal","abc"+cluster);Thread.sleep(150);click("⌫");requireEditor("abc","delete_grapheme_"+cluster.codePointCount(0,cluster.length()));}
        stage="selected_delete";runOnMainSync(()->{ProbeActivity a=ProbeActivity.current.get();a.normal.setSelection(0,a.normal.length());});click("⌫");requireEditor("","delete_selection");
        setText("probe-normal","focus safe text");Thread.sleep(200);down=SystemClock.uptimeMillis();touchAt(MotionEvent.ACTION_DOWN,down,r.centerX(),r.centerY());
        runOnMainSync(()->ProbeActivity.current.get().password.requestFocus());Thread.sleep(700);touchAt(MotionEvent.ACTION_CANCEL,down,r.centerX(),r.centerY());waitFor("隐私输入 · 不学习");click("a");Thread.sleep(600);
        final boolean[] safe={false};runOnMainSync(()->safe[0]=ProbeActivity.current.get().password.length()==1);if(!safe[0])throw new Exception("Delete repeated into next focused field");
        click("probe-normal");waitFor("⚡ 闪电 · 离线");
    }
    private void requireKeyColor(int expected) throws Exception {
        Rect r=bounds("九键 2");Bitmap bitmap=automation.takeScreenshot();if(bitmap==null)throw new Exception("Palette screenshot unavailable");
        try{int color=bitmap.getPixel(r.left+8,r.top+8);for(int shift:new int[]{0,8,16})if(Math.abs(((color>>shift)&255)-((expected>>shift)&255))>5)throw new Exception("Keyboard palette did not change");}finally{bitmap.recycle();}
    }
    private void appearanceAcceptance(Intent probe) throws Exception {
        stage="appearance_settings";
        Intent home=new Intent();home.setClassName("org.zapime.android","org.zapime.android.MainActivity");home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);getContext().startActivity(home);Thread.sleep(350);
        reveal("键盘与反馈设置");click("键盘与反馈设置");click("键盘主题 light",false);click("按键震动开关",false);waitFor("返回",true);click("返回");
        probe.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);getContext().startActivity(probe);click("probe-normal");waitFor("⚡ 闪电 · 离线");click("切换九键");waitFor("九键 6",true);requireKeyColor(0xffffffff);screenshot("nine-key-light.png");
        click("九键 6");click("九键 4");click("重输");requireEditor("","nine_key_retype");click("，");click("。");click("？");click("！");requireEditor("，。？！","punctuation_rail");
        click("表情");click("😀");requireEditor("，。？！😀","emoji_commit");click("⌫");requireEditor("，。？！","emoji_delete");click("❤️");requireEditor("，。？！❤️","heart_commit");click("⌫");requireEditor("，。？！","delete_heart_grapheme");click("返回");
        click("光标编辑");click("全选");click("复制");click("→");click("粘贴");requireEditor("，。？！，。？！","explicit_copy_paste");click("返回");
        getContext().startActivity(home);Thread.sleep(300);reveal("键盘与反馈设置");click("键盘与反馈设置");click("键盘主题 dark",false);if(waitFor("按键震动开关").isChecked())throw new Exception("Haptic preference did not persist disabled");screenshot("feedback-settings.png");click("返回");
        getContext().startActivity(probe);click("probe-normal");waitFor("⚡ 闪电 · 离线");waitFor("九键 6",true);requireKeyColor(0xff4d4d50);screenshot("nine-key-dark.png");
        click("九键 6");click("九键 4");click("九键读音 ni");click("九键 4");click("九键 2");click("九键 6");waitFor("你好",true);screenshot("nine-key-dark-candidates.png");chooseCandidate("你好","你好","dark_theme_pinyin");
        click("切换全键");click("中/英");repeatAcceptance();
    }
    private void queryText(String expected) throws Exception {
        for(int i=0;i<100;i++){AccessibilityNodeInfo node=find("资料搜索内容");if(node!=null&&expected.contentEquals(node.getText()))return;Thread.sleep(100);}
        throw new Exception("Local search text assertion failed at "+stage);
    }
    private void rowAction(String row,String action) throws Exception {
        for(int i=0;i<30;i++){
            AccessibilityNodeInfo item=waitFor(row,true),parent=item.getParent();
            AccessibilityNodeInfo control=walk(parent,action,true);
            if(control!=null&&control.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(250);return;}
            Thread.sleep(100);
        }
        throw new Exception("Local row action rejected: "+action);
    }
    private void syntheticClipboard(String text,boolean sensitive) {
        runOnMainSync(()->{ProbeActivity a=ProbeActivity.current.get();ClipData clip=ClipData.newPlainText("ZapIME synthetic test",text);PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",sensitive);clip.getDescription().setExtras(extras);((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(clip);});
    }
    private void localDataAcceptance() throws Exception {
        stage="local_panel_insert";setText("probe-normal","");Thread.sleep(200);click("本机资料面板");click("快捷短语");waitFor("wx\nZapIME test snippet",true);screenshot("local-data.png");
        click("wx\nZapIME test snippet");requireEditor("ZapIME test snippet","local_snippet_insert");
        click("搜索");waitFor("搜索资料 · 本机");click("中/英");AccessibilityNodeInfo rapid=waitFor("w",true);for(int i=0;i<20;i++)rapid.performAction(AccessibilityNodeInfo.ACTION_CLICK);waitFor("清空搜索",true).performAction(AccessibilityNodeInfo.ACTION_CLICK);queryText("查找：");Thread.sleep(350);queryText("查找：");click("w");click("x");queryText("查找：wx");requireEditor("ZapIME test snippet","search_never_enters_editor");screenshot("local-search.png");
        click("完成搜索");waitFor("本机 · 共 1 条 · 搜索：wx");click("wx\nZapIME test snippet");requireEditor("ZapIME test snippetZapIME test snippet","searched_snippet_insert");
        stage="explicit_local_clipboard";syntheticClipboard("你好合成剪贴板",false);click("保存剪贴板");String clip="剪贴板\n你好合成剪贴板";waitFor(clip,true);waitFor("本机 · 共 1 条");screenshot("local-clipboard.png");
        rowAction(clip,"置顶");waitFor("取消置顶",true);rowAction(clip,"取消置顶");waitFor("置顶",true);rowAction(clip,"转为收藏");
        click("永久收藏");String favorite="你好合成剪贴板\n你好合成剪贴板";waitFor(favorite,true);screenshot("local-favorites.png");
        click("本机剪贴板");rowAction(clip,"删除");rowAction(clip,"确认删除");waitFor("本机 · 共 0 条");
        syntheticClipboard("sensitive synthetic clipboard",true);click("保存剪贴板");Thread.sleep(350);click("本机剪贴板");waitFor("本机 · 共 0 条");if(find("剪贴板\nsensitive synthetic clipboard",true)!=null)throw new Exception("Sensitive clipboard was saved");
        click("永久收藏");waitFor(favorite,true);click("搜索");waitFor("搜索资料 · 本机");
        for(char c:"nihao".toCharArray())click(String.valueOf(c));
        // Search candidates belong to an isolated, non-learning native session.
        boolean chosen=false;for(int i=0;i<20;i++){if(waitFor("你好",true).performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(150);AccessibilityNodeInfo value=find("资料搜索内容");if(value!=null&&"查找：你好".contentEquals(value.getText())){chosen=true;break;}}}
        if(!chosen)throw new Exception("Chinese local search candidate was not committed");queryText("查找：你好");requireEditor("ZapIME test snippetZapIME test snippet","chinese_search_isolation");click("完成搜索");waitFor("本机 · 共 1 条 · 搜索：你好");click(favorite);String expected="ZapIME test snippetZapIME test snippet你好合成剪贴板";requireEditor(expected,"favorite_survives_clipboard_delete");
        stage="search_cancel_composition";click("搜索");waitFor("搜索资料 · 本机");click("清空搜索");click("n");click("i");click("取消搜索");waitFor("本机 · 共 1 条 · 搜索：你好");requireEditor(expected,"cancel_search_preserves_editor");
        stage="search_focus_isolation";click("搜索");waitFor("搜索资料 · 本机");click("n");click("i");
        final int[] passwordLength={0};runOnMainSync(()->{ProbeActivity a=ProbeActivity.current.get();passwordLength[0]=a.password.length();a.password.requestFocus();});waitFor("隐私输入 · 不学习");Thread.sleep(400);
        final boolean[] isolated={false};runOnMainSync(()->isolated[0]=ProbeActivity.current.get().password.length()==passwordLength[0]);if(!isolated[0])throw new Exception("Search leaked into next editor");click("本机资料面板");Thread.sleep(200);if(find("全部资料",true)!=null)throw new Exception("Private editor exposed local records");
        click("probe-normal");waitFor("⚡ 闪电 · 离线");requireEditor(expected,"search_focus_preserves_editor");
        final int[] options={0};runOnMainSync(()->{ProbeActivity a=ProbeActivity.current.get();options[0]=a.normal.getImeOptions();a.normal.setImeOptions(options[0]|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);((android.view.inputmethod.InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(a.normal);});waitFor("隐私输入 · 不学习");click("本机资料面板");Thread.sleep(200);if(find("全部资料",true)!=null)throw new Exception("No-learning editor exposed local records");
        runOnMainSync(()->{ProbeActivity a=ProbeActivity.current.get();a.normal.setImeOptions(options[0]);((android.view.inputmethod.InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(a.normal);});waitFor("⚡ 闪电 · 离线");click("本机资料面板");click("永久收藏");waitFor(favorite,true);click("返回键盘");requireEditor(expected,"local_favorite_persists_after_restart");
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
            for(char c:"nihao".toCharArray())click(String.valueOf(c));chooseCandidate("你好","你好qw你好","focus_return");click("工具");click("符号");click("？");requireEditor("你好qw你好？","symbol_panel");click("⌫");click("返回");requireEditor("你好qw你好","symbol_return");
            Intent manager=new Intent();manager.setClassName("org.zapime.android","org.zapime.android.MainActivity");manager.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getContext().startActivity(manager);waitFor("个人资料 · 离线管理与备份",true);screenshot("home.png");click("个人资料 · 离线管理与备份");
            setText("personal-code","wx");setText("personal-text","ZapIME test snippet");click("保存到本机");waitFor("wx\nZapIME test snippet");screenshot("personal.png");
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);getContext().startActivity(intent);click("probe-normal");waitFor("⚡ 闪电 · 离线");click("w");click("x");click("空格");requireEditor("ZapIME test snippet","saved_shortcut");
            stage="restart_selection";runOnMainSync(()->{ProbeActivity a=ProbeActivity.current.get();a.normal.setSelection(0,a.normal.length());((android.view.inputmethod.InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(a.normal);});Thread.sleep(300);waitFor("⚡ 闪电 · 离线");requireEditor("ZapIME test snippet","restart_preserves_selected_text");runOnMainSync(()->{ProbeActivity a=ProbeActivity.current.get();a.normal.setSelection(a.normal.length());});
            stage="nine_key";click("切换九键");waitFor("九键 6",true);click("九键 6");click("九键 4");click("九键读音 ni");click("九键 4");click("九键 2");click("九键 6");waitFor("你好",true);screenshot("nine-key.png");chooseCandidate("你好","ZapIME test snippet你好","nine_key_commit");
            click("九键 6");click("⌫");requireEditor("ZapIME test snippet你好","nine_key_backspace");
            click("中/英");click("q");requireEditor("ZapIME test snippet你好q","nine_to_english");click("⌫");click("中/英");waitFor("九键 9",true);click("九键 9");click("九键 9");click("空格");requireEditor("ZapIME test snippet你好ZapIME test snippet","nine_key_shortcut");
            click("123");click("1");requireEditor("ZapIME test snippet你好ZapIME test snippet1","nine_numeric_literal");click("⌫");click("返回");
            click("probe-password");waitFor("隐私输入 · 不学习");click("a");click("probe-normal");waitFor("⚡ 闪电 · 离线");waitFor("九键 6",true);click("切换全键");waitFor("q",true);
            appearanceAcceptance(intent);
            localDataAcceptance();
            screenshot("smoke.png"); result.putString("stream","PASS ZapIME native keyboard: offline pinyin, real commit, English, backspace, private field, focus isolation, shift, numeric/symbol panels, tools, saved shortcut UI, nine-key spelling/commit/backspace/shortcut/numeric/privacy and layout switching, QQ-reference light/dark palettes, settings, retype, rail punctuation, emoji, explicit clipboard actions, repeat/cancel/move/focus deletion, Unicode/grapheme and selected delete; local panel insert/search/pin/favorite/delete, sensitive clipboard rejection, isolated English/Chinese search and focus/restart/no-learning privacy\n"); finish(Activity.RESULT_OK,result);
        } catch(Throwable error) { dumpHierarchy();try{screenshot("smoke.png");}catch(Exception ignored){} result.putString("stream","FAIL ZapIME native keyboard at "+stage+": "+error.getClass().getSimpleName()+": "+error.getMessage()+"\n"); finish(Activity.RESULT_CANCELED,result); }
    }
}
