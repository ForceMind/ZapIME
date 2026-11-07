#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run both independent candidate gates once on one disposable CI emulator.

A failed method/JNI gate must not hide the system-IME result. Each child keeps
its own device guards, network isolation, assertions and cleanup. This wrapper
only preserves their output and combines their actual process results.
"""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

from android_ci_device import dedicated_ci


STAGES = (
    ("method", "scripts/android_candidate_ranking_smoke.py", 360),
    ("system-ime", "scripts/android_candidate_ime_smoke.py", 360),
)
CLEANUP_GRACE_SECONDS = 60


def stop_child(process, grace_seconds):
    """Let the existing Python finally blocks run before a bounded hard stop."""
    if process.poll() is not None:
        return process.returncode
    try:
        os.killpg(process.pid, signal.SIGINT)
    except ProcessLookupError:
        pass
    try:
        return process.wait(timeout=grace_seconds)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        return process.wait(timeout=10)


def run_stage(root, name, argv, timeout_seconds, cleanup_grace_seconds):
    """Always record nonzero exits, timeouts and launch errors as failures."""
    log_path = root / "target" / ("candidate-suite-" + name + ".log")
    result = {
        "name": name,
        "status": "exception",
        "exit_code": None,
        "passed": False,
        "timeout_seconds": timeout_seconds,
        "log": str(log_path.relative_to(root)),
    }
    started = time.monotonic()
    process = None
    print("START candidate suite:", name, flush=True)
    try:
        # Keep bytes already written even if the child times out or raises.
        # A new session confines any timeout signals to this gate's processes.
        with log_path.open("xb") as output:
            process = subprocess.Popen(argv, cwd=root, stdout=output,
                                       stderr=subprocess.STDOUT, shell=False,
                                       start_new_session=True)
            try:
                result["exit_code"] = process.wait(timeout=timeout_seconds)
                result["status"] = "completed"
            except subprocess.TimeoutExpired as failure:
                result["status"] = "timeout"
                result["error"] = type(failure).__name__
                result["exit_code"] = stop_child(process, cleanup_grace_seconds)
    except Exception as failure:
        if result["status"] != "timeout":
            result["status"] = "exception"
        result["error"] = type(failure).__name__ + ": " + str(failure)
        if process is not None:
            try:
                result["exit_code"] = stop_child(process, cleanup_grace_seconds)
            except Exception as cleanup_failure:
                result["stop_error"] = type(cleanup_failure).__name__ + ": " + str(cleanup_failure)
    result["elapsed_seconds"] = round(time.monotonic() - started, 3)
    result["passed"] = (result["status"] == "completed" and result["exit_code"] == 0
                        and "error" not in result)
    if log_path.is_file():
        try:
            # Raw bytes remain in the artifact. Also expose the child's original
            # diagnostic text in the job log, including failure tracebacks.
            with log_path.open("r", encoding="utf-8", errors="replace") as output:
                for line in output:
                    print(line, end="", flush=True)
        except Exception as failure:
            result["output_error"] = type(failure).__name__ + ": " + str(failure)
            result["passed"] = False
    print("RESULT candidate suite:", json.dumps(result, sort_keys=True), flush=True)
    return result


def write_summary(path, summary):
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def run_suite(root, stages, run_attempt, cleanup_grace_seconds=CLEANUP_GRACE_SECONDS):
    """The CLI supplies fixed stages; synthetic subprocesses can test aggregation."""
    output = root / "target"
    output.mkdir(parents=True, exist_ok=True)
    summary_path = output / "candidate-suite-summary.json"
    evidence = [summary_path, summary_path.with_suffix(".json.tmp")]
    evidence += [output / ("candidate-suite-" + name + ".log") for name, _, _ in stages]
    if any(path.exists() or path.is_symlink() for path in evidence):
        raise RuntimeError("Prior candidate suite evidence exists; refusing to overwrite or rerun")
    summary = {
        "schema_version": 1,
        "scope": "android-candidate-gates",
        "run_attempt": run_attempt,
        "passed": False,
        "completed": False,
        "stages": [],
        "errors": [],
    }
    write_summary(summary_path, summary)
    for name, argv, timeout_seconds in stages:
        # Never short-circuit after the method gate. A timeout/exception is still
        # a failed gate even if its existing cleanup eventually exits with zero.
        summary["stages"].append(run_stage(root, name, argv, timeout_seconds,
                                           cleanup_grace_seconds))
        try:
            write_summary(summary_path, summary)
        except Exception as failure:
            summary["errors"].append(type(failure).__name__ + ": " + str(failure))
            print("Failed to checkpoint candidate suite:", summary["errors"][-1], file=sys.stderr, flush=True)
    summary["completed"] = True
    summary["passed"] = (len(summary["stages"]) == 2 and not summary["errors"]
                         and all(stage["passed"] for stage in summary["stages"]))
    try:
        write_summary(summary_path, summary)
    except Exception as failure:
        print("Failed to save candidate suite summary:", failure, file=sys.stderr, flush=True)
        return 1
    print(("PASS" if summary["passed"] else "FAIL") + " Android candidate suite; both independent gates required", flush=True)
    return 0 if summary["passed"] else 1


def main():
    if len(sys.argv) != 3:
        raise SystemExit("Dedicated CI emulator arguments required")
    adb, serial = sys.argv[1:]
    if not dedicated_ci([adb, "-s", serial]):
        raise SystemExit("Dedicated numeric-run GitHub emulator required")
    root = Path(__file__).resolve().parents[1]
    stages = [(name, [sys.executable, "-u", str(root / script), adb, serial], timeout)
              for name, script, timeout in STAGES]
    return run_suite(root, stages, os.environ["GITHUB_RUN_ID"] + "-" + os.environ["GITHUB_RUN_ATTEMPT"])


if __name__ == "__main__":
    raise SystemExit(main())
