#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Native voice acceptance exclusively on an ephemeral, synthetic GitHub emulator."""
import os
from pathlib import Path
import subprocess
import sys
import wave

if os.environ.get("GITHUB_ACTIONS") != "true" or len(sys.argv) != 3:
    raise SystemExit("Ephemeral GitHub emulator required")
adb, serial = sys.argv[1:]
if not serial.startswith("emulator-") or not serial[len("emulator-"):].isdigit():
    raise SystemExit("Dedicated emulator serial required")
root = Path(__file__).resolve().parents[1]
out = root / "target/voice-android"
out.mkdir(parents=True, exist_ok=True)


def run(*args, **options):
    return subprocess.run([adb, "-s", serial, *args], check=True, **options)


with wave.open(str(root / "target/voice-probe/synthetic.wav")) as source:
    if source.getnchannels() != 1 or source.getsampwidth() != 2 or source.getframerate() != 16000:
        raise SystemExit("Synthetic 16 kHz PCM16 mono fixture required")
    (out / "synthetic.pcm").write_bytes(source.readframes(source.getnframes()))
run("install", "-r", str(root / "target/android-voice-tests/ZapIME-voice-tests.apk"))
# Revoke before instrumentation: revoking a runtime permission can kill its UID.
run("shell", "pm", "revoke", "org.zapime.android", "android.permission.RECORD_AUDIO")
run("shell", "mkdir", "-p", "/sdcard/Android/data/org.zapime.android/files/voice-fixtures")
for path, name in ((root / "target/ZapIME-XASR-model.zip", "model.zip"), (out / "synthetic.pcm", "synthetic.pcm")):
    run("push", str(path), "/sdcard/Android/data/org.zapime.android/files/voice-fixtures/" + name)
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
finally:
    log = run("logcat", "-d", "-b", "crash", text=True, capture_output=True).stdout
    (out / "crash.txt").write_text(log)
    if "org.zapime.android" in log and ("FATAL EXCEPTION" in log or "Fatal signal" in log):
        raise SystemExit("Target process fatal during voice acceptance")
print("PASS isolated Android native voice: verified model, real synthetic decode and emulator microphone lifecycle")
