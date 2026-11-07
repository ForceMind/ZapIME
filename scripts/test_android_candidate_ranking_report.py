# SPDX-License-Identifier: GPL-3.0-only
"""Synthetic report validation only; does not execute a model or Android."""
import copy
import unittest

from android_candidate_ranking_report import (CASES, FIXTURE_ID, FIXTURE_SHA, LIMITS, MODEL_ID,
                                             MODEL_SHA, PROOFS, LEGACY_HOST_REFERENCE, ORACLE_FIXTURE_SHA,
                                             REFERENCE_KIND, SCOPE,
                                             expected_revision, runtime_sha, verify_report)


class CandidateReportTest(unittest.TestCase):
    def setUp(self):
        # Deliberately invented values test the validator only. They are never
        # used by the Android probe and are not real model evidence.
        scores = [-4.0, -6.0, -1.0]
        self.report = {"schema_version": 2, "passed": True, "status": "PASS", "synthetic": True,
                       "checks": len(CASES), "cases": [{"id": case, "passed": True} for case in sorted(CASES)],
                       "api_level": 36, "abi": "x86_64", "fixture_id": FIXTURE_ID, "fixture_sha256": FIXTURE_SHA,
                       "model_id": MODEL_ID, "model_sha256": MODEL_SHA, "model_revision": expected_revision(),
                       "runtime_sha256": runtime_sha("x86_64"), "scores": list(scores), "score_tolerance": 0.001,
                       "reference_runtime_version": "1.28.2", "apk_runtime_version_expected": "1.28.2",
                       "reference_kind": REFERENCE_KIND,
                       "reference": {"executed": True, "session_closed": True, "runtime_version": "1.28.2",
                                     "graph_calls": 2, "context_cache_nodes": 7, "candidate_cache_nodes": 10,
                                     "fixture_sha256": ORACLE_FIXTURE_SHA, "scores": list(scores)},
                       "reference_max_abs_error": 0.0,
                       "legacy_host_reference": {"runtime_version": "1.24.4", "scores": list(LEGACY_HOST_REFERENCE),
                                                 "used_as_android_oracle": False,
                                                 "max_abs_error": max(abs(value - old) for value, old in zip(scores, LEGACY_HOST_REFERENCE))},
                       "model_load_ms": 15.0, "old_click_index": 0, "ranked_index_for_same_id": 1,
                       "score_calls": 9, "completed_scores": 7, "open_native_handles_at_exit": 0,
                       "open_core_handles_at_exit": 0, "scope": SCOPE}
        self.report.update({field: True for field in PROOFS})
        self.report.update({field: False for field in LIMITS})

    def test_complete_metadata_shape_and_exact_api_abi(self):
        for api in (28, 35, 36):
            row = copy.deepcopy(self.report)
            row["api_level"] = api
            verify_report(row, api)
        with self.assertRaises(ValueError):
            verify_report(self.report, 28)
        row = copy.deepcopy(self.report)
        row.update(abi="arm64-v8a", runtime_sha256=runtime_sha("arm64-v8a"))
        with self.assertRaises(ValueError):
            verify_report(row)

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
        for values in ([0.0, 0.0, 0.0], [float("nan"), -6.0, -1.0], [True, -6.0, -1.0], [-4.0, -6.0]):
            row = copy.deepcopy(self.report)
            row["scores"] = values
            with self.subTest(scores=values), self.assertRaises(ValueError): verify_report(row)
        row = copy.deepcopy(self.report)
        row["score_tolerance"] = 1.0
        with self.assertRaises(ValueError): verify_report(row)

    def test_independent_oracle_identity_shape_execution_and_release_are_required(self):
        for field, value in (("executed", False), ("session_closed", False), ("runtime_version", "1.24.4"),
                             ("fixture_sha256", "changed"), ("graph_calls", 1), ("context_cache_nodes", 6),
                             ("candidate_cache_nodes", True), ("extra", True), ("scores", [0.0, 0.0, 0.0]),
                             ("scores", [float("nan"), -6.0, -1.0])):
            row = copy.deepcopy(self.report)
            row["reference"][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                verify_report(row)

    def test_same_runtime_tolerance_and_error_accounting_cannot_be_weakened(self):
        row = copy.deepcopy(self.report)
        row["reference"]["scores"][0] -= 0.0011
        row["reference_max_abs_error"] = abs(row["reference"]["scores"][0] - row["scores"][0])
        with self.assertRaises(ValueError):
            verify_report(row)
        row = copy.deepcopy(self.report)
        row["reference"]["scores"][0] -= 0.0005
        with self.assertRaises(ValueError):
            verify_report(row)  # A concealed nonzero numerical delta is rejected.
        row["reference_max_abs_error"] = abs(row["reference"]["scores"][0] - row["scores"][0])
        verify_report(row)
        for field, value in (("scores", [-1.0, -2.0, -3.0]), ("runtime_version", "1.28.2"),
                             ("used_as_android_oracle", True), ("max_abs_error", 0.0)):
            row = copy.deepcopy(self.report)
            row["legacy_host_reference"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_report(row)

    def test_handle_leak_mock_lifecycle_and_unchanged_click_position_rejected(self):
        for field, value in (("open_native_handles_at_exit", 1), ("open_core_handles_at_exit", 1),
                             ("score_calls", 0), ("completed_scores", 0), ("ranked_index_for_same_id", 0),
                             ("model_load_ms", -1), ("checks", True), ("api_level", True), ("passed", 1)):
            row = copy.deepcopy(self.report)
            row[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): verify_report(row)


if __name__ == "__main__":
    unittest.main()
