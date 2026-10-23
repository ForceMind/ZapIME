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
    private static final java.util.ArrayDeque<String> lifecycle=new java.util.ArrayDeque<>();
    private static synchronized void note(String event){if(lifecycle.size()==128)lifecycle.removeFirst();lifecycle.addLast(event);}
    static synchronized String lifecycle(){return String.join("\n",lifecycle)+"\n";}
    static WeakReference<ProbeActivity> current = new WeakReference<>(null);
    EditText normal, password;
    private int focusRequest;
    static final java.util.concurrent.atomic.AtomicInteger normalConnections=new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger normalCommandConnection=new java.util.concurrent.atomic.AtomicInteger();
    static final AtomicBoolean delayNextContext=new AtomicBoolean();
    static volatile CountDownLatch contextEntered=new CountDownLatch(0),contextRelease=new CountDownLatch(0);
    @Override public void onCreate(Bundle state) {
        note("create");
        super.onCreate(state);
        getWindow().setStatusBarColor(0xfff7f8fb);getWindow().setNavigationBarColor(0xffedf0f5);getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        int pad=Math.round(20*getResources().getDisplayMetrics().density);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(1);panel.setPadding(pad,pad,pad,pad);panel.setBackgroundColor(0xfff7f8fb);
        TextView title=new TextView(this);title.setText("输入体验");title.setTextSize(24);title.setTextColor(0xff263446);panel.addView(title);
        TextView note=new TextView(this);note.setText("独立测试应用 · 以下均为合成测试内容");note.setTextSize(12);note.setTextColor(0xff7b8798);note.setPadding(0,pad/2,0,pad);panel.addView(note);
        normal=new EditText(this){
            @Override public InputConnection onCreateInputConnection(EditorInfo info){
                note("normal connection created; length="+length()+" selection="+getSelectionStart()+":"+getSelectionEnd()+" composing="+BaseInputConnection.getComposingSpanStart(getText())+":"+BaseInputConnection.getComposingSpanEnd(getText()));
                InputConnection connection=super.onCreateInputConnection(info);if(connection==null)return null;
                final int serial=normalConnections.incrementAndGet();
                if(android.os.Build.VERSION.SDK_INT>=30)note("initial snapshot before length="+InitialInfo.beforeLength(info));
                return new InputConnectionWrapper(connection,false){
                    private void observed(String operation){normalCommandConnection.accumulateAndGet(serial,Math::max);note("IME command on connection="+serial+" operation="+operation);}
                    @Override public boolean endBatchEdit(){boolean result=super.endBatchEdit();observed("batch-end");return result;}
                    @Override public boolean setComposingText(CharSequence text,int cursor){boolean result=super.setComposingText(text,cursor);observed("compose");return result;}
                    @Override public boolean setComposingRegion(int start,int end){boolean result=super.setComposingRegion(start,end);observed("region");return result;}
                    @Override public boolean finishComposingText(){boolean result=super.finishComposingText();observed("finish");return result;}
                    @Override public boolean commitText(CharSequence text,int cursor){boolean result=super.commitText(text,cursor);observed("commit");return result;}
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
        Button settings=new Button(this);settings.setText("打开闪电设置");settings.setContentDescription("probe-open-settings");settings.setOnClickListener(v->{note("settings click from foreground Activity");startActivity(new android.content.Intent().setClassName("org.zapime.android","org.zapime.android.MainActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));});panel.addView(settings);
        setContentView(panel); current=new WeakReference<>(this); normal.requestFocus();
    }
    @Override public void onResume(){super.onResume();note("resume");}
    @Override public void onPause(){note("pause");super.onPause();}
    @Override public void onStop(){note("stop");super.onStop();}
    @Override public void onDestroy() { note("destroy");++focusRequest;if(current.get()==this)current.clear(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        note("window focus="+focused);
        final int request=++focusRequest;
        if(focused&&normal!=null&&normal.hasFocus())normal.post(()->requestKeyboard(request,0));
    }
    private void requestKeyboard(int request,int attempt){
        if(request!=focusRequest||current.get()!=this||!normal.hasWindowFocus()||!normal.hasFocus())return;
        // A fresh CLEAR_TASK Activity can gain window focus before its view has
        // become InputMethodManager's served editor. Retry that system request,
        // bounded, without calling the IME or writing the Editable.
        InputMethodManager manager=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        boolean active=manager.isActive(normal);
        // The return value only reports a sent request. Once the actual served
        // editor's IME is visible, do not keep asking while old retries drain.
        if(active&&android.os.Build.VERSION.SDK_INT>=30&&ImeVisibility.visible(normal)){note("show stopped: already visible");return;}
        if(active){boolean sent=manager.showSoftInput(normal,InputMethodManager.SHOW_IMPLICIT);note("show request; attempt="+attempt+" sent="+sent);if(sent)return;}
        else note("show deferred: editor not served; attempt="+attempt);
        if(attempt<30)normal.postDelayed(()->requestKeyboard(request,attempt+1),100);
    }
    private static final class InitialInfo {static int beforeLength(EditorInfo info){CharSequence before=info.getInitialTextBeforeCursor(128,0);return before==null?-1:before.length();}}
    private static final class ImeVisibility {
        static boolean visible(android.view.View view){android.view.WindowInsets insets=view.getRootWindowInsets();return insets!=null&&insets.isVisible(android.view.WindowInsets.Type.ime());}
    }
}
