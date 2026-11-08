# SPDX-License-Identifier: GPL-3.0-only
import copy
import json
from pathlib import Path
import unittest
from android_sync_preparation_report import (CASES, UI_CASES, FAILURE_SUBSTEPS, FAILURE_WINDOWS,
                                             failure_diagnostic, verify_report, verify_ui_report)


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

    def diagnostic(self, **changes):
        report = copy.deepcopy(self.report)
        report.update(status="FAIL", failure_diagnostic={"substep": "navigation_close_wait",
            "active_window": "documents_ui", "provider_roots_present": True,
            "matching_filename_present": True, "observed_polls": 150,
            "navigation_close_attempts": 1, "physical_selection_attempts": 0})
        report["failure_diagnostic"].update(changes)
        return report

    def test_provider_timeout_touch_and_return_diagnostics_do_not_pass(self):
        self.assertIsNone(failure_diagnostic(self.report))
        for step in FAILURE_SUBSTEPS:
            report = self.diagnostic(substep=step)
            self.assertEqual(failure_diagnostic(report)["substep"], step)
            with self.assertRaises(ValueError): verify_ui_report(report)
        for window in FAILURE_WINDOWS:
            report = self.diagnostic(active_window=window,
                provider_roots_present=window == "documents_ui", matching_filename_present=False)
            self.assertEqual(failure_diagnostic(report)["active_window"], window)
        report = self.diagnostic(); report["status"] = "PASS"
        with self.assertRaises(ValueError): failure_diagnostic(report)
        with self.assertRaises(ValueError): verify_ui_report(report)

    def test_failure_diagnostics_reject_text_payloads_unknown_fields_and_unbounded_counts(self):
        invalid = (("substep", "navigation_close_wait\nPRIVATE-CODE"),
            ("substep", {"private": "code"}), ("active_window", "org.secret.provider"),
            ("provider_roots_present", 1), ("matching_filename_present", "true"),
            ("observed_polls", 151), ("observed_polls", -1), ("observed_polls", True),
            ("navigation_close_attempts", 2), ("physical_selection_attempts", 2),
            ("uri", "content://private/code"))
        for field, value in invalid:
            report = self.diagnostic(**{field: value})
            with self.subTest(field=field, value=value), self.assertRaises(ValueError) as error:
                failure_diagnostic(report)
            self.assertNotIn("PRIVATE-CODE", str(error.exception))
            with self.assertRaises(ValueError): verify_ui_report(report)
        for field in self.diagnostic()["failure_diagnostic"]:
            report = self.diagnostic(); del report["failure_diagnostic"][field]
            with self.subTest(missing=field), self.assertRaises(ValueError): failure_diagnostic(report)
        with self.assertRaises(ValueError): failure_diagnostic(self.diagnostic(active_window="product"))

    def root_report(self, covered=False, navigation=False):
        report = copy.deepcopy(self.report)
        report.pop("provider_navigation_close_count")
        report.update(schema_version=2, provider_navigation_cover_detected=covered,
            provider_navigation_cover_cleared_before_selection=covered,
            provider_navigation_root_selection_count=1 if covered or navigation else 0,
            provider_navigation_root_selection_method="observed_DocumentsUI_Downloads_root_ACTION_CLICK" if covered or navigation else "none",
            provider_navigation_present_at_selection=navigation)
        if covered or navigation:
            report.update(provider_navigation_root_bounds=[0, 80, 220, 640],
                provider_navigation_control_bounds=[0, 160, 220, 208],
                provider_navigation_root_control_descendant=True)
        if navigation:
            report.update(provider_navigation_bounds_at_selection=[0, 80, 220, 640],
                provider_touch_point=[300, 300], provider_selected_filename_bounds=[280, 280, 350, 330])
        return report

    def test_observed_root_navigation_and_noncovering_sidebar(self):
        verify_ui_report(self.root_report(), 36)
        verify_ui_report(self.root_report(covered=True), 36)
        verify_ui_report(self.root_report(navigation=True), 36)
        verify_ui_report(self.root_report(covered=True, navigation=True), 36)

    def test_root_navigation_cannot_inflate_action_or_ignore_final_cover(self):
        for field, value in (("provider_navigation_root_selection_count", 2),
                ("provider_navigation_root_selection_count", True),
                ("provider_navigation_root_selection_method", "observed_cover_single_global_back"),
                ("provider_navigation_root_control_descendant", False),
                ("provider_navigation_cover_cleared_before_selection", False),
                ("provider_navigation_control_bounds", [0, 0, 500, 640]),
                ("provider_navigation_control_bounds", [0, 80, 220, 640]),
                ("provider_navigation_control_bounds", [0, 160, True, 208]),
                ("provider_navigation_bounds_at_selection", [0, 0, 400, 640]),
                ("provider_navigation_bounds_at_selection", [0, 0, 220, 32769]),
                ("provider_navigation_present_at_selection", "false")):
            report = self.root_report(covered=True, navigation=True);report[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): verify_ui_report(report)
        report = self.root_report();report["provider_navigation_bounds_at_selection"] = [0, 0, 20, 20]
        with self.assertRaises(ValueError): verify_ui_report(report)
        report = self.root_report(covered=True);report["provider_navigation_root_selection_count"] = 0
        report["provider_navigation_root_selection_method"] = "none"
        with self.assertRaises(ValueError): verify_ui_report(report)

    def test_original_reports_remain_truthful_under_new_validator(self):
        root = Path(__file__).resolve().parents[1] / "docs/evidence/sync-recovery-navigation-20261107/failure-42459710009"
        for api in (35, 36):
            report = json.loads((root / f"api{api}/sync-preparation-ui-report.json").read_text())
            verify_ui_report(report, api)
        report = json.loads((root / "api28/sync-preparation-ui-report.json").read_text())
        self.assertEqual(failure_diagnostic(report)["substep"], "navigation_close_wait")
        self.assertEqual(failure_diagnostic(report)["physical_selection_attempts"], 0)
        with self.assertRaises(ValueError): verify_ui_report(report, 28)


if __name__ == "__main__": unittest.main()
