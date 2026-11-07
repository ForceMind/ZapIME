// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
final class NativeCore {
    static { System.loadLibrary("zapime_core"); }
    static native long open(String root, boolean privateMode);
    static native String request(long handle, String json);
    static native String queryPersonal(String root, String json);
    static native String syncProfile(String root);
    // Stateless sync-worker operations; never borrow a keyboard InputSession.
    static native byte[] newSyncMaterial(String account);
    static native String syncMaterialInfo(String account, byte[] material);
    static native String syncWorkspaceStatus(String root, String account, byte[] material);
    static native String prepareSync(String root, String account, byte[] material);
    static native byte[] createSyncRecovery(String account, byte[] material, byte[] codeOut);
    static native byte[] restoreSyncRecovery(String account, byte[] code, byte[] encrypted);
    static native byte[] createLocalBackup(String root, byte[] codeOut);
    static native String restoreLocalBackup(String root, byte[] code, byte[] encrypted);
    static native void close(long handle);
}
