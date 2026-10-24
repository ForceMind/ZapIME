// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

/**
 * Main-thread-confined policy for an optional, future local translation worker.
 * No translator, editor reads, networking, logging, persistence or UI is provided.
 * The host must pass only accepted, eligible Chinese commit snapshots and reset
 * on EVERY editor restart, even when the input engine retains its composition.
 */
public final class EnglishLearningGate {
    public static final int MAX_SOURCE_UNITS = 256;
    public static final int MAX_TARGET_UNITS = 1024;
    public static final long PAUSE_MS = 600;

    private boolean enabled, eligible;
    private String modelVersion;
    private Object session = new Object(), revision = new Object(), activeToken;
    private Pending pending;
    private Pair visible;

    private static final class Pending {
        final String source;
        final long due;
        Pending(String source, long due) { this.source = source; this.due = due; }
        @Override public String toString() { return "Pending{redacted}"; }
    }

    public static final class Request {
        private final Object token, session, revision;
        private final String source, modelVersion;
        private Request(Object session, Object revision, String source, String model) {
            token = new Object(); this.session = session; this.revision = revision;
            this.source = source; modelVersion = model;
        }
        public String source() { return source; }
        public String modelVersion() { return modelVersion; }
        @Override public String toString() { return "TranslationRequest{redacted}"; }
    }

    /** A displayed immutable pair, not an automatically saved learning record. */
    public static final class Pair {
        private final Object session, revision;
        private final String source, english, modelVersion;
        private Pair(Request request, String english) {
            session = request.session; revision = request.revision;
            source = request.source; modelVersion = request.modelVersion;
            this.english = english;
        }
        public String source() { return source; }
        public String english() { return english; }
        public String modelVersion() { return modelVersion; }
        @Override public String toString() { return "TranslationPair{redacted}"; }
    }

    public void setEnabled(boolean value) {
        if (enabled != value) { enabled = value; invalidate(); }
    }

    /** Null means unavailable. This is a caller-supplied verified model identity. */
    public void setModelVersion(String version) {
        if (version != null && (version.isEmpty() || version.length() > 128)) {
            throw new IllegalArgumentException("Invalid model identity");
        }
        if (modelVersion == null ? version != null : !modelVersion.equals(version)) {
            modelVersion = version; invalidate();
        }
    }

    /** False for private/password/no-learning/search/URI/email/numeric fields. */
    public void beginEditor(boolean allowed) {
        eligible = allowed; session = new Object(); invalidate();
    }

    /** Call on context edits, hide, focus loss, cancellation and destruction. */
    public void invalidate() {
        revision = new Object(); pending = null; visible = null;
        // Do not release the worker slot before that worker actually finishes.
        // The gate keeps only its opaque token, not the old request's plaintext.
    }

    private boolean available() { return enabled && eligible && modelVersion != null; }

    /**
     * Offers the whole current bounded snippet, not an incremental character.
     * Assembly and actual editor-acceptance checks belong to the future host.
     * New snapshots replace the single pending item and invalidate old results.
     */
    public boolean offerCommittedSnippet(String text, long monotonicMs) {
        invalidate();
        if (!available() || monotonicMs < 0 || !validText(text, MAX_SOURCE_UNITS)
                || !hasHan(text)) return false;
        long due = sentenceEnd(text) ? monotonicMs
                : monotonicMs > Long.MAX_VALUE - PAUSE_MS ? Long.MAX_VALUE : monotonicMs + PAUSE_MS;
        pending = new Pending(text, due);
        return true;
    }

    /** At most one running request; the host dispatches it on a separate worker. */
    public Request takeReady(long monotonicMs) {
        if (!available() || activeToken != null || pending == null || monotonicMs < pending.due) return null;
        Request request = new Request(session, revision, pending.source, modelVersion);
        pending = null; activeToken = request.token;
        return request;
    }

    /**
     * Post actual worker completion to the owner thread; null is failure/cancel.
     * Every leased request must finish here, including dispatch rejection.
     * A cancellation request must not release the slot while inference still runs.
     */
    public boolean complete(Request request, String english) {
        if (request == null || activeToken != request.token) return false;
        activeToken = null;
        if (!available() || request.session != session || request.revision != revision
                || !request.modelVersion.equals(modelVersion) || !validText(english, MAX_TARGET_UNITS)) return false;
        visible = new Pair(request, english);
        return true;
    }

    public Pair visiblePair() { return visible; }

    /** Validate at the explicit collect click, then save that immutable pair. */
    public boolean isCurrent(Pair pair) {
        return available() && pair != null && visible == pair
                && pair.session == session && pair.revision == revision;
    }

    static boolean validFragment(String text) {
        return validText(text, MAX_SOURCE_UNITS, false);
    }

    private static boolean validText(String text, int limit) {
        return validText(text, limit, true);
    }

    private static boolean validText(String text, int limit, boolean requireContent) {
        if (text == null || text.isEmpty() || text.length() > limit) return false;
        boolean content = false, spacing = false;
        for (int i = 0; i < text.length();) {
            char first = text.charAt(i);
            if (Character.isLowSurrogate(first)) return false;
            if (Character.isHighSurrogate(first)
                    && (i + 1 == text.length() || !Character.isLowSurrogate(text.charAt(i + 1)))) return false;
            int cp = text.codePointAt(i);
            if (Character.isISOControl(cp) || cp == 0x061c || cp == 0x200e || cp == 0x200f
                    || (cp >= 0x202a && cp <= 0x202e) || (cp >= 0x2066 && cp <= 0x2069)) return false;
            int type = Character.getType(cp);
            spacing |= Character.isWhitespace(cp) || Character.isSpaceChar(cp);
            content |= !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)
                    && type != Character.FORMAT && type != Character.NON_SPACING_MARK
                    && type != Character.ENCLOSING_MARK && type != Character.COMBINING_SPACING_MARK;
            i += Character.charCount(cp);
        }
        return content || (!requireContent && spacing);
    }

    private static boolean hasHan(String text) {
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    static boolean sentenceEnd(String text) {
        int end = text.length();
        while (end > 0) {
            int cp = text.codePointBefore(end);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) return cp == 0x3002
                    || cp == 0xff01 || cp == 0xff1f || cp == '!' || cp == '?' || cp == '.';
            end -= Character.charCount(cp);
        }
        return false;
    }
}
