// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.test;
import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import java.lang.ref.WeakReference;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProbeActivity extends Activity {
    static WeakReference<ProbeActivity> current = new WeakReference<>(null);
    EditText normal, password;
    private int focusRequest;
    static final AtomicBoolean delayNextContext=new AtomicBoolean();
    static volatile CountDownLatch contextEntered=new CountDownLatch(0),contextRelease=new CountDownLatch(0);
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xfff7f8fb);getWindow().setNavigationBarColor(0xffedf0f5);getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        int pad=Math.round(20*getResources().getDisplayMetrics().density);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(1);panel.setPadding(pad,pad,pad,pad);panel.setBackgroundColor(0xfff7f8fb);
        TextView title=new TextView(this);title.setText("输入体验");title.setTextSize(24);title.setTextColor(0xff263446);panel.addView(title);
        TextView note=new TextView(this);note.setText("独立测试应用 · 以下均为合成测试内容");note.setTextSize(12);note.setTextColor(0xff7b8798);note.setPadding(0,pad/2,0,pad);panel.addView(note);
        normal=new EditText(this){
            @Override public InputConnection onCreateInputConnection(EditorInfo info){
                InputConnection connection=super.onCreateInputConnection(info);if(connection==null)return null;
                return new InputConnectionWrapper(connection,false){
                    @Override public CharSequence getTextBeforeCursor(int length,int flags){
                        // Synthetic unresponsive-editor fixture. No IME API or
                        // recognition text is injected by this test host.
                        if(delayNextContext.compareAndSet(true,false)){contextEntered.countDown();try{contextRelease.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
                        return super.getTextBeforeCursor(length,flags);
                    }
                };
            }
        }; normal.setHint("试着输入一句问候"); normal.setContentDescription("probe-normal"); normal.setInputType(InputType.TYPE_CLASS_TEXT); panel.addView(normal);
        password=new EditText(this); password.setHint("隐私输入测试"); password.setContentDescription("probe-password"); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); panel.addView(password);
        Button show=new Button(this);show.setText("显示键盘");show.setContentDescription("probe-show-keyboard");show.setOnClickListener(v->{normal.requestFocus();requestKeyboard(++focusRequest,0);});panel.addView(show);
        setContentView(panel); current=new WeakReference<>(this); normal.requestFocus();
    }
    @Override public void onDestroy() { ++focusRequest;if(current.get()==this)current.clear(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        final int request=++focusRequest;
        if(focused&&normal!=null&&normal.hasFocus())normal.post(()->requestKeyboard(request,0));
    }
    private void requestKeyboard(int request,int attempt){
        if(request!=focusRequest||current.get()!=this||!normal.hasWindowFocus()||!normal.hasFocus())return;
        // A fresh CLEAR_TASK Activity can gain window focus before its view has
        // become InputMethodManager's served editor. Retry that system request,
        // bounded, without calling the IME or writing the Editable.
        InputMethodManager manager=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(manager.isActive(normal)&&manager.showSoftInput(normal,InputMethodManager.SHOW_IMPLICIT))return;
        if(attempt<30)normal.postDelayed(()->requestKeyboard(request,attempt+1),100);
    }
}
