// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import java.lang.reflect.Method;

/**
 * Actual Android-runtime execution of the product's offline voice cleanup.
 * This runs against the installed APK, not a copied algorithm. No ASR model,
 * microphone, synthetic transcript persistence, HTTP or editor mutation.
 */
final class VoiceTextCleanupProbe {
    private VoiceTextCleanupProbe(){}

    private static void require(boolean condition,String reason)throws Exception {
        if(!condition)throw new Exception(reason);
    }

    private static void assertText(Method concise,String original,String expected)throws Exception {
        Object cleaned=concise.invoke(null,original);
        require(cleaned instanceof String&&expected.equals(cleaned),"voice_cleanup_unexpected_result");
    }

    private static Switch findConciseSwitch(View root) {
        if(root instanceof Switch
                &&"默认预览精简版（可切回原文）".contentEquals(((Switch)root).getText()))
            return (Switch)root;
        if(root instanceof ViewGroup){
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++){
                Switch found=findConciseSwitch(group.getChildAt(i));
                if(found!=null)return found;
            }
        }
        return null;
    }

    /** Exercise the real non-exported settings Activity's listener, not a
     * synthetic checkbox or a direct preference setter. No physical tap claim. */
    private static void exerciseSettingsUi(Instrumentation instrumentation,
                                           Context target,Method enabled,
                                           Method setEnabled)throws Exception {
        boolean previous=(Boolean)enabled.invoke(null,target);
        Activity activity=null;
        try{
            setEnabled.invoke(null,target,false);
            Intent launch=new Intent().setClassName(
                    target,"org.zapime.android.VoiceSettingsActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=instrumentation.startActivitySync(launch);
            require(activity!=null,"voice_cleanup_settings_not_launched");
            final Activity opened=activity;
            final Switch[] control=new Switch[1];
            instrumentation.runOnMainSync(
                    ()->control[0]=findConciseSwitch(opened.getWindow().getDecorView()));
            require(control[0]!=null&& !control[0].isChecked(),
                    "voice_cleanup_actual_settings_switch_missing_or_enabled");
            instrumentation.runOnMainSync(()->control[0].performClick());
            require(control[0].isChecked()&&(Boolean)enabled.invoke(null,target),
                    "voice_cleanup_settings_click_not_persisted");
            instrumentation.runOnMainSync(()->control[0].performClick());
            require(!control[0].isChecked()&&!(Boolean)enabled.invoke(null,target),
                    "voice_cleanup_settings_click_not_reversible");
        }finally{
            if(activity!=null){
                final Activity closing=activity;
                instrumentation.runOnMainSync(closing::finish);
            }
            setEnabled.invoke(null,target,previous);
        }
        require((Boolean)enabled.invoke(null,target)==previous,
                "voice_cleanup_settings_original_value_not_restored");
    }

    static void run(Instrumentation instrumentation)throws Exception {
        Context target=instrumentation.getTargetContext();
        ClassLoader loader=target.getClassLoader();
        Class<?> cleaner=Class.forName("org.zapime.android.VoiceTextCleanup",true,loader);
        Method concise=cleaner.getDeclaredMethod("concise",String.class);
        concise.setAccessible(true);

        assertText(concise,"嗯，那个，我们先检查网络。","我们先检查网络。");
        assertText(concise,"我们先检查网络，我们先检查网络，然后重试。","我们先检查网络，然后重试。");
        assertText(concise,"呃，今天三点开会。","今天三点开会。");
        assertText(concise,"不要删除，不要删除。","不要删除，不要删除。");
        assertText(concise,"今天三点开会，今天三点开会。","今天三点开会，今天三点开会。");
        assertText(concise,"那个项目需要改进。","那个项目需要改进。");
        assertText(concise,"请支付100元，请支付100元。","请支付100元，请支付100元。");
        assertText(concise,"🙂🙂，🙂🙂","🙂🙂，🙂🙂");
        assertText(concise,"嗯。","嗯。");
        assertText(concise,"hello hello","hello hello");
        StringBuilder longText=new StringBuilder("嗯，");
        for(int index=0;index<8192;index++)longText.append('长');
        assertText(concise,longText.toString(),longText.toString());

        Class<?> preferences=Class.forName(
                "org.zapime.android.VoiceTextCleanupPreferences",true,loader);
        Method enabled=preferences.getDeclaredMethod("enabled",Context.class);
        Method setEnabled=preferences.getDeclaredMethod("setEnabled",Context.class,boolean.class);
        enabled.setAccessible(true);
        setEnabled.setAccessible(true);
        boolean before=(Boolean)enabled.invoke(null,target);
        try {
            setEnabled.invoke(null,target,false);
            require(!(Boolean)enabled.invoke(null,target),"voice_cleanup_opt_out_rejected");
            setEnabled.invoke(null,target,true);
            require((Boolean)enabled.invoke(null,target),"voice_cleanup_opt_in_rejected");
            setEnabled.invoke(null,target,false);
            require(!(Boolean)enabled.invoke(null,target),"voice_cleanup_switch_not_reversible");
            // A changed local default must never implicitly commit a preview.
            // Existing explicit VoiceCommitProbe separately validates the
            // exact-once InputConnection transaction.
        } finally {
            setEnabled.invoke(null,target,before);
        }
        require((Boolean)enabled.invoke(null,target)==before,
                "voice_cleanup_preference_restore_failed");
        exerciseSettingsUi(instrumentation,target,enabled,setEnabled);
    }
}
