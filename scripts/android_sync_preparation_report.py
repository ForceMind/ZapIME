# SPDX-License-Identifier: GPL-3.0-only
"""Strict local-only JNI/vault/Activity report, not a data-sync success marker."""

CASES = frozenset({
    "fresh_create_and_binding", "create_only_idempotent",
    "authenticated_recovery_roundtrip", "bound_missing_material_requires_recovery",
    "vault_before_binding_retry", "different_material_and_fingerprint_preserved",
    "connection_snapshot_and_member_policy", "absent_future_database_fail_closed",
    "worker_guard_before_lock", "connection_removal_preserves_material",
    "secure_preparation_activity_lifecycle",
})


def verify_report(report, api=None):
    if not isinstance(report, dict) or report.get("synthetic") is not True or report.get("cleanup_completed") is not True:
        raise ValueError("Incomplete local sync preparation or fixture cleanup")
    level = report.get("api_level")
    if type(level) is not int or level not in (28, 35, 36) or api is not None and level != api:
        raise ValueError("Wrong Android API for local sync preparation")
    rows = report.get("cases")
    if not isinstance(rows, list) or len(rows) != len(CASES):
        raise ValueError("Missing local sync preparation cases")
    if any(not isinstance(row, dict) or row.get("passed") is not True for row in rows):
        raise ValueError("Failed local sync preparation case")
    if {row.get("id") for row in rows} != CASES:
        raise ValueError("Unknown or duplicate local sync preparation case")
    if not isinstance(report.get("scope"), str) or not report["scope"]:
        raise ValueError("Missing local-only acceptance scope")


UI_CASES = frozenset({
    "fresh_main_fixture", "normal_create_confirmation", "actual_saf_export",
    "actual_native_recovery_identity", "recovery_code_confirmation", "actual_saf_open",
    "normal_restore_identity_preserved", "missing_material_requires_restore",
    "provider_file_deleted_before_finish", "stop_clears_recovery_state",
})


def verify_ui_report(report, api=None):
    from android_voicekey_model_smoke import verify_finger_selection
    if not isinstance(report, dict) or report.get("schema_version") != 1 or report.get("status") != "PASS" or report.get("synthetic") is not True:
        raise ValueError("Incomplete sync recovery UI report")
    level = report.get("api_level")
    if type(level) is not int or level not in (28, 35, 36) or api is not None and api != level:
        raise ValueError("Wrong sync recovery UI API")
    rows = report.get("cases")
    if not isinstance(rows, list) or len(rows) != len(UI_CASES) or any(not isinstance(row, dict) or row.get("passed") is not True for row in rows):
        raise ValueError("Missing sync recovery UI cases")
    if {row.get("id") for row in rows} != UI_CASES:
        raise ValueError("Unknown or duplicate sync recovery UI case")
    for flag in ("actual_provider_create", "actual_provider_open", "actual_native_recovery",
                 "recovery_identity_unchanged", "normal_buttons_and_dialogs_used",
                 "missing_material_restore_ui", "provider_exit_observed", "fresh_fixture_owned",
                 "provider_document_deleted", "cleanup_completed"):
        if report.get(flag) is not True:
            raise ValueError("Incomplete sync recovery UI or cleanup evidence")
    size = report.get("encrypted_package_bytes")
    if type(size) is not int or not 0 < size <= 16 * 1024 or report.get("secret_or_code_logged") is not False:
        raise ValueError("Invalid recovery UI file budget or logging policy")
    if report.get("button_activation_method") != "production_View_performClick_and_visible_AlertDialog_confirmation" or report.get("code_entry_method") != "synthetic_programmatic_EditText_normal_confirmation":
        raise ValueError("Missing actual test activation scope")
    if report.get("provider_selection_method") != "unique_stable_filename_single_finger":
        raise ValueError("Wrong provider selection method")
    if report.get("provider_selection_unoccluded") is not True or report.get("provider_occlusion_scope") != "identified_DocumentsUI_roots_navigation_only":
        raise ValueError("Provider filename touch was not checked for navigation occlusion")
    covered = report.get("provider_navigation_cover_detected")
    closes = report.get("provider_navigation_close_count")
    if type(covered) is not bool or type(closes) is not int or closes != int(covered):
        raise ValueError("Missing observed navigation-cover evidence")
    if covered and (report.get("provider_cover_closed_before_selection") is not True or report.get("provider_navigation_close_method") != "observed_cover_single_global_back"):
        raise ValueError("Provider cover not closed before filename selection")
    verify_finger_selection(report)
