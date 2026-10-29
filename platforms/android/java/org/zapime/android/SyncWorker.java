// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.util.concurrent.*;
/** Account I/O never uses either the keyboard or speech executor. */
final class SyncWorker {
    static final ExecutorService EXECUTOR=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"zapime-sync-control");t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private SyncWorker(){}
}
