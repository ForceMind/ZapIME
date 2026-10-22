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
    private long handle, generation;
    private int epoch;
    private boolean chinese = true, shift, sensitive, privateSession, ready;
    private TextView status;
    private LinearLayout candidates, body;
    private String layer="letters";
    private String preedit = "";
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }

    @Override public View onCreateInputView() {
        getWindow().getWindow().setNavigationBarColor(Ui.KEYBOARD);
        LinearLayout panel=Ui.column(this);panel.setPadding(dp(4),dp(2),dp(4),dp(6));panel.setBackgroundColor(Ui.KEYBOARD);
        status=Ui.text(this,"",12,Ui.MUTED);status.setGravity(Gravity.CENTER_VERTICAL);status.setPadding(dp(12),0,0,0);panel.addView(status,new LinearLayout.LayoutParams(-1,dp(24)));
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);candidates=new LinearLayout(this);candidates.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(candidates);panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(44)));
        body=Ui.column(this);body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);panel.addView(body);layer="letters";renderKeys();refreshStatus();idleToolbar();return panel;
    }
    private Button key(String label,String action,boolean special) {
        Button b=Ui.button(this,label,special?Ui.SPECIAL:Color.WHITE,false);b.setPadding(0,0,0,0);b.setTextSize(label.length()==1?23:14);b.setContentDescription(action);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x242878fa),Ui.shape(this,special?Ui.SPECIAL:Color.WHITE,7),null));b.setElevation(dp(1));
        if(action.equals("⌫")||action.equals("⇧")||action.equals("↵")) {
            String icon=action.equals("⌫")?"back":action.equals("⇧")?"shift":"enter";
            b.setText("");b.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,null,null);
            // A centered native image span keeps the control a fully accessible Button.
            android.text.SpannableString glyph=new android.text.SpannableString(" ");glyph.setSpan(new android.text.style.ImageSpan(Ui.icon(this,icon,action.equals("↵")?Color.WHITE:Ui.INK)),0,1,0);b.setText(glyph);
            if(action.equals("↵"))b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),Ui.shape(this,Ui.BLUE,7),null));
            if(action.equals("⇧")&&shift)b.setBackground(Ui.shape(this,0xffc7dcff,7));
        }
        if(action.equals("中/英"))b.setTextSize(12);b.setOnClickListener(v -> press(action));return b;
    }
    private void addRow(String keys,int indent) {
        LinearLayout row=new LinearLayout(this);row.setPadding(dp(indent),0,dp(indent),0);
        for(String action:keys.split(" ")) {
            String label=action.equals("空格")?(chinese?"拼音 · 空格":"English"):action.equals("中/英")?(chinese?"中/英":"英/中"):action.equals("返回")?"ABC":action;
            if(action.length()==1&&Character.isLetter(action.charAt(0)))label=shift?action.toUpperCase(java.util.Locale.ROOT):action;
            boolean special=action.equals("⇧")||action.equals("⌫")||action.equals("↵")||action.equals("123")||action.equals("中/英")||action.equals("返回");
            Button b=key(label,action,special);float weight=action.equals("空格")?3.5f:action.equals("↵")?1.5f:layer.equals("letters")&&(action.equals("⇧")||action.equals("⌫"))?1.3f:1;
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),weight);p.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(b,p);
        }body.addView(row,new LinearLayout.LayoutParams(-1,dp(52)));
    }
    private void renderKeys() {
        if(body==null)return;body.removeAllViews();
        body.post(()->body.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        if(layer.equals("tools")) {
            LinearLayout tiles=new LinearLayout(this);tiles.setPadding(dp(8),dp(14),dp(8),0);
            String[] labels={"个人资料","数字键盘","符号面板","输入设置"};String[] icons={"book","keys","keys","settings"};
            for(int i=0;i<labels.length;i++){final int index=i;Button b=Ui.button(this,labels[i],Color.WHITE,false);b.setTextSize(13);b.setPadding(0,dp(16),0,dp(10));b.setCompoundDrawablesRelativeWithIntrinsicBounds(null,Ui.icon(this,icons[i],Ui.BLUE),null,null);b.setCompoundDrawablePadding(dp(10));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(88),1);p.setMargins(dp(4),0,dp(4),0);tiles.addView(b,p);b.setOnClickListener(v->{if(index==0)open(PersonalDataActivity.class);if(index==1){layer="numbers";renderKeys();}if(index==2){layer="symbols";renderKeys();}if(index==3)open(MainActivity.class);});}
            body.addView(tiles);TextView note=Ui.text(this,"资料保存在本机 · 长按候选可置顶",12,Ui.MUTED);note.setGravity(Gravity.CENTER);body.addView(note,new LinearLayout.LayoutParams(-1,dp(46)));
            Button back=Ui.button(this,"返回键盘",Ui.SPECIAL,false);back.setContentDescription("返回键盘");back.setOnClickListener(v->{layer="letters";renderKeys();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(44));p.setMargins(dp(12),dp(8),dp(12),dp(8));body.addView(back,p);return;
        }
        if(layer.equals("numbers")){addRow("1 2 3 ⌫",20);addRow("4 5 6 +",20);addRow("7 8 9 -",20);addRow("返回 0 . ↵",20);return;}
        if(layer.equals("symbols")){addRow("， 。 ？ ！ ： ；",4);addRow("（ ） 【 】 《 》",4);addRow("@ # % & / ⌫",4);addRow("返回 ' , 空格 . ↵",4);return;}
        addRow("q w e r t y u i o p",0);addRow("a s d f g h j k l",14);addRow("⇧ z x c v b n m ⌫",0);addRow("123 中/英 , 空格 . ↵",0);
    }
    private void open(Class<?> activity){startActivity(new Intent(this,activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private void idleToolbar(){
        if(candidates==null)return;candidates.removeAllViews();String[] labels={"工具","个人资料","符号","输入设置","收起键盘"};String[] icons={"grid","book","keys","settings","hide"};
        for(int i=0;i<labels.length;i++){final int index=i;Button b=Ui.button(this,"",Ui.KEYBOARD,false);b.setContentDescription(labels[i]);b.setWidth(dp(61));b.setCompoundDrawablesRelativeWithIntrinsicBounds(null,Ui.icon(this,icons[i],i==0?Ui.BLUE:Ui.MUTED),null,null);candidates.addView(b,new LinearLayout.LayoutParams(dp(61),dp(44)));b.setOnClickListener(v->{if(index==0){layer=layer.equals("tools")?"letters":"tools";renderKeys();}if(index==1)open(PersonalDataActivity.class);if(index==2){layer="symbols";renderKeys();}if(index==3)open(MainActivity.class);if(index==4)requestHideSelf(0);});}
    }
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info,restarting); final int active = ++epoch; ready = false; preedit=""; generation=0;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        int type = info.inputType & InputType.TYPE_MASK_CLASS;
        sensitive = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || type == InputType.TYPE_CLASS_NUMBER || type == InputType.TYPE_CLASS_PHONE;
        privateSession = sensitive || (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;
        chinese = !sensitive; shift=false;layer="letters";renderKeys();
        final boolean privateMode = privateSession;
        refreshStatus();idleToolbar();
        worker.execute(() -> {
            if(handle!=0) { NativeCore.close(handle); handle=0; }
            try {
                ResourceInstaller.prepare(this);
                handle=NativeCore.open(new File(getFilesDir(),"zapime").getAbsolutePath(), privateMode);
                final boolean success = handle!=0;
                main.post(() -> { if(epoch==active) { ready=success; refreshStatus(); } });
            } catch (Exception | LinkageError error) {
                main.post(() -> { if(epoch==active && status!=null)status.setText("词库准备失败 · 可使用英文，请重新打开键盘"); });
            }
        });
    }
    private void refreshStatus() {
        if(status==null)return;
        String state=privateSession?"隐私输入 · 不学习":ready?(chinese?"⚡ 闪电 · 离线":"⚡ ZapIME · English"):"正在准备离线词库…";
        status.setContentDescription(preedit.isEmpty()?state:preedit);
        status.setText(preedit.isEmpty()?(privateSession?"隐私输入 · 不学习":ready?"闪电输入法  /  "+(chinese?"中文拼音":"English")+"  ·  离线":"正在准备离线词库…"):preedit);
        status.setTextColor(preedit.isEmpty()?Ui.MUTED:Ui.BLUE);
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
        if(!value.isNull("commit"))connection.commitText(value.optString("commit"),1);
        if(!value.optBoolean("handled") && fallback!=null) {
            if(fallback.equals("⌫")) connection.deleteSurroundingTextInCodePoints(1,0);
            else if(fallback.equals("↵"))enter(connection);
            else connection.commitText(fallback,1);
        }
        preedit=value.optString("preedit",""); connection.setComposingText(preedit,1);
        if(preedit.isEmpty())connection.finishComposingText(); refreshStatus();
        if(candidates==null)return; candidates.removeAllViews(); JSONArray words=value.optJSONArray("candidates"); if(preedit.isEmpty()){idleToolbar();return;}if(words==null)return;
        final long shownGeneration=generation;
        for(int i=0;i<words.length();i++) { final int index=i; Button b=Ui.button(this,words.optString(i),Ui.KEYBOARD,false);b.setTextSize(21);b.setTextColor(i==0?Ui.BLUE:Ui.INK);b.setPadding(dp(16),0,dp(16),0);candidates.addView(b,new LinearLayout.LayoutParams(-2,dp(44))); b.setOnClickListener(v -> { try { send(new JSONObject().put("op","select").put("index",index).put("generation",shownGeneration),null); } catch(JSONException ignored) {} }); b.setOnLongClickListener(v -> { if(privateSession)return false; try { send(new JSONObject().put("op","personal_pin").put("index",index).put("generation",shownGeneration),null); return true; } catch(JSONException ignored) { return false; } }); }
    }
    private void press(String key) {
        InputConnection connection=getCurrentInputConnection(); if(connection==null)return;
        if(key.equals("123")){layer="numbers";renderKeys();return;}
        if(key.equals("返回")){layer="letters";renderKeys();return;}
        if(key.equals("⇧")) { shift=!shift;renderKeys();return; }
        if(key.equals("中/英")) { if(sensitive)return; command("CommitRaw",null); chinese=!chinese;shift=false;refreshStatus();renderKeys();return; }
        if(!chinese || (!layer.equals("letters")&&!key.equals("⌫")&&!key.equals("空格")&&!key.equals("↵")) || (shift&&key.length()==1&&Character.isLetter(key.charAt(0)))) {
            if(chinese)command("CommitRaw",null);
            final int active=epoch;
            final String text=key.equals("空格")?" ":(shift?key.toUpperCase(java.util.Locale.ROOT):key);
            Runnable insert=() -> {
                if(epoch!=active)return;
                InputConnection current=getCurrentInputConnection(); if(current==null)return;
                if(key.equals("⌫"))current.deleteSurroundingTextInCodePoints(1,0);
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
    private void command(String name, String fallback) { try { send(new JSONObject().put("op","command").put("name",name),fallback); } catch(JSONException ignored) {} }
    private void enter(InputConnection connection) { EditorInfo info=getCurrentInputEditorInfo(); int action=info==null?0:info.imeOptions & EditorInfo.IME_MASK_ACTION; if(action!=0 && action!=EditorInfo.IME_ACTION_NONE && action!=EditorInfo.IME_ACTION_UNSPECIFIED && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION)==0)connection.performEditorAction(action); else connection.commitText("\n",1); }
    @Override public void onFinishInput() { ++epoch; ready=false; preedit=""; if(getCurrentInputConnection()!=null)getCurrentInputConnection().finishComposingText(); worker.execute(() -> { if(handle!=0) { NativeCore.close(handle); handle=0; } }); super.onFinishInput(); }
    @Override public void onDestroy() { worker.execute(() -> { if(handle!=0)NativeCore.close(handle); }); worker.shutdown(); super.onDestroy(); }
}
