#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Two clean synthetic Android CI rounds; refuses non-CI use and preserves each result."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("Ephemeral GitHub emulator required; refuses local app-data clearing")
run = os.environ.get("GITHUB_RUN_ID", "")
attempt = os.environ.get("GITHUB_RUN_ATTEMPT", "")
if not run.isdigit() or not attempt.isdigit() or len(sys.argv) != 3:
    raise SystemExit("Expected CI run/attempt and adb path plus dedicated emulator serial")
adb, serial = sys.argv[1:]
if not serial.startswith("emulator-") or not serial[len("emulator-"):].isdigit():
    raise SystemExit("Dedicated emulator serial required")
root = Path(__file__).resolve().parents[1]
output = root / "target/device-smoke"
evidence = root / "target/device-smoke-evidence" / f"{run}-{attempt}"
evidence.mkdir(parents=True, exist_ok=False)
subprocess.run([adb, "-s", serial, "install", "-r",
                str(root / "target/android-tests/ZapIME-tests.apk")], check=True)
methods = subprocess.check_output([adb, "-s", serial, "shell", "ime", "list", "-a", "-s"], text=True)
system_methods = [line.strip() for line in methods.splitlines() if line.strip().startswith("com.android.")]
if not system_methods:
    raise SystemExit("AOSP system keyboard required for isolated fixture reset")
for round_number in (1, 2):
    # Unbind the previous ZapIME instance before clearing it; selecting the same
    # already-current ID after pm clear need not reopen its input window.
    for action in ("enable", "set"):
        subprocess.run([adb, "-s", serial, "shell", "ime", action, system_methods[0]], check=True)
    # Only our two synthetic packages on the CI emulator are cleared.
    for package in ("org.zapime.android", "org.zapime.android.test"):
        result = subprocess.run([adb, "-s", serial, "shell", "pm", "clear", package],
                                text=True, capture_output=True, check=True)
        if result.stdout.strip() != "Success":
            raise SystemExit("Synthetic package data was not cleared")
    if output.exists():
        shutil.rmtree(output)
    # This runner is guarded above as an ephemeral CI emulator. Isolate each
    # round's fatal log evidence, including the graceful teardown exercised below.
    subprocess.run([adb, "-s", serial, "logcat", "-c"], check=True)
    print(f"Native input clean round {round_number}/2", flush=True)
    try:
        subprocess.run([sys.executable, str(root / "scripts/android_smoke.py"), adb, serial],
                       check=True, cwd=root)
    finally:
        if output.exists():
            shutil.copytree(output, evidence / f"round-{round_number}")
