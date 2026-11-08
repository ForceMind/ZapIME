// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;

/** Engine preference never implies cloud fallback or network consent. */
final class VoiceEnginePreferences {
    static final String XASR="xasr", VOICEKEY="voicekey";
    private VoiceEnginePreferences(){}
    static String selected(Context context){
        String value=context.getSharedPreferences("voice_engine",Context.MODE_PRIVATE)
            .getString("recognizer",XASR);
        return VOICEKEY.equals(value)?VOICEKEY:XASR;
    }
    static void select(Context context,String engine){
        if(!XASR.equals(engine)&&!VOICEKEY.equals(engine))
            throw new IllegalArgumentException("Unknown offline recognizer");
        context.getSharedPreferences("voice_engine",Context.MODE_PRIVATE).edit()
            .putString("recognizer",engine).apply();
    }
}
