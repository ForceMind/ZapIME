# SPDX-License-Identifier: GPL-3.0-only
"""Stock platform HTTPS/App Links on the authorized disposable A3 CI emulator."""
import base64
from contextlib import ExitStack, contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import xml.etree.ElementTree as ET

from android_sync_https_smoke import Adb, Rejected, TemporaryCA, baseline, ci_identity, require
from fixtures.sync_https_server import Fixture
from test_sync_https_server import request as fixture_https


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "org.zapime.android.a3loginprobe"
ACTIVITY = PACKAGE + "/.ProbeActivity"
APP_PHASE = "/data/user/0/" + PACKAGE + "/files/phase"
MAIN_PACKAGE = "org.zapime.android"
MAIN_ACCOUNT = "98a01050-3185-4eed-bac4-127426c17372"
LOGIN_HOST = "a3-oidc.example.test"
AUTOMATIC_MODES = ("automatic", "automatic-recovery")
RETURN_HOST = "return-a3.example.test"
HOSTS = "/system/etc/hosts"
BROWSER = "org.chromium.webview_shell/.WebViewBrowserActivity"


def guard(adb, identity, root=False):
    require(ci_identity(adb.command) == identity and adb.command[2] == "emulator-5554", "numeric_a3_ci_required")
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    expected = repository + "/.github/workflows/a3-android-oidc.yml@refs/pull/"
    require(bool(repository) and os.environ.get("GITHUB_EVENT_NAME") == "pull_request" and
            os.environ.get("GITHUB_WORKFLOW_REF", "").startswith(expected), "a3_workflow_required")
    for field, value in (("ro.kernel.qemu", "1"), ("ro.build.version.sdk", "28"), ("ro.build.type", "userdebug"),
                         ("ro.debuggable", "1"), ("ro.product.cpu.abi", "x86_64")):
        require(adb.shell("getprop", field) == value, "stock_api28_required")
    require(adb.shell("getenforce") == "Enforcing", "selinux_enforcing_required")
    name = adb.run("emu", "avd", "name").stdout.splitlines()
    require(name and name[0].strip() == "zapime-a3-oidc-" + identity, "owned_a3_avd_required")
    if root:
        require(adb.shell("id", "-u") == "0", "emulator_root_required")


def command(*args):
    result = subprocess.run([str(x) for x in args], capture_output=True, timeout=20, check=False)
    require(result.returncode == 0, "fixture_tool_failed")
    return result.stdout


def make_certificates(work):
    ca, ca_key, leaf, leaf_key = [work / x for x in ("ca.pem", "ca-key.pem", "leaf.pem", "leaf-key.pem")]
    csr, extension = work / "leaf.csr", work / "leaf.ext"
    extension.write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                         "extendedKeyUsage=serverAuth\nsubjectAltName=DNS:" + LOGIN_HOST + ",DNS:" + RETURN_HOST + "\n")
    command("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "1",
            "-keyout", ca_key, "-out", ca, "-subj", "/CN=ZapIME-A3-CI-" + secrets.token_hex(8),
            "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign")
    command("openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", leaf_key, "-out", csr,
            "-subj", "/CN=" + LOGIN_HOST)
    command("openssl", "x509", "-req", "-in", csr, "-CA", ca, "-CAkey", ca_key, "-set_serial",
            str(secrets.randbits(159) + 1), "-days", "1", "-sha256", "-extfile", extension, "-out", leaf)
    return leaf, leaf_key, SimpleNamespace(ca_file=ca,
        ca_subject_hash_old=command("openssl", "x509", "-in", ca, "-subject_hash_old", "-noout").decode().strip(),
        ca_sha256_pem=hashlib.sha256(ca.read_bytes()).hexdigest(),
        ca_sha256_der=hashlib.sha256(command("openssl", "x509", "-in", ca, "-outform", "der")).hexdigest())


def obtain_fingerprint(apksigner, apk):
    data = command(apksigner, "verify", "--print-certs", apk).decode("ascii", errors="replace")
    found = re.findall(r"^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$", data, re.MULTILINE)
    require(len(found) == 1 and "Signer #2 " not in data, "fixture_signer_invalid")
    return ":".join(found[0][index:index+2].upper() for index in range(0, 64, 2))


def wait_for(operation, predicate, seconds, reason):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        value = operation()
        if predicate(value):
            return value
        time.sleep(.35)
    raise Rejected(reason)


@contextmanager
def issuer(work, leaf, key, fingerprint, go_proof, mode, foreground=None):
    summary = work / "issuer-status.json"
    program = ROOT / "target/a3-android-oidc/issuer/android-issuer.mjs"
    args = ["node", str(program), str(leaf), str(key), fingerprint, str(summary), str(go_proof), mode]
    if foreground is not None:
        args.append(str(foreground))
    child = subprocess.Popen(args,
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    def status():
        require(child.poll() is None, "oidc_provider_exited")
        return json.loads(summary.read_text()) if summary.is_file() else {}
    try:
        ready = wait_for(status, lambda data: type(data.get("port")) is int and 1024 <= data["port"] <= 65535,
                         10, "oidc_provider_not_ready")
        yield ready["port"], status
    finally:
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=8)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)


def read_remote(adb, filename):
    return command(*adb.command, "exec-out", "cat", filename)


def system_links_confirmed(adb, package):
    try:
        output = adb.shell("dumpsys", "-t", "5", "package", "domain-preferred-apps", timeout=8)
    except Rejected as failure:
        if str(failure) != "adb_timeout":
            raise
        return False
    for block in re.split(r"(?m)^\s*Package:\s+", output)[1:]:
        if block.splitlines()[0].strip() != package:
            continue
        return (re.search(r"(?m)^\s*Domains:\s+" + re.escape(RETURN_HOST) + r"\s*$", block) is not None and
                re.search(r"(?m)^\s*Status:\s+always(?:\s|:|$)", block) is not None)
    return False


def phase(adb):
    return adb.shell("cat", APP_PHASE, check=False)


def start_probe(adb, tls=False):
    adb.run("shell", "am", "force-stop", PACKAGE)
    adb.run("shell", "rm", "-f", APP_PHASE)
    args = ("--es", "mode", "tls") if tls else ()
    adb.run("shell", "am", "start", "-W", "-n", ACTIVITY, *args)


def assert_tls_rejection(adb):
    start_probe(adb, tls=True)
    marked = wait_for(lambda: phase(adb), bool, 12, "tls_probe_result_missing")
    require(marked == "tls_rejected", "default_tls_rejection_not_observed")


def window_nodes(adb, marker):
    dumped = adb.run("shell", "uiautomator", "dump", marker, check=False, timeout=12)
    if dumped.returncode != 0 or marker not in dumped.stdout:
        return ()
    raw = read_remote(adb, marker)
    require(len(raw) <= 256 * 1024, "ui_probe_report_too_large")
    try:
        hierarchy = ET.fromstring(raw)
    except ET.ParseError:
        return ()  # A window transition can leave an incomplete sample.
    return tuple(hierarchy.iter("node")) if hierarchy.tag == "hierarchy" else ()


def bounds(node):
    shape = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    return tuple(map(int, shape.groups())) if shape else None


def click_text(adb, marker, label, scroll=False):
    swipes = 0

    def locate():
        nonlocal swipes
        nodes = window_nodes(adb, marker)
        for node in nodes:
            if label in (node.get("text"), node.get("content-desc")) and node.get("enabled") == "true":
                area = bounds(node)
                if area and area[2] > area[0] and area[3] > area[1]:
                    return area
        if scroll and swipes < 2:
            for node in nodes:
                if node.get("package") != MAIN_PACKAGE or node.get("scrollable") != "true":
                    continue
                area = bounds(node)
                if area and area[2] > area[0] and area[3] - area[1] > 160:
                    x1, y1, x2, y2 = area
                    x = str((x1 + x2) // 2)
                    adb.run("shell", "input", "swipe", x, str(y2 - 40), x, str(y1 + 60), "350")
                    swipes += 1
                    break
        return None
    x1, y1, x2, y2 = wait_for(locate, bool, 18, "expected_fixture_control_missing")
    require(x2 > x1 and y2 > y1, "fixture_control_has_no_area")
    adb.run("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def copy_hosts(adb, path, staged):
    adb.run("push", str(path), staged)
    require(read_remote(adb, staged) == path.read_bytes(), "hosts_staging_bytes_differ")
    adb.run("shell", "cat " + staged + " > " + HOSTS)
    require(read_remote(adb, HOSTS) == path.read_bytes(), "hosts_update_bytes_differ")


@contextmanager
def foreground_server(work, report, go_binary, mode):
    folder = work / "foreground"
    folder.mkdir(mode=0o700)
    example = ROOT / "target/debug/examples/manual_sync_fixture"

    def probe(command, *args):
        raw = subprocess.run([str(example), command, str(folder), *map(str, args)],
                             text=True, capture_output=True, timeout=15, check=False)
        require(raw.returncode == 0, "foreground_shared_client_failed")
        rows = [json.loads(line) for line in raw.stdout.splitlines()]
        require(rows and all(row["status"] == "PASS" for row in rows), "foreground_shared_report_invalid")
        return rows

    oidc = None
    if mode in AUTOMATIC_MODES:
        signing_key, jwks = folder / "issuer.pem", folder / "jwks.json"
        command("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", signing_key)
        signing_key.chmod(0o600)
        public_config = """
const {createPublicKey}=require('node:crypto');
const {readFileSync,writeFileSync}=require('node:fs');
const publicKey=createPublicKey(readFileSync(process.argv[1])).export({format:'jwk'});
writeFileSync(process.argv[2], JSON.stringify({keys:[{...publicKey,kid:'a3-fixture',alg:'RS256',use:'sig'}]}), {flag:'wx',mode:0o600});
"""
        command("node", "-e", public_config, signing_key, jwks)
        issuer_url = f"https://{LOGIN_HOST}"
        oidc = (issuer_url, issuer_url + "/a3-device-enrollment", "zapime-android-synthetic", "a3-synthetic-user", jwks)
    fixture = Fixture(folder, go_binary, os.environ.get("ZAPIME_TEST_DATABASE_URL", ""), oidc=oidc)
    with fixture:
        tls = ssl.create_default_context(cafile=str(fixture.ca_file))

        def api(method, path, token=None, body=None, raw=None, decode=True):
            return fixture_https(fixture.origin, tls, method, path, token, body, raw, decode=decode)

        status, devices = api("GET", fixture.bootstrap_path)
        require(status == 200 and set(devices) == {"owner", "member"}, "foreground_go_bootstrap_failed")
        owner, member = devices["owner"], devices["member"]
        require(owner["account_id"] == member["account_id"] and owner["device_id"] != member["device_id"],
                "foreground_devices_not_independent")
        key_file = folder / "registered.json"
        if mode != "automatic":
            require(probe("init-foreground", owner["account_id"])[-1]["pending"] == 0, "foreground_receiver_has_pending")
            descriptor = json.loads((folder / "descriptor.json").read_bytes())
            status, key = api("PUT", "/v1/key-descriptor", owner["token"], descriptor, decode=False)
            require(status == 200 and json.loads(key)["descriptor"] == descriptor, "foreground_key_not_registered")
            key_file.write_bytes(key)
        bridge = {"port": fixture.loopback_backend_port}
        bootstrap = None
        if mode in AUTOMATIC_MODES:
            bridge.update(account=owner["account_id"], signingKeyPath=str(signing_key))
            status, original_key = api("GET", "/v1/key-descriptor", member["token"])
            require(status == 200 and original_key["state"] == ("empty" if mode == "automatic" else "registered"),
                    "automatic_fixture_initial_key_state")

            def verify_automatic_service():
                status, devices = api("GET", "/v1/devices", owner["token"])
                require(status == 200 and len(devices["devices"]) == 3, "automatic_fixture_device_count")
                new = [item for item in devices["devices"] if item["id"] not in (owner["device_id"], member["device_id"])]
                require(len(new) == 1 and new[0]["role"] == "member" and new[0]["revoked_at"] is None,
                        "automatic_fixture_expected_member")
                status, key = api("GET", "/v1/key-descriptor", member["token"])
                require(status == 200 and key["state"] == "registered", "automatic_fixture_key_not_registered")
                if mode == "automatic":
                    require(key["registered_by"] == new[0]["id"], "automatic_fixture_not_initialized_by_android")
                else:
                    require(key == original_key, "automatic_fixture_replaced_original_key")
                status, page = api("GET", "/v1/changes?after=0", member["token"])
                require(status == 200 and page["head"] == page["next_cursor"] == 0 and page["changes"] == [],
                        "automatic_fixture_unexpected_data_changes")
        else:
            bootstrap = {"device": owner, "material": base64.b64encode((folder / "material.bin").read_bytes()).decode("ascii")}
            bridge["bootstrap"] = bootstrap
        if mode == "archive":
            require(probe("export-archive-origin-fixture")[-1]["pending"] == 1, "archive_origin_source_not_pending")
            bootstrap["archive"] = (folder / "records-only.json").read_bytes().decode("utf-8")
            packet = folder / "archive-source-upload.json"
            probe("stage-automatic", "b", key_file, packet)
            status, receipt = api("POST", "/v1/changes", member["token"], raw=packet.read_bytes(), decode=False)
            require(status == 200 and json.loads(receipt)["revision"] == 1, "archive_origin_service_revision_missing")
            receipt_file = folder / "archive-source-receipt.json"
            receipt_file.write_bytes(receipt)
            results = probe("ack-automatic", "b", key_file, member["device_id"], packet, receipt_file)
            require(results[0]["revision"] == 1 and results[-1]["pending"] == 0, "archive_origin_source_not_acknowledged")
            status, original_page = api("GET", "/v1/changes?after=0", member["token"], decode=False)
            page = json.loads(original_page)
            require(status == 200 and page["head"] == page["next_cursor"] == 1 and len(page["changes"]) == 1,
                    "archive_origin_source_page_missing")

            def verify_archive_server():
                status, page = api("GET", "/v1/changes?after=0", member["token"], decode=False)
                require(status == 200 and page == original_page, "archive_origin_service_history_changed")
                status, terminal = api("GET", "/v1/changes?after=1", member["token"])
                require(status == 200 and terminal["head"] == terminal["next_cursor"] == 1 and terminal["changes"] == [],
                        "archive_origin_unexpected_service_change")

        config = folder / "bridge.json"
        with os.fdopen(os.open(config, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w") as output:
            json.dump(bridge, output)

        def verify_receiver(revision, *, read_only=False):
            after = revision if read_only else revision - 1
            status, page = api("GET", f"/v1/changes?after={after}", member["token"], decode=False)
            decoded = json.loads(page)
            require(status == 200 and decoded["head"] == decoded["next_cursor"] == revision
                    and len(decoded["changes"]) == (0 if read_only else 1), "foreground_real_server_revision_mismatch")
            if read_only:
                return
            page_file = folder / f"receiver-page-{revision}.json"
            page_file.write_bytes(page)
            results = probe("apply", "b", key_file, page_file)
            require(results[0]["applied"] == 1 and results[0]["echoed"] == 0, "foreground_receiver_did_not_apply")
            final = probe("verify-foreground-receiver", revision)[-1]
            require(final["records"] == final["cursor"] == revision and final["pending"] == 0, "foreground_receiver_state")
            receiver_checks = {1: "independent_receiver", 2: "recovery_receiver", 3: "default_network_receiver",
                               4: "interrupted_upload_receiver"}
            report[receiver_checks[revision]] = "PASS"

        def publish_member_edit():
            require(probe("edit-foreground-member")[-1]["pending"] == 1, "conflict_member_edit_missing")
            packet = folder / "member-conflict.json"
            require(probe("stage-automatic", "b", key_file, packet)[-1]["pending"] == 1, "conflict_member_stage_failed")
            raw = packet.read_bytes()
            require(json.loads(raw)["base_revision"] == 2, "conflict_member_used_wrong_base")
            status, receipt = api("POST", "/v1/changes", member["token"], raw=raw, decode=False)
            require(status == 200 and json.loads(receipt)["revision"] == 5, "conflict_member_server_receipt_missing")
            receipt_file = folder / "member-conflict-receipt.json"
            receipt_file.write_bytes(receipt)
            results = probe("ack-automatic", "b", key_file, member["device_id"], packet, receipt_file)
            require(results[0]["revision"] == 5 and results[-1]["pending"] == 0
                    and results[-1]["cursor"] == 4, "conflict_member_ack_not_persisted")

        def verify_queue_receiver(revision):
            after = {16: 0, 18: 16}[revision]
            status, page = api("GET", f"/v1/changes?after={after}", member["token"], decode=False)
            decoded = json.loads(page)
            require(status == 200 and decoded["head"] == decoded["next_cursor"] == revision
                    and len(decoded["changes"]) == revision - after, "queue_window_server_page_mismatch")
            page_file = folder / f"queue-page-{revision}.json"
            page_file.write_bytes(page)
            results = probe("apply", "b", key_file, page_file)
            require(results[0]["applied"] == revision - after and results[0]["echoed"] == 0,
                    "queue_window_receiver_did_not_apply")
            probe("verify-queue-window-receiver", revision)

        def replay_first_upload():
            raw = (folder / "bridge.json.first-upload").read_bytes()
            status, receipt = api("POST", "/v1/changes", owner["token"], raw=raw, decode=False)
            require(status == 200 and json.loads(receipt)["revision"] == 1, "queue_window_replay_receipt_mismatch")
            status, page = api("GET", "/v1/changes?after=18", member["token"])
            require(status == 200 and page["head"] == page["next_cursor"] == 18 and page["changes"] == [],
                    "queue_window_replay_changed_remote_history")
            probe("verify-queue-window-receiver", 18)

        if mode in AUTOMATIC_MODES:
            yield config, verify_automatic_service, None
        elif mode == "archive":
            yield config, verify_archive_server, None
        elif mode == "queue":
            status, page = api("GET", "/v1/changes?after=0", member["token"])
            require(status == 200 and page["head"] == page["next_cursor"] == 0 and page["changes"] == [],
                    "queue_window_remote_not_empty")
            yield config, verify_queue_receiver, replay_first_upload
        else:
            yield config, verify_receiver, publish_member_edit
    summary = fixture.safe_summary()
    expected_revision = {"archive": 1, "queue": 18, "foreground": 5}.get(mode, 0)
    require(summary["cleanup_completed"] and summary["service_revision"] == expected_revision
            and summary["owner_session_valid"] and summary["member_session_valid"], "foreground_service_cleanup_or_final_state")
    report["foreground_service_cleanup"] = True


def default_network_round(report, adb, identity, status, verify_receiver):
    report["stage"] = "default_network_baseline"
    report["default_network_cycle"] = "FAIL"
    baseline = report["default_network_capability"]
    require(baseline["network_default"] == "wifi" and set(baseline["network_available"].split(",")) == {"wifi", "cellular"}
            and baseline["svc_wifi_present"] and baseline["svc_data_present"], "default_network_controls_unavailable")
    guard(adb, identity, root=True)
    settings = {"data": "mobile_data", "wifi": "wifi_on"}

    def original_settings():
        return {name: adb.shell("settings", "get", "global", option, timeout=10) for name, option in settings.items()}

    original = original_settings()
    require(original == {"data": "1", "wifi": "1"}, "default_network_baseline_not_restorable")
    routes = adb.run("reverse", "--list").stdout.splitlines()
    marker = f"/data/user/0/{MAIN_PACKAGE}/cache/a3-default-network.phase"
    adb.run("shell", "test", "!", "-e", marker)
    command = adb.command + ["shell", "am", "instrument", "-w", "-e", "stage", "foreground-default-network",
                             MAIN_PACKAGE + "/.EnrollmentVaultProbe"]
    changed = []
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) as process:
        def phase(expected):
            value = adb.shell("cat", marker, check=False, timeout=10)
            require(value in ("", "ready", "pending", "acknowledged"), "default_network_phase_invalid")
            require(value == expected or process.poll() is None, "default_network_instrumentation_ended_early")
            return value == expected

        def restore_controls():
            restored = True
            for service in reversed(changed):
                try:
                    guard(adb, identity, root=True)
                    adb.run("shell", "svc", service, "enable", timeout=10)
                except Rejected:
                    restored = False
            try:
                guard(adb, identity, root=True)
                wait_for(original_settings, lambda observed: observed == original, 10, "default_network_settings_not_restored")
            except Rejected:
                restored = False
            report["default_network_settings_restored"] = restored

        try:
            try:
                wait_for(lambda: phase("ready"), bool, 42, "default_network_activity_not_ready")
                process_before = adb.shell("pidof", MAIN_PACKAGE, timeout=10)
                require(re.fullmatch(r"[1-9]\d*", process_before), "default_network_process_missing")
                before = status()["dataRequests"]
                report["stage"] = "default_network_loss"
                for service in settings:
                    guard(adb, identity, root=True)
                    changed.append(service)
                    adb.run("shell", "svc", service, "disable", timeout=10)
                wait_for(lambda: phase("pending"), bool, 42, "default_network_loss_not_observed")
                wait_for(original_settings, lambda observed: observed == {"data": "0", "wifi": "0"},
                         10, "default_network_disable_not_confirmed")
                require(adb.shell("pidof", MAIN_PACKAGE, timeout=10) == process_before
                        and status()["uploads"] == 2 and status()["dataRequests"] == before,
                        "default_network_offline_state_changed")
                verify_receiver(2, read_only=True)
                require(adb.run("reverse", "--list").stdout.splitlines() == routes, "default_network_reverse_changed")
                report["default_network_loss"] = "PASS"
            finally:
                if changed:
                    restore_controls()
            require(report.get("default_network_settings_restored"), "default_network_settings_not_restored")
            report["stage"] = "default_network_return"
            wait_for(lambda: phase("acknowledged"), bool, 45, "default_network_return_not_observed")
            output, _ = process.communicate(timeout=12)
            require(process.returncode == 0 and "INSTRUMENTATION_RESULT: foreground-default-network=PASS" in output.splitlines()
                    and "INSTRUMENTATION_CODE: -1" in output.splitlines(), "default_network_observer_failed")
            require(status()["uploads"] == 3 and not status()["failure"], "default_network_recovery_upload_count")
            require(adb.run("reverse", "--list").stdout.splitlines() == routes, "default_network_reverse_changed")
            verify_receiver(3)
            report["default_network_cycle"] = "PASS"
        finally:
            failed = sys.exc_info()[0] is not None
            try:
                if process.poll() is None:
                    process.kill()
                    process.communicate(timeout=8)
                guard(adb, identity, root=True)
                for file in (marker, marker + ".new", marker + ".bak"):
                    adb.run("shell", "rm", "-f", file)
                report["default_network_observer_cleanup"] = True
            except (Rejected, OSError, subprocess.TimeoutExpired):
                report["default_network_observer_cleanup"] = False
                if not failed:
                    raise Rejected("default_network_observer_cleanup_failed") from None


def foreground_instrument(adb, stage):
    output = adb.run("shell", "am", "instrument", "-w", "-e", "stage", stage,
                     MAIN_PACKAGE + "/.EnrollmentVaultProbe", timeout=65).stdout.splitlines()
    require(f"INSTRUMENTATION_RESULT: {stage}=PASS" in output and "INSTRUMENTATION_CODE: -1" in output,
            stage + "_failed")
    return output


def private_digest(output, field):
    digests = [match.group(1) for line in output
               if (match := re.fullmatch(rf"INSTRUMENTATION_RESULT: {field}=([0-9a-f]{{64}})", line))]
    require(len(digests) == 1, "foreground_private_digest_missing")
    return digests[0]


def open_sync_account(adb, marker):
    adb.run("shell", "am", "start", "-W", "-n", MAIN_PACKAGE + "/.MainActivity")
    click_text(adb, marker, "账号与同步", scroll=True)


def tap_sync_control(adb, marker, select, reason, before_tap=None):
    def control():
        matches = [bounds(node) for node in window_nodes(adb, marker)
                   if node.get("package") == MAIN_PACKAGE and node.get("enabled") == "true"
                   and node.get("clickable") == "true" and select(node)]
        if len(matches) == 1 and matches[0]:
            x1, y1, x2, y2 = matches[0]
            if x2 > x1 and y2 > y1:
                return x1, y1, x2, y2
        return None
    x1, y1, x2, y2 = wait_for(control, bool, 25, reason)
    if before_tap:
        before_tap()
    adb.run("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def tap_sync_switch(adb, marker, checked, before_tap=None):
    target = "true" if checked else "false"
    tap_sync_control(adb, marker, lambda node: node.get("class") == "android.widget.Switch"
                     and node.get("checkable") == "true" and node.get("checked") == target,
                     "foreground_sync_switch_missing", before_tap)


def confirm_sync_enable(adb, marker):
    tap_sync_control(adb, marker, lambda node: node.get("resource-id") == "android:id/button1"
                     and node.get("text") == "开启加密同步", "foreground_confirmation_button_missing")


def foreground_round(report, adb, identity, marker, status, verify_receiver, publish_member_edit, without_tls):
    def instrument(stage):
        return foreground_instrument(adb, stage)

    def network_capability():
        report["stage"] = "read_default_network_capability"
        values = {}
        kinds = "wifi|cellular|ethernet|vpn|other"
        patterns = {"network_callback": r"matches_default|no_event|changed_during_read",
                    "network_default": rf"none|unknown|(?:{kinds})(?:,(?:{kinds}))*",
                    "network_available": rf"none|(?:{kinds})(?:,(?:{kinds}))*",
                    "network_validated": r"true|false"}
        for line in instrument("foreground-network-capability"):
            match = re.fullmatch(r"INSTRUMENTATION_RESULT: (network_[a-z]+)=(.*)", line)
            if match:
                key, value = match.groups()
                require(key in patterns and key not in values and re.fullmatch(patterns[key], value),
                        "foreground_network_capability_invalid")
                values[key] = value
        require(values.keys() == patterns.keys(), "foreground_network_capability_incomplete")
        services = adb.run("shell", "svc", "help", check=False, timeout=10)
        wifi = adb.shell("settings", "get", "global", "wifi_on", timeout=10)
        mobile = adb.shell("settings", "get", "global", "mobile_data", timeout=10)
        commands = services.stdout + services.stderr
        values["svc_wifi_present"] = re.search(r"(?m)^\s+wifi\s+", commands) is not None
        values["svc_data_present"] = re.search(r"(?m)^\s+data\s+", commands) is not None
        values["wifi_setting"] = wifi if wifi in ("0", "1", "2") else "unavailable"
        values["mobile_data_setting"] = mobile if mobile in ("0", "1") else "unavailable"
        report["default_network_capability"] = values

    def open_account():
        open_sync_account(adb, marker)

    def tap_control(select, reason, before_tap=None):
        tap_sync_control(adb, marker, select, reason, before_tap)

    def tap_switch(checked, before_tap=None):
        tap_sync_switch(adb, marker, checked, before_tap)

    def confirm_enable():
        confirm_sync_enable(adb, marker)

    def frozen_digest(stage):
        return private_digest(instrument(stage), "frozen_sha256")

    network_capability()
    instrument("foreground-seed")
    report["foreground_seed"] = "PASS"
    require(status()["dataRequests"] == 0, "foreground_unsolicited_data_before_display")
    report["stage"] = "open_foreground_account_page"
    open_account()
    report["stage"] = "tap_unchecked_sync_switch"
    tap_switch(False)
    require(status()["dataRequests"] == 0, "foreground_unsolicited_data_before_confirmation")
    report["stage"] = "confirm_foreground_sync_scope"
    confirm_enable()
    report["foreground_trigger"] = "product_account_screen_confirmation"

    def shows(label):
        return any(node.get("package") == MAIN_PACKAGE and label in node.get("text", "")
                   for node in window_nodes(adb, marker))

    def displayed_receipt(revision):
        require(not status()["failure"], "foreground_tls_handler_failed")
        receipts = (f"服务器已确认本次写入，修订号 {revision}", f"服务器最近确认写入，修订号 {revision}")
        return any(node.get("package") == MAIN_PACKAGE and "本轮同步已检查" in node.get("text", "")
                   and any(receipt in node.get("text", "") for receipt in receipts) for node in window_nodes(adb, marker))

    report["stage"] = "foreground_round_and_durable_receipt"
    wait_for(lambda: displayed_receipt(1), bool, 35, "foreground_ui_receipt_missing")
    report["foreground_ui_receipt"] = "PASS"
    require(status().get("uploads") == 1 and status()["dataRequests"] >= 2, "foreground_request_counts_invalid")
    report["foreground_uploads"] = 1
    instrument("foreground-verify")
    report["foreground_sqlite_ack"] = "PASS"
    verify_receiver(1)

    before = status()["dataRequests"]
    report["stage"] = "reopen_with_persisted_consent"
    open_account()
    wait_for(lambda: shows("本轮同步已检查"), bool, 35, "foreground_reopen_round_missing")
    require(status()["dataRequests"] > before and status()["uploads"] == 1, "foreground_reopen_replayed_upload")
    report["foreground_reopen"] = "PASS"

    report["stage"] = "foreground_unreachable_endpoint"
    adb.run("shell", "input", "keyevent", "KEYCODE_HOME")
    before = status()["dataRequests"]
    with without_tls():
        instrument("foreground-queue-recovery")
        open_account()
        wait_for(lambda: shows("无法连接同步服务器。待发操作保留。"), bool, 35, "foreground_unreachable_error_missing")
        require(status()["dataRequests"] == before and status()["uploads"] == 1, "foreground_unreachable_write_observed")
        verify_receiver(1, read_only=True)
        adb.run("shell", "input", "keyevent", "KEYCODE_HOME")
        instrument("foreground-recovery-pending")
    report["foreground_unreachable_pending"] = "PASS"

    report["stage"] = "foreground_resume_after_route_restore"
    open_account()
    wait_for(lambda: displayed_receipt(2), bool, 35, "foreground_recovery_receipt_missing")
    require(status()["uploads"] == 2, "foreground_recovery_upload_count")
    adb.run("shell", "input", "keyevent", "KEYCODE_BACK")
    click_text(adb, marker, "账号与同步", scroll=True)
    wait_for(lambda: shows("服务器最近确认写入，修订号 2"), bool, 35, "foreground_recent_receipt_lost_after_empty_round")
    instrument("foreground-recovery-ack")
    verify_receiver(2)
    report["foreground_route_recovery"] = "PASS"
    report["foreground_uploads"] = 2

    open_account()
    wait_for(lambda: shows("本轮同步已检查"), bool, 35, "foreground_recovery_reopen_round_missing")
    require(status()["uploads"] == 2, "foreground_recovery_reopen_replayed_upload")

    default_network_round(report, adb, identity, status, verify_receiver)
    report["foreground_uploads"] = 3

    report["stage"] = "queue_upload_while_sync_off"
    open_account()
    wait_for(lambda: shows("本轮同步已检查"), bool, 35, "interrupted_upload_baseline_missing")
    tap_switch(True)
    wait_for(lambda: shows("同步已关闭"), bool, 18, "interrupted_upload_setup_not_disabled")
    before = status()["dataRequests"]
    instrument("foreground-queue-interrupted")
    open_account()
    wait_for(lambda: shows("同步已关闭"), bool, 18, "interrupted_upload_disabled_state_missing")
    require(status()["uploads"] == 3 and status()["dataRequests"] == before, "disabled_interrupted_upload_transmitted")
    verify_receiver(3, read_only=True)

    report["stage"] = "close_while_server_receipt_is_held"
    tap_switch(False)
    confirm_enable()
    wait_for(lambda: status().get("heldAckLive") is True, bool, 25, "interrupted_upload_not_committed_by_server")
    tapped_at = 0

    def record_close_tap():
        nonlocal tapped_at
        require(status().get("heldAckLive") is True, "interrupted_ack_not_held_at_tap")
        tapped_at = time.time_ns() // 1_000_000

    tap_switch(True, record_close_tap)
    wait_for(lambda: status().get("heldAckLive") is False, bool, 12, "interrupted_ack_connection_not_closed")
    observed = status()
    require(observed["heldAckClosedAt"] >= tapped_at > 0 and not observed["heldAckDelivered"]
            and 0 <= observed["heldAckDurationMs"] < 8500 and observed["uploads"] == 4,
            "interrupted_ack_not_cancelled_before_deadline")
    wait_for(lambda: shows("同步已关闭"), bool, 18, "interrupted_upload_close_not_persisted")
    expected = observed["heldAckDigest"]
    require(re.fullmatch(r"[0-9a-f]{64}", expected) and frozen_digest("foreground-closed-upload") == expected,
            "interrupted_upload_pending_wire_changed")
    verify_receiver(4, read_only=True)
    report["close_during_upload"] = "PASS"

    report["stage"] = "reconcile_interrupted_upload_by_pull"
    before = status()["dataRequests"]
    open_account()
    wait_for(lambda: shows("同步已关闭"), bool, 18, "interrupted_upload_reopen_off_missing")
    require(status()["dataRequests"] == before and status()["uploads"] == 4, "interrupted_upload_replayed_while_off")
    tap_switch(False)
    confirm_enable()
    wait_for(lambda: shows("本轮同步已检查"), bool, 35, "interrupted_upload_pull_confirmation_missing")
    wait_for(lambda: shows("服务器最近确认写入，修订号 4"), bool, 25, "interrupted_upload_pull_receipt_missing")
    require(frozen_digest("foreground-reconciled-upload") == expected, "interrupted_upload_reconciled_wire_changed")
    verify_receiver(4)
    require(status()["uploads"] == 4, "interrupted_upload_extra_post")
    report["interrupted_upload_reconciled"] = "PASS"
    report["foreground_uploads"] = 4

    report["stage"] = "foreground_concurrent_record_edits"
    instrument("foreground-queue-conflict")
    publish_member_edit()
    open_account()
    conflict_message = "远端资料未通过核对，请检查密钥或冲突记录。本机资料已保留"
    wait_for(lambda: shows(conflict_message), bool, 35, "foreground_conflict_message_missing")
    require(status()["uploads"] == 4 and not status()["failure"], "foreground_conflict_uploaded_local_version")
    instrument("foreground-conflict-pending")
    verify_receiver(5, read_only=True)
    report["foreground_conflict_preserved"] = "PASS"

    report["stage"] = "sign_out_and_reopen_without_transfer"
    open_account()
    wait_for(lambda: shows(conflict_message), bool, 35, "foreground_conflict_not_repeated_after_reopen")
    click_text(adb, marker, "退出登录", scroll=True)
    wait_for(lambda: shows("仅登录"), bool, 18, "foreground_signout_missing")
    old_connection = private_digest(instrument("foreground-signed-out"), "prior_connection_sha256")
    before = status()["dataRequests"]
    open_account()
    wait_for(lambda: shows("仅登录"), bool, 18, "foreground_signout_not_reloaded")
    require(status()["dataRequests"] == before and status()["uploads"] == 4 and not status()["failure"],
            "foreground_signed_out_sent_data")
    report["foreground_signout"] = "PASS"

    def sync_requests():
        snapshot = status()
        return tuple(snapshot[key] for key in ("dataRequests", "uploads", "foregroundDeviceSessions",
                                                 "foregroundDescriptorRequests"))

    def new_account_closed():
        nodes = [node for node in window_nodes(adb, marker) if node.get("package") == MAIN_PACKAGE]
        if not any(all(part in node.get("text", "") for part in ("已登录", MAIN_ACCOUNT, "此设备尚未获授权", "同步已关闭"))
                   for node in nodes):
            return False
        require(not any(node.get("class") == "android.widget.Switch" or "修订号" in node.get("text", "")
                        or conflict_message in node.get("text", "") for node in nodes), "foreground_account_reused_old_ui")
        return True

    report["stage"] = "switch_to_distinct_account_through_browser"
    before_switch = sync_requests()
    click_text(adb, marker, "登录并开启同步", scroll=True)
    wait_for(lambda: adb.shell("dumpsys", "activity", "activities"),
             lambda raw: any("mResumedActivity" in line and BROWSER in line for line in raw.splitlines()),
             12, "foreground_account_switch_no_stock_browser")
    click_text(adb, marker, "Use synthetic test account")
    click_text(adb, marker, "Approve synthetic access")
    wait_for(new_account_closed, bool, 30, "foreground_distinct_account_not_shown")
    counts = status()
    require(counts["accountLookups"] == counts["goProofs"] == counts["tokenPosts"] == 1
            and counts["distinctAccountInForegroundDatabase"] is True and counts["callbackOpenedInBrowser"] == 0,
            "foreground_distinct_account_not_verified_by_go")
    require(sync_requests() == before_switch, "foreground_distinct_account_sent_sync_requests")

    report["stage"] = "verify_switched_account_preserves_original_workspace"
    result = instrument("foreground-switched-account")
    require(private_digest(result, "frozen_sha256") == expected
            and private_digest(result, "prior_connection_sha256") == old_connection, "foreground_account_changed_original_vault_or_wire")
    open_account()
    wait_for(new_account_closed, bool, 25, "foreground_distinct_account_not_reloaded")
    require(sync_requests() == before_switch and not status()["failure"], "foreground_distinct_account_restart_attempted_transfer")
    verify_receiver(5, read_only=True)
    report["foreground_account_switch"] = "PASS"


def queue_window_round(report, adb, marker, status, verify_receiver, replay_first_upload):
    def wait_for_receipt(revision, stage_message):
        def displayed():
            counts = status()
            require(not counts["failure"] and counts.get("uploads", 0) <= revision, "queue_window_upload_count_exceeded")
            receipts = (f"服务器已确认本次写入，修订号 {revision}", f"服务器最近确认写入，修订号 {revision}")
            return counts.get("uploads") == revision and any(
                node.get("package") == MAIN_PACKAGE and stage_message in node.get("text", "")
                and any(receipt in node.get("text", "") for receipt in receipts) for node in window_nodes(adb, marker))
        wait_for(displayed, bool, 50, "queue_window_ui_receipt_missing")

    report["stage"] = "queue_window_seed_unfrozen"
    foreground_instrument(adb, "foreground-queue-window-seed")
    report["initial_unfrozen_operations"] = 18
    open_sync_account(adb, marker)
    tap_sync_switch(adb, marker, False)
    require(status()["dataRequests"] == 0, "queue_window_unsolicited_transfer")
    confirm_sync_enable(adb, marker)

    report["stage"] = "queue_window_first_round"
    wait_for_receipt(16, "仍有待处理的同步任务")
    foreground_instrument(adb, "foreground-queue-window-first")
    require(not status()["failure"] and status().get("uploads") == 16, "queue_window_first_oversent")
    verify_receiver(16)
    report["first_round"] = {"posts": 16, "android_cursor": 16, "unfrozen_pending": 2, "receiver_records": 16}

    report["stage"] = "queue_window_reopen_foreground"
    open_sync_account(adb, marker)
    wait_for_receipt(18, "本轮同步已检查")
    foreground_instrument(adb, "foreground-queue-window-final")
    verify_receiver(18)
    report["second_round"] = {"posts": 2, "android_cursor": 18, "unfrozen_pending": 0,
                              "receiver_records": 17, "receiver_tombstones": 1}

    report["stage"] = "queue_window_replay_earliest_after_deletion"
    replay_first_upload()
    require(not status()["failure"] and status().get("uploads") == 18, "queue_window_replay_changed_android_count")
    report["original_packet_replay"] = {"receipt_revision": 1, "remote_head": 18, "new_changes": 0}


def archive_origin_round(report, adb, marker, status, verify_server):
    def wait_for_screen(label, previous_reads):
        def displayed():
            counts = status()
            require(not counts["failure"] and counts.get("uploads", 0) == 0, "archive_origin_unexpected_upload")
            return counts["dataRequests"] > previous_reads and any(
                node.get("package") == MAIN_PACKAGE and label in node.get("text", "") for node in window_nodes(adb, marker))
        wait_for(displayed, bool, 35, "archive_origin_ui_state_missing")

    report["stage"] = "archive_origin_import_unbound"
    imported = private_digest(foreground_instrument(adb, "foreground-archive-import"), "imported_sha256")
    open_sync_account(adb, marker)
    tap_sync_switch(adb, marker, False)
    require(status()["dataRequests"] == 0, "archive_origin_unsolicited_transfer")
    confirm_sync_enable(adb, marker)

    report["stage"] = "archive_origin_actual_matching_pull"
    wait_for_screen("本轮同步已检查", 0)
    after_pull = private_digest(foreground_instrument(adb, "foreground-archive-edit"), "imported_sha256")
    require(after_pull == imported, "archive_origin_matching_pull_replaced_import")
    report["matching_pull"] = {"cursor": 1, "server_revision": 1, "imported_record_unchanged": True,
                               "imported_record_marked_as_pulled": False}

    report["stage"] = "archive_origin_unverified_head_blocks_staging"
    previous_reads = status()["dataRequests"]
    open_sync_account(adb, marker)
    wait_for_screen("有待发资料的来源需要核对", previous_reads)
    foreground_instrument(adb, "foreground-archive-verify")
    verify_server()
    require(not status()["failure"] and status().get("uploads", 0) == 0, "archive_origin_failed_upload_guard")
    report["blocked_queue"] = {"android_posts": 0, "unfrozen_pending": 2, "verified_operations_behind_head": 1,
                               "cursor": 1, "remote_head": 1, "remote_history_unchanged": True}


def automatic_login_round(report, adb, marker, status, verify_service, mode):
    ready = mode == "automatic"
    report["stage"] = "automatic_browser_sign_in"
    open_sync_account(adb, marker)
    click_text(adb, marker, "登录并开启同步")
    wait_for(lambda: adb.shell("dumpsys", "activity", "activities"),
             lambda raw: any("mResumedActivity" in line and BROWSER in line for line in raw.splitlines()),
             12, "stock_external_browser_not_foreground")
    report["browser_launched"] = "PASS"
    click_text(adb, marker, "Use synthetic test account")
    click_text(adb, marker, "Approve synthetic access")
    report["stage"] = "automatic_login_expected_outcome"

    def observed():
        counts = status()
        require(not counts["failure"] and counts["callbackOpenedInBrowser"] == 0, "automatic_login_provider_failed")
        label = "本轮同步已检查" if ready else "需要恢复同步密钥"
        return any(node.get("package") == MAIN_PACKAGE and label in node.get("text", "") for node in window_nodes(adb, marker))

    wait_for(observed, bool, 45, "automatic_login_status_missing")
    report["verified_browser_callback"] = "PASS"
    counts = status()
    require(counts["accountLookups"] == counts["tokenPosts"] == counts.get("automaticGrants") == 1,
            "automatic_login_grant_count_mismatch")
    require(counts.get("automaticInitializations", 0) == int(ready) and counts.get("uploads", 0) == 0
            and (counts["dataRequests"] > 0 if ready else counts["dataRequests"] == 0), "automatic_login_unexpected_transfer")
    foreground_instrument(adb, "foreground-automatic-ready" if ready else "foreground-automatic-needs-key")
    verify_service()
    report["automatic_login"] = {"grant": "PASS", "durable_state": "PASS", "first_key_registered": ready,
                                 "recovery_prompt": not ready, "real_data_check": ready, "android_uploads": 0}


def run(report, adb, identity, apk, go_proof, fingerprint, work, main_apk=None, sync_binary=None, specialized=None):
    package = MAIN_PACKAGE if main_apk else PACKAGE
    guard(adb, identity)
    adb.run("root")
    adb.run("wait-for-device")
    guard(adb, identity, root=True)
    require(adb.shell("pm", "path", PACKAGE, check=False) == "", "probe_package_already_present")
    if main_apk:
        require(adb.shell("pm", "path", MAIN_PACKAGE, check=False) == "", "main_fixture_package_already_present")
    require(adb.shell("readlink", "-f", HOSTS) == HOSTS, "unexpected_hosts_path")
    original = read_remote(adb, HOSTS)
    hosts_names = {name for line in original.decode("ascii").splitlines()
                   for name in line.split("#", 1)[0].split()[1:]}
    require(not hosts_names.intersection((LOGIN_HOST, RETURN_HOST)), "test_domain_already_present")
    reverse_before = adb.run("reverse", "--list").stdout.splitlines()
    require(not any("tcp:443" in line.split() for line in reverse_before), "fixture_reverse_port_in_use")
    initial = baseline(adb)
    old = work / "hosts.original"
    new = work / "hosts.fixture"
    old.write_bytes(original)
    new.write_bytes(original.rstrip(b"\n") + f"\n127.0.0.1 {LOGIN_HOST}\n127.0.0.1 {RETURN_HOST}\n".encode())
    staged = "/data/local/tmp/zapime-a3-" + identity + "-hosts"
    marker = "/data/local/tmp/zapime-a3-" + identity + "-ui.xml"
    for path in (staged, marker):
        adb.run("shell", "test", "!", "-e", path)
    leaf, leaf_key, cert = make_certificates(work)
    ca = TemporaryCA(adb, identity, cert, initial, report["certificate"], guard=guard)
    submitted, main_submitted, mapped, reverse_added = False, False, False, False
    with ExitStack() as resources:
        mode = specialized or ("foreground" if sync_binary else "main" if main_apk else "probe")
        if sync_binary:
            bridge, verify_receiver, follow_up = resources.enter_context(foreground_server(work, report, sync_binary, mode))
        else:
            bridge = verify_receiver = follow_up = None
        port, status = resources.enter_context(issuer(work, leaf, leaf_key, fingerprint, go_proof, mode, bridge))
        try:
            guard(adb, identity, root=True)
            adb.run("remount")
            require(read_remote(adb, HOSTS) == original, "hosts_changed_before_install")
            mapped = True
            copy_hosts(adb, new, staged)
            adb.run("reverse", "--no-rebind", "tcp:443", "tcp:" + str(port))
            reverse_added = True
            submitted = True
            adb.run("install", "-t", str(apk))
            assert_tls_rejection(adb)
            report["tls_before"] = "PASS"
            adb.run("uninstall", PACKAGE)
            require(adb.shell("pm", "path", PACKAGE, check=False) == "", "first_fixture_uninstall_failed")
            ca.install()
            main_submitted = main_apk is not None
            report["stage"] = "test_apk_install"
            adb.run("install", "-t", str(main_apk or apk), timeout=60)
            report["stage"] = "system_app_link_verification"
            wait_for(lambda: system_links_confirmed(adb, package) and status()["assetlinks"] > 0, bool,
                     80, "system_app_link_verification_not_observed")
            report["verified_domain"] = "PASS"
            if mode in AUTOMATIC_MODES:
                automatic_login_round(report, adb, marker, status, verify_receiver, mode)
                return
            if mode == "archive":
                archive_origin_round(report, adb, marker, status, verify_receiver)
                return
            if mode == "queue":
                queue_window_round(report, adb, marker, status, verify_receiver, follow_up)
                return
            if bridge:
                @contextmanager
                def without_tls():
                    guard(adb, identity, root=True)
                    expected = ["tcp:443", "tcp:" + str(port)]
                    current = adb.run("reverse", "--list").stdout.splitlines()
                    require(sum(line.split()[-2:] == expected for line in current) == 1, "recovery_route_not_owned")
                    adb.run("reverse", "--remove", expected[0])
                    try:
                        require(adb.run("reverse", "--list").stdout.splitlines() == reverse_before, "recovery_route_not_removed")
                        yield
                    finally:
                        guard(adb, identity, root=True)
                        adb.run("reverse", "--no-rebind", *expected)
                        require(adb.run("reverse", "--list").stdout.splitlines() == current, "recovery_route_not_restored")

                report["stage"] = "foreground_native_seed_and_round"
                foreground_round(report, adb, identity, marker, status, verify_receiver, follow_up, without_tls)
                return
            if main_apk:
                consent = adb.run("shell", "am", "instrument", "-w", "-e", "stage", "consent",
                                  MAIN_PACKAGE + "/.EnrollmentVaultProbe", timeout=35).stdout.splitlines()
                require("INSTRUMENTATION_RESULT: sync_consent_store=PASS" in consent
                        and "INSTRUMENTATION_CODE: -1" in consent, "android_sync_consent_not_verified")
                report["sync_consent_store"] = "PASS"
                adb.run("shell", "am", "start", "-W", "-n", MAIN_PACKAGE + "/.MainActivity")
                click_text(adb, marker, "账号与同步", scroll=True)
                click_text(adb, marker, "仅登录")
            else:
                start_probe(adb)
                click_text(adb, marker, "Only sign in")
            wait_for(lambda: adb.shell("dumpsys", "activity", "activities"),
                     lambda raw: any("mResumedActivity" in line and BROWSER in line for line in raw.splitlines()),
                     12, "stock_external_browser_not_foreground")
            report["browser_launched"] = "PASS"
            click_text(adb, marker, "Use synthetic test account")
            click_text(adb, marker, "Approve synthetic access")
            def browser_outcome():
                counts = status()
                require(not counts["failure"], "oidc_fixture_handler_failed")
                require(counts["callbackOpenedInBrowser"] == 0, "browser_opened_callback_without_dispatch")
                if main_apk:
                    for node in window_nodes(adb, marker):
                        text = node.get("text", "")
                        if node.get("package") == MAIN_PACKAGE and all(part in text for part in
                                ("已登录", MAIN_ACCOUNT, "此设备尚未获授权", "同步已关闭")):
                            return "signed_in_only"
                    return ""
                observed = phase(adb)
                require(observed in ("", "ready", "external_browser_opening", "verified_callback_received", "signed_in_only"),
                        "test_app_callback_or_exchange_failed")
                return observed
            wait_for(browser_outcome, lambda observed: observed == "signed_in_only", 24, "browser_callback_not_observed")
            report["verified_browser_callback"] = "PASS"
            counts = status()
            require(counts["goProofs"] == 1 and counts["dataRequests"] == 0 and counts["unexpected"] == 0,
                    "unexpected_proof_or_data_requests")
            if main_apk:
                require(counts["accountLookups"] == 1, "missing_real_account_lookup")
                report["main_account_lookup"] = "PASS"
                report["main_signed_in_only"] = "PASS"
                vault = adb.run("shell", "am", "instrument", "-w",
                                MAIN_PACKAGE + "/.EnrollmentVaultProbe", timeout=50).stdout.splitlines()
                require("INSTRUMENTATION_RESULT: enrollment_vault=PASS" in vault, "android_enrollment_vault_not_verified")
                report["android_enrollment_vault"] = "PASS"
                final_counts = status()
                require("INSTRUMENTATION_RESULT: enrollment_http=PASS" in vault and "INSTRUMENTATION_CODE: -1" in vault
                        and final_counts["enrollmentFinished"] == 1 and final_counts["dataRequests"] == 0
                        and final_counts["droppedClaimReplies"] == 1 and not final_counts["failure"],
                        "android_device_enrollment_http_not_verified")
                report["device_enrollment"] = "PASS"
                report["main_persisted_sign_in_off"] = "PASS"
            report["oidc_go_token_proof"] = "PASS"
            report["login_data_requests"] = 0
        finally:
            def cleanup(label, action):
                try:
                    guard(adb, identity, root=True)
                    action()
                    report["cleanup"][label] = True
                except (Rejected, OSError, ValueError, subprocess.TimeoutExpired):
                    report["cleanup"][label] = False

            def remove_ca():
                ca.remove()
                if report["tls_before"] == "PASS" and submitted:
                    if main_apk and not adb.shell("pm", "path", PACKAGE, check=False):
                        adb.run("install", "-t", str(apk))
                    assert_tls_rejection(adb)
                    report["tls_after"] = "PASS"

            def restore_hosts():
                observed = read_remote(adb, HOSTS)
                if observed != original:
                    require(mapped and observed == new.read_bytes(), "hosts_cleanup_identity_changed")
                    copy_hosts(adb, old, staged)
                require(read_remote(adb, HOSTS) == original, "hosts_baseline_changed")

            def restore_reverse():
                current = adb.run("reverse", "--list").stdout.splitlines()
                owned = any(line.split()[-2:] == ["tcp:443", "tcp:" + str(port)] for line in current)
                require(not reverse_added or owned, "reverse_mapping_cleanup_identity_changed")
                if owned:
                    adb.run("reverse", "--remove", "tcp:443")
                require(adb.run("reverse", "--list").stdout.splitlines() == reverse_before, "reverse_baseline_changed")

            def remove_probe():
                if main_submitted and adb.shell("pm", "path", MAIN_PACKAGE, check=False):
                    adb.run("uninstall", MAIN_PACKAGE)
                if main_apk:
                    require(adb.shell("pm", "path", MAIN_PACKAGE, check=False) == "", "main_fixture_cleanup_failed")
                if submitted and adb.shell("pm", "path", PACKAGE, check=False):
                    adb.run("uninstall", PACKAGE)
                require(adb.shell("pm", "path", PACKAGE, check=False) == "", "fixture_package_cleanup_failed")
                for owned_path in (staged, marker):
                    adb.run("shell", "rm", "-f", owned_path)
                    adb.run("shell", "test", "!", "-e", owned_path)

            cleanup("certificate_and_tls", remove_ca)
            cleanup("hosts", restore_hosts)
            cleanup("reverse", restore_reverse)
            cleanup("probe_apk_and_staging", remove_probe)
            try:
                private_fields = {"port", "heldAckDigest", "heldAckClosedAt"}
                report["provider"] = {key: count for key, count in status().items() if key not in private_fields}
            except (Rejected, OSError, ValueError):
                report["provider_status_unavailable"] = True


def main():
    modes = {"queue-window": "queue", "archive-origin": "archive", "automatic": "automatic",
             "automatic-recovery": "automatic-recovery"}
    specialized = modes.get(sys.argv[6]) if len(sys.argv) == 7 else None
    foreground = len(sys.argv) == 6 or specialized is not None
    main_mode = len(sys.argv) == 5 or foreground
    output = ROOT / "target/a3-android-oidc" / (f"{sys.argv[6]}-report.json" if specialized else "foreground-report.json" if foreground else "main-report.json" if main_mode else "report.json")
    if specialized in AUTOMATIC_MODES:
        scope = "API28 native test-only APK with real Android browser OIDC, automatic authorization, and PostgreSQL"
    elif specialized == "archive":
        scope = "API28 native test-only APK with seeded sign-in; imported origin remains unverified after matching authenticated HTTPS pull"
    elif specialized == "queue":
        scope = "API28 native test-only APK with seeded sign-in; automatic upload limit and trusted tombstone over real HTTPS"
    elif foreground:
        scope = "API28 native test-only APK with seeded sign-in; real main-app foreground HTTPS and independent shared client"
    else:
        scope = "test-only APK built from main-app Java; real OIDC and PostgreSQL" if main_mode else "disposable API28 test APK and real isolated OIDC"
    report = {"status": "FAIL", "scope": scope,
              "source_sha": os.environ.get("GITHUB_SHA", ""), "tls_before": "NOT RUN", "tls_after": "NOT RUN",
              "verified_domain": "NOT RUN", "browser_launched": "NOT RUN", "verified_browser_callback": "NOT RUN",
              "oidc_go_token_proof": "NOT RUN", "login_data_requests": None, "device_enrollment": "NOT RUN",
              "certificate": {}, "cleanup": {}}
    try:
        require((len(sys.argv) in (4, 5, 6) or specialized) and os.name == "posix", "requires_a3_ci_arguments")
        adb = Adb([str(shutil.which("adb")), "-s", "emulator-5554"])
        identity = ci_identity(adb.command)
        guard(adb, identity)
        apk, apksigner, go_proof = [Path(value).resolve(strict=True) for value in sys.argv[1:4]]
        main_apk = Path(sys.argv[4]).resolve(strict=True) if main_mode else None
        sync_binary = Path(sys.argv[5]).resolve(strict=True) if foreground else None
        report["test_apk_signer"] = obtain_fingerprint(apksigner, main_apk or apk)
        report["test_apk_sha256"] = hashlib.sha256((main_apk or apk).read_bytes()).hexdigest()
        if foreground and not specialized:
            report["foreground_ui_receipt"] = report["foreground_sqlite_ack"] = report["independent_receiver"] = "NOT RUN"
        elif main_mode and not specialized:
            report["main_account_lookup"] = report["main_signed_in_only"] = "NOT RUN"
            report["android_enrollment_vault"] = "NOT RUN"
            report["sync_consent_store"] = "NOT RUN"
            report["main_persisted_sign_in_off"] = "NOT RUN"
        with tempfile.TemporaryDirectory(prefix="a3-private-") as temporary:
            run(report, adb, identity, apk, go_proof, report["test_apk_signer"], Path(temporary), main_apk, sync_binary, specialized)
        require(all(report["cleanup"].values()) and len(report["cleanup"]) == 4 and report["tls_after"] == "PASS",
                "fixture_cleanup_or_after_tls_failed")
        report.pop("stage", None)
        report["status"] = "PASS"
    except Rejected as failure:
        report["failure"] = str(failure)
    except (OSError, ValueError, ET.ParseError, subprocess.TimeoutExpired) as failure:
        report["failure"] = type(failure).__name__
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        result = json.dumps(report, sort_keys=True)
        output.write_text(result + "\n", encoding="utf-8")
        print(result)
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
