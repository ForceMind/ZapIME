// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.*;
import android.widget.*;
import android.graphics.Color;
import android.content.Intent;
import android.view.Gravity;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.*;

public final class ZapInputMethodService extends InputMethodService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final KeyboardTouch touches=new KeyboardTouch(this,main);
    private KeyboardTheme theme;
    private long handle, generation;
    private int epoch;
    private boolean chinese = true, shift, sensitive, privateSession, ready, nineKey;
    private TextView status;
    private LinearLayout candidates, body, readingRail;
    private JSONArray readingChoices=new JSONArray();
    private String layer="letters";
    private String preedit = "";
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }

    @Override public View onCreateInputView() {
        touches.cancel();theme=KeyboardTheme.load(this);
        getWindow().getWindow().setNavigationBarColor(theme.deck);
        View decor=getWindow().getWindow().getDecorView();int flags=decor.getSystemUiVisibility();decor.setSystemUiVisibility(theme.dark?flags&~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:flags|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout panel=Ui.column(this);panel.setPadding(dp(2),0,dp(2),dp(6));panel.setBackgroundColor(theme.deck);
        status=Ui.text(this,"",12,theme.muted);status.setGravity(Gravity.CENTER_VERTICAL);status.setPadding(dp(12),0,0,0);panel.addView(status,new LinearLayout.LayoutParams(-1,dp(22)));
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setFillViewport(true);scroll.setHorizontalScrollBarEnabled(false);scroll.setBackgroundColor(theme.toolbar);candidates=new LinearLayout(this);candidates.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(candidates,new android.widget.FrameLayout.LayoutParams(-1,-1));panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(46)));
        nineKey=getSharedPreferences("keyboard",MODE_PRIVATE).getBoolean("nine_key",false);
        body=Ui.column(this);body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);panel.addView(body);layer="letters";renderKeys();refreshStatus();idleToolbar();return panel;
    }
    private void refreshTheme() {
        if(body!=null&&theme.dark!=KeyboardTheme.load(this).dark){setInputView(onCreateInputView());try{send(new JSONObject().put("op","view"),null);}catch(JSONException ignored){}}
    }
    @Override public void onStartInputView(EditorInfo info,boolean restarting){super.onStartInputView(info,restarting);refreshTheme();}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){touches.cancel();super.onConfigurationChanged(config);refreshTheme();}
    @Override public void onFinishInputView(boolean finishingInput){touches.cancel();super.onFinishInputView(finishingInput);}
    @Override public void onWindowHidden(){touches.cancel();super.onWindowHidden();}
    private void bind(Button button,Runnable action,boolean repeat){touches.bind(button,action,repeat,()->getCurrentInputConnection()!=null);}
    private Button key(String label,String action,boolean special) {
        String hint="",icon="";
        if(action.startsWith("t9-")){hint=action.substring(3);label=new String[]{"","","ABC","DEF","GHI","JKL","MNO","PQRS","TUV","WXYZ"}[Integer.parseInt(hint)];}
        if(action.equals("分词")){hint="1";label="分词";}
        if(action.equals("⌫"))icon="back";if(action.equals("⇧"))icon="shift";if(action.equals("↵"))icon="enter";if(action.equals("表情"))icon="emoji";if(action.equals("空格")&&chinese&&nineKey&&layer.equals("letters"))icon="space";
        Button b=new KeyCap(this,label,hint,icon,special,theme);b.setContentDescription(action.startsWith("t9-")?"九键 "+hint:action);
        if(action.equals("⇧")&&shift)b.setBackground(Ui.shape(this,theme.accent,7));
        bind(b,()->press(action),action.equals("⌫"));return b;
    }
    private void place(LinearLayout row,Button key,float weight,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(height),weight);p.setMargins(dp(2),dp(3),dp(2),dp(3));row.addView(key,p);}
    private void nineKeys() {
        LinearLayout upper=new LinearLayout(this);upper.setBaselineAligned(false);upper.setPadding(dp(2),dp(3),dp(2),0);
        ScrollView railScroll=new ScrollView(this);railScroll.setFillViewport(true);railScroll.setVerticalScrollBarEnabled(false);railScroll.setBackground(Ui.shape(this,theme.key,7));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(180),.82f);rp.setMargins(dp(2),dp(3),dp(2),dp(3));upper.addView(railScroll,rp);
        readingRail=Ui.column(this);railScroll.addView(readingRail);renderReadingRail();
        LinearLayout grid=Ui.column(this);upper.addView(grid,new LinearLayout.LayoutParams(0,dp(186),3.3f));
        String[][] actions={{"分词","t9-2","t9-3"},{"t9-4","t9-5","t9-6"},{"t9-7","t9-8","t9-9"}};
        for(String[] rowActions:actions){LinearLayout row=new LinearLayout(this);row.setBaselineAligned(false);for(String action:rowActions)place(row,key("",action,false),1,56);grid.addView(row,new LinearLayout.LayoutParams(-1,dp(62)));}
        LinearLayout right=Ui.column(this);upper.addView(right,new LinearLayout.LayoutParams(0,dp(186),.82f));for(String action:new String[]{"⌫","重输","表情"}){Button b=key(action,action,true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.setMargins(dp(2),dp(3),dp(2),dp(3));right.addView(b,lp);}body.addView(upper);
        LinearLayout bottom=new LinearLayout(this);bottom.setBaselineAligned(false);bottom.setPadding(dp(2),0,dp(2),0);for(String action:new String[]{"符","中/英","空格","123","↵"})place(bottom,key(action,action,!action.equals("空格")),action.equals("空格")?1.5f:.85f,48);body.addView(bottom,new LinearLayout.LayoutParams(-1,dp(54)));
    }
    private void renderReadingRail() {
        if(readingRail==null)return;readingRail.removeAllViews();
        if(readingChoices.length()>0){final long shownGeneration=generation;
            for(int i=0;i<readingChoices.length();i++){final int index=i;String spelling=readingChoices.optString(i);Button b=Ui.button(this,spelling,theme.key,false);b.setTextColor(theme.ink);b.setPadding(0,0,0,0);b.setTextSize(16);b.setContentDescription("九键读音 "+spelling);readingRail.addView(b,new LinearLayout.LayoutParams(-1,dp(45)));bind(b,()->{try{send(new JSONObject().put("op","nine_spelling").put("index",index).put("generation",shownGeneration),null);}catch(JSONException ignored){}},false);}
        }else{String[] punctuation={"，","。","？","！"};for(int i=0;i<punctuation.length;i++){String text=punctuation[i];Button b=key(text,text,false);b.setElevation(0);b.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.ripple),Ui.shape(this,theme.key,7),null));readingRail.addView(b,new LinearLayout.LayoutParams(-1,dp(i<3?44:45)));if(i<3){View line=new View(this);line.setBackgroundColor(theme.stroke);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.setMargins(dp(8),0,dp(8),0);readingRail.addView(line,lp);}}}
    }
    private void addRow(String keys,int indent) {
        LinearLayout row=new LinearLayout(this);row.setBaselineAligned(false);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(indent),0,dp(indent),0);
        for(String action:keys.split(" ")) {
            String label=action.equals("空格")?(chinese?"拼音 · 空格":"English"):action.equals("中/英")?(chinese?"中/英":"英/中"):action.equals("返回")?"ABC":action;
            if(action.startsWith("t9-")){int digit=Integer.parseInt(action.substring(3));label=new String[]{"","","ABC","DEF","GHI","JKL","MNO","PQRS","TUV","WXYZ"}[digit]+"\n"+digit;}
            if(action.equals("分词"))label="分词\n’";
            if(action.equals("空格")&&chinese&&nineKey&&layer.equals("letters"))label="空格";
            if(action.length()==1&&Character.isLetter(action.charAt(0)))label=shift?action.toUpperCase(java.util.Locale.ROOT):action;
            boolean special=action.equals("⇧")||action.equals("⌫")||action.equals("↵")||action.equals("123")||action.equals("中/英")||action.equals("返回");
            Button b=key(label,action,special);if(action.startsWith("t9-")){b.setTextSize(17);b.setIncludeFontPadding(false);b.setGravity(Gravity.CENTER);b.setContentDescription("九键 "+action.substring(3));}float weight=chinese&&nineKey&&layer.equals("letters")?1:action.equals("空格")?3.5f:action.equals("↵")?1.5f:layer.equals("letters")&&(action.equals("⇧")||action.equals("⌫"))?1.3f:1;
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),weight);p.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(b,p);
        }body.addView(row,new LinearLayout.LayoutParams(-1,dp(52)));
    }
    private void renderKeys() {
        if(body==null)return;touches.cancel();readingRail=null;body.removeAllViews();
        body.post(()->body.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        if(layer.equals("tools")) {
            LinearLayout tiles=new LinearLayout(this);tiles.setPadding(dp(8),dp(14),dp(8),0);
            String[] labels={"个人资料","数字键盘","符号","输入设置"};String[] icons={"book","keys","keys","settings"};
            for(int i=0;i<labels.length;i++){final int index=i;Button b=Ui.button(this,labels[i],theme.key,false);b.setTextColor(theme.ink);b.setTextSize(13);b.setPadding(0,dp(16),0,dp(10));b.setCompoundDrawablesRelativeWithIntrinsicBounds(null,Ui.icon(this,icons[i],theme.accent),null,null);b.setCompoundDrawablePadding(dp(10));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(88),1);p.setMargins(dp(4),0,dp(4),0);tiles.addView(b,p);b.setOnClickListener(v->{if(index==0)open(PersonalDataActivity.class);if(index==1){layer="numbers";renderKeys();}if(index==2){layer="symbols";renderKeys();}if(index==3)open(KeyboardSettingsActivity.class);});}
            body.addView(tiles);TextView note=Ui.text(this,"资料保存在本机 · 长按候选可置顶",12,theme.muted);note.setGravity(Gravity.CENTER);body.addView(note,new LinearLayout.LayoutParams(-1,dp(46)));
            Button back=Ui.button(this,"返回键盘",theme.special,false);back.setTextColor(theme.ink);back.setContentDescription("返回键盘");back.setOnClickListener(v->{layer="letters";renderKeys();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(44));p.setMargins(dp(12),dp(8),dp(12),dp(8));body.addView(back,p);return;
        }
        if(layer.equals("emoji")){addRow("😀 😂 😊 😍 🥰 😎",2);addRow("👍 👏 🙏 💪 ❤️ 🎉",2);addRow("😭 😅 🤔 🙃 ✨ ✅",2);addRow("返回 ， 。 空格 ⌫ ↵",2);return;}
        if(layer.equals("edit")){addRow("← ↑ ↓ →",4);addRow("全选 复制 剪切 粘贴",4);addRow("， 。 ⌫ ↵",4);addRow("返回 空格",4);return;}
        if(layer.equals("numbers")){addRow("1 2 3 ⌫",20);addRow("4 5 6 +",20);addRow("7 8 9 -",20);addRow("返回 0 . ↵",20);return;}
        if(layer.equals("symbols")){addRow("， 。 ？ ！ ： ；",4);addRow("（ ） 【 】 《 》",4);addRow("@ # % & / ⌫",4);addRow("返回 ' , 空格 . ↵",4);return;}
        if(chinese&&nineKey){nineKeys();return;}
        addRow("q w e r t y u i o p",0);addRow("a s d f g h j k l",14);addRow("⇧ z x c v b n m ⌫",0);addRow("123 中/英 , 空格 . ↵",0);
    }
    private void open(Class<?> activity){startActivity(new Intent(this,activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private void idleToolbar(){
        if(candidates==null)return;candidates.removeAllViews();String[] labels={"工具","表情",nineKey?"切换全键":"切换九键","光标编辑","个人资料","收起键盘"};String[] icons={"bolt","emoji","keys","cursor","clipboard","hide"};
        for(int i=0;i<labels.length;i++){final int index=i;Button b=Ui.button(this,"",theme.toolbar,false);b.setContentDescription(labels[i]);b.setCompoundDrawablesRelativeWithIntrinsicBounds(null,Ui.icon(this,icons[i],i==0?theme.accent:theme.ink),null,null);candidates.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));bind(b,()->{if(index==0){layer=layer.equals("tools")?"letters":"tools";renderKeys();}if(index==1){layer="emoji";renderKeys();}if(index==2)switchKeyboard();if(index==3){layer="edit";renderKeys();}if(index==4)open(PersonalDataActivity.class);if(index==5)requestHideSelf(0);},false);}
    }
    private void switchKeyboard(){if(sensitive||!ready)return;command("CommitRaw",null);nineKey=!nineKey;getSharedPreferences("keyboard",MODE_PRIVATE).edit().putBoolean("nine_key",nineKey).apply();chinese=true;shift=false;layer="letters";syncLayout();renderKeys();idleToolbar();}
    private void syncLayout(){try{send(new JSONObject().put("op","layout").put("name",chinese&&nineKey?"nine_key":"qwerty"),null);}catch(JSONException ignored){}}
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info,restarting);touches.cancel(); final int active = ++epoch; ready = false; preedit=""; generation=0;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        int type = info.inputType & InputType.TYPE_MASK_CLASS;
        sensitive = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || type == InputType.TYPE_CLASS_NUMBER || type == InputType.TYPE_CLASS_PHONE;
        privateSession = sensitive || (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;
        chinese = !sensitive;nineKey=getSharedPreferences("keyboard",MODE_PRIVATE).getBoolean("nine_key",false); shift=false;layer="letters";readingChoices=new JSONArray();renderKeys();
        final boolean privateMode = privateSession;
        final boolean useNine=chinese&&nineKey;
        refreshStatus();idleToolbar();
        worker.execute(() -> {
            if(handle!=0) { NativeCore.close(handle); handle=0; }
            try {
                ResourceInstaller.prepare(this);
                handle=NativeCore.open(new File(getFilesDir(),"zapime").getAbsolutePath(), privateMode);
                final boolean success = handle!=0;
                final JSONObject initial=success?new JSONObject(NativeCore.request(handle,new JSONObject().put("op","layout").put("name",useNine?"nine_key":"qwerty").toString())):null;
                main.post(() -> { if(epoch==active) { ready=success;if(initial!=null)apply(initial,null); refreshStatus(); } });
            } catch (Exception | LinkageError error) {
                main.post(() -> { if(epoch==active && status!=null)status.setText("词库准备失败 · 可使用英文，请重新打开键盘"); });
            }
        });
    }
    private void refreshStatus() {
        if(status==null)return;
        String state=privateSession?"隐私输入 · 不学习":ready?(chinese?"⚡ 闪电 · 离线":"⚡ ZapIME · English"):"正在准备离线词库…";
        status.setContentDescription(preedit.isEmpty()?state:preedit);
        status.setText(preedit.isEmpty()?(privateSession?"隐私输入 · 不学习":ready?"闪电输入法  /  "+(chinese?(nineKey?"九键拼音":"中文拼音"):"English")+"  ·  离线":"正在准备离线词库…"):preedit);
        status.setTextColor(preedit.isEmpty()?theme.muted:theme.accent);
    }
    private void send(JSONObject request, String fallback) {
        final int active=epoch;
        if(!ready)return;
        worker.execute(() -> {
            if(handle==0)return;
            try {
                JSONObject result=new JSONObject(NativeCore.request(handle,request.toString()));
                main.post(() -> { if(epoch==active)apply(result,fallback); });
            } catch (Exception error) { main.post(() -> { if(epoch==active && status!=null)status.setText("输入会话错误 · 请重新打开键盘"); }); }
        });
    }
    private void apply(JSONObject value, String fallback) {
        if(value.has("error")){ if(status!=null)status.setText("操作未完成，请在个人资料中检查候选规则。"); return; }
        InputConnection connection=getCurrentInputConnection(); if(connection==null)return;
        generation=value.optLong("generation");
        final String previousPreedit=preedit;
        boolean wroteText=!value.isNull("commit");
        if(wroteText)connection.commitText(value.optString("commit"),1);
        if(!value.optBoolean("handled") && fallback!=null) {
            if(fallback.equals("⌫")) delete(connection);
            else if(fallback.equals("↵"))enter(connection);
            else {connection.commitText(fallback,1);wroteText=true;}
        }
        preedit=value.optString("preedit","");
        if(!preedit.isEmpty())connection.setComposingText(preedit,1);
        else {
            // Empty layout/view replies must not replace an editor selection.
            // Only clear a composition that this IME actually owns and cancelled.
            if(!wroteText&&!previousPreedit.isEmpty())connection.setComposingText("",1);
            connection.finishComposingText();
        }
        refreshStatus();
        // Readings replace the punctuation rail; all key hit targets stay fixed.
        JSONArray choices=value.optJSONArray("spellings");readingChoices=preedit.isEmpty()||choices==null?new JSONArray():choices;renderReadingRail();
        if(candidates==null)return; candidates.removeAllViews(); JSONArray words=value.optJSONArray("candidates"); if(preedit.isEmpty()){idleToolbar();return;}if(words==null)return;
        final long shownGeneration=generation;
        for(int i=0;i<words.length();i++) { final int index=i; Button b=Ui.button(this,words.optString(i),theme.toolbar,false);b.setTextSize(21);b.setTextColor(i==0?theme.accent:theme.ink);b.setPadding(dp(16),0,dp(16),0);candidates.addView(b,new LinearLayout.LayoutParams(-2,dp(44))); bind(b,() -> { try { send(new JSONObject().put("op","select").put("index",index).put("generation",shownGeneration),null); } catch(JSONException ignored) {} },false); b.setOnLongClickListener(v -> { if(privateSession)return false; try { send(new JSONObject().put("op","personal_pin").put("index",index).put("generation",shownGeneration),null); return true; } catch(JSONException ignored) { return false; } }); }
    }
    private void press(String key) {
        InputConnection connection=getCurrentInputConnection(); if(connection==null)return;
        if(key.equals("重输")){try{send(new JSONObject().put("op","reset"),null);}catch(JSONException ignored){}return;}
        if(key.equals("符")){layer="symbols";renderKeys();return;}
        if(key.equals("表情")){layer="emoji";renderKeys();return;}
        if(layer.equals("edit")&&!key.equals("返回")&&!key.equals("空格")&&!key.equals("⌫")&&!key.equals("↵")&&!key.equals("，")&&!key.equals("。")){
            final int active=epoch;command("CommitRaw",null);Runnable edit=()->{if(epoch!=active)return;InputConnection c=getCurrentInputConnection();if(c==null)return;
                int code=key.equals("←")?21:key.equals("→")?22:key.equals("↑")?19:key.equals("↓")?20:0;
                if(code!=0){c.sendKeyEvent(new android.view.KeyEvent(0,code));c.sendKeyEvent(new android.view.KeyEvent(1,code));}
                else {int id=key.equals("全选")?android.R.id.selectAll:key.equals("复制")?android.R.id.copy:key.equals("剪切")?android.R.id.cut:android.R.id.paste;if(!privateSession||id==android.R.id.paste||id==android.R.id.selectAll)c.performContextMenuAction(id);}
            };if(ready)worker.execute(()->main.post(edit));else edit.run();return;
        }
        if(key.equals("123")){layer="numbers";renderKeys();return;}
        if(key.equals("返回")){layer="letters";renderKeys();return;}
        if(key.equals("⇧")) { shift=!shift;renderKeys();return; }
        if(key.equals("中/英")) { if(sensitive)return; command("CommitRaw",null); chinese=!chinese;shift=false;syncLayout();refreshStatus();renderKeys();return; }
        if(key.startsWith("t9-")){try{send(new JSONObject().put("op","key").put("text",key.substring(3)),null);}catch(JSONException ignored){}return;}
        if(key.equals("分词")){try{send(new JSONObject().put("op","key").put("text","'"),null);}catch(JSONException ignored){}return;}
        if(!chinese || key.equals("，") || key.equals("。") || (!layer.equals("letters")&&!key.equals("⌫")&&!key.equals("空格")&&!key.equals("↵")) || (shift&&key.length()==1&&Character.isLetter(key.charAt(0)))) {
            if(chinese)command("CommitRaw",null);
            final int active=epoch;
            final String text=key.equals("空格")?" ":(shift?key.toUpperCase(java.util.Locale.ROOT):key);
            Runnable insert=() -> {
                if(epoch!=active)return;
                InputConnection current=getCurrentInputConnection(); if(current==null)return;
                if(key.equals("⌫"))delete(current);
                else if(key.equals("↵"))enter(current);
                else current.commitText(text,1);
            };
            // 与尚在处理的中文按键共用队列，避免切到英文后先插入新字符。
            if(ready)worker.execute(() -> main.post(insert)); else insert.run();
            boolean wasShift=shift;shift=false;if(wasShift)renderKeys();return;
        }
        if(key.equals("⌫")) { command("Backspace","⌫"); return; }
        if(key.equals("空格")) { command("CommitCandidate"," "); return; }
        if(key.equals("↵")) { command("CommitRaw","↵"); return; }
        try { send(new JSONObject().put("op","key").put("text",key),key); } catch(JSONException ignored) {}
    }
    private void delete(InputConnection c){CharSequence selected=c.getSelectedText(0);if(selected!=null&&selected.length()>0)c.commitText("",1);else c.deleteSurroundingTextInCodePoints(1,0);}
    private void command(String name, String fallback) { try { send(new JSONObject().put("op","command").put("name",name),fallback); } catch(JSONException ignored) {} }
    private void enter(InputConnection connection) { EditorInfo info=getCurrentInputEditorInfo(); int action=info==null?0:info.imeOptions & EditorInfo.IME_MASK_ACTION; if(action!=0 && action!=EditorInfo.IME_ACTION_NONE && action!=EditorInfo.IME_ACTION_UNSPECIFIED && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION)==0)connection.performEditorAction(action); else connection.commitText("\n",1); }
    @Override public void onFinishInput() { touches.cancel();++epoch; ready=false; preedit=""; if(getCurrentInputConnection()!=null)getCurrentInputConnection().finishComposingText(); worker.execute(() -> { if(handle!=0) { NativeCore.close(handle); handle=0; } }); super.onFinishInput(); }
    @Override public void onDestroy() {
        // InputMethodService.onDestroy invokes our onFinishInput. Keep the worker
        // accepting that final close before shutting it down; both closes are queued
        // on its owning thread and clear the handle so the core is closed once.
        touches.cancel();++epoch;ready=false;
        try { super.onDestroy(); }
        finally {
            worker.execute(() -> { if(handle!=0){NativeCore.close(handle);handle=0;} });
            worker.shutdown();
        }
    }
}
