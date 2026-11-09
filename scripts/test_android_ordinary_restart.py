# SPDX-License-Identifier: GPL-3.0-only
"""Execute live ordinary-restart guards with synthetic public-interface state.

The guard/restore/retention methods and the outer frame are extracted from the
current product Java. This does not run an Android Service, Binder or editor.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PRODUCT = ROOT / "platforms/android/java/org/zapime/android/ZapInputMethodService.java"
TRACKER = ROOT / "platforms/android/java/org/zapime/android/SelectionEchoTracker.java"


class OrdinaryRestartTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java_home = os.environ.get("JAVA_HOME")
        binary = Path(java_home) / "bin" if java_home else None
        cls.javac = str(binary / "javac") if binary else shutil.which("javac")
        cls.java = str(binary / "java") if binary else shutil.which("java")
        if not cls.javac or not cls.java:
            raise RuntimeError("JDK 17 javac/java required for ordinary restart regression")
        cls.temporary = tempfile.TemporaryDirectory(prefix="zapime-ordinary-restart-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.build = Path(cls.temporary.name)
        source = PRODUCT.read_text(encoding="utf-8")
        begin = source.index("    private enum RestartRetention")
        end = source.index("    private static final class InitialContext", begin)
        guards = source[begin:end]
        initial = re.search(r"static boolean matches\(EditorInfo info,String text\)\{[^\n]+\}", source).group(0)
        frame_begin = source.index("        // Ordinary restart frame begins")
        frame_end = source.index("        // Ordinary restart frame ends", frame_begin)
        frame = source[frame_begin:frame_end]
        # Keep the actual post-cancel/super lifecycle check before the frame.
        setup = source.index("cancelMarkerLoss();super.onStartInput(info,restarting);")
        check_begin = source.index("        if(inputLifecycle", setup)
        entry_check = source[check_begin:frame_begin]
        start = source.index("@Override public void onStartInput(EditorInfo info, boolean restarting)")
        start_body = source.index("{", start) + 1
        start_prefix = source[start_body:source.index(";", start_body) + 1].strip()
        finish = source.index("@Override public void onFinishInput()")
        finish_body = source.index("{", finish) + 1
        finish_prefix = source[finish_body:source.index(";", finish_body) + 1].strip()
        harness = r'''
package org.zapime.android;
public final class OrdinaryRestartHarness {
    static class android { static class os { static class Build {
        static class VERSION { static int SDK_INT=35; }
    } } }
    static final class EditorInfo {
        String packageName="old.editor", before="shi";
        int fieldId=1,inputType=1,imeOptions=0,initialSelStart=5,initialSelEnd=5;
        CharSequence getInitialTextBeforeCursor(int length,int flags){return before;}
    }
    interface InputConnection {
        boolean beginBatchEdit();
        boolean setComposingRegion(int start,int end);
        boolean endBatchEdit();
    }
    static final class CompositionTrace { enum Event { INPUT_RETAINED } }
    static final class InitialContext { ''' + initial + r''' }
    boolean ready=true,chinese=true,privateSession,searchActive,searchClosing;
    String preedit="shi",layer="letters",editorPackage="old.editor",restartDecision="none",scenario;
    int editorField=1,editorType=1,editorOptions,selectionStart=5,selectionEnd=5;
    int epoch=1,inputStarts=1,inputLifecycle=1,selectionVersion;
    Object pendingMarkerLoss;
    EditorInfo currentInfo,editorInfoIdentity;
    InputConnection currentConnection,editorConnection;
    final SelectionEchoTracker selectionEcho=new SelectionEchoTracker();
    int beginCalls,setCalls,endCalls,staleSetters,drains,rankBegins,fallbacks;
    OrdinaryRestartHarness(String scenario){
        this.scenario=scenario;currentInfo=new EditorInfo();editorInfoIdentity=currentInfo;
        currentConnection=new Connection();editorConnection=currentConnection;
        selectionEcho.reset(2,2);selectionEcho.compose(3);
        if(scenario.equals("bad_snapshot"))currentInfo.before="other";
        if(scenario.equals("bad_owned"))selectionEcho.reset(99,99);
        if(scenario.equals("bad_editor"))currentInfo.fieldId=2;
        if(scenario.equals("private_admission"))privateSession=true;
    }
    final class Connection implements InputConnection {
        public boolean beginBatchEdit(){
            ++beginCalls;
            if(scenario.startsWith("begin_")&&!scenario.equals("begin_false"))reenter(scenario);
            return !scenario.equals("begin_false");
        }
        public boolean setComposingRegion(int start,int end){
            ++setCalls;if(currentConnection!=this)++staleSetters;
            if(scenario.startsWith("set_"))reenter(scenario);
            return true;
        }
        public boolean endBatchEdit(){
            ++endCalls;if(scenario.startsWith("end_"))reenter(scenario);return true;
        }
    }
    void reenter(String kind){
        selectionStart=99;selectionEnd=99;++selectionVersion;
        if(kind.endsWith("metadata")){currentInfo.imeOptions=1;return;}
        if(kind.endsWith("privacy")){privateSession=true;return;}
        if(kind.endsWith("preedit")){preedit="other";return;}
        if(kind.endsWith("owned")){selectionEcho.reset(99,99);return;}
        if(kind.endsWith("pending")){pendingMarkerLoss=new Object();return;}
        ++epoch;selectionEcho.invalidate();preedit="";
        if(kind.endsWith("finish")){
            ''' + finish_prefix + r'''
            ready=false;currentInfo=null;currentConnection=null;
            editorInfoIdentity=null;editorConnection=null;editorPackage=null;
        }else{
            ++inputLifecycle;++inputStarts;currentInfo=new EditorInfo();
            currentInfo.packageName="new.editor";currentInfo.fieldId=2;
            currentInfo.initialSelStart=99;currentInfo.initialSelEnd=99;currentInfo.before="";
            currentConnection=new Connection();editorInfoIdentity=currentInfo;editorConnection=currentConnection;
            editorPackage="new.editor";editorField=2;
        }
    }
    InputConnection getCurrentInputConnection(){return currentConnection;}
    EditorInfo getCurrentInputEditorInfo(){return currentInfo;}
    void traceComposition(CompositionTrace.Event event){}
    void refreshStatus(){}
    void drainInputReplies(){
        ++drains;
        if(scenario.equals("drain_advance")){
            preedit="shij";selectionEcho.compose(4);selectionStart=6;selectionEnd=6;
        }
        if(scenario.equals("drain_finish")||scenario.equals("drain_new"))reenter(scenario);
    }
    void beginRankingEditor(EditorInfo info,boolean initial){++rankBegins;}
    void configureRanking(){}
''' + guards + r'''
    void outer(EditorInfo info){
        boolean restarting=true;
        ''' + start_prefix + r'''
        final InputConnection startingConnection=getCurrentInputConnection();
        if(scenario.equals("own_cancel_epoch"))++epoch;
        if(scenario.equals("nested_finish_before_frame"))reenter("before_finish");
''' + entry_check + frame + r'''
        ++fallbacks;
    }
    public static void main(String[] args){
        String scenario=args[0];
        OrdinaryRestartHarness direct=new OrdinaryRestartHarness(scenario);
        EditorInfo old=direct.currentInfo;
        RestartRetention retained=direct.retainRestartedComposition(old);
        OrdinaryRestartHarness outer=new OrdinaryRestartHarness(scenario);
        outer.outer(outer.currentInfo);
        System.out.println("retained="+retained+";begin="+direct.beginCalls+";set="+direct.setCalls
                +";end="+direct.endCalls+";stale="+direct.staleSetters+";selection="+direct.selectionStart
                +";drains="+direct.drains+";fallback="+outer.fallbacks+";outerSet="+outer.setCalls
                +";outerSelection="+outer.selectionStart+";identityCurrent="+(outer.editorInfoIdentity==outer.currentInfo)
                +";rank="+outer.rankBegins);
    }
}
'''
        file = cls.build / "OrdinaryRestartHarness.java"
        file.write_text(harness, encoding="utf-8")
        subprocess.run([cls.javac, "-source", "8", "-target", "8", "-encoding", "UTF-8",
                        "-d", str(cls.build), str(file), str(TRACKER)],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def result(self, scenario):
        completed = subprocess.run([self.java, "-cp", str(self.build),
                                    "org.zapime.android.OrdinaryRestartHarness", scenario],
                                   check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        return dict(item.split("=", 1) for item in completed.stdout.strip().split(";"))

    def test_lifecycle_reentry_invalidates_retention_without_old_frame_fallback(self):
        for scenario in ("begin_finish", "begin_new", "set_finish", "set_new",
                         "end_finish", "end_new", "drain_finish", "drain_new"):
            with self.subTest(scenario=scenario):
                result = self.result(scenario)
                self.assertEqual(result["retained"], "INVALIDATED")
                self.assertEqual(result["selection"], "99")
                self.assertEqual(result["outerSelection"], "99")
                self.assertEqual(result["identityCurrent"], "true")
                self.assertEqual(result["fallback"], "0")
                self.assertEqual(result["rank"], "0")
                self.assertEqual(result["stale"], "0")
                self.assertEqual(result["set"], "0" if scenario.startswith("begin_") else "1")
                self.assertEqual(result["drains"], "1" if scenario.startswith("drain_") else "0")

    def test_mutated_metadata_privacy_preedit_or_owned_range_cannot_resume(self):
        for scenario in ("set_metadata", "set_privacy", "set_preedit", "set_owned", "set_pending"):
            with self.subTest(scenario=scenario):
                result = self.result(scenario)
                self.assertEqual(result["retained"], "INVALIDATED")
                self.assertEqual(result["selection"], "99")
                self.assertEqual(result["outerSelection"], "99")
                self.assertEqual(result["drains"], "0")
                self.assertEqual(result["fallback"], "0")
                self.assertEqual(result["rank"], "0")

    def test_original_snapshot_owned_and_privacy_admission_reject_before_setter(self):
        for scenario in ("bad_snapshot", "bad_owned", "bad_editor", "private_admission"):
            with self.subTest(scenario=scenario):
                result = self.result(scenario)
                self.assertEqual(result["retained"], "REJECTED")
                self.assertEqual((result["begin"], result["set"], result["end"]), ("0", "0", "0"))
                self.assertEqual(result["fallback"], "1")
                self.assertEqual(result["rank"], "0")

    def test_normal_false_batch_and_legitimate_queue_advance_still_retain(self):
        for scenario in ("normal", "begin_false", "drain_advance"):
            with self.subTest(scenario=scenario):
                result = self.result(scenario)
                self.assertEqual(result["retained"], "RETAINED")
                self.assertEqual((result["begin"], result["set"], result["end"]), ("1", "1", "1"))
                self.assertEqual(result["selection"], "6" if scenario == "drain_advance" else "5")
                self.assertEqual(result["fallback"], "0")
                self.assertEqual(result["rank"], "1")
                self.assertEqual(result["identityCurrent"], "true")

    def test_start_lifecycle_token_allows_owned_cancel_and_aborts_nested_finish(self):
        own = self.result("own_cancel_epoch")
        self.assertEqual((own["outerSet"], own["rank"], own["fallback"]), ("1", "1", "0"))
        nested = self.result("nested_finish_before_frame")
        self.assertEqual((nested["outerSet"], nested["rank"], nested["fallback"]), ("0", "0", "0"))
        self.assertEqual(nested["outerSelection"], "99")
        self.assertEqual(nested["identityCurrent"], "true")


if __name__ == "__main__":
    unittest.main()
