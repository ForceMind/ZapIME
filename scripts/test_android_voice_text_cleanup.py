# SPDX-License-Identifier: GPL-3.0-only
"""Exercise the actual shared Java ASR cleanup without Android or network I/O.

This deterministic JVM suite is NOT system-IME, VoiceKey native decode, or
physical phone acceptance. Source wiring checks preserve opt-in/confirmation.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "platforms/android/java/org/zapime/android"
HARNESS = r"""
package org.zapime.android;
public final class VoiceTextCleanupHarness {
    static void same(String raw,String expected) {
        String got=VoiceTextCleanup.concise(raw);
        if(!expected.equals(got))throw new AssertionError("cleanup_mismatch: "+Integer.toHexString(raw.hashCode()));
    }
    static void untouched(String raw) {same(raw,raw);}
    public static void main(String[] argv) {
        same("嗯，那个，我觉得这个方案很好。","我觉得这个方案很好。");
        same("我觉得，呃，这个方法可以。","我觉得，这个方法可以。");
        same("好的，就是说，嗯，计划照旧。","好的，计划照旧。");
        same("嗯，嗯，我们现在开始。","我们现在开始。");
        same("我们先检查网络，我们先检查网络，然后重试。","我们先检查网络，然后重试。");
        same("我们先检查网络。我们先检查网络。","我们先检查网络。");
        same("我们先检查网络，我们先检查网络，我们先检查网络。","我们先检查网络。");
        same("那个，\n我们开始讨论。","\n我们开始讨论。");
        same("不，那个，我们不要自动替我提交。","不，我们不要自动替我提交。");
        untouched("那个项目需要改进。");
        untouched("这个结果很重要。");
        untouched("不要删除，不要删除。");
        untouched("今天三点开会，今天三点开会。");
        untouched("第一步重新验证，第一步重新验证。");
        untouched("检查一下网络，检查一下网络。");
        untouched("好的？好的？");
        untouched("你好，世界！");
        untouched("嗯。");
        untouched("嗯，");
        untouched("不不不");
        untouched("hello hello");
        untouched("这是一个金额100元，这是一个金额100元。");
        untouched("🙂🙂，🙂🙂");
        untouched("原始语音，没有中断的话，不做任何改写。");
        untouched("  这个真的是要留下来的  ");
        untouched("这是新的方案；那是旧的方案。");
        String longRaw="嗯，"+"长".repeat(8192);
        untouched(longRaw);
        StringBuilder many=new StringBuilder();
        for(int i=0;i<140;i++)many.append("嗯，");
        untouched(many.toString());
        if(!"".equals(VoiceTextCleanup.concise(null)))throw new AssertionError("null_invalid");
        System.out.println("PASS actual offline voice cleanup: 29 fixed cases");
    }
}
""".replace('\\n','\\n')

class VoiceTextCleanupTest(unittest.TestCase):
    def test_actual_java_with_bounded_semantic_preservation(self):
        javac=str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if os.environ.get("JAVA_HOME") else shutil.which("javac")
        java=str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else shutil.which("java")
        if not javac or not java:
            self.fail("JDK required to test exact shipping cleanup source")
        with tempfile.TemporaryDirectory(prefix="zapime-voice-cleanup-") as d:
            root=Path(d)
            package=root / "org/zapime/android"
            package.mkdir(parents=True)
            harness=package / "VoiceTextCleanupHarness.java"
            harness.write_text(HARNESS,encoding="utf-8")
            compile_result=subprocess.run(
                [javac,"-encoding","UTF-8","-d",str(root),
                 str(JAVA / "VoiceTextCleanup.java"),str(harness)],
                capture_output=True,text=True,timeout=30
            )
            self.assertEqual(compile_result.returncode,0,compile_result.stderr)
            result=subprocess.run(
                [java,"-cp",str(root),"org.zapime.android.VoiceTextCleanupHarness"],
                capture_output=True,text=True,timeout=20
            )
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn("PASS actual offline voice cleanup",result.stdout)

    def test_actual_android_runtime_probe_is_in_both_voice_paths_and_restores_preferences(self):
        instrumentation=(ROOT / "platforms/android/voice-tests/java/org/zapime/android/voicetest/VoiceInstrumentation.java").read_text(encoding="utf-8")
        probe=(ROOT / "platforms/android/voice-tests/java/org/zapime/android/voicetest/VoiceTextCleanupProbe.java").read_text(encoding="utf-8")
        self.assertEqual(instrumentation.count("VoiceTextCleanupProbe.run(this)"),2)
        self.assertIn("Class.forName(\"org.zapime.android.VoiceTextCleanup\"",probe)
        self.assertIn("VoiceTextCleanupPreferences",probe)
        self.assertIn("} finally {",probe)
        self.assertIn("setEnabled.invoke(null,target,before)",probe)
        self.assertIn("voice_cleanup_preference_restore_failed",probe)
        self.assertNotIn("commitText(",probe)
        self.assertNotIn("HttpURLConnection",probe)
        self.assertNotIn("String.repeat",probe)

    def test_both_engines_reuse_final_result_and_explicit_confirmation(self):
        service=(JAVA / "ZapInputMethodService.java").read_text(encoding="utf-8")
        settings=(JAVA / "VoiceSettingsActivity.java").read_text(encoding="utf-8")
        preference=(JAVA / "VoiceTextCleanupPreferences.java").read_text(encoding="utf-8")
        original=(JAVA / "VoiceCapture.java").read_text(encoding="utf-8")
        commit=(JAVA / "VoiceCommitTransaction.java").read_text(encoding="utf-8")
        self.assertIn('if(state.equals("result"))',service)
        self.assertIn("voiceRawText=text==null",service)
        self.assertIn("VoiceTextCleanup.concise(voiceRawText)",service)
        self.assertIn("voiceText=voiceUseConcise?voiceConciseText:voiceRawText;",service)
        self.assertIn("voiceUseConcise=!voiceUseConcise",service)
        self.assertIn('voiceRawText="";voiceConciseText="";',service)
        self.assertIn("voiceBefore,voiceAfter,voiceText);",service)
        self.assertIn("VoiceTextCleanupPreferences.enabled(this)",service)
        self.assertIn("VoiceTextCleanupPreferences.setEnabled(this,checked)",settings)
        self.assertIn(".getBoolean(KEY,false)",preference)
        self.assertIn('NativeVoiceKey.finish(handle',original)
        self.assertIn('NativeVoice.finish(handle)',original)
        self.assertIn("target.commitText(confirmed,1)",commit)
        self.assertNotIn("http", (JAVA/"VoiceTextCleanup.java").read_text(encoding="utf-8").lower())

if __name__ == "__main__":
    unittest.main()
