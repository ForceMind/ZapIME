// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

/**
 * Ephemeral context for one verified editor session. No history, persistence or
 * network access. All methods are owned by the IME main thread.
 */
final class CandidateRankingContext {
    static final int MAX_CODE_POINTS = 64;
    static final int MAX_INITIAL_UNITS = MAX_CODE_POINTS * 2;

    private long version;
    private boolean allowed;
    private String text = "";

    void begin(boolean eligible, String initialBefore, int start, int end) {
        ++version;
        allowed = eligible && start >= 0 && start == end;
        text = allowed && initialBefore != null && initialBefore.length() <= MAX_INITIAL_UNITS
                ? tail(initialBefore) : "";
    }

    void clear() {
        ++version;
        text = "";
    }

    void suspend() {
        clear();
        allowed = false;
    }

    /** An external/unknown edit starts a new, empty chain at a known caret. */
    void rebase(boolean eligible, int start, int end) {
        begin(eligible, null, start, end);
    }

    /**
     * Only user typing accepted by commitText belongs here. That return value
     * is not a final remote-editor receipt: a non-own selection echo clears it.
     * Saved snippets, voice, paste, rejected writes and message boundaries clear
     * the chain instead of retaining unrelated text.
     */
    void committed(String value, boolean accepted, boolean typed) {
        if (!allowed || !accepted || !typed || value == null || value.isEmpty()
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || !validUtf16(value)) {
            clear();
            return;
        }
        ++version;
        text = tail(text + tail(value));
    }

    long version() { return version; }
    boolean active() { return allowed; }
    // The LM's unconditional word probabilities are not a replacement for the
    // dictionary's base frequencies. No verified context means base order.
    boolean hasContext() { return allowed && !text.isEmpty(); }
    String text() { return allowed ? text : ""; }

    private static String tail(String value) {
        if (!validUtf16(value)) return "";
        // An initial snapshot may be multiline, but never carry an earlier
        // message/line across the latest explicit boundary.
        int boundary = Math.max(value.lastIndexOf('\n'), value.lastIndexOf('\r'));
        String current = boundary >= 0 ? value.substring(boundary + 1) : value;
        int count = current.codePointCount(0, current.length());
        return count <= MAX_CODE_POINTS ? current
                : current.substring(current.offsetByCodePoints(0, count - MAX_CODE_POINTS));
    }

    static boolean validUtf16(String value) {
        for (int i = 0; i < value.length(); ++i) {
            char unit = value.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(unit)) return false;
        }
        return true;
    }
}
