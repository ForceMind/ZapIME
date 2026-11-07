// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.util.concurrent.*;
/** Account I/O never uses either the keyboard or speech executor. */
final class SyncWorker {
    // Provider/Keystore calls can finish late. Never grow an unbounded backlog
    // when a page is repeatedly resumed while the real worker is still busy.
    static final ExecutorService EXECUTOR=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(8),r->{Thread t=new Thread(r,"zapime-sync-control");t.setPriority(Thread.NORM_PRIORITY-1);return t;},new ThreadPoolExecutor.AbortPolicy());
    private SyncWorker(){}
}
