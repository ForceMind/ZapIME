# SPDX-License-Identifier: GPL-3.0-only
import copy
import unittest
from android_sync_preparation_report import CASES, verify_report


class SyncPreparationReportTests(unittest.TestCase):
    def setUp(self):
        self.report = {"synthetic": True, "api_level": 36, "cleanup_completed": True,
                       "cases": [{"id": name, "passed": True} for name in sorted(CASES)],
                       "scope": "Synthetic JNI/vault/Activity; no HTTP, provider or phone acceptance"}

    def test_exact_case_set_and_expected_api(self):
        verify_report(self.report, 36)
        for api in (28, 35):
            with self.assertRaises(ValueError): verify_report(self.report, api)

    def test_failed_missing_duplicate_unknown_and_non_boolean_pass_are_rejected(self):
        for mutation in ("failed", "missing", "duplicate", "unknown", "truthy", "cleanup", "api_bool"):
            report = copy.deepcopy(self.report)
            if mutation == "failed": report["cases"][0]["passed"] = False
            if mutation == "missing": report["cases"].pop()
            if mutation == "duplicate": report["cases"][0] = report["cases"][1]
            if mutation == "unknown": report["cases"][0]["id"] = "invented_case"
            if mutation == "truthy": report["cases"][0]["passed"] = 1
            if mutation == "cleanup": report["cleanup_completed"] = False
            if mutation == "api_bool": report["api_level"] = True
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): verify_report(report)


if __name__ == "__main__": unittest.main()
