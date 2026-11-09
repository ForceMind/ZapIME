# SPDX-License-Identifier: GPL-3.0-only
"""Strict UI/lifecycle proof; queued cancellation is not active HTTPS abortion."""

CASES = frozenset({
    "fresh_main_prepared_fixture", "key_page_secure_local_selection",
    "key_confirmation_negative_cancel", "key_queued_cancel_before_io",
    "transfer_page_secure_local_selection", "transfer_confirmation_negative_cancel",
    "transfer_queued_cancel_before_io", "stopped_selection_and_generation_invalidated",
    "prepared_business_and_vault_preserved", "owned_main_fixture_cleanup",
    "inventory_page_readonly_pagination", "inventory_queued_cancel_and_clear",
})
SCOPE = ("Actual non-exported Android Activities on a freshly owned prepared main fixture; "
         "local selection and resume seam, normal View.performClick and AlertDialog negative/positive "
         "controls, read-only inventory pagination, actual SyncWorker queued cancellation before work I/O, stopped-generation rejection, "
         "unchanged business/frozen/cursor/vault state and exact fixture cleanup. No physical input, "
         "active HTTPS interruption, successful network registration/transfer, phone or user data acceptance.")
ACTIVATION = "production_View_performClick_and_AlertDialog_buttons"
CANCEL_SCOPE = "actual_sync_worker_queued_cancel_before_work_io"
FAILURE_SUBSTEPS = frozenset({
    "case_body", "queued_worker_blocker", "queued_confirmation_dialog", "queued_positive_click",
    "queued_positive_dispatch", "queued_request_capture", "queued_stop_invalidation",
    "queued_cancellation_check", "queued_worker_drain", "queued_late_result_rejected",
    "owned_fixture_cleanup",
})
PROOFS = frozenset({
    "actual_activities", "manifest_nonexported", "secure_window_flags",
    "displayed_selection_matches_local", "resume_local_only_seam_verified",
    "negative_dialog_cancel_no_task", "actual_sync_worker_queued_cancel",
    "captured_cancellation_checked", "late_result_rejected",
    "business_queue_frozen_cursor_preserved", "vault_ciphertext_preserved",
    "fresh_fixture_owned", "cleanup_completed",
    "actual_inventory_preview", "inventory_page_cleared_on_stop",
})
FIELDS = PROOFS | {"schema_version", "status", "synthetic", "api_level", "cases", "scope",
                  "button_activation_method", "cancellation_scope", "android_https",
                  "active_https_interruption_tested", "physical_input_tested", "network_success_tested",
                  "secret_content_or_code_logged", "queued_cancelled_pages"}


def failure_substep(report):
    """Read one bounded failure observation; it never makes a report pass."""
    if not isinstance(report, dict):
        raise ValueError("Invalid sync page failure metadata")
    if "failure_substep" not in report:
        return None
    step = report["failure_substep"]
    if report.get("status") != "FAIL" or type(step) is not str or step not in FAILURE_SUBSTEPS:
        raise ValueError("Invalid sync page fixed failure substep")
    return step


def verify_report(report, api=None):
    failure_substep(report)
    if not isinstance(report, dict) or report.keys() != FIELDS:
        raise ValueError("Unknown or missing sync pages UI report fields")
    if type(report["schema_version"]) is not int or report["schema_version"] != 2 or report["status"] != "PASS" or report["synthetic"] is not True:
        raise ValueError("Incomplete sync pages UI report")
    level = report["api_level"]
    if type(level) is not int or level not in (28, 35, 36) or api is not None and level != api:
        raise ValueError("Wrong sync pages Android API")
    rows = report["cases"]
    if not isinstance(rows, list) or len(rows) != len(CASES) or any(not isinstance(row, dict) or row.keys() != {"id", "passed"} or type(row["id"]) is not str or row["passed"] is not True for row in rows):
        raise ValueError("Missing or failed sync pages UI cases")
    if {row["id"] for row in rows} != CASES:
        raise ValueError("Unknown or duplicate sync pages UI case")
    if any(report[name] is not True for name in PROOFS):
        raise ValueError("Missing actual page/selection/cancellation/cleanup evidence")
    if type(report["queued_cancelled_pages"]) is not int or report["queued_cancelled_pages"] != 2:
        raise ValueError("Both pages must cancel actual queued work")
    if report["button_activation_method"] != ACTIVATION or report["cancellation_scope"] != CANCEL_SCOPE or report["scope"] != SCOPE:
        raise ValueError("Incorrect sync page activation or cancellation scope")
    if report["android_https"] != "NOT RUN" or any(report[name] is not False for name in ("active_https_interruption_tested", "physical_input_tested", "network_success_tested", "secret_content_or_code_logged")):
        raise ValueError("Queued synthetic UI evidence cannot claim HTTPS/physical success or log secrets")
