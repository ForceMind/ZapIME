#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""专用模拟器上的原生输入验收，全部文本为合成内容。"""
from pathlib import Path
import subprocess
import sys

adb, serial = sys.argv[1:3]
root = Path(__file__).resolve().parents[1]
output = root / "target/device-smoke"
output.mkdir(parents=True, exist_ok=True)
command = [adb,"-s",serial]
subprocess.run(command+["install","-r",str(root/"target/android-tests/ZapIME-tests.apk")],check=True)
subprocess.run(command+["shell","settings","put","secure","show_ime_with_hard_keyboard","1"],check=True)
result=subprocess.run(command+["shell","am","instrument","-w","org.zapime.android.test/.SmokeInstrumentation"],text=True,capture_output=True,timeout=180)
(output/"instrumentation.log").write_text(result.stdout+result.stderr)
subprocess.run(command+["pull","/sdcard/Android/data/org.zapime.android/files/smoke.png",str(output/"smoke.png")],check=False)
diagnostics=subprocess.run(command+["shell","dumpsys","input_method"],text=True,capture_output=True)
(output/"input-method-state.log").write_text(diagnostics.stdout)
logs=subprocess.run(command+["logcat","-d","-s","AndroidRuntime:E"],text=True,capture_output=True)
(output/"runtime-errors.log").write_text(logs.stdout)
print(result.stdout,flush=True)
if "PASS ZapIME native keyboard:" not in result.stdout:
    raise SystemExit("Native keyboard instrumentation failed; inspect target/device-smoke")
