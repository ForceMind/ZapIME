// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.io.IOException;
import java.util.*;

/** Transport-independent ordering/budget tests. No HTTP, crypto or Android evidence. */
public final class SyncRoundSmoke {
    private static int checks;
    private static void check(boolean yes){if(!yes)throw new AssertionError("manual sync round invariant");checks++;}
    private static final class Fixture implements SyncRound.Backend {
        final List<String> trace=new ArrayList<>();final List<byte[]> issued=new ArrayList<>();
        final Deque<SyncRound.Page> pages=new ArrayDeque<>();
        int remaining,pulls,pushes,stages;boolean stopped,cancelAfterStage,identityFailure,pullFailure,pushFailure;
        int failPushAt=1,stopAfterPull;
        Fixture(int pending){remaining=pending;}
        public void check()throws IOException{trace.add("check");if(stopped)throw new IOException("cancelled");}
        public void verifyIdentity()throws IOException{trace.add("identity");if(identityFailure)throw new IOException("key_conflict");}
        public SyncRound.Page pull()throws IOException{trace.add("pull");pulls++;if(pulls==stopAfterPull)stopped=true;if(pullFailure)throw new IOException("page_rejected");return pages.isEmpty()?new SyncRound.Page(0,0,0,0,0):pages.remove();}
        public byte[] stage(){trace.add("stage");stages++;if(remaining==0)return null;byte[] frozen=new byte[]{1,2,(byte)remaining--};issued.add(frozen);if(cancelAfterStage)stopped=true;return frozen;}
        public void pushAndAcknowledge(byte[] frozen)throws IOException{trace.add("push_ack");pushes++;SyncRoundSmoke.check(issued.contains(frozen)&&frozen[0]==1);if(pushFailure&&pushes==failPushAt)throw new IOException("unconfirmed");}
        boolean zero(){for(byte[] bytes:issued)for(byte b:bytes)if(b!=0)return false;return true;}
    }
    private static SyncRound.Failure failed(Fixture fixture)throws Exception{try{SyncRound.run(fixture);throw new AssertionError("failure accepted");}catch(SyncRound.Failure expected){return expected;}}
    public static void main(String[] args)throws Exception {
        Fixture empty=new Fixture(0);SyncRound.Result idle=SyncRound.run(empty);
        check(empty.pulls==1&&empty.stages==1&&empty.pushes==0&&idle.uploaded==0);check(!idle.downloadBudgetReached&&!idle.uploadBudgetReached);
        check(empty.trace.indexOf("identity")<empty.trace.indexOf("pull")&&empty.trace.indexOf("pull")<empty.trace.indexOf("stage"));
        Fixture key=new Fixture(2);key.identityFailure=true;failed(key);check(key.pulls==0&&key.stages==0&&key.pushes==0);
        Fixture badPull=new Fixture(2);badPull.pullFailure=true;failed(badPull);check(badPull.stages==0&&badPull.pushes==0);
        Fixture catchup=new Fixture(2);catchup.pages.add(new SyncRound.Page(100,300,100,0,0));catchup.pages.add(new SyncRound.Page(200,300,100,0,0));
        SyncRound.Result partial=SyncRound.run(catchup);check(partial.downloadBudgetReached&&partial.cursor==200&&partial.head==300&&partial.applied==200);check(catchup.stages==0&&catchup.pushes==0&&catchup.pulls==2);
        Fixture success=new Fixture(2);success.pages.add(new SyncRound.Page(5,5,2,1,2));success.pages.add(new SyncRound.Page(7,7,0,2,0));
        SyncRound.Result done=SyncRound.run(success);check(done.uploaded==2&&done.applied==2&&done.echoed==3&&done.unchanged==2&&done.cursor==7&&done.head==7&&done.pages==2);check(success.pushes==2&&success.zero());
        check(success.trace.indexOf("stage")<success.trace.indexOf("push_ack"));
        Fixture bounded=new Fixture(100);SyncRound.Result limited=SyncRound.run(bounded);check(limited.uploadBudgetReached&&limited.uploaded==16&&bounded.pushes==16&&bounded.stages==16&&bounded.remaining==84);check(bounded.zero());
        Fixture cancelled=new Fixture(3);cancelled.cancelAfterStage=true;SyncRound.Failure cancel=failed(cancelled);check(cancel.completed.uploaded==0&&cancelled.stages==1&&cancelled.pushes==0&&cancelled.zero());
        Fixture unknown=new Fixture(3);unknown.pushFailure=true;SyncRound.Failure unconfirmed=failed(unknown);check(unknown.pushes==1&&unknown.stages==1&&unconfirmed.completed.uploaded==0&&unknown.zero());
        Fixture second=new Fixture(3);second.pushFailure=true;second.failPushAt=2;SyncRound.Failure afterOne=failed(second);check(afterOne.completed.uploaded==1&&second.pushes==2&&second.stages==2&&second.zero());
        Fixture invalid=new Fixture(1);invalid.pages.add(new SyncRound.Page(2,1,0,0,0));failed(invalid);check(invalid.stages==0&&invalid.pushes==0);
        Fixture later=new Fixture(1);later.pages.add(new SyncRound.Page(0,0,0,0,0));later.pages.add(new SyncRound.Page(100,300,10,0,0));later.pages.add(new SyncRound.Page(200,300,10,0,0));
        SyncRound.Result pending=SyncRound.run(later);check(pending.uploaded==1&&pending.downloadBudgetReached&&pending.cursor==200&&pending.head==300&&later.pulls==3&&later.zero());
        Fixture lateCancel=new Fixture(2);lateCancel.stopAfterPull=2;lateCancel.pages.add(new SyncRound.Page(100,300,100,0,0));lateCancel.pages.add(new SyncRound.Page(200,300,100,0,0));SyncRound.Failure committed=failed(lateCancel);check(committed.completed.applied==200&&committed.completed.cursor==200&&lateCancel.stages==0);
        Fixture alreadyCancelled=new Fixture(1);alreadyCancelled.stopped=true;failed(alreadyCancelled);check(!alreadyCancelled.trace.contains("identity")&&alreadyCancelled.pulls==0&&alreadyCancelled.stages==0);
        System.out.println("PASS manual sync ordering/budgets/cancellation: "+checks+" assertions; synthetic policy only, no transport/crypto/Android claims");
    }
}
