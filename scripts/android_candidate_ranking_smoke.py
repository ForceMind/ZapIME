#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Real candidate model/JNI/Editable checks on a disposable CI emulator.

This does not activate an IME, operate a phone, or measure typing accuracy.
The target APK contains the reviewed 8M candidate model; the harness contains
only synthetic test code. Existing voice-model and system-IME gates stay separate.
"""
import json
import os
from pathlib import Path
import subprocess
import sys

from android_ci_device import dedicated_ci, assert_no_app_anr
from android_offline_uid import offline_product
from android_candidate_ranking_report import verify_report


def verify_restart_methods(report, api):
    expected = {"commit_finish_reentry", "compose_finish_reentry", "begin_batch_finish_guard", "delete_getter_finish_guard",
                "ordinary_begin_finish_reentry", "ordinary_region_finish_reentry", "ordinary_end_finish_reentry"}
    cases = report.get("cases") if isinstance(report, dict) else None
    if (not isinstance(report, dict) or report.get("synthetic") is not True
            or report.get("api_level") != api or api < 31 or not isinstance(cases, list)
            or len(cases) != len(expected) or {case.get("id") for case in cases if isinstance(case, dict)} != expected
            or any(not isinstance(case, dict) or case.get("passed") is not True for case in cases)):
        raise ValueError("Incomplete verified-restart Editable method evidence")


def main():
    if len(sys.argv) != 3:
        raise SystemExit("Dedicated CI emulator arguments required")
    adb, serial = sys.argv[1:]
    command = [adb, "-s", serial]
    if not dedicated_ci(command):
        raise SystemExit("Dedicated numeric-run GitHub emulator required")
    root = Path(__file__).resolve().parents[1]
    output = root / "target/candidate-ranking-android"
    output.mkdir(parents=True, exist_ok=True)
    remote = "/sdcard/Android/data/org.zapime.android/files/candidate-fixtures/candidate-ranking-report.json"

    def run(*args, **options):
        options.setdefault("check", True)
        options.setdefault("timeout", 30)
        return subprocess.run(command + list(args), **options)

    run("install", "-r", "-t", str(root / "target/android-voice-tests/ZapIME-voice-tests.apk"), timeout=90)
    api = int(run("shell", "getprop", "ro.build.version.sdk", text=True, capture_output=True).stdout.strip())
    run("logcat", "-c")
    try:
        with offline_product(command, output):
            result = run("shell", "am", "instrument", "-w", "-r", "-e", "zapime_ci_candidate_ranking",
                         os.environ["GITHUB_RUN_ID"] + "-" + os.environ["GITHUB_RUN_ATTEMPT"],
                         "org.zapime.android.voicetest/.VoiceInstrumentation",
                         text=True, capture_output=True, timeout=180)
            (output / "instrumentation.txt").write_text(result.stdout + result.stderr)
            if ("PASS Android offline candidate ranking acceptance" not in result.stdout
                    or "FAIL Android voice" in result.stdout or "INSTRUMENTATION_FAILED" in result.stdout):
                raise RuntimeError("Candidate ranking instrumentation failed")
            run("pull", remote, str(output / "candidate-ranking-report.json"))
            verify_report(json.loads((output / "candidate-ranking-report.json").read_text()), api)
            if api >= 31:
                run("pull", remote.replace("candidate-ranking-report.json","verified-restart-method-report.json"), str(output / "verified-restart-method-report.json"))
                verify_restart_methods(json.loads((output / "verified-restart-method-report.json").read_text()), api)
    finally:
        run("pull", remote.replace("candidate-ranking-report.json","verified-restart-method-report.json"), str(output / "verified-restart-method-report.json"), check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
        run("pull", remote, str(output / "candidate-ranking-report.json"), check=False,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        crash = run("logcat", "-d", "-b", "crash", text=True, capture_output=True).stdout
        (output / "crash.txt").write_text(crash)
        if "org.zapime.android" in crash and ("FATAL EXCEPTION" in crash or "Fatal signal" in crash):
            raise RuntimeError("Target fatal during candidate ranking")
        assert_no_app_anr(command, output)
    proof = json.loads((output / "offline-uid.json").read_text())
    if any(proof.get(field) is not True for field in ("ipv4_reject", "ipv6_reject", "removed")):
        raise RuntimeError("Incomplete offline network isolation evidence")
    print("PASS real offline 8M candidate model, native IDs and Android Editable; no system IME or phone claim")


if __name__ == "__main__":
    main()
