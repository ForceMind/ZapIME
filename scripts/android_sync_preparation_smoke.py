#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Model-free synthetic JNI/vault/Activity checks on a disposable CI emulator."""
import json
import os
from pathlib import Path
import subprocess
import sys

from android_ci_device import dedicated_ci, collect_failure_state, assert_no_app_anr
from android_offline_uid import offline_product
from android_sync_preparation_report import verify_report


def main():
    if len(sys.argv) != 3:
        raise SystemExit("Dedicated CI emulator arguments required")
    adb, serial = sys.argv[1:]
    command = [adb, "-s", serial]
    if not dedicated_ci(command):
        raise SystemExit("Dedicated numeric-run GitHub emulator required")
    root = Path(__file__).resolve().parents[1]
    out = root / "target/sync-preparation-android"
    out.mkdir(parents=True, exist_ok=True)

    def run(*args, **kw):
        kw.setdefault("check", True)
        return subprocess.run(command + list(args), **kw)

    run("install", "-r", "-t", str(root / "target/android-voice-tests/ZapIME-voice-tests.apk"))
    api = int(run("shell", "getprop", "ro.build.version.sdk", text=True, capture_output=True).stdout.strip())
    # Do not erase logs or adjust global networking on a non-CI device.
    run("logcat", "-c")
    try:
        with offline_product(command, out):
            result = run("shell", "am", "instrument", "-w", "-r", "-e", "zapime_ci_sync_preparation",
                         os.environ["GITHUB_RUN_ID"] + "-" + os.environ["GITHUB_RUN_ATTEMPT"],
                         "org.zapime.android.voicetest/.VoiceInstrumentation",
                         text=True, capture_output=True, timeout=300)
            (out / "instrumentation.txt").write_text(result.stdout + result.stderr)
            if "PASS Android local sync preparation acceptance" not in result.stdout or "FAIL Android voice" in result.stdout or "INSTRUMENTATION_FAILED" in result.stdout:
                raise RuntimeError("Local sync preparation instrumentation failed")
            run("pull", "/sdcard/Android/data/org.zapime.android/files/voice-fixtures/sync-preparation-report.json",
                str(out / "sync-preparation-report.json"))
            verify_report(json.loads((out / "sync-preparation-report.json").read_text()), api)
    except BaseException:
        collect_failure_state(command, out)
        raise
    finally:
        run("pull", "/sdcard/Android/data/org.zapime.android/files/voice-fixtures/sync-preparation-report.json",
            str(out / "sync-preparation-report.json"), check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        log = run("logcat", "-d", "-b", "crash", text=True, capture_output=True).stdout
        (out / "crash.txt").write_text(log)
        if "org.zapime.android" in log and ("FATAL EXCEPTION" in log or "Fatal signal" in log):
            raise RuntimeError("Target fatal during local sync preparation")
        assert_no_app_anr(command, out)
    print("PASS local sync preparation JNI/vault/Activity; no data sync or phone claim")


if __name__ == "__main__": main()
