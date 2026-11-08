#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Production candidate worker through a real system IME on a disposable CI AVD.

The independent host injects touchscreen taps into the installed keyboard and
reads its own Editable. It does not call JNI, write a product preference, replace
the production dictionary/model, or provide a fabricated ranking result.
"""
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time

from android_ci_device import (
    assert_no_app_anr,
    collect_failure_state,
    dedicated_ci,
    system_keyboard,
    write_diagnostic,
)
from android_offline_uid import offline_product


PRODUCT = "org.zapime.android"
HOST = "org.zapime.android.test"
METHOD = PRODUCT + "/.ZapInputMethodService"
MODEL = "msime-keystroke-8m-rerank-v2"
REMOTE = "/sdcard/Android/data/" + HOST + "/files/"
PROBE_FILES = (
    "candidate-ime-report.json",
    "candidate-ime-service-before.txt",
    "candidate-ime-service-scored.txt",
    "candidate-ime-service-after.txt",
    "candidate-ime-service-failure.txt",
    "candidate-ime-host-lifecycle.txt",
    "candidate-ime-candidates.png",
    "candidate-ime-committed.png",
    "candidate-ime-failure.png",
)


def restore_setting_args(name, value):
    """Quote the remote shell as well as the local adb argument boundary."""
    if value in (None, "null", ""):
        arguments = ["settings", "delete", "secure", name]
    else:
        arguments = ["settings", "put", "secure", name, value]
    # adb shell rejoins its arguments before the device shell parses them.
    # Enabled IMEs can include semicolon-delimited subtype IDs; preserve them.
    return ["shell", shlex.join(arguments)]


def verify_report(report, token, api):
    """Keep a model/Editable method probe distinct from this system-IME gate."""
    if (not isinstance(report, dict) or report.get("schema_version") != 2
            or report.get("scope") != "production-system-ime-candidate-ranking"
            or report.get("run_attempt") != token or report.get("api_level") != api
            or report.get("model_id") != MODEL):
        raise ValueError("Wrong system-IME report identity")
    for field in ("passed", "synthetic", "system_ime_executed", "production_lexicon",
                  "production_model", "ranking_enabled", "ranking_eligible", "candidate_touch", "current_ranking_applied",
                  "empty_context_base_order", "context_seed_touch"):
        if report.get(field) is not True:
            raise ValueError("Missing system-IME evidence: " + field)
    for field in ("product_preferences_written", "human_touch_or_phone_latency", "editor_composing"):
        if report.get(field) is not False:
            raise ValueError("Unexpected system-IME claim: " + field)
    before, after = report.get("scored_before"), report.get("scored_after")
    if type(before) is not int or type(after) is not int or not 0 <= before < after:
        raise ValueError("Production scored counter did not increase")
    current, applied = report.get("scoring_input_revision"), report.get("scoring_applied_revision")
    if type(current) is not int or type(applied) is not int or current < 0 or applied != current:
        raise ValueError("Only an earlier input prefix was ranked")
    submitted, empty, submitted_after = (report.get("submitted_before"), report.get("submitted_after_empty"),
                                          report.get("submitted_after"))
    if (type(submitted) is not int or type(empty) is not int or type(submitted_after) is not int
            or not 0 <= submitted == empty < submitted_after):
        raise ValueError("Empty context submitted work or contextual inference was not submitted")
    seed = report.get("context_seed_text")
    if (not isinstance(seed, str) or len(seed) not in (2, 4) or not all(0x3400 <= ord(char) <= 0x9fff for char in seed)):
        raise ValueError("No real accepted context seed")
    selected = report.get("selected_text")
    if (not isinstance(selected, str) or len(selected) != 2
            or not all(0x3400 <= ord(char) <= 0x4DBF or 0x4E00 <= ord(char) <= 0x9FFF
                       or 0xF900 <= ord(char) <= 0xFAFF or 0x20000 <= ord(char) <= 0x323AF for char in selected)
            or seed + selected != report.get("actual_editable")):
        raise ValueError("Visible candidate was not committed exactly")
    if (type(report.get("visible_han_candidates")) is not int or report["visible_han_candidates"] < 2
            or type(report.get("touch_keypresses")) is not int or report["touch_keypresses"] < 6
            or type(report.get("observed_connection_serial")) is not int or report["observed_connection_serial"] < 1):
        raise ValueError("Incomplete real key/host evidence")
    attempts = report.get("attempts")
    if not isinstance(attempts, list) or not 1 <= len(attempts) <= 2:
        raise ValueError("Unbounded or missing cold/warm attempts")
    if (type(report.get("context_seed_commits")) is not int
            or report["context_seed_commits"] != len(attempts) or len(seed) != 2 * len(attempts)):
        raise ValueError("Missing accepted context after a clearing backspace")
    if report["touch_keypresses"] != (11 if len(attempts) == 1 else 28):
        raise ValueError("Unexpected touchscreen retry outside the frozen attempts")
    for index, attempt in enumerate(attempts):
        if (not isinstance(attempt, dict) or attempt.get("code") != ("shijie", "tianqi")[index]
                or attempt.get("attempt") != index + 1
                or type(attempt.get("scored_before")) is not int
                or type(attempt.get("scored_after")) is not int
                or type(attempt.get("input_revision")) is not int
                or type(attempt.get("applied_revision")) is not int
                or attempt["input_revision"] < 0 or attempt["applied_revision"] < -1
                or not before <= attempt["scored_before"] <= attempt["scored_after"] <= after):
            raise ValueError("Invalid frozen pinyin attempt")
        if attempt.get("scored_increased") is not (attempt["scored_after"] > attempt["scored_before"]):
            raise ValueError("Scored delta was not preserved")
        applied_now = (attempt["scored_after"] > attempt["scored_before"] and attempt.get("state") == "ready"
                       and attempt["applied_revision"] == attempt["input_revision"])
        if attempt.get("current_ranking_applied") is not applied_now:
            raise ValueError("Earlier prefix was presented as current scoring")
    final = attempts[-1]
    if (final.get("scored_increased") is not True or final.get("current_ranking_applied") is not True
            or final.get("state") != "ready" or final["input_revision"] != current
            or final["applied_revision"] != applied
            or final["scored_after"] <= final["scored_before"]):
        raise ValueError("Enabled alone is not actual production scoring")
    if len(attempts) == 2 and attempts[0].get("current_ranking_applied") is not False:
        raise ValueError("Unnecessary second attempt changed the frozen gate")


def main():
    if len(sys.argv) != 3:
        raise SystemExit("Dedicated CI emulator arguments required")
    adb, serial = sys.argv[1:]
    command = [adb, "-s", serial]
    if not dedicated_ci(command):
        raise SystemExit("Numeric-run GitHub emulator required; refuses local data clearing")
    root = Path(__file__).resolve().parents[1]
    output = root / "target/candidate-ime-android"
    output.mkdir(parents=True, exist_ok=True)
    if (output / "run-summary.json").exists():
        raise SystemExit("Existing system-IME evidence must be preserved; use a fresh CI workspace")
    token = os.environ["GITHUB_RUN_ID"] + "-" + os.environ["GITHUB_RUN_ATTEMPT"]
    summary = {
        "schema_version": 1,
        "scope": "production-system-ime-candidate-ranking",
        "run_attempt": token,
        "passed": False,
        "instrumentation_verified": False,
        "system_keyboard_restored": False,
        "service_exited": False,
        "settings_restored": False,
        "initial_data_clear": {},
        "final_data_clear": {},
        "errors": [],
    }
    restore_method = None
    original_enabled = None
    original_hardware = None
    installed_host = False
    cleared_runtime_log = False
    stage = "setup"

    def run(*args, **options):
        options.setdefault("check", True)
        options.setdefault("timeout", 30)
        return subprocess.run(command + list(args), **options)

    def text(*args, **options):
        return run(*args, text=True, capture_output=True, **options).stdout.strip()

    def error(label, failure):
        summary["errors"].append({"stage": label, "type": type(failure).__name__, "reason": str(failure)[:500]})

    def restore_setting(name, value):
        run(*restore_setting_args(name, value))

    def pull_probe_files():
        for name in PROBE_FILES:
            run("pull", REMOTE + name, str(output / name), check=False,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)

    def clear(package, phase):
        result = text("shell", "pm", "clear", package)
        if result != "Success":
            raise RuntimeError("Synthetic package clear rejected")
        summary[phase][package] = True

    try:
        stage = "system_keyboard_baseline"
        verified, proof = system_keyboard(command)
        api = proof["api_level"]
        summary["api_level"] = api
        original_default = text("shell", "settings", "get", "secure", "default_input_method")
        original_enabled = text("shell", "settings", "get", "secure", "enabled_input_methods")
        original_hardware = text("shell", "settings", "get", "secure", "show_ime_with_hard_keyboard")
        registered = set(text("shell", "ime", "list", "-a", "-s").splitlines())
        packages = {line.removeprefix("package:") for line in text("shell", "pm", "list", "packages", "-s").splitlines() if line.startswith("package:")}
        original_is_system = (original_default in registered and "/" in original_default
                              and original_default.split("/", 1)[0] in packages
                              and (api >= 35 or original_default.startswith("com.android.")))
        restore_method = original_default if original_is_system else verified
        proof.update({"original_default": original_default, "restore_ime": restore_method,
                      "original_system_default": original_is_system,
                      "original_enabled": original_enabled, "original_hardware_keyboard": original_hardware})
        (output / "system-keyboard.json").write_text(json.dumps(proof, indent=2) + "\n")
        for action in ("enable", "set"):
            run("shell", "ime", action, restore_method)

        stage = "prepare_fresh_synthetic_packages"
        run("install", "-r", "-t", str(root / "target/android-tests/ZapIME-tests.apk"), timeout=90)
        installed_host = True
        if METHOD not in registered:
            raise RuntimeError("Installed product IME is not registered")
        for package in (PRODUCT, HOST):
            clear(package, "initial_data_clear")
        # Restricted above to a numeric-run disposable CI emulator. The earlier
        # method/JNI gate retains its own logs; it is not counted in this report.
        run("logcat", "-c")
        cleared_runtime_log = True

        stage = "activate_product_offline"
        with offline_product(command, output):
            for action in ("enable", "set"):
                run("shell", "ime", action, METHOD)
            if text("shell", "settings", "get", "secure", "default_input_method") != METHOD:
                raise RuntimeError("Product IME was not actually selected")
            run("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
            stage = "instrumentation"
            result = run("shell", "am", "instrument", "-w", "-r", "-e", "candidate_only", "true",
                         "-e", "zapime_ci_candidate_ime", token,
                         "org.zapime.android.test/.SmokeInstrumentation",
                         text=True, capture_output=True, check=False, timeout=150)
            write_diagnostic(output / "instrumentation.txt", result.stdout + result.stderr)
            if (result.returncode != 0 or "PASS ZapIME system IME candidate enhancement:" not in result.stdout
                    or "FAIL ZapIME" in result.stdout or "INSTRUMENTATION_FAILED" in result.stdout):
                raise RuntimeError("System-IME instrumentation failed")
            stage = "verify_system_ime_report"
            pull_probe_files()
            verify_report(json.loads((output / "candidate-ime-report.json").read_text()), token, api)
            summary["instrumentation_verified"] = True
    except BaseException as failure:
        if isinstance(failure, subprocess.TimeoutExpired):
            stdout = failure.stdout or b""
            stderr = failure.stderr or b""
            if isinstance(stdout, bytes):
                stdout = stdout.decode("utf-8", errors="replace")
            if isinstance(stderr, bytes):
                stderr = stderr.decode("utf-8", errors="replace")
            write_diagnostic(output / "instrumentation.txt", stdout + stderr + "\ncontrolled_timeout\n")
        error(stage, failure)
    finally:
        # Keep original failure/report evidence before destroying only our
        # synthetic host. Never force-stop the product to fake service teardown.
        if installed_host:
            try:
                pull_probe_files()
            except BaseException as failure:
                error("pull_probe_evidence", failure)
        if summary["errors"]:
            try:
                collect_failure_state(command, output)
            except BaseException as failure:
                error("collect_failure_state", failure)
        try:
            state = text("shell", "dumpsys", "-t", "3", "input_method", timeout=8)
            write_diagnostic(output / "input-method-state.txt", state)
        except BaseException as failure:
            error("input_method_state", failure)
        if installed_host:
            try:
                run("shell", "am", "force-stop", HOST)
            except BaseException as failure:
                error("stop_synthetic_host", failure)
        if restore_method is not None:
            try:
                for action in ("enable", "set"):
                    run("shell", "ime", action, restore_method)
                summary["system_keyboard_restored"] = text("shell", "settings", "get", "secure", "default_input_method") == restore_method
                if not summary["system_keyboard_restored"]:
                    raise RuntimeError("System keyboard selection was not restored")
                deadline = time.monotonic() + 8
                teardown = ""
                while time.monotonic() < deadline:
                    teardown = text("shell", "dumpsys", "-t", "3", "activity", "services", PRODUCT, timeout=8)
                    if "ZapInputMethodService" not in teardown:
                        summary["service_exited"] = True
                        break
                    time.sleep(0.1)
                write_diagnostic(output / "ime-teardown.txt", teardown)
                if not summary["service_exited"]:
                    raise RuntimeError("Product IME did not actually exit after switching")
                time.sleep(1)  # Confined to CI: allow the final onDestroy callbacks to finish.
            except BaseException as failure:
                error("restore_system_and_teardown", failure)
        if cleared_runtime_log:
            try:
                crash = text("logcat", "-d", "-b", "crash", timeout=10)
                write_diagnostic(output / "runtime-errors.txt", crash)
                if "org.zapime.android" in crash and ("FATAL EXCEPTION" in crash or "Fatal signal" in crash or "Cmdline:" in crash):
                    raise RuntimeError("Product/test host runtime fatal")
                assert_no_app_anr(command, output)
            except BaseException as failure:
                error("runtime_fatal_or_anr", failure)
        # After the real teardown result and fatal evidence have been recorded,
        # remove this run's synthetic model/context/dictionary learning data.
        for package in (PRODUCT, HOST):
            if package in summary["initial_data_clear"]:
                try:
                    clear(package, "final_data_clear")
                except BaseException as failure:
                    error("clear_synthetic_package", failure)
        if restore_method is not None:
            try:
                restore_setting("show_ime_with_hard_keyboard", original_hardware)
                # In a normal fresh image the original system default was also
                # enabled. If it was absent, retain only the proven fallback in
                # addition to the original list; never return to the product IME.
                enabled = [] if original_enabled in (None, "null", "") else original_enabled.split(":")
                if not any(item.split(";", 1)[0] == restore_method for item in enabled):
                    enabled.append(restore_method)
                restore_setting("enabled_input_methods", ":".join(enabled))
                run("shell", "ime", "set", restore_method)
                summary["settings_restored"] = (
                    text("shell", "settings", "get", "secure", "show_ime_with_hard_keyboard") in (original_hardware, "null" if original_hardware in (None, "") else original_hardware)
                    and text("shell", "settings", "get", "secure", "enabled_input_methods") == ":".join(enabled)
                    and text("shell", "settings", "get", "secure", "default_input_method") == restore_method
                )
                if not summary["settings_restored"]:
                    raise RuntimeError("IME settings restoration was not verified")
            except BaseException as failure:
                error("restore_ime_settings", failure)
        try:
            proof = json.loads((output / "offline-uid.json").read_text())
            if any(proof.get(field) is not True for field in ("ipv4_reject", "ipv6_reject", "removed")):
                raise RuntimeError("Incomplete offline UID isolation/cleanup")
            summary["offline_uid_verified"] = True
        except BaseException as failure:
            error("offline_uid_evidence", failure)
        summary["passed"] = (
            not summary["errors"] and summary["instrumentation_verified"]
            and summary["system_keyboard_restored"] and summary["service_exited"] and summary["settings_restored"]
            and all(summary["final_data_clear"].get(package) is True for package in (PRODUCT, HOST))
        )
        (output / "run-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    if not summary["passed"]:
        raise SystemExit("System-IME candidate gate failed; original evidence and cleanup results retained")
    print("PASS production system-IME candidate worker: default on, real model scoring, touchscreen pinyin and exact Editable commit; restored system keyboard and verified actual service exit")


if __name__ == "__main__":
    main()
