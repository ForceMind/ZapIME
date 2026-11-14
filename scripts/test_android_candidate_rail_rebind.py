# SPDX-License-Identifier: GPL-3.0-only
"""Compile and execute the real IME rail-rebind method with synthetic identities.

This JVM boundary test is not Android Binder, touchscreen, or phone acceptance.
Those are required from the independent candidate-ranking workflow.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "platforms/android/java/org/zapime/android/ZapInputMethodService.java"


class CandidateRailRebindTest(unittest.TestCase):
    def test_real_method_respects_editor_identity_and_never_replays_input(self):
        source = SOURCE.read_text(encoding="utf-8")
        start = source.index("    private void rebindCandidateRail(EditorInfo info) {")
        end = source.index("    @Override public void onConfigurationChanged(", start)
        method = source[start:end]
        # Do not weaken the real Android system-IME gate by retrying taps or
        # treating a missing current snapshot as an accepted candidate.
        for forbidden in ("commitText(", "setComposingText(", "NativeCore.request(", "send(", "postDelayed(", "Thread.sleep("):
            self.assertNotIn(forbidden, method)
        self.assertIn("rebindCandidateRail(info);", source[source.index("@Override public void onStartInputView("):source.index("@Override public void onConfigurationChanged(")])
        self.assertIn("rebindCandidateRail(ticket.info);", source[source.index("private void completeVerifiedRestart("):source.index("private enum RestartRetention")])
        javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if os.environ.get("JAVA_HOME") else shutil.which("javac")
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else shutil.which("java")
        if not javac or not java:
            self.fail("JDK required for actual Java rebind contract")
        harness = r"""
public final class CandidateRailRebindHarness {
    static class EditorInfo {}
    static class JSONArray {}
    static class JSONObject {
        String preedit;long generation;JSONArray candidates;
        JSONObject(String p,long g,boolean withCandidates) {
            preedit=p;generation=g;candidates=withCandidates?new JSONArray():null;
        }
        String optString(String key,String fallback){return "preedit".equals(key)?preedit:fallback;}
        long optLong(String key,long fallback){return "generation".equals(key)?generation:fallback;}
        JSONArray optJSONArray(String key){return "candidates".equals(key)?candidates:null;}
    }
    EditorInfo editorInfoIdentity=new EditorInfo();
    Object editorConnection=new Object(),realConnection=editorConnection;
    Object verifiedRestart,restoringRestart,pendingMarkerLoss;
    Object candidates=new Object();
    boolean ready=true,chinese=true,privateSession,searchActive,searchClosing,editorCompatible=true;
    String preedit="shijie";
    long generation=7;
    JSONObject latestCandidateView=new JSONObject("shijie",7,true);
    int railRebindSkips,railRebinds,draws;
    JSONObject lastDrawn;
    Object getCurrentInputConnection(){return realConnection;}
    boolean sameEditor(EditorInfo info){return editorCompatible;}
    void renderResult(JSONObject view){lastDrawn=view;draws++;}
    /** SOURCE METHOD INSERTED HERE */
    static void check(boolean condition){if(!condition)throw new AssertionError();}
    static void rejected(CandidateRailRebindHarness test){
        int before=test.draws;
        test.rebindCandidateRail(test.editorInfoIdentity);
        check(test.draws==before && test.railRebindSkips>0);
    }
    static CandidateRailRebindHarness baseline(){return new CandidateRailRebindHarness();}
    public static void main(String[] args){
        CandidateRailRebindHarness valid=baseline();
        valid.rebindCandidateRail(valid.editorInfoIdentity);
        check(valid.draws==1&&valid.railRebinds==1&&valid.lastDrawn==valid.latestCandidateView);

        CandidateRailRebindHarness stale=baseline();stale.latestCandidateView.generation=6;rejected(stale);
        CandidateRailRebindHarness text=baseline();text.latestCandidateView.preedit="different";rejected(text);
        CandidateRailRebindHarness noChoices=baseline();noChoices.latestCandidateView.candidates=null;rejected(noChoices);
        CandidateRailRebindHarness otherConnection=baseline();otherConnection.realConnection=new Object();rejected(otherConnection);
        CandidateRailRebindHarness otherInfo=baseline();otherInfo.editorInfoIdentity=new EditorInfo();
        check(otherInfo.draws==0);otherInfo.rebindCandidateRail(new EditorInfo());check(otherInfo.draws==0);
        CandidateRailRebindHarness waiting=baseline();waiting.verifiedRestart=new Object();rejected(waiting);
        CandidateRailRebindHarness restoring=baseline();restoring.restoringRestart=new Object();rejected(restoring);
        CandidateRailRebindHarness marker=baseline();marker.pendingMarkerLoss=new Object();rejected(marker);
        CandidateRailRebindHarness inactive=baseline();inactive.ready=false;rejected(inactive);
        CandidateRailRebindHarness privateField=baseline();privateField.privateSession=true;rejected(privateField);
        CandidateRailRebindHarness english=baseline();english.chinese=false;rejected(english);
        CandidateRailRebindHarness searching=baseline();searching.searchActive=true;rejected(searching);
        CandidateRailRebindHarness closing=baseline();closing.searchClosing=true;rejected(closing);
        CandidateRailRebindHarness nullRail=baseline();nullRail.candidates=null;rejected(nullRail);
        CandidateRailRebindHarness empty=baseline();empty.preedit="";rejected(empty);
        CandidateRailRebindHarness noView=baseline();noView.latestCandidateView=null;rejected(noView);
        CandidateRailRebindHarness unrelated=baseline();unrelated.editorCompatible=false;rejected(unrelated);
        System.out.println("PASS candidate rail rebind: valid identity + 17 fail-closed cases");
    }
}
"""
        harness = harness.replace("    /** SOURCE METHOD INSERTED HERE */", method.rstrip())
        with tempfile.TemporaryDirectory(prefix="zapime-candidate-rail-") as d:
            root = Path(d)
            target = root / "CandidateRailRebindHarness.java"
            target.write_text(harness, encoding="utf-8")
            subprocess.run([javac, "-encoding", "UTF-8", "-d", str(root), str(target)],
                           capture_output=True, text=True, timeout=25, check=True)
            result = subprocess.run([java, "-cp", str(root), "CandidateRailRebindHarness"],
                                    capture_output=True, text=True, timeout=25, check=True)
            self.assertIn("PASS candidate rail rebind", result.stdout)


if __name__ == "__main__":
    unittest.main()
