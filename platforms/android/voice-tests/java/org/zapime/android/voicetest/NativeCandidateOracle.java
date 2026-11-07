// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

/** Test-only raw ORT execution of frozen tensors made by upstream feed builders. */
final class NativeCandidateOracle {
    static { System.loadLibrary("zapime_candidate_oracle"); }

    static native String evaluate(String absoluteModelPath, String absoluteRuntimePath, String fixtureJson);

    private NativeCandidateOracle() { throw new AssertionError(); }
}
