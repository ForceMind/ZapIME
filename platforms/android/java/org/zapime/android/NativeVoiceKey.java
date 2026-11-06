// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Optional VoiceKey WeChat offline engine. Never loads in default X-ASR mode. */
final class NativeVoiceKey {
    static { System.loadLibrary("zapime_voicekey_adapter"); }

    static native long allocate();
    static native byte[] start(long handle, byte[] model, byte[] unusedRuntime);
    static native byte[] accept(long handle, short[] pcm, int count);
    static native byte[] finish(long handle);
    static native void cancel(long handle);
    static native boolean close(long handle);
    static native boolean checkModel(String dir);
    static native boolean releaseCache();

    static JSONObject result(byte[] bytes) throws Exception {
        if (bytes == null) throw new java.io.IOException("voicekey_native_unavailable");
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
}
