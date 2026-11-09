# SPDX-License-Identifier: GPL-3.0-only
"""Run actual pending admission/read callbacks with synthetic Android identities.

The real proof, physical worker/slot and held queues run on the JVM. Editor body
and main callback delivery are deterministic stand-ins, not Binder/IME evidence.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PRODUCT = ROOT / "platforms/android/java/org/zapime/android"


class PendingRestartTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = os.environ.get("JAVA_HOME")
        cls.javac = str(Path(home) / "bin/javac") if home else shutil.which("javac")
        cls.java = str(Path(home) / "bin/java") if home else shutil.which("java")
        if not cls.javac or not cls.java:
            raise RuntimeError("JDK javac/java required for pending restart regression")
        cls.temporary = tempfile.TemporaryDirectory(prefix="zapime-pending-restart-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.build = Path(cls.temporary.name)
        source = (PRODUCT / "ZapInputMethodService.java").read_text()
        begin = source.index("    private boolean sameRestartEditor(")
        end = source.index("    private boolean beginVerifiedRestart(", begin)
        methods = source[begin:end]
        same_editor = re.search(r"    private boolean sameEditor\(EditorInfo info\)\{[^\n]+\}", source).group(0)
        start = source.index("    @Override public void onStartInput(")
        begin = source.index("        if(inputLifecycle!=startingLifecycle", start)
        end = source.index("        RestartTicket priorRestart=", begin)
        admission = source[begin:end]
        harness = r'''
package org.zapime.android;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class PendingRestartBase {
    void onStartInput(PendingRestartHarness.EditorInfo info,boolean restarting){}
}
public final class PendingRestartHarness extends PendingRestartBase {
    static final class EditorInfo {
        String packageName="synthetic.editor";
        int fieldId=1,inputType=1,imeOptions;
    }
    static final class EnglishLearningEligibility {
        static boolean allows(EditorInfo info,boolean privateSession){return !privateSession&&info.imeOptions==0;}
    }
    static final class RestartTicket {
        final VerifiedRestart gate;final int epoch;final Object connection;final EditorInfo info;
        RestartTicket(VerifiedRestart gate,int epoch,Object connection,EditorInfo info){this.gate=gate;this.epoch=epoch;this.connection=connection;this.info=info;}
    }
    static final class Main {
        final ArrayDeque<Runnable> callbacks=new ArrayDeque<>();
        synchronized boolean post(Runnable task){callbacks.add(task);notifyAll();return true;}
        synchronized Runnable await()throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(callbacks.isEmpty()){
                long left=end-System.nanoTime();check(left>0);TimeUnit.NANOSECONDS.timedWait(this,left);
            }
            return callbacks.remove();
        }
    }
    static final class RestartConnectionSnapshot {
        static PendingRestartHarness host;
        static VerifiedRestart.Snapshot read(Object ignored,VerifiedRestart.Expected expected)throws Exception {
            int index=host.reads.incrementAndGet();
            VerifiedRestart.Snapshot value=new VerifiedRestart.Snapshot(host.body,0,4,4);
            if(index==2&&host.blockFollowup){host.followupStarted.countDown();check(host.allowFollowup.await(2,TimeUnit.SECONDS));}
            return value;
        }
    }
    final CompositionTrace trace=new CompositionTrace();
    final VerifiedRestart.Visibility restartVisibility=new VerifiedRestart.Visibility();
    final SelectionEchoTracker selectionEcho=new SelectionEchoTracker();
    final ArrayDeque<Object> inputReplies=new ArrayDeque<>();
    final Main main=new Main();final AtomicInteger reads=new AtomicInteger();
    final CountDownLatch followupStarted=new CountDownLatch(1),allowFollowup=new CountDownLatch(1);
    final List<Integer> order=new ArrayList<>();
    volatile String body="你好sh";boolean blockFollowup;
    RestartTicket verifiedRestart,restoringRestart;
    int epoch=3,inputStarts=1,inputLifecycle=1,selectionVersion=4;
    boolean ready=true,chinese=true,privateSession,searchActive,searchClosing;
    String preedit="sh",layer="letters",editorPackage="synthetic.editor",restartDecision="fresh_pending";
    int editorField=1,editorType=1,editorOptions;
    Object currentConnection=new Object(),editorConnection=currentConnection,pendingMarkerLoss;
    EditorInfo currentInfo=new EditorInfo(),editorInfoIdentity=currentInfo;
    int fallbacks,cancelled,writes,consumed;
    PendingRestartHarness(){
        RestartConnectionSnapshot.host=this;
        restartVisibility.shown();selectionEcho.reset(2,2);selectionEcho.compose(2);
        verifiedRestart=new RestartTicket(new VerifiedRestart(new VerifiedRestart.Expected("sh","你好","",4),selectionVersion),epoch,currentConnection,currentInfo);
    }
    Object getCurrentInputConnection(){return currentConnection;}
    EditorInfo getCurrentInputEditorInfo(){return currentInfo;}
    void traceComposition(CompositionTrace.Event event){trace.note(event,epoch,1);}
    void cancelVerifiedRestart(String reason,boolean prompt){
        if(verifiedRestart!=null){verifiedRestart.gate.reject();verifiedRestart=null;++cancelled;restartDecision=reason;}
    }
    void completeVerifiedRestart(RestartTicket ticket){
        if(verifiedRestart!=ticket||!ticket.gate.ready())return;
        VerifiedRestart.Release release=ticket.gate.consume();check(release!=null);++consumed;++writes;
        check(ticket.gate.consume()==null);verifiedRestart=null;
        check(release.drainReplies(()->true));for(Runnable operation:release.operations())operation.run();
    }
''' + same_editor + methods + r'''
    void duplicate(boolean restarting,boolean invalidFrame){
        EditorInfo info=currentInfo;
        final int startingLifecycle=++inputLifecycle;
        final Object startingConnection=getCurrentInputConnection();
        ++inputStarts;
        if(invalidFrame)++inputLifecycle;
''' + admission + r'''
        ++fallbacks;
        // The existing outer fallback explicitly retires the pending attempt.
        cancelVerifiedRestart("fresh_superseded",true);
    }
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        String scenario=args[0];PendingRestartHarness host=new PendingRestartHarness();
        RestartTicket original=host.verifiedRestart;VerifiedRestart gate=original.gate;long deadline=gate.deadline;
        check(gate.hold(true,()->host.order.add(1),1));check(gate.hold(false,()->host.order.add(3),1));
        check(gate.hold(true,()->host.order.add(2),1));
        if(!scenario.equals("proof_before_barrier"))gate.barrier();
        Runnable old=null;
        if(scenario.equals("consumed")){gate.proof();check(gate.consume()!=null);}
        else {host.requestVerifiedRestartRead(original);old=host.main.await();check(host.reads.get()==1);}
        if(scenario.equals("proof_before_barrier")){
            old.run();old=null;check(!gate.ready()&&host.writes==0&&gate.pending());
        }
        switch(scenario){
            case "body_changed":host.body="你好xx";break;
            case "cap":case "followup_selection_changed":case "followup_expired":host.blockFollowup=true;break;
            case "connection":host.currentConnection=new Object();break;
            case "info":host.currentInfo=new EditorInfo();break;
            case "identity":host.editorInfoIdentity=new EditorInfo();break;
            case "stored_connection":host.editorConnection=new Object();break;
            case "metadata":host.currentInfo.fieldId=2;break;
            case "options":host.currentInfo.imeOptions=1;break;
            case "epoch":++host.epoch;break;
            case "selection_version":++host.selectionVersion;break;
            case "preedit":host.preedit="other";break;
            case "owned":host.selectionEcho.reset(99,99);break;
            case "private":host.privateSession=true;break;
            case "search":host.searchActive=true;break;
            case "closing":host.searchClosing=true;break;
            case "layer":host.layer="tools";break;
            case "not_ready":host.ready=false;break;
            case "not_chinese":host.chinese=false;break;
            case "hidden":host.restartVisibility.hidden();break;
            case "marker_loss":host.pendingMarkerLoss=new Object();break;
            case "legacy_reply":host.inputReplies.add(new Object());break;
            case "restoring":host.restoringRestart=original;break;
            case "absent":host.verifiedRestart=null;break;
            case "expired":Thread.sleep(VerifiedRestart.BUDGET_MS+30);check(gate.expired());break;
            default:break;
        }
        host.duplicate(!scenario.equals("new_input"),scenario.equals("invalid_frame"));
        if(scenario.equals("coalesced")){host.duplicate(true,false);check(gate.generation()==2);}
        check(gate.deadline==deadline&&host.writes==0&&host.order.isEmpty());
        boolean reused=host.trace.snapshot().contains("RESTART_FRESH_REUSED");
        boolean accepted=scenario.equals("normal")||scenario.equals("coalesced")||scenario.equals("body_changed")||scenario.equals("proof_before_barrier")||scenario.equals("cap")||scenario.startsWith("followup_");
        if(accepted){
            check(reused&&host.fallbacks==0&&host.verifiedRestart==original&&gate.pending());
            check(!gate.ready());if(old!=null)old.run();
            check(host.writes==0&&host.order.isEmpty());
            if(scenario.equals("proof_before_barrier")){gate.barrier();check(!gate.ready());host.completeVerifiedRestart(original);check(host.writes==0);}
            if(scenario.equals("cap")||scenario.startsWith("followup_")){
                check(host.followupStarted.await(2,TimeUnit.SECONDS));
                check(!VerifiedRestart.submit(new VerifiedRestart(gate.expected,4),()->null,Runnable::run,ok->{}));
                if(scenario.equals("cap")){
                    host.duplicate(true,false);check(host.fallbacks==1&&host.cancelled==1&&!gate.pending());
                }else if(scenario.equals("followup_selection_changed"))++host.selectionVersion;
                else {Thread.sleep(VerifiedRestart.BUDGET_MS+30);check(gate.expired());}
                // Logical cancellation never frees the still-blocked physical slot.
                check(!VerifiedRestart.submit(new VerifiedRestart(gate.expected,4),()->null,Runnable::run,ok->{}));
                host.allowFollowup.countDown();host.main.await().run();
                check(host.reads.get()==2&&host.cancelled==1&&host.writes==0&&host.consumed==0&&host.order.isEmpty());
            }else{
                host.main.await().run();check(host.reads.get()==2);
                if(scenario.equals("body_changed")){
                    check(host.cancelled==1&&host.writes==0&&host.consumed==0&&!gate.pending()&&host.body.equals("你好xx")&&host.order.isEmpty());
                }else{
                    check(host.writes==1&&host.consumed==1&&host.order.toString().equals("[1, 2, 3]")&&gate.consume()==null);
                }
            }
        }else{
            check(!reused&&host.fallbacks==(scenario.equals("invalid_frame")?0:1));
            gate.reject();if(old!=null)old.run();check(host.writes==0&&host.order.isEmpty()&&host.reads.get()<=1);
        }
        check(gate.deadline==deadline);
        System.out.println("PASS "+scenario+": reused="+reused+",reads="+host.reads.get()+",writes="+host.writes+",deadline_unchanged=true,order="+host.order);
    }
}
'''
        path = cls.build / "PendingRestartHarness.java"
        path.write_text(harness)
        production = [PRODUCT / name for name in (
            "VerifiedRestart.java", "SelectionEchoTracker.java", "CandidateRankingContext.java", "CompositionTrace.java")]
        subprocess.run([cls.javac, "-source", "8", "-target", "8", "-encoding", "UTF-8",
                        "-d", str(cls.build), str(path), *map(str, production)], check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def run_case(self, name):
        result = subprocess.run([self.java, "-cp", str(self.build), "org.zapime.android.PendingRestartHarness", name],
                                check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertIn("PASS " + name + ":", result.stdout)

    def test_duplicate_uses_latest_proof_with_original_ticket_queue_and_deadline(self):
        self.run_case("normal")

    def test_multiple_notifications_before_old_callback_coalesce_into_one_followup(self):
        self.run_case("coalesced")

    def test_returned_old_read_cannot_authorize_body_changed_before_duplicate(self):
        self.run_case("body_changed")

    def test_accepted_old_proof_waiting_barrier_is_invalidated(self):
        self.run_case("proof_before_barrier")

    def test_followup_invalidated_at_read_cap_cancels_without_freeing_physical_slot(self):
        self.run_case("cap")

    def test_followup_scope_change_or_original_expiry_rejects_late_proof(self):
        for scenario in ("followup_selection_changed", "followup_expired"):
            with self.subTest(scenario=scenario):
                self.run_case(scenario)

    def test_changed_session_or_owned_state_cannot_reuse(self):
        for scenario in ("connection", "info", "identity", "stored_connection", "metadata", "options", "epoch",
                         "selection_version", "preedit", "owned", "private", "search", "closing", "layer",
                         "not_ready", "not_chinese", "hidden", "marker_loss", "legacy_reply"):
            with self.subTest(scenario=scenario):
                self.run_case(scenario)

    def test_consumed_restoring_or_absent_ticket_cannot_reuse(self):
        for scenario in ("consumed", "restoring", "absent"):
            with self.subTest(scenario=scenario):
                self.run_case(scenario)

    def test_expiry_does_not_grant_a_new_budget(self):
        self.run_case("expired")

    def test_new_input_and_invalidated_entry_frame_do_not_reuse(self):
        for scenario in ("new_input", "invalid_frame"):
            with self.subTest(scenario=scenario):
                self.run_case(scenario)


if __name__ == "__main__":
    unittest.main()
