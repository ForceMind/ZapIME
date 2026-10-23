#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Native voice acceptance exclusively on an ephemeral, synthetic GitHub emulator."""
import os
from pathlib import Path
import subprocess
import sys
import time
import wave
import json
from android_ci_device import system_keyboard

if os.environ.get("GITHUB_ACTIONS") != "true" or len(sys.argv) != 3:
    raise SystemExit("Ephemeral GitHub emulator required")
adb, serial = sys.argv[1:]
if not serial.startswith("emulator-") or not serial[len("emulator-"):].isdigit():
    raise SystemExit("Dedicated emulator serial required")
root = Path(__file__).resolve().parents[1]
out = root / "target/voice-android"
out.mkdir(parents=True, exist_ok=True)


def run(*args, **options):
    options.setdefault("check",True)
    return subprocess.run([adb, "-s", serial, *args], **options)


with wave.open(str(root / "target/voice-probe/synthetic.wav")) as source:
    if source.getnchannels() != 1 or source.getsampwidth() != 2 or source.getframerate() != 16000:
        raise SystemExit("Synthetic 16 kHz PCM16 mono fixture required")
    (out / "synthetic.pcm").write_bytes(source.readframes(source.getnframes()))
run("install", "-r", "-t", str(root / "target/android-voice-tests/ZapIME-voice-tests.apk"))
# Revoke before instrumentation: revoking a runtime permission can kill its UID.
run("shell", "pm", "revoke", "org.zapime.android", "android.permission.RECORD_AUDIO")
# Test-only APK assets carry public model/synthetic PCM. Product storage grants
# and Android/data ownership are never changed just to deliver CI fixtures.
# App has no INTERNET permission. Also disconnect the emulator's network radios.
run("shell", "svc", "wifi", "disable")
run("shell", "svc", "data", "disable")
run("logcat", "-c")
try:
    result = run("shell", "am", "instrument", "-w", "-r", "org.zapime.android.voicetest/.VoiceInstrumentation", text=True, capture_output=True, timeout=600)
    (out / "instrumentation.txt").write_text(result.stdout + result.stderr)
    print(result.stdout)
    if "PASS Android offline voice native acceptance" not in result.stdout or "FAIL Android voice" in result.stdout or "INSTRUMENTATION_FAILED" in result.stdout:
        raise SystemExit("Native voice acceptance failed")
    run("pull","/sdcard/Android/data/org.zapime.android/files/voice-fixtures/native-report.json",str(out/"native-report.json"))
    method="org.zapime.android/.ZapInputMethodService"
    for action in ("enable","set"):
        run("shell","ime",action,method)
    result=run("shell","am","instrument","-w","-r","-e","voice_only","true","org.zapime.android.test/.SmokeInstrumentation",text=True,capture_output=True,timeout=600)
    (out/"ui-instrumentation.txt").write_text(result.stdout+result.stderr)
    print(result.stdout)
    if "PASS ZapIME voice UI:" not in result.stdout:
        raise SystemExit("Voice keyboard UI acceptance failed")
    for name in ("voice-recording.png","voice-stopped.png"):
        run("pull","/sdcard/Android/data/org.zapime.android.test/files/"+name,str(out/name))
    system_method,proof=system_keyboard([adb,"-s",serial])
    (out/"system-keyboard.json").write_text(json.dumps(proof,indent=2)+"\n")
    for action in ("enable","set"):run("shell","ime",action,system_method)
    for attempt in range(50):
        teardown=run("shell","dumpsys","activity","services","org.zapime.android",text=True,capture_output=True).stdout
        if "ZapInputMethodService" not in teardown:break
        time.sleep(.1)
    else:raise SystemExit("Voice IME service did not exit")
    (out/"ime-teardown.txt").write_text(teardown)
    time.sleep(1)
finally:
    # A failed early UI assertion may not have reached either voice screenshot.
    # Always retain its actual failure hierarchy/screenshot before checking fatals.
    for name in ("voice-recording.png","voice-stopped.png","hierarchy.txt","smoke.png"):
        run("pull","/sdcard/Android/data/org.zapime.android.test/files/"+name,str(out/name),check=False,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    run("pull","/sdcard/Android/data/org.zapime.android/files/voice-fixtures/native-report.json",str(out/"native-report.json"),check=False,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    log = run("logcat", "-d", "-b", "crash", text=True, capture_output=True).stdout
    (out / "crash.txt").write_text(log)
    if "org.zapime.android" in log and ("FATAL EXCEPTION" in log or "Fatal signal" in log):
        raise SystemExit("Target process fatal during voice acceptance")
print("PASS isolated Android native voice: verified model, real synthetic decode and emulator microphone lifecycle")
