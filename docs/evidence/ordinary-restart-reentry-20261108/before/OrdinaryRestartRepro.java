package org.zapime.android;
public final class OrdinaryRestartRepro {
 static class android {static class os {static class Build {static class VERSION {static int SDK_INT=35;}}}}
 static final class EditorInfo {
  String packageName="old.editor",before="shi";int fieldId=1,inputType=1,imeOptions=0,initialSelStart=5,initialSelEnd=5;
  CharSequence getInitialTextBeforeCursor(int n,int flags){return before;}
 }
 interface InputConnection {boolean beginBatchEdit();boolean setComposingRegion(int start,int end);boolean endBatchEdit();}
 static final class CompositionTrace {enum Event{INPUT_RETAINED}}
 static final class InitialContext {static boolean matches(EditorInfo info,String text){CharSequence before=info.getInitialTextBeforeCursor(text.length(),0);return before!=null&&text.contentEquals(before);}}
 boolean ready=true,chinese=true,privateSession,searchActive,searchClosing;
 String preedit="shi",editorPackage="old.editor",restartDecision="none",mode;
 int editorField=1,editorType=1,editorOptions,selectionStart=5,selectionEnd=5,epoch=1,inputStarts=1,selectionVersion;
 EditorInfo currentInfo,editorInfoIdentity;InputConnection currentConnection,editorConnection;
 final SelectionEchoTracker selectionEcho=new SelectionEchoTracker();
 int beginCalls,setCalls,endCalls,staleSetters,setStart=-1,setEnd=-1,retainedEvents,refreshes,drains,rankBegins;
 OrdinaryRestartRepro(String mode){this.mode=mode;currentInfo=new EditorInfo();editorInfoIdentity=currentInfo;currentConnection=new Connection();editorConnection=currentConnection;selectionEcho.reset(2,2);selectionEcho.compose(3);}
 final class Connection implements InputConnection {
  final int originalEpoch=epoch;
  public boolean beginBatchEdit(){++beginCalls;if(mode.startsWith("begin_"))reenter();return true;}
  public boolean setComposingRegion(int a,int b){++setCalls;setStart=a;setEnd=b;if(epoch!=originalEpoch||currentConnection!=this)++staleSetters;if(mode.startsWith("set_"))reenter();return true;}
  public boolean endBatchEdit(){++endCalls;if(mode.startsWith("end_"))reenter();return true;}
 }
 void reenter(){
  ++epoch;++selectionVersion;selectionStart=99;selectionEnd=99;selectionEcho.invalidate();preedit="";
  if(mode.endsWith("finish")){ready=false;currentInfo=null;currentConnection=null;editorInfoIdentity=null;editorConnection=null;editorPackage=null;}
  else{++inputStarts;currentInfo=new EditorInfo();currentInfo.packageName="new.editor";currentInfo.fieldId=2;currentInfo.initialSelStart=99;currentInfo.initialSelEnd=99;currentInfo.before="";currentConnection=new Connection();editorInfoIdentity=currentInfo;editorConnection=currentConnection;editorPackage="new.editor";editorField=2;}
 }
 InputConnection getCurrentInputConnection(){return currentConnection;}
 EditorInfo getCurrentInputEditorInfo(){return currentInfo;}
 boolean rejectRestart(String reason){restartDecision=reason;return false;}
 boolean sameEditor(EditorInfo info){return info!=null&&editorPackage!=null&&editorPackage.equals(info.packageName)&&editorField==info.fieldId&&editorType==info.inputType&&editorOptions==info.imeOptions;}
 void traceComposition(CompositionTrace.Event e){++retainedEvents;}
 void refreshStatus(){++refreshes;}
 void drainInputReplies(){++drains;}
 void beginRankingEditor(EditorInfo info,boolean initial){++rankBegins;}
 void configureRanking(){}
    private boolean retainRestartedComposition(EditorInfo info) {
        if(android.os.Build.VERSION.SDK_INT<30)return rejectRestart("api_before_30");
        if(!ready||!chinese||privateSession||searchActive||searchClosing)return rejectRestart("inactive_or_private");
        if(preedit.isEmpty()||preedit.length()>128)return rejectRestart("no_bounded_composition");
        if(!sameEditor(info))return rejectRestart("editor_changed");
        if(!selectionEcho.matchesRestartComposition(info.initialSelStart,info.initialSelEnd,preedit.length()))return rejectRestart("selection_not_owned");
        // EditorInfo carries this bounded snapshot; never query the remote App
        // for context on the input/UI thread.
        if(!InitialContext.matches(info,preedit))return rejectRestart("initial_context_mismatch");
        InputConnection connection=getCurrentInputConnection();
        if(connection==null)return rejectRestart("region_rejected");
        boolean restored=false;connection.beginBatchEdit();
        try{restored=connection.setComposingRegion(info.initialSelEnd-preedit.length(),info.initialSelEnd);}
        finally{connection.endBatchEdit();}
        if(!restored)return rejectRestart("region_rejected");
        selectionStart=info.initialSelStart;selectionEnd=info.initialSelEnd;
        restartDecision="retained";traceComposition(CompositionTrace.Event.INPUT_RETAINED);refreshStatus();drainInputReplies();return true;
    }

 void actualOuterBranch(EditorInfo info){boolean restarting=true;if(restarting&&retainRestartedComposition(info)){editorInfoIdentity=info;editorConnection=getCurrentInputConnection();beginRankingEditor(info,false);configureRanking();return;}}
 public static void main(String[] args){
  for(String mode:new String[]{"normal","begin_finish","begin_new","set_finish","set_new","end_finish","end_new"}){
   OrdinaryRestartRepro p=new OrdinaryRestartRepro(mode);EditorInfo old=p.currentInfo;
   boolean result=p.retainRestartedComposition(old);
   if(!result)throw new AssertionError("expected old method to reproduce unsafe retain in "+mode);
   System.out.println(mode+": return="+result+", epoch="+p.epoch+", starts="+p.inputStarts+", begin/set/end="+p.beginCalls+"/"+p.setCalls+"/"+p.endCalls+", region="+p.setStart+":"+p.setEnd+", stale_setters="+p.staleSetters+", selection="+p.selectionStart+":"+p.selectionEnd+", decision="+p.restartDecision+", drains="+p.drains);
   OrdinaryRestartRepro outer=new OrdinaryRestartRepro(mode);EditorInfo old2=outer.currentInfo;outer.actualOuterBranch(old2);
   System.out.println("  exact outer: oldIdentity="+(outer.editorInfoIdentity==old2)+", identityMatchesCurrent="+(outer.editorInfoIdentity==outer.currentInfo)+", oldInfoRankingBegins="+outer.rankBegins);
   if(!mode.equals("normal")&&(outer.editorInfoIdentity==outer.currentInfo||outer.selectionStart!=5||outer.drains!=1))throw new AssertionError("reentry did not reproduce "+mode);
  }
 }
}
