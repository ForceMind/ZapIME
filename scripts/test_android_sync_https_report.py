# SPDX-License-Identifier: GPL-3.0-only
import copy
import unittest
from android_sync_https_report import FIELDS, ROUND_CASES, ROUND_SCOPE, TLS_CASES, TLS_SCOPE, safe_report, verify_report


def report(phase):
    roundtrip = phase == "roundtrip"
    return {"schema_version": 1, "status": "PASS", "synthetic": True, "api_level": 28, "phase": phase,
            "cases": [{"id": case, "passed": True} for case in sorted(ROUND_CASES if roundtrip else TLS_CASES)],
            "cleanup_completed": True, "worker_name_verified": True, "platform_defaults_unchanged": True,
            "secret_content_or_ciphertext_logged": False, "scope": ROUND_SCOPE if roundtrip else TLS_SCOPE,
            "android_https": "PASS_FIXTURE_CA" if roundtrip else "TLS_REJECTED",
            "server_exchange": "actual_go_postgresql" if roundtrip else "not_attempted_untrusted_tls",
            "profiles": 2 if roundtrip else 0, "server_head": 2 if roundtrip else None,
            "final_cursors": [2, 2] if roundtrip else [], "uploaded_operations": 2 if roundtrip else 0,
            "tls_failure_code": "tls"}


class SyncHttpsReportTests(unittest.TestCase):
    def test_three_fixed_phases_and_api(self):
        for phase in ("before", "roundtrip", "after"):
            value = report(phase)
            safe_report(value, phase, 28); verify_report(value, phase, 28)
            with self.assertRaises(ValueError): verify_report(value, phase, 35)
            with self.assertRaises(ValueError): verify_report(value, "wrong", 28)

    def test_incomplete_duplicate_truthy_cleanup_and_counts_rejected(self):
        for mutation in ("missing", "duplicate", "unknown", "truthy", "cleanup", "worker", "defaults", "api_bool", "profile_bool", "head", "cursor", "uploads"):
            value = report("roundtrip")
            if mutation == "missing": value["cases"].pop()
            if mutation == "duplicate": value["cases"][0] = value["cases"][1]
            if mutation == "unknown": value["cases"][0]["id"] = "invented_case"
            if mutation == "truthy": value["cases"][0]["passed"] = 1
            if mutation == "cleanup": value["cleanup_completed"] = False
            if mutation == "worker": value["worker_name_verified"] = False
            if mutation == "defaults": value["platform_defaults_unchanged"] = False
            if mutation == "api_bool": value["api_level"] = True
            if mutation == "profile_bool": value["profiles"] = True
            if mutation == "head": value["server_head"] = 3
            if mutation == "cursor": value["final_cursors"] = [2, 1]
            if mutation == "uploads": value["uploaded_operations"] = 1
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): verify_report(value)

    def test_network_failure_scope_inflation_and_secret_output_rejected(self):
        for field, value in (("tls_failure_code", "network"), ("scope", "Two Android phones and R2 passed"),
                             ("secret_content_or_ciphertext_logged", True), ("origin", "https://forbidden"),
                             ("bootstrap", "forbidden"), ("credential", "forbidden"), ("synthetic", False)):
            source = report("roundtrip"); source[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): safe_report(source)
        source = report("before"); source["profiles"] = 1
        with self.assertRaises(ValueError): safe_report(source)

    def test_failed_original_can_be_saved_but_never_passes(self):
        for phase in ("before", "roundtrip", "after"):
            source = report(phase); source.update(status="FAIL", android_https="NOT PROVEN", server_exchange="NOT PROVEN", tls_failure_code="NOT PROVEN", profiles=0, server_head=None, final_cursors=[], uploaded_operations=0)
            source["cases"] = [{"id": sorted(ROUND_CASES if phase == "roundtrip" else TLS_CASES)[0], "passed": False, "failure_type": "IOException"}]
            safe_report(source, phase)
            with self.assertRaises(ValueError): verify_report(source, phase)
            source.update(cleanup_completed=False, cleanup_failure_type="IOException")
            safe_report(source, phase)
            source["cleanup_failure_type"] = "https://forbidden"
            with self.assertRaises(ValueError): safe_report(source, phase)


if __name__ == "__main__": unittest.main()
