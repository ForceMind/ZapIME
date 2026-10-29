// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
final class NativeCore {
    static { System.loadLibrary("zapime_core"); }
    static native long open(String root, boolean privateMode);
    static native String request(long handle, String json);
    static native String queryPersonal(String root, String json);
    static native String syncProfile(String root);
    static native byte[] createLocalBackup(String root, byte[] codeOut);
    static native String restoreLocalBackup(String root, byte[] code, byte[] encrypted);
    static native void close(long handle);
}
