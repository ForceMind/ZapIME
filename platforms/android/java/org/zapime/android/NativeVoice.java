// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Lazy loaded only on the dedicated voice worker; never starts a microphone. */
final class NativeVoice {
    static { System.loadLibrary("onnxruntime");System.loadLibrary("zapime_voice"); }
    static native long allocate();
    static native byte[] start(long handle,byte[] model,byte[] runtime);
    static native byte[] accept(long handle,short[] pcm,int count);
    static native byte[] finish(long handle);
    static native void cancel(long handle);
    static native boolean close(long handle);
    static native int release(long idleMillis);
    static JSONObject result(byte[] data)throws Exception {
        if(data==null)throw new java.io.IOException("native_voice_unavailable");
        return new JSONObject(new String(data,StandardCharsets.UTF_8));
    }
}
