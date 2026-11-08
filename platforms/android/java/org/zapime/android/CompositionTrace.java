// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

/** Bounded lifecycle evidence containing fixed events and counters, never input. */
final class CompositionTrace {
    static final int CAPACITY = 64;
    enum Event {
        INPUT_NEW, INPUT_RESTART, INPUT_RETAINED, INPUT_REBUILT, INPUT_FINISHED,
        SELECTION_ECHO, SELECTION_EXTERNAL, MARKER_WAIT, MARKER_APPLIED,
        MARKER_SETTLED_BEFORE_INPUT, ABANDON_COMPOSITION,
        REPLY_HELD, REPLY_OVERFLOW, REPLY_STALE, PERSONAL_REPLY_STALE, REPLY_APPLY,
        APPLY_ERROR, APPLY_NO_CONNECTION, APPLY_COMMIT, APPLY_PREEDIT,
        APPLY_FINISH_EMPTY, APPLY_FINISH_AFTER_COMMIT, APPLY_CLEAR_OWNED,
        RESTART_FRESH_BEGIN, RESTART_FRESH_REUSED, RESTART_FRESH_VALIDATED, RESTART_FRESH_REJECTED
    }
    private final Event[] events = new Event[CAPACITY];
    private final long[] times = new long[CAPACITY];
    private final long[] sequences = new long[CAPACITY];
    private final int[] epochs = new int[CAPACITY];
    private int next, size;
    private long sequence;
    enum ReplyPath { INPUT, PERSONAL }

    /** Observe the original caller's epoch gate without deciding admission. */
    void noteRejectedEpoch(ReplyPath path, int expectedEpoch, int actualEpoch, long uptimeMs) {
        if (path == null) throw new IllegalArgumentException("Fixed reply path required");
        if (expectedEpoch != actualEpoch)
            note(path == ReplyPath.INPUT ? Event.REPLY_STALE : Event.PERSONAL_REPLY_STALE, actualEpoch, uptimeMs);
    }

    synchronized void note(Event event, int epoch, long uptimeMs) {
        if (event == null) throw new IllegalArgumentException("Fixed composition event required");
        events[next] = event;
        epochs[next] = epoch;
        times[next] = uptimeMs;
        sequences[next] = ++sequence;
        next = (next + 1) % CAPACITY;
        size = Math.min(CAPACITY, size + 1);
    }

    synchronized String snapshot() {
        StringBuilder out = new StringBuilder("[");
        for (int offset = 0; offset < size; ++offset) {
            int index = (next - size + offset + CAPACITY) % CAPACITY;
            if (offset != 0) out.append(", ");
            out.append(sequences[index]).append('@').append(times[index])
                    .append(':').append(events[index].name()).append(':').append(epochs[index]);
        }
        return out.append(']').toString();
    }
}
