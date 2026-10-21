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
for round_number in (1, 2):
    # Only our two synthetic packages on the CI emulator are cleared.
    for package in ("org.zapime.android", "org.zapime.android.test"):
        result = subprocess.run([adb, "-s", serial, "shell", "pm", "clear", package],
                                text=True, capture_output=True, check=True)
        if result.stdout.strip() != "Success":
            raise SystemExit("Synthetic package data was not cleared")
    if output.exists():
        shutil.rmtree(output)
    print(f"Native input clean round {round_number}/2", flush=True)
    try:
        subprocess.run([sys.executable, str(root / "scripts/android_smoke.py"), adb, serial],
                       check=True, cwd=root)
    finally:
        if output.exists():
            shutil.copytree(output, evidence / f"round-{round_number}")
