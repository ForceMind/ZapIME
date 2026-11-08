// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

/**
 * Offline scores from the pinned lm_step model, loaded only on its worker.
 *
 * open/score/clear/close must run on the same worker thread. cancel is the sole
 * cross-thread method, is cooperative, and stays set until that worker clears
 * it. Clear on field/privacy/session changes even when the context text is the
 * same. No method chooses a UI row or commits text; the host must still verify
 * its editor epoch, composition revision and candidate IDs before applying
 * scores. Library-load failures must be caught by the host's offline fallback.
 */
final class NativeCandidateRanker {
    static { System.loadLibrary("zapime_candidate"); }

    private NativeCandidateRanker() {}

    /** Absolute verified model directory and APK libonnxruntime.so; 0 on failure. */
    static native long open(String modelDir, String runtimePath);

    /**
     * One finite score per supplied row, in the same order; null on failure.
     * Context uses its last 64 Unicode code points. Accepts 1–32 nonempty rows,
     * each at most 64 code points. Invalid UTF-16 or oversized rows are rejected,
     * never truncated into a different candidate. Unknown characters use UNK.
     */
    static native float[] score(long handle, String context, String[] candidates);

    /** Discard context/prefix tensors and clear cancellation; false for a wrong owner/handle. */
    static native boolean clear(long handle);

    /** Request termination of the current ORT run; this is not a hard kill or a close. */
    static native void cancel(long handle);

    /** Release on the owning worker; repeated/foreign closes return false. */
    static native boolean close(long handle);
}
