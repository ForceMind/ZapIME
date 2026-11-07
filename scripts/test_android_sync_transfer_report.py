# SPDX-License-Identifier: GPL-3.0-only
import copy
import unittest
from android_sync_transfer_report import CASES, PROOFS, SCOPE, verify_report


class SyncTransferReportTests(unittest.TestCase):
    def setUp(self):
        self.report = {"schema_version": 1, "status": "PASS", "synthetic": True, "api_level": 36,
                       "cases": [{"id": case, "passed": True} for case in sorted(CASES)],
                       "android_https": "NOT RUN", "server_exchange": "synthetic_outer_only", "scope": SCOPE,
                       "failed_page_retention": "saved", "secret_content_or_ciphertext_logged": False}
        self.report.update({field: True for field in PROOFS})

    def test_complete_fixed_report_and_expected_api(self):
        verify_report(self.report, 36)
        for level in (28, 35):
            self.report["api_level"] = level
            verify_report(self.report, level)
            with self.assertRaises(ValueError): verify_report(self.report, 36)

    def test_case_set_failure_and_nonboolean_rejected(self):
        for mutation in ("missing", "duplicate", "unknown", "failed", "truthy", "row_extra", "status", "schema_bool", "api_bool"):
            report = copy.deepcopy(self.report)
            if mutation == "missing": report["cases"].pop()
            if mutation == "duplicate": report["cases"][0] = report["cases"][1]
            if mutation == "unknown": report["cases"][0]["id"] = "invented_case"
            if mutation == "failed": report["cases"][0]["passed"] = False
            if mutation == "truthy": report["cases"][0]["passed"] = 1
            if mutation == "row_extra": report["cases"][0]["ciphertext"] = "forbidden"
            if mutation == "status": report["status"] = "FAIL"
            if mutation == "schema_bool": report["schema_version"] = True
            if mutation == "api_bool": report["api_level"] = True
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): verify_report(report)

    def test_each_actual_proof_and_cleanup_are_required(self):
        for field in PROOFS:
            for value in (False, 1, None):
                report = copy.deepcopy(self.report); report[field] = value
                with self.subTest(field=field, value=value), self.assertRaises(ValueError): verify_report(report)
        for value in ("failed", "not_eligible", "NOT PROVEN"):
            report = copy.deepcopy(self.report); report["failed_page_retention"] = value
            with self.subTest(value=value), self.assertRaises(ValueError): verify_report(report)

    def test_scope_inflation_unknown_fields_and_secret_output_rejected(self):
        for field, value in (("android_https", "PASS"), ("server_exchange", "real"), ("scope", "Four-device sync passed"),
                             ("secret_content_or_ciphertext_logged", True), ("synthetic", False), ("root_key", "forbidden")):
            report = copy.deepcopy(self.report); report[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): verify_report(report)
        report = copy.deepcopy(self.report); del report["scope"]
        with self.assertRaises(ValueError): verify_report(report)


if __name__ == "__main__": unittest.main()
