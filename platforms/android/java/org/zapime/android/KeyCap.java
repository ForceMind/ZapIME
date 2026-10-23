// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.Button;

/** Native Button semantics with explicit digit/letter geometry and scalable text. */
final class KeyCap extends Button {
    private final String label,hint,icon;
    private final KeyboardTheme theme;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    KeyCap(Context c,String label,String hint,String icon,boolean special,KeyboardTheme theme) {
        super(c);this.label=label;this.hint=hint;this.icon=icon;this.theme=theme;
        setText("");setAllCaps(false);setPadding(0,0,0,0);setMinWidth(0);setMinimumWidth(0);setMinHeight(0);setMinimumHeight(0);setStateListAnimator(null);setGravity(android.view.Gravity.CENTER);
        GradientDrawable cap=Ui.shape(c,special?theme.special:theme.key,7);cap.setStroke(Ui.dp(c,1),theme.stroke);
        setBackground(new RippleDrawable(ColorStateList.valueOf(theme.ripple),cap,null));setElevation(Ui.dp(c,1));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density=getResources().getDisplayMetrics().density;
        float sp=getResources().getDisplayMetrics().scaledDensity;
        if(!icon.isEmpty()) {
            Drawable image=Ui.icon(getContext(),icon,theme.ink);int size=Math.min(Ui.dp(getContext(),28),getHeight()-Ui.dp(getContext(),12));int x=(getWidth()-size)/2,y=(getHeight()-size)/2;
            image.setBounds(x,y,x+size,y+size);image.draw(canvas);return;
        }
        paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setTextAlign(Paint.Align.CENTER);
        if(!hint.isEmpty())drawText(canvas,hint,12*sp,getHeight()*.21f,theme.muted);
        float desired=(hint.isEmpty()?(label.length()==1?24:18):22)*sp;
        paint.setTextSize(desired);float measured=paint.measureText(label);
        float size=measured>getWidth()-8*density?desired*(getWidth()-8*density)/measured:desired;
        drawText(canvas,label,size,getHeight()*(hint.isEmpty()?.50f:.63f),theme.ink);
    }
    private void drawText(Canvas canvas,String text,float size,float center,int color){paint.setTextSize(size);paint.setColor(color);Paint.FontMetrics f=paint.getFontMetrics();canvas.drawText(text,getWidth()/2f,center-(f.ascent+f.descent)/2,paint);}
}
