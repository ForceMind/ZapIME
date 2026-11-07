// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** A blocked provider must not allow unlimited queued resume/retry tasks. */
public final class SyncWorkerSmoke {
    private static void check(boolean ok){if(!ok)throw new AssertionError("sync worker bound");}
    public static void main(String[] args)throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(8);
        AtomicInteger completed=new AtomicInteger();
        try{
            SyncWorker.EXECUTOR.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException stop){Thread.currentThread().interrupt();}});
            check(entered.await(5,TimeUnit.SECONDS));
            for(int n=0;n<8;n++)SyncWorker.EXECUTOR.execute(()->{completed.incrementAndGet();done.countDown();});
            boolean rejected=false;try{SyncWorker.EXECUTOR.execute(()->{throw new AssertionError("rejected task ran");});}catch(RejectedExecutionException expected){rejected=true;}
            check(rejected&&completed.get()==0);release.countDown();check(done.await(5,TimeUnit.SECONDS)&&completed.get()==8);
            Future<Integer> recovered=SyncWorker.EXECUTOR.submit(()->17);check(recovered.get(5,TimeUnit.SECONDS)==17);
            System.out.println("PASS sync worker bounded backlog, explicit rejection and actual recovery");
        }finally{release.countDown();SyncWorker.EXECUTOR.shutdownNow();check(SyncWorker.EXECUTOR.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
