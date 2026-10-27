// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.os.Looper;
import android.view.inputmethod.InputConnection;

/** One explicit confirmation, owned by the IME main thread. No automatic retry. */
final class VoiceCommitTransaction {
    enum Outcome { COMMITTED, REJECTED, INVALID, ALREADY_FINISHED }

    static final class Editor {
        final InputConnection connection;
        final int ticket, epoch, selectionVersion, start, end;
        final boolean allowed;
        Editor(InputConnection connection, int ticket, int epoch, int selectionVersion,
                int start, int end, boolean allowed) {
            this.connection=connection;this.ticket=ticket;this.epoch=epoch;
            this.selectionVersion=selectionVersion;this.start=start;this.end=end;this.allowed=allowed;
        }
        boolean eligible() { return allowed && connection!=null && start>=0 && start==end; }
    }

    private Editor origin;
    private String before, after, text;
    private boolean finished;

    VoiceCommitTransaction(Editor origin, String before, String after, String text) {
        requireMain();
        if(origin==null || !origin.eligible() || before==null || after==null
                || before.length()>128 || after.length()>128 || text==null
                || text.trim().isEmpty() || text.length()>65536) throw new IllegalArgumentException("voice_confirmation");
        this.origin=origin;this.before=before;this.after=after;this.text=text;
    }

    Outcome finish(Editor current, String[] context, Runnable endSession) {
        requireMain();
        if(finished) return Outcome.ALREADY_FINISHED;
        finished=true;
        InputConnection target=origin.connection;
        String confirmed=text;
        boolean valid=current!=null && current.eligible()
                && target==current.connection && origin.ticket==current.ticket
                && origin.epoch==current.epoch && origin.selectionVersion==current.selectionVersion
                && origin.start==current.start && origin.end==current.end
                && context!=null && context.length==2
                && before.equals(context[0]) && after.equals(context[1]);
        clear();
        // Consume before calling the editor: it can reenter selection/lifecycle
        // callbacks. Restore the UI afterward, so restoration cannot invalidate
        // the checked position before this one commit attempt.
        try {
            if(!valid) return Outcome.INVALID;
            try { return target.commitText(confirmed,1) ? Outcome.COMMITTED : Outcome.REJECTED; }
            catch(RuntimeException unavailable) { return Outcome.REJECTED; }
        } finally { endSession.run(); }
    }

    void cancel() { requireMain();finished=true;clear(); }
    private void clear() { origin=null;before=null;after=null;text=null; }
    private static void requireMain() {
        if(Looper.myLooper()!=Looper.getMainLooper()) throw new IllegalStateException("voice_confirmation_requires_main");
    }
}
