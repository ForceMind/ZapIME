// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.test;

/** Test-only proof of a stable tools panel; missing observations never pass. */
final class ToolsPanelObservation {
    static final long BUDGET_MS = 8000;
    static final long STABLE_MS = 500;
    static final int MIN_SAMPLES = 10;

    enum Outcome { WAITING, STABLE, WRONG_PANEL, HOST_CHANGED, TIMED_OUT, CLOCK_INVALID }

    private final long started;
    private long last, stableSince = -1;
    private int samples;
    private Outcome outcome = Outcome.WAITING;

    ToolsPanelObservation(long now) { started = now; last = now; }

    Outcome observe(long now, boolean originalHost, boolean lettersVisible,
                    boolean complete, boolean sameSnapshot) {
        if (outcome != Outcome.WAITING) return outcome;
        if (now < started || now < last) return outcome = Outcome.CLOCK_INVALID;
        last = now;
        if (!originalHost) return outcome = Outcome.HOST_CHANGED;
        // A visible letter layout is positive contrary evidence, even during
        // rebuilding. Never wait for the product to change back to tools.
        if (lettersVisible) return outcome = Outcome.WRONG_PANEL;
        if (now - started >= BUDGET_MS) return outcome = Outcome.TIMED_OUT;
        if (!complete) {
            stableSince = -1;
            samples = 0;
            return outcome;
        }
        if (stableSince < 0 || !sameSnapshot) {
            stableSince = now;
            samples = 1;
        } else {
            ++samples;
        }
        if (samples >= MIN_SAMPLES && now - stableSince >= STABLE_MS)
            outcome = Outcome.STABLE;
        return outcome;
    }

    int samples() { return samples; }
    long stableMillis(long now) { return stableSince < 0 ? 0 : now - stableSince; }
}
