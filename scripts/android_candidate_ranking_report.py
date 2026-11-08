#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Validate fixed Android candidate-model JNI evidence, without claiming system IME acceptance."""
import argparse
import hashlib
import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODEL_ID = "msime-keystroke-8m-rerank-v2"
MODEL_SHA = "e036febfb8f7bdf941f1637be127ff539ea95b937a4c2a27f4c6ab897138fc6c"
FIXTURE_ID = "candidate-ranking-android-v1"
FIXTURE = "ni/你/900\nhao/好/900\nni'hao/拟好/900\nni'hao/逆号/800\nni'hao/你好/100\ncontext=朋友见面先说\nscore-order=拟好|逆号|你好\n"
FIXTURE_SHA = hashlib.sha256(FIXTURE.encode()).hexdigest()
LEGACY_HOST_REFERENCE = [-9.413619995117188, -11.37354850769043, -3.759044647216797]
ORACLE_FIXTURE_SHA = "1687b8e30cf49ba532ad70346447bf6c851ab3562cc97bf04da23ac61d1bc076"
REFERENCE_KIND = "upstream-frozen-tensors-same-apk-runtime-v1"
SCOPE = ("Actual Android JNI on APK-pinned 8M model, independent upstream tensor execution on the same APK runtime, "
         "and real MSIME with a synthetic lexicon; "
         "original-ID selection enters production trackCoreCommit and an independent EditText Editable. "
         "Not activated system IME, physical taps, phone latency, active-inference kill or heap-leak acceptance.")
CASES = frozenset({
    "default_on_preference", "pinned_apk_resources", "real_model_msime_pool",
    "invalid_rank_requests_preserve_view", "model_rank_changes_display_only",
    "stale_generation_and_bad_id", "original_id_click_after_rank_commits_editable",
    "old_composition_revision_rejected", "private_pool_cannot_enable", "clear_context_isolation",
    "cross_thread_cancel_and_owner_guards", "close_stale_handle_and_reopen",
    "corrupt_model_rejected_valid_bundle_preserved",
    "blocked_rank_publication_preserves_view",
})
PROOFS = frozenset({
    "real_model_executed", "real_msime_executed", "actual_editable_verified",
    "production_commit_path_used", "default_on_verified", "private_pool_empty",
    "non_main_model_thread", "cleanup_completed", "independent_reference_verified",
})
LIMITS = frozenset({"system_ime_executed", "phone_executed", "active_inference_cancel_tested",
                   "heap_leak_tested", "probe_opens_network"})
FIELDS = PROOFS | LIMITS | {
    "schema_version", "passed", "status", "synthetic", "checks", "cases", "api_level", "abi",
    "fixture_id", "fixture_sha256", "model_id", "model_sha256", "model_revision", "runtime_sha256",
    "scores", "score_tolerance", "reference_runtime_version", "apk_runtime_version_expected",
    "reference_kind", "reference", "reference_max_abs_error", "legacy_host_reference",
    "model_load_ms", "old_click_index", "ranked_index_for_same_id", "score_calls", "completed_scores",
    "open_native_handles_at_exit", "open_core_handles_at_exit", "scope",
}


def expected_revision():
    lock = json.loads((ROOT / "third_party/candidate-model-lock.json").read_text(encoding="utf-8"))
    if lock["model_id"] != MODEL_ID or lock["files"]["lm_step.ort"]["sha256"] != MODEL_SHA:
        raise ValueError("Candidate probe no longer matches reviewed model identity")
    rows = [{"name": name, "size": spec["size"], "sha256": spec["sha256"]}
            for name, spec in sorted(lock["files"].items())]
    return hashlib.sha256(json.dumps(rows, separators=(",", ":"), sort_keys=True).encode()).hexdigest()


def runtime_sha(abi):
    lock = json.loads((ROOT / "third_party/android-voice-lock.json").read_text(encoding="utf-8"))
    if abi not in ("arm64-v8a", "x86_64"):
        raise ValueError("Unsupported candidate runtime ABI")
    return lock["libraries"][abi]["libonnxruntime.so"]["sha256"]


def finite_number(value):
    return type(value) in (int, float) and math.isfinite(value)


def verify_report(report, api=None):
    """Reject missing, inflated, altered-fixture or mock-only success reports."""
    if not isinstance(report, dict) or report.keys() != FIELDS:
        raise ValueError("Unknown or missing Android candidate report fields")
    if (type(report["schema_version"]) is not int or report["schema_version"] != 2
            or report["passed"] is not True or report["status"] != "PASS" or report["synthetic"] is not True):
        raise ValueError("Incomplete Android candidate probe")
    level = report["api_level"]
    if type(level) is not int or level not in (28, 35, 36) or api is not None and api != level:
        raise ValueError("Wrong Android candidate API level")
    if (type(report["checks"]) is not int or report["checks"] != len(CASES)
            or not isinstance(report["cases"], list) or len(report["cases"]) != len(CASES)):
        raise ValueError("Wrong Android candidate check count")
    rows = report["cases"]
    if any(not isinstance(row, dict) or row.keys() != {"id", "passed"}
           or type(row["id"]) is not str or row["passed"] is not True for row in rows):
        raise ValueError("Missing or failed Android candidate case")
    if {row["id"] for row in rows} != CASES:
        raise ValueError("Unknown or duplicate Android candidate case")
    if any(report[field] is not True for field in PROOFS):
        raise ValueError("Actual model/core/Editable/cleanup proof missing")
    if any(report[field] is not False for field in LIMITS) or report["scope"] != SCOPE:
        raise ValueError("Candidate method probe scope overstated")
    expected = {"fixture_id": FIXTURE_ID, "fixture_sha256": FIXTURE_SHA, "model_id": MODEL_ID,
                "model_sha256": MODEL_SHA, "model_revision": expected_revision(),
                "reference_runtime_version": "1.28.2", "apk_runtime_version_expected": "1.28.2",
                "reference_kind": REFERENCE_KIND}
    if any(report[field] != value for field, value in expected.items()):
        raise ValueError("Candidate model, fixed fixture or reference identity mismatch")
    # The independent native runner is currently bundled only in the x86_64
    # dedicated CI harness. ARM and phone acceptance require separate evidence.
    if report["abi"] != "x86_64" or report["runtime_sha256"] != runtime_sha(report["abi"]):
        raise ValueError("Actual APK runtime differs from reviewed ABI library")
    tolerance = report["score_tolerance"]
    if not finite_number(tolerance) or abs(tolerance - 0.001) > 1e-12:
        raise ValueError("Candidate reference tolerance changed")
    scores = report["scores"]
    reference = report["reference"]
    if (not isinstance(reference, dict) or reference.keys() != {
            "executed", "runtime_version", "graph_calls", "scores", "context_cache_nodes",
            "candidate_cache_nodes", "session_closed", "fixture_sha256"}
            or reference["executed"] is not True or reference["session_closed"] is not True
            or reference["runtime_version"] != "1.28.2" or reference["fixture_sha256"] != ORACLE_FIXTURE_SHA):
        raise ValueError("Independent upstream same-runtime oracle evidence missing")
    for field, expected_value in (("graph_calls", 2), ("context_cache_nodes", 7), ("candidate_cache_nodes", 10)):
        if type(reference[field]) is not int or reference[field] != expected_value:
            raise ValueError("Independent upstream frozen tensor shape changed")
    values = reference["scores"]
    if any(not isinstance(row, list) or len(row) != len(LEGACY_HOST_REFERENCE)
           or any(not finite_number(value) for value in row) for row in (scores, values)):
        raise ValueError("Actual model or independent scores are invalid")
    maximum_error = max(abs(actual - expected) for actual, expected in zip(scores, values))
    if (maximum_error > 0.001 or not finite_number(report["reference_max_abs_error"])
            or abs(report["reference_max_abs_error"] - maximum_error) > 1e-12):
        raise ValueError("Actual candidate scores differ from independent same-runtime reference")
    if not scores[2] > max(scores[:2]):
        raise ValueError("Actual model did not select the frozen fixture winner")
    if not values[2] > max(values[:2]):
        raise ValueError("Independent model did not select the frozen fixture winner")
    legacy = report["legacy_host_reference"]
    if (not isinstance(legacy, dict)
            or legacy.keys() != {"runtime_version", "scores", "max_abs_error", "used_as_android_oracle"}
            or legacy["runtime_version"] != "1.24.4" or legacy["used_as_android_oracle"] is not False
            or legacy["scores"] != LEGACY_HOST_REFERENCE or not finite_number(legacy["max_abs_error"])
            or abs(legacy["max_abs_error"] - max(abs(value - old) for value, old in zip(scores, LEGACY_HOST_REFERENCE))) > 1e-12):
        raise ValueError("Legacy cross-runtime diagnostic was omitted or altered")
    for field, expected_value in (("score_calls", 9), ("completed_scores", 7),
                                  ("open_native_handles_at_exit", 0), ("open_core_handles_at_exit", 0)):
        if type(report[field]) is not int or report[field] != expected_value:
            raise ValueError("Candidate native lifecycle accounting mismatch")
    old, new = report["old_click_index"], report["ranked_index_for_same_id"]
    if type(old) is not int or type(new) is not int or not 0 <= old < 40 or not 0 <= new < 40 or old == new:
        raise ValueError("Original-ID selection did not cross an actual row movement")
    if not finite_number(report["model_load_ms"]) or report["model_load_ms"] < 0:
        raise ValueError("Invalid actual native model-load measurement")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--api", type=int, choices=(28, 35, 36))
    arguments = parser.parse_args()
    verify_report(json.loads(arguments.report.read_text(encoding="utf-8")), arguments.api)
    print("PASS 14 fixed Android candidate model/JNI/MSIME/Editable checks with independent same-runtime oracle; system IME and phone scope remain separate")
