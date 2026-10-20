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
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.*;

public final class ZapInputMethodService extends InputMethodService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long handle, generation;
    private int epoch;
    private boolean chinese = true, shift, sensitive, ready;
    private TextView status;
    private LinearLayout candidates;
    private String preedit = "";
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }

    @Override public View onCreateInputView() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(1); panel.setPadding(dp(4),dp(6),dp(4),dp(8)); panel.setBackgroundColor(Color.rgb(230,234,241));
        status = new TextView(this); status.setTextSize(14); status.setTextColor(Color.rgb(47,62,82)); status.setPadding(dp(10),dp(4),0,dp(4)); panel.addView(status);
        HorizontalScrollView scroll = new HorizontalScrollView(this); candidates = new LinearLayout(this); scroll.addView(candidates); panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(48)));
        addRow(panel,"1 2 3 4 5 6 7 8 9 0"); addRow(panel,"q w e r t y u i o p"); addRow(panel,"a s d f g h j k l"); addRow(panel,"⇧ z x c v b n m ⌫"); addRow(panel,"中/英 ' , 空格 . ↵");
        refreshStatus(); return panel;
    }
    private void addRow(LinearLayout panel, String keys) {
        LinearLayout row = new LinearLayout(this);
        for (String key : keys.split(" ")) {
            Button b = new Button(this); b.setText(key); b.setTextSize(17); b.setPadding(0,0,0,0); b.setMinWidth(0); b.setMinimumWidth(0); b.setContentDescription(key);
            row.addView(b,new LinearLayout.LayoutParams(0,dp(51),key.equals("空格")?3:1)); b.setOnClickListener(v -> press(key));
        } panel.addView(row);
    }
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info,restarting); final int active = ++epoch; ready = false; preedit=""; generation=0;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        int type = info.inputType & InputType.TYPE_MASK_CLASS;
        sensitive = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || type == InputType.TYPE_CLASS_NUMBER || type == InputType.TYPE_CLASS_PHONE || (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;
        chinese = !sensitive;
        final boolean privateMode = sensitive;
        refreshStatus(); if(candidates!=null)candidates.removeAllViews();
        worker.execute(() -> {
            if(handle!=0) { NativeCore.close(handle); handle=0; }
            try {
                prepareResources();
                handle=NativeCore.open(new File(getFilesDir(),"zapime").getAbsolutePath(), privateMode);
                final boolean success = handle!=0;
                main.post(() -> { if(epoch==active) { ready=success; refreshStatus(); } });
            } catch (Exception | LinkageError error) {
                main.post(() -> { if(epoch==active && status!=null)status.setText("词库准备失败 · 可使用英文，请重新打开键盘"); });
            }
        });
    }
    private void prepareResources() throws IOException {
        File root = new File(getFilesDir(),"zapime/resources");
        if(!root.isDirectory() && !root.mkdirs())throw new IOException("resource_directory");
        for(String name : getAssets().list("dictionary")) {
            File output = new File(root,name);
            // 安装包只从经过哈希校验的资源构建；版本化目录在词库升级切片完善。
            if(output.isFile() && output.length()>0)continue;
            File staged = new File(root,name+".incoming");
            try(InputStream in=getAssets().open("dictionary/"+name); OutputStream out=new FileOutputStream(staged)) { byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))!=-1)out.write(buffer,0,n); }
            if(!staged.renameTo(output))throw new IOException("resource_install");
        }
    }
    private void refreshStatus() { if(status!=null)status.setText(preedit.isEmpty()?(sensitive?"隐私输入 · 不学习":(ready?"⚡ 闪电 · 离线":"正在准备离线词库…")):preedit); }
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
        if(value.has("error"))return;
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
        if(candidates==null)return; candidates.removeAllViews(); JSONArray words=value.optJSONArray("candidates"); if(words==null)return;
        final long shownGeneration=generation;
        for(int i=0;i<words.length();i++) { final int index=i; Button b=new Button(this); b.setText(words.optString(i)); b.setTextSize(20); candidates.addView(b); b.setOnClickListener(v -> { try { send(new JSONObject().put("op","select").put("index",index).put("generation",shownGeneration),null); } catch(JSONException ignored) {} }); }
    }
    private void press(String key) {
        InputConnection connection=getCurrentInputConnection(); if(connection==null)return;
        if(key.equals("⇧")) { shift=!shift; return; }
        if(key.equals("中/英")) { if(sensitive)return; command("CommitRaw",null); chinese=!chinese; refreshStatus(); return; }
        if(!chinese) {
            if(key.equals("⌫"))connection.deleteSurroundingTextInCodePoints(1,0);
            else if(key.equals("↵"))enter(connection);
            else connection.commitText(key.equals("空格")?" ":(shift?key.toUpperCase(java.util.Locale.ROOT):key),1);
            shift=false; return;
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
