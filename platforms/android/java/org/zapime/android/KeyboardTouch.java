// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.os.Handler;
import android.view.*;
import android.widget.Button;
import java.util.function.BooleanSupplier;

/** One active pointer. A cancelled/hidden/focus-changed keyboard cannot repeat. */
final class KeyboardTouch {
    private final Context context;
    private final Handler handler;
    private Binding active;
    KeyboardTouch(Context context,Handler handler){this.context=context;this.handler=handler;}
    private void feedback(View view) {
        boolean haptic=context.getSharedPreferences("keyboard",Context.MODE_PRIVATE).getBoolean("haptic",true);
        view.setHapticFeedbackEnabled(haptic);
        if(haptic)
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if(context.getSharedPreferences("keyboard",Context.MODE_PRIVATE).getBoolean("sound",false)) {
            view.setSoundEffectsEnabled(true);view.playSoundEffect(SoundEffectConstants.CLICK);view.setSoundEffectsEnabled(false);
        }
    }
    void cancel(){if(active!=null){Binding b=active;active=null;b.stop();}}
    void bind(Button button,Runnable action,boolean repeat,BooleanSupplier valid) {
        Binding binding=new Binding(button,action,repeat,valid);
        button.setHapticFeedbackEnabled(context.getSharedPreferences("keyboard",Context.MODE_PRIVATE).getBoolean("haptic",true));
        button.setSoundEffectsEnabled(false);
        button.setOnClickListener(v->{if(!binding.touch)feedback(v);if(valid.getAsBoolean())action.run();});
        button.setOnTouchListener(binding);
        button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){}
            public void onViewDetachedFromWindow(View v){if(active==binding)cancel();}
        });
    }
    private final class Binding implements View.OnTouchListener,Runnable {
        final Button button;final Runnable action;final boolean repeat;final BooleanSupplier valid;
        boolean touch,held;
        Binding(Button b,Runnable a,boolean r,BooleanSupplier v){button=b;action=a;repeat=r;valid=v;}
        void stop(){held=false;touch=false;button.setPressed(false);handler.removeCallbacks(this);}
        @Override public void run(){
            if(!held||active!=this||!button.isAttachedToWindow()||!button.isShown()||!valid.getAsBoolean()){stop();return;}
            // Repeats do not queue additional motor pulses or synthetic clicks.
            action.run();handler.postDelayed(this,65);
        }
        @Override public boolean onTouch(View v,MotionEvent event) {
            switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancel();active=this;touch=true;feedback(v);
                if(repeat){held=true;button.setPressed(true);button.performClick();handler.postDelayed(this,380);}break;
            case MotionEvent.ACTION_MOVE:
                if(event.getX()<0||event.getY()<0||event.getX()>=v.getWidth()||event.getY()>=v.getHeight()) {
                    cancel();if(!repeat){MotionEvent cancelled=MotionEvent.obtain(event);cancelled.setAction(MotionEvent.ACTION_CANCEL);button.onTouchEvent(cancelled);cancelled.recycle();}return true;
                }break;
            case MotionEvent.ACTION_POINTER_DOWN:
                cancel();MotionEvent cancelled=MotionEvent.obtain(event);cancelled.setAction(MotionEvent.ACTION_CANCEL);button.onTouchEvent(cancelled);cancelled.recycle();return true;
            case MotionEvent.ACTION_CANCEL:cancel();return repeat;
            case MotionEvent.ACTION_UP:
                if(repeat){cancel();return true;}
                // The framework performs the click after this listener returns.
                handler.post(()->{touch=false;if(active==this)active=null;});break;
            default:break;
            }return repeat;
        }
    }
}
