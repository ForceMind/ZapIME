// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

public final class CompositionTraceSmoke {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        CompositionTrace trace = new CompositionTrace();
        check("[]".equals(trace.snapshot()));
        trace.note(CompositionTrace.Event.INPUT_NEW, 1, 100);
        String first = trace.snapshot();
        trace.note(CompositionTrace.Event.APPLY_PREEDIT, 1, 102);
        trace.note(CompositionTrace.Event.SELECTION_EXTERNAL, 1, 103);
        trace.note(CompositionTrace.Event.ABANDON_COMPOSITION, 1, 104);
        trace.note(CompositionTrace.Event.APPLY_FINISH_EMPTY, 2, 105);
        check(first.equals("[1@100:INPUT_NEW:1]"));
        check(trace.snapshot().equals("[1@100:INPUT_NEW:1, 2@102:APPLY_PREEDIT:1, 3@103:SELECTION_EXTERNAL:1, 4@104:ABANDON_COMPOSITION:1, 5@105:APPLY_FINISH_EMPTY:2]"));
        // The trace must distinguish an in-place abandonment from the ordinary
        // empty-reply finish path, without accepting text or an arbitrary code.
        for (java.lang.reflect.Method method : CompositionTrace.class.getDeclaredMethods())
            for (Class<?> parameter : method.getParameterTypes())
                check(parameter != String.class && parameter != CharSequence.class && parameter != Object.class);
        boolean rejected = false;
        try { trace.note(null, 2, 106); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && trace.snapshot().contains("5@105:APPLY_FINISH_EMPTY:2"));
        for (int i = 0; i < 100; ++i) trace.note(CompositionTrace.Event.REPLY_APPLY, 3, 200 + i);
        String bounded = trace.snapshot();
        check(bounded.startsWith("[42@236:REPLY_APPLY:3, "));
        check(bounded.endsWith("105@299:REPLY_APPLY:3]"));
        check(bounded.split(", ").length == CompositionTrace.CAPACITY);
        check(!bounded.contains("INPUT_NEW") && first.equals("[1@100:INPUT_NEW:1]"));
        CompositionTrace replies = new CompositionTrace();
        replies.noteRejectedEpoch(CompositionTrace.ReplyPath.INPUT, 4, 4, 400);
        replies.noteRejectedEpoch(CompositionTrace.ReplyPath.PERSONAL, 4, 4, 401);
        check("[]".equals(replies.snapshot()));
        // The callers' original epoch guards remain the admission authority;
        // this observer records both paths even when they reject above apply.
        replies.noteRejectedEpoch(CompositionTrace.ReplyPath.INPUT, 3, 4, 402);
        replies.noteRejectedEpoch(CompositionTrace.ReplyPath.PERSONAL, 3, 4, 403);
        check(replies.snapshot().equals("[1@402:REPLY_STALE:4, 2@403:PERSONAL_REPLY_STALE:4]"));
        check(replies.snapshot().split(", ").length == 2);
        System.out.println("PASS bounded fixed-code composition trace: ordering, immutable snapshots, distinct finish causes and no text-bearing API");
    }
}
