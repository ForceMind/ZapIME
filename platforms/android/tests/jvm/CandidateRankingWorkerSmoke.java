// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class CandidateRankingWorkerSmoke {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void await(CountDownLatch latch) throws Exception { check(latch.await(3,TimeUnit.SECONDS)); }
    private static CandidateRankingWorker.Request request(long serial) {
        return new CandidateRankingWorker.Request(7,serial,serial,8,"合成",
                new String[]{"engine:0","engine:1","engine:2"},new String[]{"甲","乙","丙"},2000);
    }
    private static final class Callbacks implements Executor {
        final BlockingQueue<Runnable> tasks=new LinkedBlockingQueue<>();
        public void execute(Runnable task){tasks.add(task);}
        void runNext() throws Exception {Runnable task=tasks.poll(3,TimeUnit.SECONDS);check(task!=null);task.run();}
    }
    private static class Backend implements CandidateRankingWorker.Backend {
        volatile Thread owner;
        final AtomicInteger clears=new AtomicInteger(),closes=new AtomicInteger(),cancels=new AtomicInteger(),calls=new AtomicInteger();
        final CountDownLatch scored=new CountDownLatch(1);
        volatile String[] received;
        void owns(){if(owner==null)owner=Thread.currentThread();check(owner==Thread.currentThread());}
        public float[] score(String context,String[] candidates)throws Exception{owns();received=candidates;calls.incrementAndGet();scored.countDown();return new float[]{1,3,3};}
        public void clear(){owns();clears.incrementAndGet();}
        public void close(){owns();closes.incrementAndGet();}
        public void cancel(){cancels.incrementAndGet();}
    }
    private static final class BlockingBackend extends Backend {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),last=new CountDownLatch(1);
        final AtomicInteger running=new AtomicInteger(),maximum=new AtomicInteger();
        final List<String> seen=Collections.synchronizedList(new ArrayList<>());
        @Override public float[] score(String context,String[] candidates)throws Exception{
            owns();int current=running.incrementAndGet();maximum.set(Math.max(maximum.get(),current));
            int call=calls.incrementAndGet();seen.add(context);
            try{if(call==1){entered.countDown();await(release);}else last.countDown();return new float[]{1,3,3};}
            finally{running.decrementAndGet();}
        }
    }
    private static CandidateRankingWorker.Request withContext(long serial,String context){
        return new CandidateRankingWorker.Request(7,serial,serial,serial,context,
            new String[]{"engine:0","engine:1","engine:2"},new String[]{"甲","乙","丙"},2000);
    }

    private static void stableAndImmutable()throws Exception{
        Backend backend=new Backend();Callbacks callbacks=new Callbacks();List<String[]> result=new ArrayList<>();
        CandidateRankingWorker worker=new CandidateRankingWorker(backend,callbacks,(request,ids,state)->{check(state.equals("ready"));result.add(ids);});
        try{
            String[] ids={"engine:0","engine:1","engine:2"},texts={"甲","乙","丙"};
            CandidateRankingWorker.Request request=new CandidateRankingWorker.Request(1,2,3,4,"",ids,texts,2000);
            ids[0]="changed";texts[0]="changed";
            worker.submit(request);callbacks.runNext();
            check(Arrays.equals(result.get(0),new String[]{"engine:1","engine:2","engine:0"}));
            check(backend.received[0].equals("甲"));
            check(backend.owner!=Thread.currentThread());
        }finally{worker.close();check(worker.awaitTermination(3000));}
    }

    private static void onlyLatestPending()throws Exception{
        BlockingBackend backend=new BlockingBackend();Callbacks callbacks=new Callbacks();List<Long> delivered=new ArrayList<>();
        CandidateRankingWorker worker=new CandidateRankingWorker(backend,callbacks,(request,ids,state)->delivered.add(request.inputRevision));
        try{
            worker.submit(withContext(1,"一"));await(backend.entered);
            worker.submit(withContext(2,"二"));worker.submit(withContext(3,"三"));
            check(backend.calls.get()==1&&backend.cancels.get()>=1);
            backend.release.countDown();await(backend.last);callbacks.runNext();
            check(backend.seen.equals(Arrays.asList("一","三")));
            check(delivered.equals(Arrays.asList(3L))&&backend.maximum.get()==1);
            check(backend.clears.get()>=2);
        }finally{backend.release.countDown();worker.close();check(worker.awaitTermination(3000));}
    }

    private static void lateCallbackAndPhysicalShutdown()throws Exception{
        Backend backend=new Backend();Callbacks callbacks=new Callbacks();AtomicInteger delivered=new AtomicInteger();
        CandidateRankingWorker worker=new CandidateRankingWorker(backend,callbacks,(request,ids,state)->delivered.incrementAndGet());
        worker.submit(request(1));await(backend.scored);
        // Wait until the completion has crossed the executor boundary, then
        // change editor/privacy. A posted callback still must not be delivered.
        Runnable callback=callbacks.tasks.poll(3,TimeUnit.SECONDS);check(callback!=null);
        worker.invalidate(true,true);callback.run();check(delivered.get()==0);
        worker.close();check(worker.awaitTermination(3000));

        BlockingBackend blocking=new BlockingBackend();Callbacks pending=new Callbacks();
        CandidateRankingWorker closing=new CandidateRankingWorker(blocking,pending,(request,ids,state)->delivered.incrementAndGet());
        try{
            closing.submit(request(2));await(blocking.entered);closing.close();
            check(blocking.cancels.get()>0&&!closing.awaitTermination(30));
            check(blocking.running.get()==1); // close requested is not physical exit.
            blocking.release.countDown();check(closing.awaitTermination(3000));
            check(pending.tasks.isEmpty()&&delivered.get()==0);
        }finally{blocking.release.countDown();closing.close();check(closing.awaitTermination(3000));}
    }

    private static void failuresAndBounds()throws Exception{
        Callbacks callbacks=new Callbacks();List<String> states=new ArrayList<>();
        Backend invalid=new Backend(){@Override public float[] score(String c,String[] t){owns();return new float[]{1,Float.NaN,2};}};
        CandidateRankingWorker worker=new CandidateRankingWorker(invalid,callbacks,(request,ids,state)->{check(ids==null);states.add(state);});
        try{worker.submit(request(1));callbacks.runNext();check(states.equals(Arrays.asList("unavailable")));}
        finally{worker.close();check(worker.awaitTermination(3000));}
        boolean rejected=false;
        try{new CandidateRankingWorker.Request(1,2,3,4,"",new String[]{"x","x"},new String[]{"甲","乙"},100);}
        catch(IllegalArgumentException expected){rejected=true;}check(rejected);
        rejected=false;
        try{new CandidateRankingWorker.Request(1,2,3,4,"\ud800",new String[]{"a","b"},new String[]{"甲","乙"},100);}
        catch(IllegalArgumentException expected){rejected=true;}check(rejected);
    }

    private static void deadlineCancelsWithoutReleasingSlot()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),cancelled=new CountDownLatch(1),physicalExit=new CountDownLatch(1);
        Backend backend=new Backend(){
            @Override public float[] score(String c,String[] t)throws Exception{
                owns();int call=calls.incrementAndGet();
                if(call==1){entered.countDown();await(physicalExit);}
                return new float[]{1,3,3};
            }
            @Override public void cancel(){super.cancel();cancelled.countDown();}
        };
        Callbacks callbacks=new Callbacks();List<String> states=new ArrayList<>();
        CandidateRankingWorker worker=new CandidateRankingWorker(backend,callbacks,(request,ids,state)->{
            if(state.equals("expired"))check(ids==null);states.add(state);
        });
        try{
            worker.submit(new CandidateRankingWorker.Request(7,1,1,8,"合成",
                new String[]{"engine:0","engine:1","engine:2"},new String[]{"甲","乙","丙"},500));
            await(entered);await(cancelled);
            check(backend.calls.get()==1&&backend.cancels.get()>0&&physicalExit.getCount()==1);
            // Timer cancellation was real, but the physical scorer still owns
            // its slot and resources until the cooperative call returns.
            physicalExit.countDown();callbacks.runNext();
            check(states.equals(Arrays.asList("expired")));
            worker.submit(request(2));callbacks.runNext();
            check(states.equals(Arrays.asList("expired","ready"))&&backend.clears.get()>=2);
        }finally{physicalExit.countDown();worker.close();check(worker.awaitTermination(3000));}

        Backend quick=new Backend();Callbacks delayed=new Callbacks();List<String> delayedStates=new ArrayList<>();
        CandidateRankingWorker slowMain=new CandidateRankingWorker(quick,delayed,(request,ids,state)->{check(ids==null);delayedStates.add(state);});
        try{
            CandidateRankingWorker.Request shortRequest=new CandidateRankingWorker.Request(1,2,3,4,"",
                new String[]{"engine:0","engine:1","engine:2"},new String[]{"甲","乙","丙"},100);
            slowMain.submit(shortRequest);
            Runnable callback=delayed.tasks.poll(3,TimeUnit.SECONDS);check(callback!=null);
            while(!shortRequest.expired())Thread.sleep(5);
            callback.run();check(delayedStates.equals(Arrays.asList("expired")));
        }finally{slowMain.close();check(slowMain.awaitTermination(3000));}
    }

    public static void main(String[] args)throws Exception{
        stableAndImmutable();onlyLatestPending();lateCallbackAndPhysicalShutdown();failuresAndBounds();deadlineCancelsWithoutReleasingSlot();
        System.out.println("Candidate ranking worker smoke passed");
    }
}
