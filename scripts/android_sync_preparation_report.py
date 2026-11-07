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
