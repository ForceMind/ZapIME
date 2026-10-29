// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.util.concurrent.*;
/** Process-wide bound: one active task and one pending; never the input/sync worker. */
final class BackupWorker {
    static final ThreadPoolExecutor EXECUTOR=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(1),r->new Thread(r,"zapime-local-backup"),new ThreadPoolExecutor.AbortPolicy());
    private BackupWorker(){}
}
