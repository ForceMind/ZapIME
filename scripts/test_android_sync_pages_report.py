# SPDX-License-Identifier: GPL-3.0-only
import copy
import unittest
from android_sync_pages_report import (ACTIVATION, CANCEL_SCOPE, CASES, FIELDS, PROOFS, SCOPE, verify_report)


class SyncPagesUiReportTests(unittest.TestCase):
    def setUp(self):
        self.report = {"schema_version": 1, "status": "PASS", "synthetic": True, "api_level": 36,
            "cases": [{"id": name, "passed": True} for name in sorted(CASES)], "scope": SCOPE,
            "button_activation_method": ACTIVATION, "cancellation_scope": CANCEL_SCOPE,
            "android_https": "NOT RUN", "active_https_interruption_tested": False,
            "physical_input_tested": False, "network_success_tested": False,
            "secret_content_or_code_logged": False, "queued_cancelled_pages": 2}
        self.report.update({name: True for name in PROOFS})

    def reject(self, report):
        with self.assertRaises(ValueError):
            verify_report(report, 36)

    def test_exact_report_and_expected_api(self):
        self.assertEqual(set(self.report), FIELDS)
        verify_report(self.report, 36)
        for api in (28, 35):
            report = copy.deepcopy(self.report); report["api_level"] = api
            verify_report(report, api)
            self.reject(report)

    def test_missing_unknown_and_coerced_fields_rejected(self):
        for name in FIELDS:
            report = copy.deepcopy(self.report); del report[name]
            self.reject(report)
        for name, value in (("extra", True), ("schema_version", True), ("api_level", "36"),
                            ("queued_cancelled_pages", True), ("queued_cancelled_pages", 1),
                            ("status", "FAIL"), ("synthetic", 1)):
            report = copy.deepcopy(self.report); report[name] = value
            self.reject(report)

    def test_case_duplicates_failures_unknown_and_extra_payload_rejected(self):
        for mutation in ("missing", "duplicate", "failed", "unknown", "extra", "coerced"):
            report = copy.deepcopy(self.report)
            if mutation == "missing": report["cases"].pop()
            if mutation == "duplicate": report["cases"][0] = report["cases"][1]
            if mutation == "failed": report["cases"][0]["passed"] = False
            if mutation == "unknown": report["cases"][0]["id"] = "unknown"
            if mutation == "extra": report["cases"][0]["secret"] = "forbidden"
            if mutation == "coerced": report["cases"][0]["passed"] = 1
            self.reject(report)

    def test_each_proof_and_scope_cannot_be_downgraded_or_inflated(self):
        for name in PROOFS:
            for value in (False, 1, "true"):
                report = copy.deepcopy(self.report); report[name] = value
                self.reject(report)
        for name, value in (("android_https", "PASS"), ("active_https_interruption_tested", True),
                            ("physical_input_tested", True), ("network_success_tested", True),
                            ("secret_content_or_code_logged", True), ("button_activation_method", "physical_touch"),
                            ("cancellation_scope", "active_https_abort"), ("scope", SCOPE + " Success")):
            report = copy.deepcopy(self.report); report[name] = value
            self.reject(report)


if __name__ == "__main__":
    unittest.main()
