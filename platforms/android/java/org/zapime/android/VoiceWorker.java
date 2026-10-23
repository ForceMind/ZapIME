// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.util.concurrent.*;
/** Separate from both keyboard input and SQLite readers. Models never load on a UI thread. */
final class VoiceWorker {
    static volatile boolean nativeLoaded;
    static final ScheduledExecutorService EXECUTOR=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"zapime-voice");t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private VoiceWorker(){}
}
