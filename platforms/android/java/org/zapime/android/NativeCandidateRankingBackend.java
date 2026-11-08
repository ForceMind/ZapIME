// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import java.io.File;

/** Resource verification, loading and all ordinary JNI calls stay on the scorer. */
final class NativeCandidateRankingBackend implements CandidateRankingWorker.Backend {
    private final Context context;
    private volatile long handle;
    private volatile boolean cancelled;
    private boolean unavailable;

    NativeCandidateRankingBackend(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public float[] score(String before, String[] candidates) throws Exception {
        if (cancelled || unavailable) return null;
        try {
            if (handle == 0) {
                File model = ResourceInstaller.prepareCandidateRanking(context);
                if (cancelled) return null;
                File runtime = new File(context.getApplicationInfo().nativeLibraryDir, "libonnxruntime.so");
                handle = NativeCandidateRanker.open(model.getAbsolutePath(), runtime.getAbsolutePath());
                if (handle == 0) { unavailable = true; return null; }
            }
            return cancelled ? null : NativeCandidateRanker.score(handle, before, candidates);
        } catch (Exception | LinkageError failure) {
            // Avoid repeated model I/O or library loading on every key after a
            // failure. Hiding/reopening or disabling/enabling releases this
            // backend and permits a fresh verified load of the pinned version.
            unavailable = true;
            throw failure;
        }
    }

    @Override public void clear() {
        long current = handle;
        if (current != 0 && !NativeCandidateRanker.clear(current)) close();
        cancelled = false;
    }

    @Override public void cancel() {
        cancelled = true;
        long current = handle;
        if (current != 0) NativeCandidateRanker.cancel(current);
    }

    @Override public void close() {
        long current = handle;
        // cancel is the only concurrent JNI operation; the native registry
        // validates handles, so a racing cancellation cannot reuse freed state.
        handle = 0;
        try { if (current != 0) NativeCandidateRanker.close(current); }
        finally { unavailable = false; cancelled = false; }
    }
}
