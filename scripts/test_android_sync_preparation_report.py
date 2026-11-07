# SPDX-License-Identifier: GPL-3.0-only
import copy
import unittest
from android_sync_preparation_report import CASES, UI_CASES, verify_report, verify_ui_report


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


class SyncRecoveryUiReportTests(unittest.TestCase):
    def setUp(self):
        self.report = {"schema_version": 1, "status": "PASS", "synthetic": True, "api_level": 36,
            "cases": [{"id": name, "passed": True} for name in sorted(UI_CASES)],
            "encrypted_package_bytes": 256, "secret_or_code_logged": False,
            "provider_touch_point": [10, 20], "provider_selected_filename_bounds": [0, 0, 100, 100],
            "provider_pointer_id": 0, "provider_pointer_count": 1, "provider_tool_type": 1,
            "provider_event_source": 4098, "provider_physical_selection_count": 1,
            "provider_selection_method": "unique_stable_filename_single_finger",
            "provider_selection_unoccluded": True, "provider_navigation_cover_detected": False,
            "provider_occlusion_scope": "identified_DocumentsUI_roots_navigation_only",
            "provider_navigation_close_count": 0,
            "button_activation_method": "production_View_performClick_and_visible_AlertDialog_confirmation",
            "code_entry_method": "synthetic_programmatic_EditText_normal_confirmation"}
        for flag in ("actual_provider_create", "actual_provider_open", "actual_native_recovery",
                     "recovery_identity_unchanged", "normal_buttons_and_dialogs_used",
                     "missing_material_restore_ui", "provider_exit_observed", "fresh_fixture_owned",
                     "provider_document_deleted", "cleanup_completed"):
            self.report[flag] = True

    def test_exact_ui_report_passes(self):
        verify_ui_report(self.report, 36)
        self.report.update(provider_navigation_cover_detected=True, provider_navigation_close_count=1,
                           provider_cover_closed_before_selection=True, provider_navigation_close_method="observed_cover_single_global_back")
        verify_ui_report(self.report, 36)

    def test_ui_case_failure_scope_and_cleanup_cannot_be_skipped(self):
        for field in ("actual_provider_create", "actual_provider_open", "actual_native_recovery", "cleanup_completed", "fresh_fixture_owned", "provider_document_deleted"):
            report = copy.deepcopy(self.report); report[field] = False
            with self.subTest(field=field), self.assertRaises(ValueError): verify_ui_report(report)
        for mutation in ("case", "size", "api", "logging", "pointer", "point", "occluded", "close_count", "close_unobserved"):
            report = copy.deepcopy(self.report)
            if mutation == "case": report["cases"][0] = report["cases"][1]
            if mutation == "size": report["encrypted_package_bytes"] = 16385
            if mutation == "api": report["api_level"] = True
            if mutation == "logging": report["secret_or_code_logged"] = True
            if mutation == "pointer": report["provider_tool_type"] = 0
            if mutation == "point": report["provider_touch_point"] = [100, 100]
            if mutation == "occluded": report["provider_selection_unoccluded"] = False
            if mutation == "close_count": report["provider_navigation_close_count"] = 2
            if mutation == "close_unobserved": report["provider_navigation_cover_detected"] = True; report["provider_navigation_close_count"] = 1
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): verify_ui_report(report)


if __name__ == "__main__": unittest.main()
