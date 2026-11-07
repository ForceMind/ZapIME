# SPDX-License-Identifier: GPL-3.0-only
"""A real API28 fixture TLS/Go boundary, kept separate from synthetic JNI proof."""
import re

TLS_CASES = frozenset({"production_worker_platform_defaults", "untrusted_ca_rejected_as_tls"})
ROUND_CASES = frozenset({
    "production_worker_platform_defaults", "strict_https_bootstrap", "actual_owner_member_session_and_vault",
    "same_root_local_recovery", "owner_register_member_inspect", "trusted_ca_wrong_san_rejected",
    "owner_upload_actual_https", "member_download_decrypted_no_echo",
    "member_upload_owner_download_actual_https", "final_cursors_head_and_pending",
})
TLS_SCOPE = ("One API28 temporary CI emulator, production SyncHttp and unchanged platform TLS defaults; "
             "a valid dummy credential must fail specifically as tls before fixture CA installation or after "
             "its removal in a fresh process. No profile, server data exchange, public-PKI, phone or R2 acceptance.")
ROUND_SCOPE = ("One API28 temporary CI emulator with a temporary host-installed system CA, two isolated profiles, "
               "production Android HTTPS and actual Go/PostgreSQL service. Local same-root recovery, bidirectional "
               "synthetic favorites and strict hostname rejection; not two devices, stock root-store/public-PKI, "
               "R2, IME input, pairing or phone acceptance.")
FIELDS = frozenset({"schema_version", "status", "synthetic", "api_level", "phase", "cases", "cleanup_completed",
                    "worker_name_verified", "platform_defaults_unchanged", "secret_content_or_ciphertext_logged",
                    "scope", "android_https", "server_exchange", "profiles", "server_head", "final_cursors",
                    "uploaded_operations", "tls_failure_code"})
CLASS_NAME = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]{0,127}\Z")


def safe_report(report, phase=None, api=28):
    """Admit only bounded metadata, including genuine failed/partial originals.

    This does not mark a run successful. A caller may save the original bytes
    after this check, then separately require verify_report for CI success.
    """
    if not isinstance(report, dict) or report.keys() not in (FIELDS, FIELDS | {"cleanup_failure_type"}):
        raise ValueError("Unknown or missing Android HTTPS metadata fields")
    if type(report["schema_version"]) is not int or report["schema_version"] != 1 or report["status"] not in ("PASS", "FAIL") or report["synthetic"] is not True:
        raise ValueError("Invalid Android HTTPS report envelope")
    actual_phase = report["phase"]
    if actual_phase not in ("before", "roundtrip", "after") or phase is not None and actual_phase != phase:
        raise ValueError("Wrong Android HTTPS phase")
    if type(report["api_level"]) is not int or report["api_level"] != 28 or api != 28:
        raise ValueError("This HTTPS fixture only verifies Android API28")
    roundtrip = actual_phase == "roundtrip"
    if report["scope"] != (ROUND_SCOPE if roundtrip else TLS_SCOPE):
        raise ValueError("Android HTTPS fixture scope inflated or changed")
    for field in ("cleanup_completed", "worker_name_verified", "platform_defaults_unchanged"):
        if type(report[field]) is not bool:
            raise ValueError("Invalid Android HTTPS evidence flag")
    if report["secret_content_or_ciphertext_logged"] is not False:
        raise ValueError("Android HTTPS output privacy boundary violated")
    if "cleanup_failure_type" in report and (report["status"] != "FAIL" or report["cleanup_completed"] is not False or type(report["cleanup_failure_type"]) is not str or not CLASS_NAME.fullmatch(report["cleanup_failure_type"])):
        raise ValueError("Invalid cleanup failure metadata")
    rows, allowed = report["cases"], ROUND_CASES if roundtrip else TLS_CASES
    if not isinstance(rows, list) or len(rows) > len(allowed):
        raise ValueError("Invalid Android HTTPS cases")
    seen = set()
    for row in rows:
        if not isinstance(row, dict) or type(row.get("id")) is not str or row["id"] not in allowed or row["id"] in seen or type(row.get("passed")) is not bool:
            raise ValueError("Unknown, duplicate or malformed Android HTTPS case")
        seen.add(row["id"])
        if row["passed"]:
            if row.keys() != {"id", "passed"}:
                raise ValueError("Unexpected passed-case output")
        elif row.keys() != {"id", "passed", "failure_type"} or type(row["failure_type"]) is not str or not CLASS_NAME.fullmatch(row["failure_type"]):
            raise ValueError("Invalid failed-case metadata")
    for field, maximum in (("profiles", 2), ("uploaded_operations", 64)):
        if type(report[field]) is not int or not 0 <= report[field] <= maximum:
            raise ValueError("Invalid Android HTTPS metadata count")
    if report["server_head"] is not None and (type(report["server_head"]) is not int or report["server_head"] != 2):
        raise ValueError("Invalid Android HTTPS server-head evidence")
    cursors = report["final_cursors"]
    if not isinstance(cursors, list) or len(cursors) > 2 or any(type(value) is not int or value != 2 for value in cursors):
        raise ValueError("Invalid final authenticated cursors")
    if not roundtrip and (report["profiles"] != 0 or report["uploaded_operations"] != 0 or report["server_head"] is not None or cursors):
        raise ValueError("Untrusted TLS phase must not claim data exchange")
    expected_tls = "PASS_FIXTURE_CA" if roundtrip else "TLS_REJECTED"
    expected_server = "actual_go_postgresql" if roundtrip else "not_attempted_untrusted_tls"
    if report["android_https"] not in (expected_tls, "NOT PROVEN") or report["server_exchange"] not in (expected_server, "NOT PROVEN") or report["tls_failure_code"] not in ("tls", "NOT PROVEN"):
        raise ValueError("Invalid Android HTTPS evidence outcome")


def verify_report(report, phase=None, api=28):
    safe_report(report, phase, api)
    roundtrip = report["phase"] == "roundtrip"
    cases = ROUND_CASES if roundtrip else TLS_CASES
    if report["status"] != "PASS" or report.keys() != FIELDS or len(report["cases"]) != len(cases) or {row["id"] for row in report["cases"]} != cases or any(row["passed"] is not True for row in report["cases"]):
        raise ValueError("Incomplete Android HTTPS phase")
    if any(report[field] is not True for field in ("cleanup_completed", "worker_name_verified", "platform_defaults_unchanged")):
        raise ValueError("Missing actual worker, default TLS or cleanup evidence")
    if report["android_https"] != ("PASS_FIXTURE_CA" if roundtrip else "TLS_REJECTED") or report["server_exchange"] != ("actual_go_postgresql" if roundtrip else "not_attempted_untrusted_tls") or report["tls_failure_code"] != "tls":
        raise ValueError("Actual HTTPS or specific TLS rejection unproven")
    if roundtrip and (report["profiles"] != 2 or report["uploaded_operations"] != 2 or report["server_head"] != 2 or report["final_cursors"] != [2, 2]):
        raise ValueError("Bidirectional real-service roundtrip counts incomplete")
