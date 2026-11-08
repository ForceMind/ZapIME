// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class VerifiedRestartSmoke {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static VerifiedRestart.Expected expected(){return new VerifiedRestart.Expected("shi","你好"," after",15);}
    private static VerifiedRestart.Snapshot valid(){return new VerifiedRestart.Snapshot("你好shi after",10,5,5);}
    public static void main(String[] args)throws Exception {
        VerifiedRestart.Visibility visibility=new VerifiedRestart.Visibility();check(!visibility.visible());
        visibility.shown();check(visibility.visible());visibility.hidden();check(!visibility.visible());
        visibility.shown();check(visibility.visible());
        VerifiedRestart.Expected expected=expected();check(expected.matches(valid()));
        for(VerifiedRestart.Snapshot invalid:new VerifiedRestart.Snapshot[]{null,
                new VerifiedRestart.Snapshot("你好shi after",-1,5,5),
                new VerifiedRestart.Snapshot("你好shi after",9,5,5),
                new VerifiedRestart.Snapshot("你好shi after",10,4,5),
                new VerifiedRestart.Snapshot("你好shj after",10,5,5),
                new VerifiedRestart.Snapshot("再见shi after",10,5,5),
                new VerifiedRestart.Snapshot("你好shi changed",10,5,5),
                new VerifiedRestart.Snapshot("你好shi after",Integer.MAX_VALUE,5,5),
                new VerifiedRestart.Snapshot(new String(new char[289]),10,5,5)})check(!expected.matches(invalid));
        // Both proof/barrier arrival orders preserve old replies before newer
        // operations, including an old direct insert which bypassed core send.
        for(boolean proofFirst:new boolean[]{true,false}){
            VerifiedRestart gate=new VerifiedRestart(expected,2);List<Integer> order=new ArrayList<>();
            check(gate.hold(false,()->order.add(3),1));
            if(proofFirst)gate.proof();else gate.barrier();check(!gate.ready());
            check(gate.hold(true,()->order.add(1),1));check(gate.hold(true,()->order.add(2),1));
            check(gate.hold(false,()->order.add(4),1));
            if(proofFirst)gate.barrier();else gate.proof();check(gate.ready());
            VerifiedRestart.Release released=gate.consume();check(released!=null&&!gate.pending()&&gate.consume()==null);
            check(!gate.hold(false,()->order.add(9),1)); // Consumed before a reentrant setter.
            check(released.holdNewOperation(()->order.add(5),1));
            check(released.drainReplies(()->true));for(Runnable op:released.operations())op.run();
            check(order.toString().equals("[1, 2, 3, 4, 5]"));
        }
        VerifiedRestart cancelled=new VerifiedRestart(expected,1);AtomicInteger writes=new AtomicInteger();
        cancelled.hold(true,writes::incrementAndGet,1);cancelled.hold(false,writes::incrementAndGet,1);
        cancelled.reject();cancelled.proof();cancelled.barrier();check(cancelled.consume()==null&&writes.get()==0);
        // A same-editor reentrant restart explicitly retires the consumed
        // handshake. Its second old reply and held new operation must not write.
        VerifiedRestart reentrant=new VerifiedRestart(expected,1);AtomicBoolean current=new AtomicBoolean(true);
        reentrant.hold(true,()->{writes.incrementAndGet();current.set(false);},1);
        reentrant.hold(true,writes::incrementAndGet,1);reentrant.hold(false,writes::incrementAndGet,1);
        reentrant.proof();reentrant.barrier();VerifiedRestart.Release interrupted=reentrant.consume();
        check(!interrupted.drainReplies(current::get)&&writes.get()==1);
        VerifiedRestart count=new VerifiedRestart(expected,1);for(int i=0;i<VerifiedRestart.MAX_HELD;i++)check(count.hold(i%2==0,()->{},1));
        check(!count.hold(false,()->{},1));count.reject();
        VerifiedRestart bytes=new VerifiedRestart(expected,1);check(bytes.hold(true,()->{},VerifiedRestart.MAX_UNITS));check(!bytes.hold(false,()->{},1));bytes.reject();
        // A predicted tail can be ahead of the last onUpdateSelection echo.
        SelectionEchoTracker echoes=new SelectionEchoTracker();echoes.reset(2,2);echoes.compose(1);echoes.expect();echoes.compose(2);echoes.expect();
        check(echoes.ownedCompositionEnd(2)==4);check(echoes.acknowledge(3,3,2,3));check(echoes.ownedCompositionEnd(2)==4);
        check(echoes.acknowledge(4,4,2,4));check(echoes.ownedCompositionEnd(2)==4);
        check(echoes.matchesCurrentSelection(4,4,2,4));check(!echoes.matchesCurrentSelection(3,3,2,4));
        check(!echoes.acknowledge(3,3,-1,-1));check(echoes.ownedCompositionEnd(2)==-1);

        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),callback=new CountDownLatch(1);
        VerifiedRestart timed=new VerifiedRestart(expected,1);AtomicInteger reads=new AtomicInteger();
        AtomicReference<Runnable> delayedCallback=new AtomicReference<>();
        check(VerifiedRestart.submit(timed,()->{reads.incrementAndGet();check(Thread.currentThread().getName().equals("zapime-restart-verification"));entered.countDown();release.await();return valid();},
                task->{delayedCallback.set(task);callback.countDown();},ok->{if(ok)timed.proof();}));
        check(entered.await(2,TimeUnit.SECONDS));Thread.sleep(VerifiedRestart.BUDGET_MS+30);check(timed.expired());timed.reject();
        for(int i=0;i<100;i++)check(!VerifiedRestart.submit(new VerifiedRestart(expected,1),()->{reads.incrementAndGet();return valid();},Runnable::run,ok->{}));
        check(reads.get()==1);release.countDown();check(callback.await(2,TimeUnit.SECONDS));delayedCallback.get().run();check(!timed.ready());
        AtomicReference<Runnable> scheduled=new AtomicReference<>();AtomicInteger cancelledReads=new AtomicInteger();
        VerifiedRestart preCancelled=new VerifiedRestart(expected,1);
        check(VerifiedRestart.submit(preCancelled,()->{cancelledReads.incrementAndGet();return valid();},Runnable::run,ok->check(!ok),scheduled::set));
        preCancelled.reject();scheduled.get().run();check(cancelledReads.get()==0);
        VerifiedRestart preExpired=new VerifiedRestart(expected,1);
        check(VerifiedRestart.submit(preExpired,()->{cancelledReads.incrementAndGet();return valid();},Runnable::run,ok->check(!ok),scheduled::set));
        Thread.sleep(VerifiedRestart.BUDGET_MS+30);scheduled.get().run();check(cancelledReads.get()==0);
        CountDownLatch next=new CountDownLatch(1);check(VerifiedRestart.submit(new VerifiedRestart(expected,1),()->valid(),Runnable::run,ok->{check(ok);next.countDown();}));
        check(next.await(2,TimeUnit.SECONDS));
        System.out.println("PASS verified restart: exact absolute proof, prefix/suffix, ordered barrier release, bounded hold, stale rejection and process physical capacity after timeout");
    }
}
