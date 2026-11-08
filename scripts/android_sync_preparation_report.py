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

FAILURE_SUBSTEPS = frozenset({"case_body", "filename_poll", "navigation_snapshot", "navigation_close_request",
                              "navigation_close_wait", "root_control_selection", "file_snapshot", "touch_down",
                              "touch_up", "provider_return_wait", "selected_document_read",
                              "selected_document_identity"})
FAILURE_WINDOWS = frozenset({"none", "documents_ui", "product", "other"})
FAILURE_FIELDS = frozenset({"substep", "active_window", "provider_roots_present",
                            "matching_filename_present", "observed_polls",
                            "navigation_close_attempts", "physical_selection_attempts"})


def failure_diagnostic(report):
    """Only fixed, content-free observations; diagnostic evidence never passes a gate."""
    if not isinstance(report, dict):
        raise ValueError("Invalid sync recovery failure metadata")
    if "failure_diagnostic" not in report:
        return None
    diagnostic = report["failure_diagnostic"]
    if report.get("status") != "FAIL" or not isinstance(diagnostic, dict) or diagnostic.keys() != FAILURE_FIELDS:
        raise ValueError("Invalid sync recovery fixed failure metadata")
    if type(diagnostic["substep"]) is not str or diagnostic["substep"] not in FAILURE_SUBSTEPS:
        raise ValueError("Invalid sync recovery failure substep")
    if type(diagnostic["active_window"]) is not str or diagnostic["active_window"] not in FAILURE_WINDOWS:
        raise ValueError("Invalid sync recovery failure window")
    if any(type(diagnostic[name]) is not bool for name in ("provider_roots_present", "matching_filename_present")):
        raise ValueError("Invalid sync recovery provider observation")
    for name, upper in (("observed_polls", 150), ("navigation_close_attempts", 1), ("physical_selection_attempts", 1)):
        if type(diagnostic[name]) is not int or not 0 <= diagnostic[name] <= upper:
            raise ValueError("Invalid sync recovery bounded failure count")
    if diagnostic["active_window"] != "documents_ui" and (diagnostic["provider_roots_present"] or diagnostic["matching_filename_present"]):
        raise ValueError("Inconsistent sync recovery provider window observation")
    return diagnostic


def verify_ui_report(report, api=None):
    from android_voicekey_model_smoke import verify_finger_selection
    failure_diagnostic(report)
    if not isinstance(report, dict) or type(report.get("schema_version")) is not int or report.get("schema_version") not in (1, 2) or report.get("status") != "PASS" or report.get("synthetic") is not True:
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
    if type(covered) is not bool:
        raise ValueError("Missing observed navigation-cover evidence")
    if report["schema_version"] == 1:
        closes = report.get("provider_navigation_close_count")
        if type(closes) is not int or closes != int(covered):
            raise ValueError("Missing observed navigation-cover evidence")
        if covered and (report.get("provider_cover_closed_before_selection") is not True or report.get("provider_navigation_close_method") != "observed_cover_single_global_back"):
            raise ValueError("Provider cover not closed before filename selection")
    else:
        verify_root_navigation(report, covered)
    verify_finger_selection(report)


def navigation_rect(value):
    if (not isinstance(value, list) or len(value) != 4 or any(type(n) is not int for n in value)
            or not 0 <= value[0] < value[2] <= 32768 or not 0 <= value[1] < value[3] <= 32768):
        raise ValueError("Invalid observed navigation geometry")
    return value


def verify_root_navigation(report, covered):
    count = report.get("provider_navigation_root_selection_count")
    if type(count) is not int or count not in (0, 1):
        raise ValueError("Invalid observed root selection count")
    expected = "observed_DocumentsUI_Downloads_root_ACTION_CLICK" if count else "none"
    if report.get("provider_navigation_root_selection_method") != expected:
        raise ValueError("Missing observed root selection method")
    if count:
        if report.get("provider_navigation_root_control_descendant") is not True:
            raise ValueError("Root control is not within the identified navigation subtree")
        area = navigation_rect(report.get("provider_navigation_root_bounds"))
        control = navigation_rect(report.get("provider_navigation_control_bounds"))
        if not (area[0] <= control[0] < control[2] <= area[2] and area[1] <= control[1] < control[3] <= area[3]
                and control[3] - control[1] < area[3] - area[1]):
            raise ValueError("Root control not inside observed navigation")
    elif any(name in report for name in ("provider_navigation_root_control_descendant",
            "provider_navigation_root_bounds", "provider_navigation_control_bounds")):
        raise ValueError("Unexpected unexecuted root-control evidence")
    if report.get("provider_navigation_cover_cleared_before_selection") is not covered or covered and count != 1:
        raise ValueError("Observed cover was not cleared before selection")
    present = report.get("provider_navigation_present_at_selection")
    if type(present) is not bool:
        raise ValueError("Missing final navigation observation")
    if present:
        bounds = navigation_rect(report.get("provider_navigation_bounds_at_selection"))
        point = report.get("provider_touch_point")
        if (not isinstance(point, list) or len(point) != 2 or any(type(n) is not int for n in point)
                or bounds[0] <= point[0] < bounds[2] and bounds[1] <= point[1] < bounds[3]):
            raise ValueError("Final navigation covers the selected file point")
    elif "provider_navigation_bounds_at_selection" in report:
        raise ValueError("Unexpected absent-navigation geometry")
