// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

/** Owner-thread-only assembly of accepted keyboard commits. No editor reads or IO. */
final class EnglishLearningSession {
    static final long CONTEXT_GAP_MS = 10_000;
    private final EnglishLearningGate gate = new EnglishLearningGate();
    private boolean enabled, eligible, closed, discarding;
    private String modelVersion, snippet = "";
    private long lastCommit = -1;
    private Ticket context = new Ticket();

    /** Opaque provenance captured before queuing a keyboard operation. */
    static final class Ticket {
        private Ticket() {}
        @Override public String toString() { return "LearningContext{redacted}"; }
    }

    void setEnabled(boolean value) {
        gate.setEnabled(value);
        if (enabled != value) { enabled = value; invalidate(); }
    }

    void setModelVersion(String version) {
        gate.setModelVersion(version); // Validate before changing the assembly state.
        if (modelVersion == null ? version != null : !modelVersion.equals(version)) {
            modelVersion = version; invalidate();
        }
    }

    /** Every editor start/restart and every newly shown input view starts afresh. */
    void beginEditor(boolean allowed) {
        eligible = allowed; gate.beginEditor(allowed); invalidate();
    }

    /** Hide/focus loss disables ingestion until a newly verified input view. */
    void suspend() { beginEditor(false); }

    void invalidate() {
        context = new Ticket(); snippet = ""; lastCommit = -1;
        closed = false; discarding = false; gate.invalidate();
    }

    Ticket capture() { return enabled && eligible && modelVersion != null ? context : null; }

    /**
     * Call only after commitText returns, with the token captured at enqueue time.
     * A stale or absent token cannot clear a newer sentence. Rejected current
     * writes invalidate the sentence; a true result is acceptance, not a claim
     * that an arbitrary remote editor stored the identical text.
     */
    void committed(Ticket ticket, String fragment, boolean accepted, long monotonicMs) {
        if (ticket == null || ticket != context || capture() == null) return;
        if (!accepted || monotonicMs < 0 || fragment == null || fragment.isEmpty()
                || (lastCommit >= 0 && monotonicMs < lastCommit)) {
            invalidate(); return;
        }
        if (fragment.length() > EnglishLearningGate.MAX_SOURCE_UNITS) {
            snippet = ""; closed = false; discarding = true;
            lastCommit = monotonicMs; gate.invalidate(); return;
        }
        if (!EnglishLearningGate.validFragment(fragment)) { invalidate(); return; }
        boolean boundary = EnglishLearningGate.sentenceEnd(fragment);
        if (lastCommit >= 0 && monotonicMs - lastCommit > CONTEXT_GAP_MS) {
            snippet = ""; closed = false; discarding = false; gate.invalidate();
        }
        if (discarding) {
            lastCommit = monotonicMs;
            if (boundary) { discarding = false; snippet = ""; closed = false; }
            return;
        }
        if (closed && hasLetterOrDigit(fragment)) {
            snippet = ""; closed = false; gate.invalidate();
        }
        lastCommit = monotonicMs;
        // Never truncate into a misleading tail or retain an oversized fragment.
        if (fragment.length() > EnglishLearningGate.MAX_SOURCE_UNITS - snippet.length()) {
            snippet = ""; closed = false; discarding = !boundary; gate.invalidate(); return;
        }
        snippet += fragment;
        closed |= boundary;
        gate.offerCommittedSnippet(snippet, monotonicMs);
    }

    EnglishLearningGate.Request takeReady(long monotonicMs) { return gate.takeReady(monotonicMs); }
    boolean complete(EnglishLearningGate.Request request, String english) { return gate.complete(request, english); }
    EnglishLearningGate.Pair visiblePair() { return gate.visiblePair(); }
    boolean isCurrent(EnglishLearningGate.Pair pair) { return gate.isCurrent(pair); }

    private static boolean hasLetterOrDigit(String text) {
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    @Override public String toString() { return "EnglishLearningSession{redacted}"; }
}
