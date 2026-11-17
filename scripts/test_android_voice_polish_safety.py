# SPDX-License-Identifier: GPL-3.0-only
"""Execute the exact proposed semantic-polish output gate without any model.

This tests a necessary fail-closed condition, NOT semantic preservation or
an installed LLM. Only frozen synthetic strings; never log user transcripts.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/"platforms/android/java/org/zapime/android/VoicePolishSafety.java"

HARNESS=r"""
package org.zapime.android;
public final class VoicePolishSafetyHarness {
    static int cases;
    static void accept(String original,String result){
        ++cases;
        if(VoicePolishSafety.inspect(original,result)!=VoicePolishSafety.Reason.ACCEPTED
                ||!result.equals(VoicePolishSafety.safePreview(original,result)))
            throw new AssertionError("rejected synthetic safe rewrite at case "+cases);
    }
    static void reject(String original,String result){
        ++cases;
        if(VoicePolishSafety.inspect(original,result)==VoicePolishSafety.Reason.ACCEPTED
                ||!original.equals(VoicePolishSafety.safePreview(original,result)))
            throw new AssertionError("accepted unsafe synthetic rewrite at case "+cases);
    }
    public static void main(String[] args){
        accept("嗯，今天下午15:30继续开会。","今天下午15:30开会。");
        accept("就是说，请不要删除文件。","请不要删除文件。");
        accept("我今天说，我们继续工作。","我们继续工作。");
        accept("给张三发电子邮件 abc@sample.com。","把电子邮件 abc@sample.com 发给张三。");
        reject("今天15:30开会。","今天16:30开会。");
        reject("每天服用10mg。","每天服用10g。");
        reject("请不要删除。","请删除。");
        reject("今天不开发。","今天开发。");
        reject("保留“保密协议”文字。","保留“普通协议”文字。");
        reject("详见 https://a.example.com/x 。","详见 https://b.example.com/x 。");
        reject("是2026年11月16日。","是2026年11月17日。");
        reject("一百元预算。","两百元预算。");
        reject("Do not transfer any money.","Transfer any money.");
        reject("Do not transfer any money.","Never transfer any money.");
        reject("费用¥100。","费用$100。");
        reject("11月16日发布。","11日16月发布。");
        reject("测试结果稳定。","<think>秘密</think>测试结果稳定。");
        reject("只说一句话。","");
        reject("一小段说明。","一小段说明。\uD800");
        reject("原文","扩充文本".repeat(600));
        if(VoicePolishSafety.inspect(null,"test")!=VoicePolishSafety.Reason.INVALID)
            throw new AssertionError("null original accepted");
        System.out.println("PASS "+cases+" frozen synthetic safety cases; no model or phone claim");
    }
}
"""

class VoicePolishSafetyTests(unittest.TestCase):
    def test_actual_product_java_numeric_negation_and_fallback(self):
        javac=str(Path(os.environ["JAVA_HOME"])/"bin/javac") if os.environ.get("JAVA_HOME") else shutil.which("javac")
        java=str(Path(os.environ["JAVA_HOME"])/"bin/java") if os.environ.get("JAVA_HOME") else shutil.which("java")
        if not javac or not java: self.fail("JDK required for exact source contract")
        with tempfile.TemporaryDirectory(prefix="zapime-polish-safety-") as folder:
            root=Path(folder)
            source=root/"VoicePolishSafetyHarness.java"
            source.write_text(HARNESS,encoding="utf-8")
            compiled=subprocess.run([javac,"-encoding","UTF-8","-d",str(root),
                                     str(SOURCE),str(source)],capture_output=True,text=True,timeout=25)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            tested=subprocess.run([java,"-cp",str(root),"org.zapime.android.VoicePolishSafetyHarness"],
                                  capture_output=True,text=True,timeout=25)
            self.assertEqual(tested.returncode,0,tested.stderr)
            self.assertIn("PASS 20 frozen synthetic safety cases",tested.stdout)

    def test_output_gate_is_not_a_model_or_network_client(self):
        text=SOURCE.read_text(encoding="utf-8")
        self.assertIn("necessary condition",text)
        self.assertIn("safePreview(String original,String proposed)",text)
        self.assertNotIn("commitText(",text)
        self.assertNotIn("HttpURLConnection",text)
        self.assertNotIn("android.content.",text)
        self.assertNotIn("loadModel(",text)

if __name__=="__main__":
    unittest.main()
