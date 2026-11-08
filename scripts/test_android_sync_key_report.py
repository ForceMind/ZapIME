# SPDX-License-Identifier: GPL-3.0-only
import copy
import unittest
from android_sync_key_report import CASES, verify_report


class SyncKeyReportTests(unittest.TestCase):
    def setUp(self):
        self.report = {"schema_version": 1, "status": "PASS", "synthetic": True, "api_level": 36,
            "cases": [{"id": case, "passed": True} for case in sorted(CASES)], "cleanup_completed": True,
            "actual_native_descriptor": True, "actual_keystore_prepared": True,
            "material_bytes": 72, "descriptor_public_fields": 3, "android_https": "NOT RUN",
            "server_cas": "NOT RUN", "remote_success_tested": False, "secret_or_code_logged": False,
            "scope": "Offline Android parser/JNI/Keystore only; no HTTP or server CAS"}

    def test_exact_cases_and_expected_api(self):
        verify_report(self.report, 36)
        for api in (28, 35):
            with self.assertRaises(ValueError): verify_report(self.report, api)

    def test_missing_duplicate_failed_unknown_or_non_boolean_case_is_rejected(self):
        for mutation in ("missing", "duplicate", "failed", "unknown", "truthy", "not_dict", "id_type", "extra"):
            report = copy.deepcopy(self.report)
            if mutation == "missing": report["cases"].pop()
            if mutation == "duplicate": report["cases"][0] = report["cases"][1]
            if mutation == "failed": report["cases"][0]["passed"] = False
            if mutation == "unknown": report["cases"][0]["id"] = "invented"
            if mutation == "truthy": report["cases"][0]["passed"] = 1
            if mutation == "not_dict": report["cases"][0] = None
            if mutation == "id_type": report["cases"][0]["id"] = []
            if mutation == "extra": report["cases"][0]["unexpected"] = "synthetic"
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): verify_report(report)

    def test_bad_provenance_shape_or_unsupported_remote_claim_is_rejected(self):
        for field, value in (("schema_version", True), ("schema_version", 2), ("status", "FAIL"),
                ("synthetic", 1), ("api_level", True), ("api_level", 34), ("cleanup_completed", False),
                ("actual_native_descriptor", False), ("actual_keystore_prepared", False),
                ("material_bytes", 71), ("material_bytes", True), ("descriptor_public_fields", 4),
                ("android_https", "PASS"), ("server_cas", "PASS"), ("remote_success_tested", True),
                ("secret_or_code_logged", True), ("scope", ""), ("unexpected", "synthetic")):
            report = copy.deepcopy(self.report); report[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): verify_report(report)


if __name__ == "__main__": unittest.main()
