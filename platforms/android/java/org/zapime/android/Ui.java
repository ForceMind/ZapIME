// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** ZapIME's native visual primitives; no third-party skin or icon assets. */
final class Ui {
    static final int INK=0xff263446, MUTED=0xff7b8798, BLUE=0xff2878fa;
    static final int BG=0xfff4f6fa, KEYBOARD=0xffedf0f5, SPECIAL=0xffdfe5ee;
    static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable shape(Context c,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static TextView text(Context c,String value,int size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");return t;}
    static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(1);return v;}
    static Button button(Context c,String value,int color,boolean primary){
        Button b=new Button(c);b.setAllCaps(false);b.setText(value);b.setTextSize(15);b.setTextColor(primary?Color.WHITE:INK);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(c,12),0,dp(c,12),0);b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x182878fa),shape(c,color,12),null));return b;
    }
    static void addButton(LinearLayout parent,Button b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(parent.getContext(),48));p.topMargin=dp(parent.getContext(),10);parent.addView(b,p);}
    static LinearLayout card(LinearLayout parent){Context c=parent.getContext();LinearLayout v=column(c);v.setPadding(dp(c,18),dp(c,18),dp(c,18),dp(c,18));v.setBackground(shape(c,Color.WHITE,18));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,16);parent.addView(v,p);return v;}
    static LinearLayout page(Activity a){a.getWindow().setStatusBarColor(BG);a.getWindow().setNavigationBarColor(BG);a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);LinearLayout p=column(a);p.setPadding(dp(a,22),dp(a,24),dp(a,22),dp(a,28));p.setBackgroundColor(BG);p.setFocusableInTouchMode(true);p.requestFocus();return p;}
    static void showPage(Activity a,LinearLayout page){ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);scroll.addView(page);a.setContentView(scroll);}
    static void field(EditText field){Context c=field.getContext();field.setTextSize(16);field.setTextColor(INK);field.setHintTextColor(MUTED);field.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));field.setBackground(shape(c,BG,10));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,10);field.setLayoutParams(p);}
    static Drawable icon(Context c,String name,int color){return new LineIcon(dp(c,24),name,color);}
    private static final class LineIcon extends Drawable {
        private final int size,color;private final String name;
        LineIcon(int size,String name,int color){this.size=size;this.name=name;this.color=color;setBounds(0,0,size,size);}
        @Override public int getIntrinsicWidth(){return size;}@Override public int getIntrinsicHeight(){return size;}
        @Override public void draw(Canvas canvas){canvas.save();Rect b=getBounds();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);Paint p=new Paint(3);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();
            switch(name){
            case "back":path.moveTo(9,5);path.lineTo(3,12);path.lineTo(9,19);path.lineTo(21,19);path.lineTo(21,5);path.close();canvas.drawPath(path,p);canvas.drawLine(12,9,17,15,p);canvas.drawLine(17,9,12,15,p);break;
            case "shift":path.moveTo(3,11);path.lineTo(12,3);path.lineTo(21,11);path.lineTo(16,11);path.lineTo(16,21);path.lineTo(8,21);path.lineTo(8,11);path.close();canvas.drawPath(path,p);break;
            case "enter":path.moveTo(20,5);path.lineTo(20,14);path.lineTo(4,14);path.moveTo(9,9);path.lineTo(4,14);path.lineTo(9,19);canvas.drawPath(path,p);break;
            case "emoji":canvas.drawCircle(12,12,9,p);canvas.drawCircle(9,10,.6f,p);canvas.drawCircle(15,10,.6f,p);canvas.drawArc(8,10,16,17,15,150,false,p);break;
            case "cursor":canvas.drawLine(12,3,12,21,p);canvas.drawLine(9,3,15,3,p);canvas.drawLine(9,21,15,21,p);path.moveTo(6,8);path.lineTo(2,12);path.lineTo(6,16);path.moveTo(18,8);path.lineTo(22,12);path.lineTo(18,16);canvas.drawPath(path,p);break;
            case "clipboard":canvas.drawRoundRect(5,5,20,22,2,2,p);canvas.drawRoundRect(9,2,16,7,1,1,p);canvas.drawLine(9,12,16,12,p);canvas.drawLine(9,16,16,16,p);break;
            case "space":path.moveTo(4,10);path.lineTo(4,15);path.lineTo(20,15);path.lineTo(20,10);canvas.drawPath(path,p);break;
            case "mic":canvas.drawRoundRect(9,2,15,14,3,3,p);canvas.drawArc(6,6,18,18,0,180,false,p);canvas.drawLine(12,18,12,22,p);canvas.drawLine(8,22,16,22,p);break;
            case "grid":for(int x=4;x<=14;x+=10)for(int y=4;y<=14;y+=10)canvas.drawRoundRect(x,y,x+6,y+6,1,1,p);break;
            case "hide":path.moveTo(5,9);path.lineTo(12,16);path.lineTo(19,9);canvas.drawPath(path,p);break;
            case "bolt":path.moveTo(14,2);path.lineTo(5,13);path.lineTo(11,13);path.lineTo(10,22);path.lineTo(19,10);path.lineTo(13,10);path.close();canvas.drawPath(path,p);break;
            case "book":canvas.drawRoundRect(5,3,20,21,2,2,p);canvas.drawLine(9,3,9,21,p);canvas.drawLine(12,8,17,8,p);canvas.drawLine(12,12,17,12,p);break;
            case "settings":canvas.drawCircle(12,12,6,p);canvas.drawCircle(12,12,2,p);for(int i=0;i<8;i++){canvas.save();canvas.rotate(i*45,12,12);canvas.drawLine(12,2,12,5,p);canvas.restore();}break;
            default:canvas.drawRoundRect(3,5,21,19,2,2,p);canvas.drawLine(7,10,17,10,p);canvas.drawLine(7,14,14,14,p);
            }canvas.restore();}
        @Override public void setAlpha(int a){}@Override public void setColorFilter(ColorFilter f){}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    private Ui(){}
}
