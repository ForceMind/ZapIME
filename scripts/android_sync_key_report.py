# SPDX-License-Identifier: GPL-3.0-only
"""Offline descriptor acceptance is not Android HTTPS or server CAS evidence."""

CASES = frozenset({
    "descriptor_roundtrip", "descriptor_schema", "descriptor_uuid_hash",
    "response_states", "response_schema", "response_account_and_nulls",
    "response_timestamp_utf8_bounds", "native_descriptor_digest",
    "native_descriptor_negative_inputs", "prepared_local_identity",
    "worker_before_io", "missing_connection_before_network",
    "unprepared_before_network", "precancel_before_io",
})

FIELDS = frozenset({"schema_version", "status", "synthetic", "api_level", "cases",
    "cleanup_completed", "actual_native_descriptor", "actual_keystore_prepared",
    "material_bytes", "descriptor_public_fields", "android_https", "server_cas",
    "remote_success_tested", "secret_or_code_logged", "scope"})


def verify_report(report, api=None):
    if not isinstance(report, dict) or set(report) != FIELDS or type(report.get("schema_version")) is not int or report["schema_version"] != 1 or report.get("status") != "PASS":
        raise ValueError("Incomplete offline key descriptor report")
    if report.get("synthetic") is not True or report.get("cleanup_completed") is not True:
        raise ValueError("Offline key descriptor fixture did not complete cleanup")
    level = report.get("api_level")
    if type(level) is not int or level not in (28, 35, 36) or api is not None and level != api:
        raise ValueError("Wrong offline key descriptor Android API")
    cases = report.get("cases")
    if not isinstance(cases, list) or len(cases) != len(CASES):
        raise ValueError("Missing offline key descriptor cases")
    if any(not isinstance(case, dict) or set(case) != {"id", "passed"} or type(case.get("id")) is not str or case.get("passed") is not True for case in cases) or {case["id"] for case in cases} != CASES:
        raise ValueError("Unknown, duplicate or failed offline key descriptor case")
    if report.get("actual_native_descriptor") is not True or report.get("actual_keystore_prepared") is not True:
        raise ValueError("Missing real descriptor JNI or Keystore fixture")
    if type(report.get("material_bytes")) is not int or report["material_bytes"] != 72 or type(report.get("descriptor_public_fields")) is not int or report["descriptor_public_fields"] != 3:
        raise ValueError("Wrong typed material or public descriptor shape")
    if report.get("android_https") != "NOT RUN" or report.get("server_cas") != "NOT RUN" or report.get("remote_success_tested") is not False or report.get("secret_or_code_logged") is not False:
        raise ValueError("Offline evidence cannot claim remote success or contain secrets")
    if not isinstance(report.get("scope"), str) or not report["scope"]:
        raise ValueError("Missing offline-only acceptance scope")
