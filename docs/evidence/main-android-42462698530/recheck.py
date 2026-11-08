# SPDX-License-Identifier: GPL-3.0-only
"""Offline original-report recheck with validators from the exact tested source."""
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SOURCE = "5a0dc56290be9c2458ee6e7e7ddafc51930dd593"
RUN = "42462698530-1"


def load(path):
    return json.loads(path.read_bytes())


def cases(report, expected, editable=False):
    rows = report["cases"]
    assert report["synthetic"] is True and isinstance(rows, list)
    assert len(rows) == len(expected) and {r["id"] for r in rows} == set(expected)
    assert all(r["passed"] is True for r in rows)
    if editable:
        assert report["actual_jni_decoded_text_used"] is True
        assert all(r["actual_editable_verified"] is True for r in rows)
    return len(rows)


def text(path):
    return path.read_text(encoding="utf-8")


def healthy(directory):
    for path in directory.glob("*errors.*"):
        assert not re.search(r"ANR in org\.zapime\.android(?:\.|\s|\(|$)", text(path))
        assert not re.search(r"Process: org\.zapime\.android(?:\.test)?,", text(path))
    for path in directory.glob("crash.txt"):
        assert not path.read_bytes(), "Nonempty target crash evidence"


def system(directory, api, teardown):
    proof = load(directory / "system-keyboard.json")
    assert type(proof["api_level"]) is int and proof["api_level"] == api
    assert proof["system_package_verified"] is True and proof["source"] == "pm list packages -s"
    if api == 28:
        assert proof["aosp"] is True
    assert "ZapInputMethodService" not in text(directory / teardown)
    return proof["selected_ime"]


def offline(directory):
    proof = load(directory / "offline-uid.json")
    assert proof["package"] == "org.zapime.android"
    assert type(proof["uid"]) is int and proof["uid"] > 0
    assert all(proof[k] is True for k in ("ipv4_reject", "ipv6_reject", "removed"))


def main():
    run = load(HERE / "workflow-run.json")
    assert run["id"] == 42462698530 and run["run_attempt"] == 1 and run["head_sha"] == SOURCE
    assert run["status"] == "completed" and run["conclusion"] == "success"
    jobs = load(HERE / "workflow-jobs.json")["jobs"]
    assert len(jobs) == 6 and all(j["status"] == "completed" and j["conclusion"] == "success" for j in jobs)
    with tempfile.TemporaryDirectory(prefix="fixed-android-report-validators-") as temporary:
        archive = subprocess.check_output(["git", "archive", SOURCE, "scripts", "third_party"], cwd=REPO)
        with tarfile.open(fileobj=io.BytesIO(archive)) as source:
            source.extractall(temporary, filter="data")
        sys.path.insert(0, str(Path(temporary) / "scripts"))
        from android_voicekey_model_smoke import (COMMIT_CASES, MODEL_CASES, verify_reports,
            verify_watchdog_report, verify_boundary_report, verify_permission_cases)
        from android_sync_preparation_report import verify_report as verify_preparation
        from voicekey_cpu_probe import trusted_lock
        lock, _ = trusted_lock(Path(temporary) / "third_party/voicekey-model-lock.json")
        result = {"source_commit": SOURCE, "source_tree": load(HERE / "git-source.json")["tree"]["sha"],
                  "run_id": 42462698530, "attempt": 1, "jobs_success": 6,
                  "native": [], "voicekey": [], "phone_arm_natural_speech": "NOT RUN"}
        for api, folder in ((28, "development"), (35, "native-api35"), (36, "native-api36")):
            base = HERE / folder / "records"
            rounds = []
            for number in (1, 2):
                d = base / f"device-smoke-evidence/{RUN}/round-{number}"
                assert "PASS ZapIME native keyboard:" in text(d / "instrumentation.log")
                assert not any(x in text(d / "instrumentation.log") for x in ("FAIL ZapIME", "INSTRUMENTATION_FAILED"))
                method = system(d, api, "ime-teardown.log")
                state = text(d / "input-method-state.log")
                assert any(f"{k}={method}" in state for k in ("mCurMethodId", "mSelectedMethodId"))
                healthy(d)
                rounds.append({"round": number, "system_ime": method, "actual_service_exit": True})
            d = base / "voice-android"
            codes = load(d / "combined-gates.json")
            assert set(codes) == {"android_smoke_repeat.py", "android_voice_smoke.py"}
            assert all(type(v) is int and v == 0 for v in codes.values())
            assert "PASS Android offline voice native acceptance" in text(d / "instrumentation.txt")
            assert "PASS ZapIME voice UI:" in text(d / "ui-instrumentation.txt")
            system(d, api, "ime-teardown.txt"); offline(d); healthy(d)
            native = load(d / "native-report.json")
            assert native["api_level"] == api and native["synthetic"] is True and native["expected_fragments_passed"] is True
            count = cases(load(d / "empty-pcm-report.json"), {"no_samples", "all_zero", "ignored_tail", "positive_lsb", "negative_lsb", "cancelled_empty"})
            commit = load(d / "voice-commit-report.json")
            count += cases(commit, COMMIT_CASES, editable=True); verify_permission_cases(commit["cases"])
            count += cases(load(d / "sync-connection-report.json"), {"strict_schema", "identity_binding", "keystore_namespace", "store_roundtrip_switch", "worker_guard", "pre_cancel", "secure_activity_lifecycle"})
            count += cases(load(d / "sync-profile-report.json"), {"absent_no_create", "canonical_binding", "cross_account_preserves_vault", "invalid_future_fail_closed", "worker_guard"})
            preparation = load(d / "sync-preparation-report.json"); verify_preparation(preparation, api); count += len(preparation["cases"])
            backup = load(d / "local-backup-report.json")
            count += cases(backup, {"native_snapshot", "authentication_and_recovery", "nonempty_bound_preserved", "worker_and_limits", "sensitive_activity", "system_saf_export_and_recovery", "explicit_sensitive_copy_and_clear", "stop_clears_sensitive_state"})
            assert backup["cleanup_completed"] is True
            boundary = load(d / "voicekey-boundary-report.json"); verify_boundary_report(boundary); count += len(boundary["cases"])
            result["native"].append({"api": api, "clean_input_rounds": rounds, "actual_voice_ime_exit": True,
                                      "jni_local_report_cases": count, "offline_uid_removed": True})
        for api in (28, 35, 36):
            d = HERE / f"voicekey-api{api}/records/voicekey-android"
            model, commit = load(d / "voicekey-model-report.json"), load(d / "voice-commit-report.json")
            assert model["api_level"] == api
            verify_reports(model, commit, lock)
            verify_boundary_report(load(d / "voicekey-boundary-report.json"))
            verify_watchdog_report(load(d / "voicekey-watchdog-report.json"), lock)
            summary = load(d / "acceptance-summary.json")
            assert summary["status"] == "PASS" and summary["model_weights_uploaded"] is False
            assert summary["saf_jni_editable"] == "PASS" and summary["activated_system_ime_and_emulator_audio_record"] == "PASS"
            assert all(summary[k] is True for k in ("actual_ime_service_exit", "emulator_soft_keyboard_policy_restored", "shared_document_removed"))
            assert "PASS ZapIME voice UI:" in text(d / "ime-instrumentation.txt")
            assert "PASS Android VoiceKey official model acceptance" in text(d / "model-instrumentation.txt")
            system(d, api, "ime-teardown.txt"); offline(d); healthy(d)
            assert "ANR in org.zapime.android" not in text(d / "last-anr.txt")
            result["voicekey"].append({"api": api, "fixed_model_and_commit_cases": len(MODEL_CASES) + len(COMMIT_CASES),
                "boundary_cases": 5, "watchdog_report": True, "actual_service_exit": True, "shared_fixture_removed": True})
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
