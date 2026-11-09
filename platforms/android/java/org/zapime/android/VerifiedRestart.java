// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Fresh, bounded editor proof. No Android dependency, storage, or network. */
final class VerifiedRestart {
    static final int MAX_BEFORE = 256, MAX_AFTER = 32, MAX_HELD = 64, MAX_UNITS = 65536;
    static final long BUDGET_MS = 800;
    // Includes skipped stale submissions: never replenish a ticket's budget.
    static final int MAX_READS = 2;
    // Process-wide physical capacity survives Service destruction and logical
    // timeouts. No task is queued until the previous IPC actually returns.
    private static final AtomicBoolean PHYSICAL = new AtomicBoolean();
    private static final Executor IPC = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "zapime-restart-verification");
        thread.setDaemon(true);return thread;
    });

    interface Read { Snapshot read() throws Exception; }
    interface Result { void completed(boolean valid); }
    static final class Visibility {
        private boolean shown;
        void shown(){shown=true;}
        void hidden(){shown=false;}
        boolean visible(){return shown;}
    }
    static final class Snapshot {
        final String text;final int offset,start,end;
        Snapshot(String text,int offset,int start,int end){this.text=text;this.offset=offset;this.start=start;this.end=end;}
    }
    static final class Expected {
        final String preedit,before,after;final int end;
        Expected(String preedit,String before,String after,int end){
            if(preedit==null||preedit.isEmpty()||preedit.length()>128||before==null||before.length()>128
                    ||after==null||after.length()>MAX_AFTER||end<preedit.length()+before.length()
                    ||!CandidateRankingContext.validUtf16(preedit)||!CandidateRankingContext.validUtf16(before)
                    ||!CandidateRankingContext.validUtf16(after))throw new IllegalArgumentException("restart_proof_bounds");
            this.preedit=preedit;this.before=before;this.after=after;this.end=end;
        }
        int start(){return end-preedit.length();}
        int beforeUnits(){return preedit.length()+before.length();}
        boolean matches(Snapshot value){
            if(value==null||value.text==null||value.text.length()>MAX_BEFORE+MAX_AFTER||value.offset<0
                    ||value.start<0||value.start!=value.end||value.end>value.text.length()
                    ||(long)value.offset+value.start!=end||!CandidateRankingContext.validUtf16(value.text))return false;
            return value.text.substring(0,value.start).endsWith(before+preedit)
                    &&value.text.substring(value.start).equals(after);
        }
    }

    final Expected expected;
    final long deadline;
    private final ArrayDeque<Runnable> replies=new ArrayDeque<>(),operations=new ArrayDeque<>();
    private int units;
    // Ticket/queue state stays on the serialized main callback path. Only the
    // read permit and generation cross to the physical worker before its IPC.
    private boolean pending=true,proof,barrier,readPending;
    private volatile int generation;
    private int proofGeneration=-1,readRequests;
    private final AtomicBoolean readPermit=new AtomicBoolean(true);
    int selectionVersion;

    VerifiedRestart(Expected expected,int selectionVersion){
        this.expected=expected;this.selectionVersion=selectionVersion;
        deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(BUDGET_MS);
    }
    boolean expired(){return System.nanoTime()>=deadline;}
    boolean pending(){return pending;}
    int generation(){return generation;}
    boolean readPending(){return readPending;}
    boolean currentGeneration(int value){return value==generation;}
    boolean restartNotification(){
        if(!pending||expired()||readRequests>=MAX_READS)return false;
        ++generation;proof=false;proofGeneration=-1;return true;
    }
    private int beginRead(){
        if(!pending||expired()||readPending||readRequests>=MAX_READS||!readPermit.get())return -1;
        readPending=true;++readRequests;return generation;
    }
    private void finishRead(){readPending=false;}
    private boolean mayRead(int value){return readPermit.get()&&value==generation&&!expired();}
    boolean hold(boolean reply,Runnable task,int size){
        if(!pending||size<0||size>MAX_UNITS-units||replies.size()+operations.size()>=MAX_HELD)return false;
        (reply?replies:operations).addLast(task);units+=size;return true;
    }
    void proof(){proof(generation);}
    void proof(int value){if(pending&&!readPending&&!expired()&&value==generation){proof=true;proofGeneration=value;}}
    void barrier(){if(pending)barrier=true;}
    boolean ready(){return pending&&!expired()&&!readPending&&proof&&proofGeneration==generation&&barrier;}
    void reject(){readPermit.set(false);pending=false;replies.clear();operations.clear();units=0;}
    Release consume(){
        if(!ready())return null;
        readPermit.set(false);pending=false;
        Release result=new Release(replies.toArray(new Runnable[0]),operations.toArray(new Runnable[0]),units);
        replies.clear();operations.clear();units=0;return result;
    }
    static final class Release {
        final Runnable[] replies,operations;
        private final ArrayDeque<Runnable> laterOperations=new ArrayDeque<>();
        private int units;
        Release(Runnable[] replies,Runnable[] operations,int units){this.replies=replies;this.operations=operations;this.units=units;}
        boolean holdNewOperation(Runnable task,int size){
            if(size<0||size>MAX_UNITS-units||replies.length+operations.length+laterOperations.size()>=MAX_HELD)return false;
            laterOperations.addLast(task);units+=size;return true;
        }
        Runnable[] operations(){
            Runnable[] result=new Runnable[operations.length+laterOperations.size()];
            System.arraycopy(operations,0,result,0,operations.length);int index=operations.length;
            while(!laterOperations.isEmpty())result[index++]=laterOperations.removeFirst();return result;
        }
        boolean drainReplies(BooleanSupplier current){
            for(Runnable reply:replies){if(!current.getAsBoolean())return false;reply.run();}
            return current.getAsBoolean();
        }
    }
    static boolean submit(VerifiedRestart owner,Read read,Executor callbacks,Result result){
        return submit(owner,read,callbacks,result,IPC);
    }
    static boolean submit(VerifiedRestart owner,Read read,Executor callbacks,Result result,Executor executor){
        if(!PHYSICAL.compareAndSet(false,true))return false;
        final int submitted=owner.beginRead();
        if(submitted<0){PHYSICAL.set(false);return false;}
        try{executor.execute(()->{
            boolean valid=false;
            try{if(owner.mayRead(submitted))valid=owner.expected.matches(read.read());}catch(Exception|LinkageError ignored){}
            finally{PHYSICAL.set(false);}
            final boolean proof=valid;callbacks.execute(()->{owner.finishRead();result.completed(proof&&owner.mayRead(submitted));});
        });return true;}catch(RuntimeException rejected){owner.finishRead();PHYSICAL.set(false);return false;}
    }
}
