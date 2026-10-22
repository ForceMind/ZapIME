#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""专用模拟器上的原生输入验收，全部文本为合成内容。"""
from pathlib import Path
import os
import re
import subprocess
import sys
import time

adb, serial = sys.argv[1:3]
root = Path(__file__).resolve().parents[1]
output = root / "target/device-smoke"
output.mkdir(parents=True, exist_ok=True)
command = [adb,"-s",serial]
subprocess.run(command+["install","-r",str(root/"target/android-tests/ZapIME-tests.apk")],check=True)
method="org.zapime.android/.ZapInputMethodService"
for attempt in range(60):
    methods=subprocess.run(command+["shell","ime","list","-a","-s"],text=True,capture_output=True)
    if method in methods.stdout:break
    time.sleep(1)
else:
    raise SystemExit("Installed ZapIME service was not registered by Android")
subprocess.run(command+["shell","settings","put","secure","enabled_input_methods",method],check=True)
subprocess.run(command+["shell","ime","set",method],check=True)
subprocess.run(command+["shell","settings","put","secure","show_ime_with_hard_keyboard","1"],check=True)
result=subprocess.run(command+["shell","am","instrument","-w","org.zapime.android.test/.SmokeInstrumentation"],text=True,capture_output=True,timeout=180)
(output/"instrumentation.log").write_text(result.stdout+result.stderr)
for name in ("smoke.png", "keyboard.png", "candidates.png", "tools.png", "numbers.png", "home.png", "personal.png", "hierarchy.txt"):
    subprocess.run(command+["pull",f"/sdcard/Android/data/org.zapime.android.test/files/{name}",str(output/name)],check=False)
# Exercise the system's real service teardown, which calls onFinishInput from
# InputMethodService.onDestroy. Only switch away on the dedicated CI emulator.
if os.environ.get("GITHUB_ACTIONS") == "true" and re.fullmatch(r"emulator-[0-9]+", serial):
    system_methods=[line.strip() for line in methods.stdout.splitlines() if line.strip().startswith("com.android.")]
    if not system_methods:
        raise SystemExit("AOSP keyboard required for teardown acceptance")
    for action in ("enable", "set"):
        subprocess.run(command+["shell","ime",action,system_methods[0]],check=True)
    for attempt in range(50):
        teardown=subprocess.run(command+["shell","dumpsys","activity","services","org.zapime.android"],text=True,capture_output=True,check=True)
        if "ZapInputMethodService" not in teardown.stdout:
            break
        time.sleep(0.1)
    else:
        raise SystemExit("Native IME service did not finish teardown")
    (output/"ime-teardown.log").write_text(teardown.stdout)
    # Unbinding is asynchronous; allow the app main looper to finish onDestroy
    # before collecting its fatal log. This delay is confined to acceptance CI.
    time.sleep(1)
diagnostics=subprocess.run(command+["shell","dumpsys","input_method"],text=True,capture_output=True)
(output/"input-method-state.log").write_text(diagnostics.stdout)
logs=subprocess.run(command+["logcat","-d","-s","AndroidRuntime:E"],text=True,capture_output=True)
(output/"runtime-errors.log").write_text(logs.stdout)
print(result.stdout,flush=True)
if re.search(r"Process: org\.zapime\.android(?:\.test)?,", logs.stdout):
    raise SystemExit("ZapIME runtime fatal exception; inspect runtime-errors.log")
if "PASS ZapIME native keyboard:" not in result.stdout:
    raise SystemExit("Native keyboard instrumentation failed; inspect target/device-smoke")

if os.environ.get("GITHUB_ACTIONS") == "true" and re.fullmatch(r"emulator-[0-9]+", serial):
    print("PASS ZapIME native service teardown: no app fatal exception",flush=True)
else:
    print("SKIP service teardown: requires dedicated CI emulator",flush=True)
