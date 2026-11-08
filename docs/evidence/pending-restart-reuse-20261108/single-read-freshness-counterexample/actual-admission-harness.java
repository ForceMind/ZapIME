
package org.zapime.android;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
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
    final CompositionTrace trace=new CompositionTrace();
    final VerifiedRestart.Visibility restartVisibility=new VerifiedRestart.Visibility();
    final SelectionEchoTracker selectionEcho=new SelectionEchoTracker();
    final ArrayDeque<Object> inputReplies=new ArrayDeque<>();
    RestartTicket verifiedRestart,restoringRestart;
    int epoch=3,inputStarts=1,inputLifecycle=1,selectionVersion=4;
    boolean ready=true,chinese=true,privateSession,searchActive,searchClosing;
    String preedit="sh",layer="letters",editorPackage="synthetic.editor",restartDecision="fresh_pending";
    int editorField=1,editorType=1,editorOptions;
    Object currentConnection=new Object(),editorConnection=currentConnection,pendingMarkerLoss;
    EditorInfo currentInfo=new EditorInfo(),editorInfoIdentity=currentInfo;
    int fallbacks;
    PendingRestartHarness(){
        restartVisibility.shown();selectionEcho.reset(2,2);selectionEcho.compose(2);
        verifiedRestart=new RestartTicket(new VerifiedRestart(new VerifiedRestart.Expected("sh","你好","",4),selectionVersion),epoch,currentConnection,currentInfo);
    }
    Object getCurrentInputConnection(){return currentConnection;}
    EditorInfo getCurrentInputEditorInfo(){return currentInfo;}
    void traceComposition(CompositionTrace.Event event){trace.note(event,epoch,1);}
    private boolean sameEditor(EditorInfo info){return info!=null&&editorPackage!=null&&editorPackage.equals(info.packageName)&&editorField==info.fieldId&&editorType==info.inputType&&editorOptions==info.imeOptions;}    private boolean sameRestartEditor(RestartTicket ticket){
        return epoch==ticket.epoch&&getCurrentInputConnection()==ticket.connection&&getCurrentInputEditorInfo()==ticket.info
                &&editorInfoIdentity==ticket.info&&editorConnection==ticket.connection&&sameEditor(ticket.info)
                &&EnglishLearningEligibility.allows(ticket.info,privateSession);
    }
    private boolean restartScope(RestartTicket ticket){
        return restartVisibility.visible()&&sameRestartEditor(ticket)&&ready&&!searchActive&&!searchClosing&&chinese
                &&preedit.equals(ticket.gate.expected.preedit)
                &&selectionEcho.ownedCompositionEnd(preedit.length())==ticket.gate.expected.end
                &&selectionVersion==ticket.gate.selectionVersion;
    }
    private boolean reusePendingRestart(EditorInfo info){
        final RestartTicket ticket=verifiedRestart;
        // This is the same still-pending proof, not a new restart budget.
        // A consumed/restoring ticket may already be inside a setter or drain
        // and must continue to use the existing invalidation path instead.
        if(ticket==null||restoringRestart!=null||info!=ticket.info
                ||!ticket.gate.pending()||ticket.gate.expired()||privateSession
                ||!layer.equals("letters")||pendingMarkerLoss!=null||!inputReplies.isEmpty()
                ||!restartScope(ticket))return false;
        restartDecision="fresh_pending_reused";
        traceComposition(CompositionTrace.Event.RESTART_FRESH_REUSED);
        return true;
    }

    void duplicate(boolean restarting,boolean invalidFrame){
        EditorInfo info=currentInfo;
        final int startingLifecycle=++inputLifecycle;
        final Object startingConnection=getCurrentInputConnection();
        ++inputStarts;
        if(invalidFrame)++inputLifecycle;
        if(inputLifecycle!=startingLifecycle||getCurrentInputConnection()!=startingConnection
                ||getCurrentInputEditorInfo()!=info)return;
        if(restarting&&reusePendingRestart(info)){
            // Keep the original read, barrier, held replies/actions, selection
            // version and deadline. Framework duplicate starts do not grant a
            // second IPC or discard input while the first proof is pending.
            super.onStartInput(info,restarting);return;
        }

        ++fallbacks;
    }
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        String scenario=args[0];PendingRestartHarness host=new PendingRestartHarness();
        if(scenario.equals("stale_before_callback")){
            VerifiedRestart old=host.verifiedRestart.gate;long originalDeadline=old.deadline;old.barrier();
            final String[] appText={"你好sh"};AtomicInteger actualReads=new AtomicInteger();
            AtomicReference<Runnable> callback=new AtomicReference<>();
            check(VerifiedRestart.submit(old,()->{actualReads.incrementAndGet();return new VerifiedRestart.Snapshot(appText[0],0,4,4);},
                    callback::set,valid->{if(valid)old.proof();},Runnable::run));
            check(actualReads.get()==1&&callback.get()!=null&&!old.ready());
            // Same-length external edit and restart precede the old main callback;
            // no selection notification has yet changed the local tracker.
            appText[0]="你好xx";host.duplicate(true,false);
            check(host.fallbacks==0&&host.verifiedRestart.gate==old);
            callback.get().run();check(old.ready()&&old.consume()!=null);
            check(actualReads.get()==1&&old.deadline==originalDeadline);
            System.out.println("PASS stale_before_callback: old_snapshot_consumed=true,editor_changed_after_read=true,read_count=1,duplicate_reused=true");return;
        }
        RestartTicket original=host.verifiedRestart;VerifiedRestart gate=original.gate;
        long deadline=gate.deadline;List<Integer> order=new ArrayList<>();
        check(gate.hold(true,()->order.add(1),1));check(gate.hold(false,()->order.add(3),1));
        check(gate.hold(true,()->order.add(2),1));gate.barrier();
        AtomicInteger reads=new AtomicInteger(),results=new AtomicInteger();AtomicReference<Runnable> physical=new AtomicReference<>();
        check(VerifiedRestart.submit(gate,()->{reads.incrementAndGet();return new VerifiedRestart.Snapshot("你好sh",0,4,4);},Runnable::run,
                valid->{results.incrementAndGet();if(valid)gate.proof();},physical::set));
        switch(scenario){
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
            case "consumed":gate.proof();check(gate.consume()!=null);break;
            default:break;
        }
        host.duplicate(!scenario.equals("new_input"),scenario.equals("invalid_frame"));
        check(gate.deadline==deadline&&reads.get()==0&&results.get()==0&&order.isEmpty());
        // A duplicate never starts another physical read, even with an executor
        // that would immediately run it. The real process-wide slot stays busy.
        check(!VerifiedRestart.submit(new VerifiedRestart(gate.expected,4),()->{reads.incrementAndGet();return null;},Runnable::run,ok->{}));
        boolean reused=host.trace.snapshot().contains("RESTART_FRESH_REUSED");
        if(scenario.equals("normal")){
            check(reused&&host.fallbacks==0&&host.verifiedRestart==original&&gate.pending());
            check(host.restartDecision.equals("fresh_pending_reused"));
            physical.get().run();check(reads.get()==1&&results.get()==1&&gate.ready());
            VerifiedRestart.Release release=gate.consume();check(release!=null&&gate.consume()==null);
            check(release.drainReplies(()->true));for(Runnable operation:release.operations())operation.run();
            check(order.toString().equals("[1, 2, 3]"));check(!host.reusePendingRestart(host.currentInfo));
        }else{
            check(!reused&&host.fallbacks==(scenario.equals("invalid_frame")?0:1));
            gate.reject();physical.get().run();check(reads.get()==0&&results.get()==1&&order.isEmpty());
        }
        System.out.println("PASS "+scenario+": reused="+reused+",reads="+reads.get()+",deadline_unchanged=true,order="+order);
    }
}
