// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.app.Instrumentation;
import android.content.Context;
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
        assertText(concise,"嗯，"+"长".repeat(8192),"嗯，"+"长".repeat(8192));

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
    }
}
