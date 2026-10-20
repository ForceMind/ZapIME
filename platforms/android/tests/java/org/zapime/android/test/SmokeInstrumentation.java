// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.test;
import android.app.*;
import android.os.*;
import android.content.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.*;
import android.graphics.Bitmap;
import java.io.*;

public final class SmokeInstrumentation extends Instrumentation {
    private UiAutomation automation;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private AccessibilityNodeInfo walk(AccessibilityNodeInfo node,String text) {
        if(node==null)return null;
        if(text.contentEquals(node.getText()==null?"":node.getText()) || text.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;
        for(int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo found=walk(node.getChild(i),text); if(found!=null)return found; }
        return null;
    }
    private AccessibilityNodeInfo find(String text) {
        for(AccessibilityWindowInfo window:automation.getWindows()) { AccessibilityNodeInfo found=walk(window.getRoot(),text); if(found!=null)return found; }
        return walk(automation.getRootInActiveWindow(),text);
    }
    private AccessibilityNodeInfo waitFor(String text) throws Exception {
        for(int i=0;i<150;i++) { AccessibilityNodeInfo node=find(text); if(node!=null)return node; Thread.sleep(100); }
        throw new Exception("Missing UI node: "+text);
    }
    private void click(String text) throws Exception {
        AccessibilityNodeInfo node=waitFor(text);
        if(!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new Exception("UI click rejected: "+text);
        Thread.sleep(150);
    }
    private void requireEditor(String expected) throws Exception {
        for(int i=0;i<100;i++) { AccessibilityNodeInfo editor=find("probe-normal"); if(editor!=null && expected.contentEquals(editor.getText()==null?"":editor.getText()))return; Thread.sleep(100); }
        throw new Exception("Editor content assertion failed");
    }
    private void screenshot() {
        try { Bitmap bitmap=automation.takeScreenshot(); if(bitmap!=null)try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"smoke.png"))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); } } catch(Exception ignored) {}
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            automation=getUiAutomation(); AccessibilityServiceInfo info=automation.getServiceInfo(); info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS; automation.setServiceInfo(info);
            Intent intent=new Intent(); intent.setClassName("org.zapime.android.test","org.zapime.android.test.ProbeActivity"); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(intent);
            click("probe-normal"); waitFor("⚡ 闪电 · 离线");
            for(char c:"nihao".toCharArray())click(String.valueOf(c));
            click("你好"); requireEditor("你好");
            click("中/英"); for(char c:"qwe".toCharArray())click(String.valueOf(c)); requireEditor("你好qwe");
            click("⌫"); requireEditor("你好qw");
            click("probe-password"); waitFor("隐私输入 · 不学习"); click("a"); click("b");
            click("probe-normal"); waitFor("⚡ 闪电 · 离线");
            for(char c:"nihao".toCharArray())click(String.valueOf(c));click("你好"); requireEditor("你好qw你好");
            screenshot(); result.putString("stream","PASS ZapIME native keyboard: offline pinyin, real commit, English, backspace, private field and focus isolation\n"); finish(Activity.RESULT_OK,result);
        } catch(Throwable error) { screenshot(); result.putString("stream","FAIL ZapIME native keyboard: "+error.getClass().getSimpleName()+": "+error.getMessage()+"\n"); finish(Activity.RESULT_CANCELED,result); }
    }
}
