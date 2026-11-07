# SPDX-License-Identifier: GPL-3.0-only
"""Synthetic report validation only; does not execute a model or Android."""
import copy
import unittest

from android_candidate_ranking_report import (CASES, FIXTURE_ID, FIXTURE_SHA, LIMITS, MODEL_ID,
                                             MODEL_SHA, PROOFS, REFERENCE, SCOPE,
                                             expected_revision, runtime_sha, verify_report)


class CandidateReportTest(unittest.TestCase):
    def setUp(self):
        self.report = {"schema_version": 1, "passed": True, "status": "PASS", "synthetic": True,
                       "checks": len(CASES), "cases": [{"id": case, "passed": True} for case in sorted(CASES)],
                       "api_level": 36, "abi": "x86_64", "fixture_id": FIXTURE_ID, "fixture_sha256": FIXTURE_SHA,
                       "model_id": MODEL_ID, "model_sha256": MODEL_SHA, "model_revision": expected_revision(),
                       "runtime_sha256": runtime_sha("x86_64"), "scores": list(REFERENCE), "score_tolerance": 0.001,
                       "reference_runtime_version": "1.24.4", "apk_runtime_version_expected": "1.28.2",
                       "model_load_ms": 15.0, "old_click_index": 0, "ranked_index_for_same_id": 1,
                       "score_calls": 9, "completed_scores": 7, "open_native_handles_at_exit": 0,
                       "open_core_handles_at_exit": 0, "scope": SCOPE}
        self.report.update({field: True for field in PROOFS})
        self.report.update({field: False for field in LIMITS})

    def test_complete_metadata_shape_and_exact_api_abi(self):
        for api in (28, 35, 36):
            for abi in ("arm64-v8a", "x86_64"):
                row = copy.deepcopy(self.report)
                row.update(api_level=api, abi=abi, runtime_sha256=runtime_sha(abi))
                verify_report(row, api)
        with self.assertRaises(ValueError):
            verify_report(self.report, 28)

    def test_missing_duplicate_unknown_and_failed_case_rejected(self):
        for mutation in ("missing", "duplicate", "unknown", "failed", "truthy", "extra"):
            row = copy.deepcopy(self.report)
            if mutation == "missing": row["cases"].pop()
            if mutation == "duplicate": row["cases"][0] = row["cases"][1]
            if mutation == "unknown": row["cases"][0]["id"] = "unmeasured"
            if mutation == "failed": row["cases"][0]["passed"] = False
            if mutation == "truthy": row["cases"][0]["passed"] = 1
            if mutation == "extra": row["cases"][0]["input_text"] = "forbidden"
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                verify_report(row)

    def test_every_actual_proof_and_cleanup_required(self):
        for field in PROOFS:
            for value in (False, 1, None):
                row = copy.deepcopy(self.report)
                row[field] = value
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    verify_report(row)

    def test_scope_cannot_inflate_to_system_ime_or_physical_memory_acceptance(self):
        for field in LIMITS:
            row = copy.deepcopy(self.report)
            row[field] = True
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_report(row)
        for field, value in (("scope", "phone passed"), ("synthetic", False), ("unreviewed", True)):
            row = copy.deepcopy(self.report)
            row[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_report(row)

    def test_wrong_hash_fixture_model_runtime_or_mutated_tolerance_rejected(self):
        for field in ("fixture_sha256", "model_sha256", "model_revision", "runtime_sha256", "model_id",
                      "fixture_id", "reference_runtime_version", "apk_runtime_version_expected"):
            row = copy.deepcopy(self.report)
            row[field] = "changed"
            with self.subTest(field=field), self.assertRaises(ValueError): verify_report(row)
        for values in ([0.0, 0.0, 0.0], [float("nan"), *REFERENCE[1:]], [True, *REFERENCE[1:]], REFERENCE[:-1]):
            row = copy.deepcopy(self.report)
            row["scores"] = values
            with self.subTest(scores=values), self.assertRaises(ValueError): verify_report(row)
        row = copy.deepcopy(self.report)
        row["score_tolerance"] = 1.0
        with self.assertRaises(ValueError): verify_report(row)

    def test_handle_leak_mock_lifecycle_and_unchanged_click_position_rejected(self):
        for field, value in (("open_native_handles_at_exit", 1), ("open_core_handles_at_exit", 1),
                             ("score_calls", 0), ("completed_scores", 0), ("ranked_index_for_same_id", 0),
                             ("model_load_ms", -1), ("checks", True), ("api_level", True), ("passed", 1)):
            row = copy.deepcopy(self.report)
            row[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): verify_report(row)


if __name__ == "__main__":
    unittest.main()
