#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Actual Android HTTPS only on this job's disposable, rooted API28 AVD.

This changes a temporary emulator system trust store, never an APK trust policy.
There is deliberately no local-device mode and no generic diagnostic collection.
"""
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
from urllib.parse import urlsplit

from android_ci_device import dedicated_ci


PACKAGE = "org.zapime.android"
TEST_PACKAGE = "org.zapime.android.voicetest"
CA_DIRECTORY = "/system/etc/security/cacerts"
REPORT_DIRECTORY = "/sdcard/Android/data/org.zapime.android/files/voice-fixtures"
SCOPE = ("API28 disposable AOSP emulator; temporary system CA; unchanged product platform TLS and "
         "hostname verification; controlled loopback TLS to actual Go/PostgreSQL. "
         "Not stock trust, two phones, API35/36, public CA, R2 or production deployment.")


class Rejected(RuntimeError):
    """Fixed codes only: never expose a subprocess command or captured secret."""


def require(value, code):
    if not value:
        raise Rejected(code)


def ci_identity(command, environment=None):
    environment = os.environ if environment is None else environment
    require(environment.get("GITHUB_ACTIONS") == "true"
            and re.fullmatch(r"[0-9]+", environment.get("GITHUB_RUN_ID", "")) is not None
            and re.fullmatch(r"[0-9]+", environment.get("GITHUB_RUN_ATTEMPT", "")) is not None
            and len(command) == 3 and command[1] == "-s"
            and re.fullmatch(r"emulator-[0-9]+", command[2]) is not None,
            "dedicated_numeric_ci_required")
    return environment["GITHUB_RUN_ID"] + "-" + environment["GITHUB_RUN_ATTEMPT"]


def endpoint_port(origin):
    try:
        value = urlsplit(origin)
        port = value.port
        require(value.scheme == "https" and value.hostname == "10.0.2.2"
                and value.username is None and value.password is None
                and value.path == "" and not value.query and not value.fragment
                and port is not None and 1024 <= port <= 65535
                and origin == "https://10.0.2.2:" + str(port), "fixture_origin_invalid")
        return port
    except (ValueError, TypeError):
        raise Rejected("fixture_origin_invalid") from None


def validate_database(value):
    try:
        uri = urlsplit(value)
        require(uri.scheme in ("postgres", "postgresql") and uri.hostname == "127.0.0.1"
                and uri.port == 5432 and uri.username == "postgres" and bool(uri.password)
                and uri.path == "/zapime_https_test" and uri.query == "sslmode=disable"
                and not uri.fragment, "fresh_ci_database_required")
    except (ValueError, TypeError):
        raise Rejected("fresh_ci_database_required") from None


def verify_product_manifest(raw):
    """Inspect compiled aapt2 XML tree, not the source manifest."""
    stack, roots = [], []
    for line in raw.splitlines():
        element = re.match(r"^(\s*)E: ([A-Za-z0-9_.-]+)(?:\s|$)", line)
        attribute = re.match(r"^(\s*)A: ([^\s=(]+)(?:\([^)]*\))?=(.*)$", line)
        if element:
            level, name = len(element[1]), element[2]
            while stack and stack[-1][0] >= level:
                stack.pop()
            node = {"name": name, "attributes": {}, "children": []}
            (stack[-1][1]["children"] if stack else roots).append(node)
            stack.append((level, node))
        elif attribute:
            require(bool(stack) and len(attribute[1]) > stack[-1][0], "compiled_manifest_shape_invalid")
            attributes = stack[-1][1]["attributes"]
            name = attribute[2]
            namespace = "http://schemas.android.com/apk/res/android:"
            if name.startswith(namespace):
                name = "android:" + name[len(namespace):]
            require(name not in attributes, "compiled_manifest_duplicate_attribute")
            attributes[name] = attribute[3]
    require(len(roots) == 1 and roots[0]["name"] == "manifest", "compiled_manifest_missing")
    apps = [node for node in roots[0]["children"] if node["name"] == "application"]
    require(len(apps) == 1, "compiled_manifest_application_invalid")
    app = apps[0]
    attributes = app["attributes"]
    require("android:networkSecurityConfig" not in attributes, "product_custom_trust_configuration")
    false_values = {"false", "(type 0x12)0x0"}
    require(attributes.get("android:debuggable", "false") in false_values,
            "product_debuggable_enabled")
    require(attributes.get("android:usesCleartextTraffic") in false_values, "product_cleartext_enabled")
    found = set()
    expected = {"SyncPreparationActivity", "SyncKeyRegistrationActivity", "SyncTransferActivity"}
    for child in app["children"]:
        if child["name"] != "activity":
            continue
        value = child["attributes"].get("android:name", "")
        name = re.fullmatch(r'"(\.[A-Za-z0-9_.]+|org\.zapime\.android\.[A-Za-z0-9_.]+)"(?: \(Raw: "[^"\r\n]+"\))?', value)
        if name and name[1].rsplit(".", 1)[-1] in expected:
            simple = name[1].rsplit(".", 1)[-1]
            require(simple not in found and child["attributes"].get("android:exported") in false_values,
                    "sync_activity_exported_or_duplicate")
            found.add(simple)
    require(found == expected, "sync_private_activity_missing")


def safe_fixture_summary(summary):
    fields = {"schema_version", "scope", "requests", "bad_hostname_http_requests", "service_revision",
              "owner_session_valid", "member_session_valid", "bootstrap_response_bytes", "request_limit_bytes",
              "response_limit_bytes", "cleanup_completed", "go_process_stopped", "tls_threads_stopped", "private_temp_removed"}
    require(isinstance(summary, dict) and summary.keys() in (fields, fields | {"observation_failed"}), "fixture_summary_fields_invalid")
    require(type(summary["schema_version"]) is int and summary["schema_version"] == 1
            and summary["scope"] == "Real isolated Go/PostgreSQL via temporary host TLS; no Android claim", "fixture_summary_scope_invalid")
    for field in ("owner_session_valid", "member_session_valid", "cleanup_completed", "go_process_stopped",
                  "tls_threads_stopped", "private_temp_removed", "observation_failed"):
        if field in summary:
            require(type(summary[field]) is bool, "fixture_summary_flag_invalid")
    require(summary["service_revision"] is None or type(summary["service_revision"]) is int
            and 0 <= summary["service_revision"] <= 100, "fixture_summary_revision_invalid")
    for field, maximum in (("bad_hostname_http_requests", 100), ("bootstrap_response_bytes", 4096),
                           ("request_limit_bytes", 100000), ("response_limit_bytes", 10 * 1024 * 1024)):
        require(type(summary[field]) is int and 0 <= summary[field] <= maximum, "fixture_summary_count_invalid")
    require(type(summary["requests"]) is list and len(summary["requests"]) <= 32, "fixture_request_summary_invalid")
    seen = set()
    paths = {"/ci/bootstrap/<redacted>", "/v1/session", "/v1/key-descriptor", "/v1/changes", "/v1/devices", "/v1/devices/<redacted>", "/<other>"}
    for row in summary["requests"]:
        require(type(row) is dict and row.keys() == {"method", "path", "count"}
                and row["method"] in {"GET", "PUT", "POST", "DELETE"} and row["path"] in paths
                and type(row["count"]) is int and 0 < row["count"] <= 1000, "fixture_request_summary_invalid")
        key = (row["method"], row["path"])
        require(key not in seen, "fixture_request_summary_duplicate")
        seen.add(key)
    return summary


def verify_fixture_summary(summary):
    safe_fixture_summary(summary)
    require(summary["service_revision"] == 2 and summary["bad_hostname_http_requests"] == 0,
            "actual_service_roundtrip_unproven")
    require(all(summary[field] is True for field in ("owner_session_valid", "member_session_valid", "cleanup_completed",
                "go_process_stopped", "tls_threads_stopped", "private_temp_removed"))
            and summary.get("observation_failed", False) is False, "actual_service_cleanup_or_sessions_unproven")
    counts = {(row["method"], row["path"]): row["count"] for row in summary["requests"]}
    require(counts.get(("GET", "/ci/bootstrap/<redacted>")) == 1
            and counts.get(("POST", "/v1/changes")) == 2
            and counts.get(("PUT", "/v1/key-descriptor")) == 1
            and counts.get(("GET", "/v1/session"), 0) >= 2
            and counts.get(("GET", "/v1/changes"), 0) >= 3
            and counts.get(("GET", "/v1/key-descriptor"), 0) >= 3,
            "actual_service_exchange_counts_unproven")


def parse_baseline(raw):
    entries = {}
    for line in raw.splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  " + re.escape(CA_DIRECTORY) + r"/([0-9a-f]{8}\.[0-9]+)", line)
        require(match is not None and match[2] not in entries, "ca_baseline_invalid")
        entries[match[2]] = match[1]
    require(len(entries) >= 20, "ca_baseline_empty")
    return entries


def rule_plan(uid, ports, chain):
    require(isinstance(uid, int) and not isinstance(uid, bool) and uid >= 10000, "product_uid_invalid")
    require(len(ports) == 2 and len(set(ports)) == 2
            and all(isinstance(port, int) and not isinstance(port, bool) and 1024 <= port <= 65535 for port in ports),
            "fixture_ports_invalid")
    require(re.fullmatch(r"ZAPHTTPS[0-9a-f]{12}", chain) is not None, "rule_chain_invalid")
    owner = ["OUTPUT", "-m", "owner", "--uid-owner", str(uid), "-j", chain]
    ipv4 = [[chain, "-p", "tcp", "-d", "10.0.2.2/32", "--dport", str(port), "-j", "ACCEPT"] for port in ports]
    return [("iptables", owner, ipv4 + [[chain, "-j", "REJECT"]]),
            ("ip6tables", owner, [[chain, "-j", "REJECT"]])]


class Adb:
    def __init__(self, command):
        self.command = command

    def run(self, *arguments, check=True, timeout=30):
        try:
            result = subprocess.run(self.command + list(arguments), text=True, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise Rejected("adb_timeout") from None
        except OSError:
            raise Rejected("adb_unavailable") from None
        if check and result.returncode != 0:
            raise Rejected("adb_command_failed")
        return result

    def shell(self, *arguments, **options):
        return self.run("shell", *arguments, **options).stdout.strip()


def guard_device(adb, identity, root=False):
    require(ci_identity(adb.command) == identity and dedicated_ci(adb.command), "dedicated_numeric_ci_required")
    require(adb.shell("getprop", "ro.kernel.qemu") == "1", "emulator_required")
    require(adb.shell("getprop", "ro.build.version.sdk") == "28", "api28_required")
    require(adb.shell("getprop", "ro.build.type") == "userdebug", "userdebug_required")
    require(adb.shell("getprop", "ro.debuggable") == "1", "debuggable_system_required")
    require(adb.shell("getprop", "ro.product.cpu.abi") == "x86_64", "x86_64_required")
    require(adb.shell("getenforce") == "Enforcing", "selinux_enforcing_required")
    name = adb.run("emu", "avd", "name").stdout.splitlines()
    require(name and name[0].strip() == "zapime-sync-https-" + identity, "owned_avd_required")
    if root:
        require(adb.shell("id", "-u") == "0", "emulator_root_required")


def become_root(adb, identity):
    # Read-only device checks precede the one privileged daemon transition.
    guard_device(adb, identity)
    adb.run("root")
    adb.run("wait-for-device")
    guard_device(adb, identity, root=True)


def product_uid(adb):
    raw = adb.shell("pm", "list", "packages", "-U", PACKAGE)
    matches = re.findall(r"^package:org\.zapime\.android\s+uid:([0-9]+)\s*$", raw, re.M)
    require(len(matches) == 1 and int(matches[0]) >= 10000, "product_uid_invalid")
    return int(matches[0])


@contextmanager
def allow_fixture(adb, identity, ports, proof):
    guard_device(adb, identity, root=True)
    uid = product_uid(adb)
    chain = "ZAPHTTPS" + secrets.token_hex(6)
    installed = []
    chains = []
    proof.update({"uid": uid, "ipv4_fixture_only": False, "ipv6_reject": False, "removed": False, "checks": 0})

    def execute(binary, operation, arguments, check=True):
        return adb.run("shell", binary, "-w", "5", operation, *arguments, check=check)

    try:
        for binary, owner, body in rule_plan(uid, ports, chain):
            require(execute(binary, "-S", [chain], check=False).returncode != 0, "owned_chain_already_exists")
            execute(binary, "-N", [chain])
            chains.append(binary)
            for rule in body:
                execute(binary, "-A", rule)
                installed.append((binary, rule))
            execute(binary, "-I", owner)
            installed.append((binary, owner))
            for rule in body + [owner]:
                execute(binary, "-C", rule)
            proof["ipv4_fixture_only" if binary == "iptables" else "ipv6_reject"] = True
        def verify():
            guard_device(adb, identity, root=True)
            require(product_uid(adb) == uid, "product_uid_changed")
            for binary, owner, body in rule_plan(uid, ports, chain):
                for rule in body + [owner]:
                    execute(binary, "-C", rule)
                output_rules = [line for line in execute(binary, "-S", ["OUTPUT"]).stdout.splitlines()
                                if line.startswith("-A OUTPUT ")]
                require(output_rules and output_rules[0] == "-A " + " ".join(owner),
                        "owned_uid_jump_not_first")
                require(len(execute(binary, "-S", [chain]).stdout.splitlines()) == len(body) + 1,
                        "owned_chain_changed")
            proof["checks"] += 1
        verify()
        yield verify
    finally:
        failed = False
        for binary, rule in reversed(installed):
            try:
                if execute(binary, "-D", rule, check=False).returncode != 0:
                    failed = True
                if execute(binary, "-C", rule, check=False).returncode == 0:
                    failed = True
            except Rejected:
                failed = True
        for binary in reversed(chains):
            try:
                if execute(binary, "-X", [chain], check=False).returncode != 0:
                    failed = True
                if execute(binary, "-S", [chain], check=False).returncode == 0:
                    failed = True
            except Rejected:
                failed = True
        proof["removed"] = not failed
        require(not failed, "uid_rule_cleanup_failed")


def baseline(adb):
    # All names and hashes are public system certificates. Reject symlinks or
    # unexpected members rather than silently omitting them from the baseline.
    script = ("for p in " + CA_DIRECTORY + "/*; do "
              "[ -f \"$p\" ] && [ ! -L \"$p\" ] || exit 19; done; "
              "sha256sum " + CA_DIRECTORY + "/*")
    return parse_baseline(adb.shell(script))


class TemporaryCA:
    def __init__(self, adb, identity, fixture, initial, proof):
        self.adb, self.identity, self.fixture, self.initial, self.proof = adb, identity, fixture, initial, proof
        subject = fixture.ca_subject_hash_old
        require(re.fullmatch(r"[0-9a-f]{8}", subject) is not None, "ca_hash_invalid")
        self.name = subject + ".0"
        require(self.name not in initial, "ca_subject_hash_collision")
        self.destination = CA_DIRECTORY + "/" + self.name
        self.staging = "/data/local/tmp/zapime-sync-https-" + identity + "-" + secrets.token_hex(8) + ".pem"
        self.staged = self.created = False
        proof.update({"installed": False, "removed": False, "staging_removed": False,
                      "baseline_restored": False, "sha256_pem": fixture.ca_sha256_pem,
                      "sha256_der": fixture.ca_sha256_der})

    def digest(self, path):
        raw = self.adb.shell("sha256sum", path)
        match = re.fullmatch(r"([0-9a-f]{64})  " + re.escape(path), raw)
        require(match is not None, "ca_fingerprint_unavailable")
        return match[1]

    def install(self):
        guard_device(self.adb, self.identity, root=True)
        data = Path(self.fixture.ca_file).read_bytes()
        require(0 < len(data) <= 16384 and hashlib.sha256(data).hexdigest() == self.fixture.ca_sha256_pem
                and re.fullmatch(r"[0-9a-f]{64}", self.fixture.ca_sha256_der) is not None,
                "ca_fixture_fingerprint_invalid")
        require(baseline(self.adb) == self.initial, "ca_baseline_changed")
        self.adb.run("remount")
        self.adb.run("shell", "test", "!", "-e", self.staging)
        self.adb.run("shell", "test", "!", "-e", self.destination)
        # Staging is a unique public-certificate file, never a private key.
        self.staged = True
        self.adb.run("push", str(self.fixture.ca_file), self.staging)
        require(self.digest(self.staging) == self.fixture.ca_sha256_pem, "ca_staging_fingerprint_invalid")
        # Establish exclusive ownership before writing; a failed no-clobber
        # creation must never grant permission to delete a concurrent file.
        self.adb.run("shell", "umask 077; set -C; : > " + self.destination)
        self.created = True
        self.adb.run("shell", "cat " + self.staging + " > " + self.destination)
        self.adb.run("shell", "chown", "0:0", self.destination)
        self.adb.run("shell", "chmod", "0644", self.destination)
        self.adb.run("shell", "restorecon", self.destination)
        require(self.adb.shell("stat", "-c", "%u:%g:%a", self.destination) == "0:0:644", "ca_mode_invalid")
        old = CA_DIRECTORY + "/" + sorted(self.initial)[0]
        label = self.adb.shell("ls", "-Z", old).split()[0]
        require(label.startswith("u:object_r:")
                and self.adb.shell("ls", "-Z", self.destination).split()[0] == label,
                "ca_selinux_label_invalid")
        require(self.digest(self.destination) == self.fixture.ca_sha256_pem, "ca_installed_fingerprint_invalid")
        expected = dict(self.initial)
        expected[self.name] = self.fixture.ca_sha256_pem
        require(baseline(self.adb) == expected, "ca_install_modified_existing_store")
        self.proof["installed"] = True

    def remove(self):
        guard_device(self.adb, self.identity, root=True)
        failed = False
        if self.created:
            # Never delete an unrecognized certificate, including after a failed
            # or interrupted write. A mismatch remains a failed cleanup.
            try:
                require(self.digest(self.destination) == self.fixture.ca_sha256_pem, "ca_cleanup_identity_changed")
                self.adb.run("shell", "rm", self.destination)
                self.adb.run("shell", "test", "!", "-e", self.destination)
                self.created = False
            except Rejected:
                failed = True
        self.proof["removed"] = not self.created
        if self.staged:
            try:
                require(self.digest(self.staging) == self.fixture.ca_sha256_pem, "ca_staging_cleanup_identity_changed")
                self.adb.run("shell", "rm", self.staging)
                self.adb.run("shell", "test", "!", "-e", self.staging)
                self.staged = False
            except Rejected:
                failed = True
        self.proof["staging_removed"] = not self.staged
        self.proof["baseline_restored"] = baseline(self.adb) == self.initial
        require(not failed and self.proof["baseline_restored"], "ca_cleanup_failed")


def restart_app(adb, identity):
    guard_device(adb, identity, root=True)
    adb.run("shell", "am", "force-stop", PACKAGE)
    adb.run("shell", "am", "force-stop", TEST_PACKAGE)
    guard_device(adb, identity, root=True)


def phase(adb, identity, fixture, selected, output, verify_network):
    from android_sync_https_report import safe_report, verify_report
    require(selected in ("before", "roundtrip", "after"), "phase_invalid")
    require(re.fullmatch(r"/ci/bootstrap/[0-9a-f]{64}", fixture.bootstrap_path) is not None, "bootstrap_route_invalid")
    restart_app(adb, identity)
    verify_network()
    execution = {"phase": selected, "status": "FAIL", "exception_type": None, "returncode": None}
    filename = "sync-https-" + selected + "-report.json"
    try:
        result = adb.run("shell", "am", "instrument", "-w", "-r",
                         "-e", "zapime_ci_sync_https", identity,
                         "-e", "sync_https_phase", selected,
                         "-e", "sync_https_origin", fixture.origin,
                         "-e", "sync_https_bad_origin", fixture.bad_origin,
                         "-e", "sync_https_bootstrap", fixture.bootstrap_path,
                         TEST_PACKAGE + "/.VoiceInstrumentation", check=False, timeout=300)
        execution["returncode"] = result.returncode
        require(adb.shell("stat", "-c", "%s", REPORT_DIRECTORY + "/" + filename).isdigit(), "phase_report_missing")
        size = int(adb.shell("stat", "-c", "%s", REPORT_DIRECTORY + "/" + filename))
        require(0 < size <= 65536, "phase_report_size_invalid")
        raw = adb.run("exec-out", "cat", REPORT_DIRECTORY + "/" + filename).stdout
        require(len(raw.encode("utf-8")) == size, "phase_report_size_changed")
        def unique_members(pairs):
            result = {}
            for key, value in pairs:
                require(key not in result, "phase_report_duplicate_member")
                result[key] = value
            return result
        report = json.loads(raw, object_pairs_hook=unique_members)
        # Parse and sanitize with the strict test-only validator before making
        # any report available as an artifact. Never save raw instrument output.
        safe_report(report, phase=selected, api=28)
        (output / filename).write_text(raw, encoding="utf-8")
        require(result.returncode == 0 and "INSTRUMENTATION_FAILED" not in result.stdout
                and "FAIL Android voice" not in result.stdout, "instrumentation_failed")
        verify_report(report, phase=selected, api=28)
        verify_network()
        execution["status"] = "PASS"
    except BaseException as error:
        execution["exception_type"] = type(error).__name__
        raise
    finally:
        (output / ("sync-https-" + selected + "-execution.json")).write_text(json.dumps(execution, indent=2) + "\n")


def main():
    require(len(sys.argv) == 5, "arguments_required")
    adb_path, serial, binary, database_environment = sys.argv[1:]
    command = [adb_path, "-s", serial]
    identity = ci_identity(command)
    require(database_environment == "ZAPIME_TEST_DATABASE_URL", "database_environment_invalid")
    require(bool(os.environ.get(database_environment)), "database_environment_missing")
    validate_database(os.environ[database_environment])
    adb = Adb(command)
    root = Path(__file__).resolve().parents[1]
    output = root / "target/sync-https-android"
    output.mkdir(parents=True, exist_ok=True)
    proof = {"schema_version": 1, "status": "FAIL", "synthetic": True, "api_level": 28,
             "scope": SCOPE, "cleanup_completed": False, "exception_type": None,
             "failure_code": None, "ca": {}, "network": {}, "fixture": {},
             "api35_36": "BLOCKED_NOT_RUN", "product_trust_code_changed": False,
             "fresh_install": False, "owned_packages_removed": False}
    fixture = ca = None
    failure = None
    installed_packages = []
    try:
        become_root(adb, identity)
        for package in (PACKAGE, TEST_PACKAGE):
            require(adb.shell("pm", "list", "packages", package) == "", "fresh_product_install_required")
        proof["fresh_install"] = True
        inputs = [root / "target/android-x86_64/ZapIME-0.1.0-dev-x86_64.apk",
                  root / "target/android-voice-tests/ZapIME-voice-tests.apk"]
        proof["apk_sha256"] = [hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs]
        adb.run("install", str(inputs[0]))
        installed_packages.append(PACKAGE)
        adb.run("install", "-t", str(inputs[1]))
        installed_packages.append(TEST_PACKAGE)
        initial = baseline(adb)
        (output / "ca-store-baseline.json").write_text(json.dumps(initial, indent=2) + "\n")
        spec = importlib.util.spec_from_file_location("sync_https_server", root / "scripts/fixtures/sync_https_server.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with module.Fixture(output.parent, Path(binary).resolve(), os.environ[database_environment]) as fixture:
            ports = [endpoint_port(fixture.origin), endpoint_port(fixture.bad_origin)]
            require(ports[0] != ports[1], "fixture_ports_invalid")
            ca = TemporaryCA(adb, identity, fixture, initial, proof["ca"])
            with allow_fixture(adb, identity, ports, proof["network"]) as verify_network:
                body_failure = None
                cleanup_errors = []
                try:
                    phase(adb, identity, fixture, "before", output, verify_network)
                    restart_app(adb, identity)
                    ca.install()
                    phase(adb, identity, fixture, "roundtrip", output, verify_network)
                except BaseException as error:
                    body_failure = error
                    proof["operation_exception_type"] = type(error).__name__
                finally:
                    for cleanup in (lambda: restart_app(adb, identity), ca.remove):
                        try:
                            cleanup()
                        except BaseException as error:
                            cleanup_errors.append(type(error).__name__)
                    proof["ca_cleanup_exception_types"] = cleanup_errors
                if not cleanup_errors:
                    try:
                        phase(adb, identity, fixture, "after", output, verify_network)
                    except BaseException as error:
                        if body_failure is None:
                            body_failure = error
                if body_failure is not None:
                    raise body_failure
                require(not cleanup_errors, "ca_cleanup_failed")
            require(baseline(adb) == initial, "ca_store_not_restored")
        proof["fixture"] = safe_fixture_summary(fixture.safe_summary())
        verify_fixture_summary(proof["fixture"])
        proof["cleanup_completed"] = (proof["network"].get("removed") is True
                                      and proof["ca"].get("removed") is True
                                      and proof["ca"].get("staging_removed") is True
                                      and proof["ca"].get("baseline_restored") is True)
        require(proof["cleanup_completed"], "cleanup_incomplete")
        proof["status"] = "PASS"
    except BaseException as error:
        failure = error
        proof["exception_type"] = type(error).__name__
        proof["failure_code"] = str(error) if isinstance(error, Rejected) else "fixed_failure"
    finally:
        if fixture is not None:
            # Fixture has already closed even when its body failed.
            try:
                proof["fixture"] = safe_fixture_summary(fixture.safe_summary())
            except BaseException as error:
                proof["fixture_summary_exception_type"] = type(error).__name__
                proof["status"] = "FAIL"
                proof["cleanup_completed"] = False
                if failure is None:
                    failure = Rejected("fixture_summary_unavailable")
        package_errors = []
        for package in reversed(installed_packages):
            try:
                guard_device(adb, identity, root=True)
                adb.run("shell", "am", "force-stop", package)
                adb.run("uninstall", package)
                require(adb.shell("pm", "list", "packages", package) == "", "owned_package_not_removed")
            except BaseException as error:
                package_errors.append(type(error).__name__)
        proof["package_cleanup_exception_types"] = package_errors
        proof["owned_packages_removed"] = not package_errors
        proof["cleanup_completed"] = proof["cleanup_completed"] and not package_errors
        if package_errors:
            proof["status"] = "FAIL"
            if failure is None:
                failure = Rejected("owned_package_cleanup_failed")
                proof["exception_type"] = type(failure).__name__
                proof["failure_code"] = str(failure)
        (output / "sync-https-driver-report.json").write_text(json.dumps(proof, indent=2) + "\n")
    if failure is not None:
        raise Rejected("android_https_acceptance_failed") from None
    print("PASS actual API28 temporary-system-CA Android HTTPS to Go/PostgreSQL; scoped synthetic acceptance")


if __name__ == "__main__":
    try:
        main()
    except BaseException as failure:
        print("FAIL Android HTTPS acceptance: " + type(failure).__name__, file=sys.stderr)
        sys.exit(1)
