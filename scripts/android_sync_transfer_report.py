# SPDX-License-Identifier: GPL-3.0-only
"""Strict Android JNI transfer proof; synthetic outer server fields are not HTTPS."""

CASES = frozenset({
    "isolated_existing_v2_shared_material", "native_edit_stage_frozen_retry",
    "ack_identity_operation_revision_rejection", "strict_ack_json_shape_rejection",
    "ack_requires_actual_stage", "ack_idempotent_preserves_cursor_and_later_edit",
    "authenticated_remote_apply_plaintext_no_echo", "authenticated_self_echo_no_duplicate",
    "bad_second_change_atomic_rollback_retained", "wrong_material_account_no_mutation",
    "negative_cursor_and_wire_budgets", "missing_future_database_not_created_or_migrated",
    "readonly_inventory_pending_known_no_mutation", "readonly_inventory_restored_pagination",
    "readonly_inventory_input_and_parser_rejection",
})
SCOPE = ("Actual Android stateless JNI and isolated existing-v2 SQLite; native synthetic edits, "
         "persistent frozen ciphertext, strict ACK, authenticated application, retained-page rollback "
         "and read-only inventory with strict host parsing. "
         "Server outer ACK/page metadata only is synthetic; no Android HTTPS, real server exchange, "
         "pairing, phone or Cloudflare acceptance.")
PROOFS = frozenset({
    "actual_native_edit", "actual_jni_staging", "actual_jni_acknowledgement",
    "actual_jni_authenticated_apply", "frozen_retry_identical", "plaintext_verified_locally",
    "no_upload_echo_on_receiver", "bad_page_rolled_back", "cleanup_completed",
    "actual_jni_readonly_inventory",
})
FIELDS = PROOFS | {"schema_version", "status", "synthetic", "api_level", "cases", "android_https",
                  "server_exchange", "scope", "failed_page_retention", "secret_content_or_ciphertext_logged"}


def verify_report(report, api=None):
    if not isinstance(report, dict) or report.keys() != FIELDS:
        raise ValueError("Unknown or missing JNI transfer report fields")
    if type(report["schema_version"]) is not int or report["schema_version"] != 2 or report["status"] != "PASS" or report["synthetic"] is not True:
        raise ValueError("Incomplete Android JNI transfer report")
    level = report["api_level"]
    if type(level) is not int or level not in (28, 35, 36) or api is not None and level != api:
        raise ValueError("Wrong Android JNI transfer API")
    rows = report["cases"]
    if not isinstance(rows, list) or len(rows) != len(CASES) or any(not isinstance(row, dict) or row.keys() != {"id", "passed"} or type(row["id"]) is not str or row["passed"] is not True for row in rows):
        raise ValueError("Missing or failed Android JNI transfer cases")
    if {row["id"] for row in rows} != CASES:
        raise ValueError("Unknown or duplicate Android JNI transfer case")
    if any(report[field] is not True for field in PROOFS):
        raise ValueError("Missing actual JNI transfer or cleanup proof")
    if report["android_https"] != "NOT RUN" or report["server_exchange"] != "synthetic_outer_only" or report["scope"] != SCOPE:
        raise ValueError("Synthetic JNI proof cannot claim Android HTTPS or real server exchange")
    if report["failed_page_retention"] != "saved" or report["secret_content_or_ciphertext_logged"] is not False:
        raise ValueError("Failed page retention or logging boundary unproven")
