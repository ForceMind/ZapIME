// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;

/** Local preference only. No telemetry, cloud rewrite, or automatic commit. */
final class VoiceTextCleanupPreferences {
    private static final String FILE="voice_text_cleanup";
    private static final String KEY="concise_preview";
    private VoiceTextCleanupPreferences(){}
    // Opt-in until real-device quality evaluation is complete.
    static boolean enabled(Context context){
        return context.getSharedPreferences(FILE,Context.MODE_PRIVATE).getBoolean(KEY,false);
    }
    static void setEnabled(Context context,boolean enabled){
        context.getSharedPreferences(FILE,Context.MODE_PRIVATE).edit().putBoolean(KEY,enabled).apply();
    }
}
