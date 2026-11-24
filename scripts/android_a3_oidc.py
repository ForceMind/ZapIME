# SPDX-License-Identifier: GPL-3.0-only
"""Stock platform HTTPS/App Links on the authorized disposable A3 CI emulator."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import xml.etree.ElementTree as ET

from android_sync_https_smoke import Adb, Rejected, TemporaryCA, baseline, ci_identity, require


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "org.zapime.android.a3loginprobe"
ACTIVITY = PACKAGE + "/.ProbeActivity"
APP_PHASE = "/data/user/0/" + PACKAGE + "/files/phase"
MAIN_PACKAGE = "org.zapime.android"
MAIN_ACCOUNT = "98a01050-3185-4eed-bac4-127426c17372"
LOGIN_HOST = "a3-oidc.example.test"
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
def issuer(work, leaf, key, fingerprint, go_proof, mode):
    summary = work / "issuer-status.json"
    program = ROOT / "target/a3-android-oidc/issuer/android-issuer.mjs"
    child = subprocess.Popen(["node", str(program), str(leaf), str(key), fingerprint, str(summary), str(go_proof), mode],
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
    output = adb.shell("dumpsys", "package", "domain-preferred-apps")
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
    if dumped.returncode != 0:
        return ()
    raw = read_remote(adb, marker)
    require(len(raw) <= 256 * 1024, "ui_probe_report_too_large")
    return tuple(ET.fromstring(raw).iter("node"))


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


def run(report, adb, identity, apk, go_proof, fingerprint, work, main_apk=None):
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
    with issuer(work, leaf, leaf_key, fingerprint, go_proof, "main" if main_apk else "probe") as (port, status):
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
            adb.run("install", "-t", str(main_apk or apk))
            wait_for(lambda: system_links_confirmed(adb, package) and status()["assetlinks"] > 0, bool,
                     80, "system_app_link_verification_not_observed")
            report["verified_domain"] = "PASS"
            if main_apk:
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
                require("INSTRUMENTATION_RESULT: enrollment_http=PASS" in vault
                        and final_counts["enrollmentFinished"] == 1 and final_counts["dataRequests"] == 0
                        and final_counts["droppedClaimReplies"] == 1 and not final_counts["failure"],
                        "android_device_enrollment_http_not_verified")
                report["device_enrollment"] = "PASS"
                require("INSTRUMENTATION_RESULT: sync_consent_store=PASS" in vault
                        and "INSTRUMENTATION_CODE: -1" in vault, "android_sync_consent_not_verified")
                report["sync_consent_store"] = "PASS"
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
                report["provider"] = {key: count for key, count in status().items() if key != "port"}
            except (Rejected, OSError, ValueError):
                report["provider_status_unavailable"] = True


def main():
    main_mode = len(sys.argv) == 5
    output = ROOT / "target/a3-android-oidc" / ("main-report.json" if main_mode else "report.json")
    scope = "test-only APK built from main-app Java; real OIDC and PostgreSQL" if main_mode else "disposable API28 test APK and real isolated OIDC"
    report = {"status": "FAIL", "scope": scope,
              "source_sha": os.environ.get("GITHUB_SHA", ""), "tls_before": "NOT RUN", "tls_after": "NOT RUN",
              "verified_domain": "NOT RUN", "browser_launched": "NOT RUN", "verified_browser_callback": "NOT RUN",
              "oidc_go_token_proof": "NOT RUN", "login_data_requests": None, "device_enrollment": "NOT RUN",
              "certificate": {}, "cleanup": {}}
    try:
        require(len(sys.argv) in (4, 5) and os.name == "posix", "requires_a3_ci_arguments")
        adb = Adb([str(shutil.which("adb")), "-s", "emulator-5554"])
        identity = ci_identity(adb.command)
        guard(adb, identity)
        apk, apksigner, go_proof = [Path(value).resolve(strict=True) for value in sys.argv[1:4]]
        main_apk = Path(sys.argv[4]).resolve(strict=True) if main_mode else None
        report["test_apk_signer"] = obtain_fingerprint(apksigner, main_apk or apk)
        report["test_apk_sha256"] = hashlib.sha256((main_apk or apk).read_bytes()).hexdigest()
        if main_mode:
            report["main_account_lookup"] = report["main_signed_in_only"] = "NOT RUN"
            report["android_enrollment_vault"] = "NOT RUN"
            report["sync_consent_store"] = "NOT RUN"
        with tempfile.TemporaryDirectory(prefix="a3-private-") as temporary:
            run(report, adb, identity, apk, go_proof, report["test_apk_signer"], Path(temporary), main_apk)
        require(all(report["cleanup"].values()) and len(report["cleanup"]) == 4 and report["tls_after"] == "PASS",
                "fixture_cleanup_or_after_tls_failed")
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
