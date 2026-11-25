# SPDX-License-Identifier: GPL-3.0-only
"""A3 on fresh Google APIs emulators using stock HTTPS trust and the system picker."""
from contextlib import ExitStack, contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

from android_a3_oidc import (
    MAIN_PACKAGE, ROOT, automatic_login_round, bounds, click_text, foreground_server,
    issuer, obtain_fingerprint, wait_for, window_nodes,
)
from android_sync_https_smoke import Adb, Rejected, ci_identity, require


OUTPUT = ROOT / "target/a3-android-oidc"
PICKER_PROBE = "org.zapime.android.a3picker"
DOCUMENTS = {"com.android.documentsui", "com.google.android.documentsui"}
BROWSERS = {"com.android.chrome", "org.chromium.webview_shell", "com.android.browser"}
ORIGIN = re.compile(rb"https://[a-z0-9-]+\.trycloudflare\.com")


def guard(adb, api):
    identity = ci_identity(adb.command)
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    expected = repository + "/.github/workflows/a3-android-oidc.yml@refs/pull/"
    require(repository and os.environ.get("GITHUB_EVENT_NAME") == "pull_request"
            and os.environ.get("GITHUB_WORKFLOW_REF", "").startswith(expected)
            and adb.command[2] == "emulator-5554", "dedicated_modern_a3_ci_required")
    for prop, value in (("ro.kernel.qemu", "1"), ("ro.build.version.sdk", str(api)), ("ro.product.cpu.abi", "x86_64")):
        require(adb.shell("getprop", prop) == value, "modern_a3_avd_properties_mismatch")
    require(adb.shell("getenforce") == "Enforcing" and adb.shell("id", "-u") == "2000",
            "modern_a3_requires_stock_unprivileged_adb")
    name = adb.run("emu", "avd", "name").stdout.splitlines()
    require(name and name[0].strip() == f"zapime-a3-modern-{api}-{identity}", "modern_a3_avd_name_mismatch")
    return identity


def checked(args, reason, *, timeout=30, env=None, data=None):
    proc = subprocess.run([str(part) for part in args], env=env, input=data,
                          capture_output=True, timeout=timeout, check=False)
    require(proc.returncode == 0, reason)
    return proc.stdout


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def read_https(origin, path):
    try:
        opener = urllib.request.build_opener(NoRedirect())
        with opener.open(origin + path, timeout=4) as response:
            return response.status, response.read(16_385)
    except urllib.error.HTTPError as error:
        return error.code, b""
    except (OSError, urllib.error.URLError):
        return 0, b""


@contextmanager
def tunnel(work, port, role, cleanup):
    logfile = work / f"{role}.cloudflared"
    with logfile.open("w+b") as log:
        child = subprocess.Popen([str(OUTPUT / "cloudflared"), "tunnel", "--no-autoupdate", "--protocol", "http2",
                                  "--url", f"http://127.0.0.1:{port}"],
                                 cwd=work, stdin=subprocess.DEVNULL, stdout=log, stderr=log)
        origin = None

        def assigned():
            require(child.poll() is None, "temporary_https_client_exited")
            match = ORIGIN.search(logfile.read_bytes()[:65_536])
            return match.group().decode("ascii") if match else None

        try:
            origin = wait_for(assigned, bool, 45, "temporary_https_origin_not_assigned")
            yield origin
        finally:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
            cleanup[role + "_process_stopped"] = child.poll() is not None
            if origin:
                path = "/.well-known/assetlinks.json" if role == "return" else "/auth"
                wait_for(lambda: read_https(origin, path)[0], lambda code: code in (0, 404, 502, 503, 530),
                         25, "temporary_https_origin_still_reachable")
                cleanup[role + "_origin_closed"] = True


def build_apks(work, login, fallback, client):
    config = work / "deployment.json"
    config.write_text(json.dumps({"issuer": login, "authorization": login + "/auth", "token": login + "/token",
        "callback": fallback + "/callback", "resource": login + "/a3-device-enrollment", "sync_server": login,
        "client_id": client}), encoding="utf-8")
    build, helper = OUTPUT / "main-source", work / "picker-probe"
    checked(["bash", "scripts/build_android_a3_source_fixture.sh", build, config, helper],
            "modern_test_apk_build_failed", timeout=110)
    env = dict(os.environ, ZAPIME_A3_TEST_SIGNING_PASS=secrets.token_hex(24))
    keyfile = work / "test-signing.jks"
    checked(["keytool", "-genkeypair", "-keystore", keyfile, "-storepass:env", "ZAPIME_A3_TEST_SIGNING_PASS",
             "-keypass:env", "ZAPIME_A3_TEST_SIGNING_PASS", "-alias", "a3-ci", "-dname", "CN=ZapIME A3 CI test",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "2"], "modern_test_signer_failed", env=env)
    signer = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0/apksigner"
    apks = [work / "product-test-only.apk", work / "picker-test-only.apk"]
    for output, unsigned in zip(apks, [build / "aligned.apk", helper / "unsigned.apk"]):
        checked([signer, "sign", "--ks", keyfile, "--ks-pass", "env:ZAPIME_A3_TEST_SIGNING_PASS",
                 "--key-pass", "env:ZAPIME_A3_TEST_SIGNING_PASS", "--out", output, unsigned],
                "modern_test_apk_signing_failed", env=env)
    return apks, obtain_fingerprint(signer, apks[0])


def system_packages(adb):
    return set(re.findall(r"(?m)^package:([\w.]+)$", adb.shell("pm", "list", "packages", "-s")))


def stock_browser(adb, origin):
    resolved = adb.shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.VIEW",
                         "-c", "android.intent.category.BROWSABLE", "-d", origin + "/auth")
    matches = set(re.findall(r"(?m)^([\w.]+)/[\w.$]+$", resolved)) & BROWSERS & system_packages(adb)
    require(len(matches) == 1, "stock_default_https_browser_unavailable")
    return matches.pop()


def browser_page(adb, marker, browser):
    def ready():
        nodes = window_nodes(adb, marker)
        controls = {node.get(attribute) for node in nodes if node.get("package") == browser and node.get("enabled") == "true"
                    for attribute in ("text", "content-desc")}
        if "Use synthetic test account" in controls:
            return True
        for label in ("Use without an account", "Continue without an account", "No thanks", "Accept & continue"):
            if label in controls:
                click_text(adb, marker, label)
                break
        return False
    wait_for(ready, bool, 40, "oidc_browser_authorization_page_missing")


def recovery_picker(report, adb, marker, status, recovery_file, code, docs_packages):
    def provider_nodes():
        return [node for node in window_nodes(adb, marker) if node.get("package") in docs_packages]

    def tap(area):
        left, top, right, bottom = area
        adb.run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def drawer(nodes):
        return next((node for node in nodes if node.get("resource-id", "").endswith(":id/roots_list") and bounds(node)), None)

    def state(label, available=None):
        def seen():
            nodes = window_nodes(adb, marker)
            shown = any(node.get("package") == MAIN_PACKAGE and label in node.get("text", "") for node in nodes)
            if available is None:
                return shown
            return shown and any(node.get("package") == MAIN_PACKAGE and node.get("text") == "验证并导入密钥…"
                                 and node.get("enabled") == str(available).lower() for node in nodes)
        return seen

    def picker_open():
        click_text(adb, marker, "选择密钥恢复文件", scroll=True)
        return wait_for(provider_nodes, bool, 25, "system_documentsui_not_foreground")

    if drawer(picker_open()) is not None:
        adb.run("shell", "input", "keyevent", "KEYCODE_BACK")
        wait_for(provider_nodes, lambda nodes: nodes and drawer(nodes) is None, 20, "system_documentsui_drawer_not_closed")
    adb.run("shell", "input", "keyevent", "KEYCODE_BACK")
    wait_for(state("恢复原同步密钥", False), bool, 20, "picker_cancel_not_observed")
    require(status()["dataRequests"] == 0, "picker_cancel_started_transfer")
    report["picker_cancel"] = "PASS"
    picker_open()

    def locate():
        nodes = provider_nodes()
        navigation = drawer(nodes)
        if navigation is not None:
            return "drawer", navigation
        for node in nodes:
            if Path(recovery_file).name in (node.get("text"), node.get("content-desc")) and node.get("enabled") == "true":
                area = bounds(node)
                if area and area[2] > area[0] and area[3] > area[1]:
                    return "file", area
        return None

    found = locate()
    if not found:
        switcher = next((bounds(node) for node in provider_nodes() if node.get("content-desc") in
                         ("Show roots", "Open navigation drawer") and node.get("enabled") == "true" and bounds(node)), None)
        if switcher:
            tap(switcher)
    kind, target = wait_for(locate, bool, 25, "synthetic_file_not_listed_by_documentsui")
    if kind == "drawer":
        downloads = next((bounds(node) for node in target.iter("node") if node.get("text") in ("Downloads", "下载")
                          and node.get("enabled") == "true" and bounds(node)), None)
        require(downloads, "system_documentsui_downloads_root_missing")
        tap(downloads)
        kind, target = wait_for(locate, lambda candidate: candidate and candidate[0] == "file",
                                25, "synthetic_file_not_listed_in_downloads")
        report["picker_downloads_root_selected"] = True
    tap(target)
    wait_for(state("已选择密钥恢复文件", True), bool, 25, "system_file_result_not_consumed")
    report["picker_selection"] = "PASS"

    def submit(value):
        checked(adb.command + ["exec-in", "run-as", PICKER_PROBE, "dd", "of=fixture-recovery-code"],
                "private_recovery_input_failed", data=value)
        result = adb.shell("am", "instrument", "-w", PICKER_PROBE + "/.RecoveryPickerProbe", timeout=30)
        failed_stage = re.search(r"(?m)^INSTRUMENTATION_RESULT: recovery_ui_stage=(private_code|password_field|import_button|import_dialog)$", result)
        if failed_stage:
            report["picker_submit_failure"] = failed_stage[1]
        require("INSTRUMENTATION_RESULT: recovery_submitted=PASS" in result
                and "INSTRUMENTATION_CODE: -1" in result, "recovery_password_or_dialog_not_submitted")

    require(re.fullmatch(rb"[A-Za-z0-9_-]{43}", code), "synthetic_recovery_input_invalid")
    wrong = (b"B" if code.startswith(b"A") else b"A") + code[1:]
    submit(wrong)
    wait_for(state("恢复码、文件用途或账号不匹配", True), bool, 25, "wrong_recovery_code_not_rejected")
    require(status()["dataRequests"] == 0, "wrong_recovery_code_started_transfer")
    report["picker_wrong_code"] = "PASS"
    submit(code)


def run(report, adb, api, identity, work):
    cleanup = report["cleanup"]
    marker = f"/data/local/tmp/zapime-a3-modern-{api}-{identity}.xml"
    fixture_file = f"/sdcard/Download/ZapIME-recovery-{api}-{identity}.bin"
    require(not any(adb.shell("pm", "path", package, check=False) for package in (MAIN_PACKAGE, PICKER_PROBE)),
            "prior_a3_test_packages_present")
    adb.run("shell", "test", "!", "-e", marker)
    adb.run("shell", "test", "!", "-e", fixture_file)
    docs = DOCUMENTS & system_packages(adb)
    require(docs, "stock_documentsui_unavailable")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]

    with ExitStack() as resources:
        report["stage"] = "temporary_public_https"
        login = resources.enter_context(tunnel(work, port, "login", cleanup))
        fallback = resources.enter_context(tunnel(work, port, "return", cleanup))
        require(login != fallback, "temporary_hosts_not_distinct")
        client = "zapime-ci-" + secrets.token_hex(16)
        report["stage"] = "test_apk_build"
        apks, fingerprint = build_apks(work, login, fallback, client)
        report["test_apk_sha256"] = hashlib.sha256(apks[0].read_bytes()).hexdigest()
        report["test_apk_signer"] = fingerprint
        mode = "automatic-recovery"
        bridge, verify, _ = resources.enter_context(foreground_server(
            work, report, OUTPUT / "sync-host-fixture", mode, login, client))
        environment = dict(os.environ, ZAPIME_A3_PUBLIC_PORT=str(port), ZAPIME_A3_PUBLIC_ISSUER=login,
                           ZAPIME_A3_PUBLIC_RETURN=fallback, ZAPIME_A3_PUBLIC_CLIENT=client)
        _, status = resources.enter_context(issuer(
            work, "-", "-", fingerprint, OUTPUT / "oidc-account.test", mode, bridge, environment))
        report["stage"] = "public_fixture_route_boundaries"
        # The runner's HTTPS client uses default public roots. The test device
        # subsequently makes independent requests using its own stock roots.
        assetlinks = "/.well-known/assetlinks.json"
        raw = wait_for(lambda: read_https(fallback, assetlinks), lambda result: result[0] == 200,
                       40, "public_assetlinks_not_reachable")
        target = json.loads(raw[1])[0]["target"]
        require(target["package_name"] == MAIN_PACKAGE and target["sha256_cert_fingerprints"] == [fingerprint],
                "public_assetlinks_wrong_signer")
        for private in ("/fixture-enrollment/automatic-recovery", "/fixture-enrollment/foreground", "/android-proof"):
            require(read_https(login, private)[0] == 404, "private_fixture_route_exposed")
        report["public_fixture_routes"] = "PASS"
        calls = status()["assetlinks"]
        try:
            report["stage"] = "test_apk_install"
            guard(adb, api)
            for apk in apks:
                adb.run("install", "-t", str(apk), timeout=60)
            report["stage"] = "stock_system_app_links"
            adb.run("shell", "pm", "verify-app-links", "--re-verify", MAIN_PACKAGE)
            verified = re.compile(r"(?m)^\s+" + re.escape(fallback.removeprefix("https://")) + r": verified\s*$")
            links = wait_for(lambda: adb.shell("pm", "get-app-links", "--user", "cur", MAIN_PACKAGE),
                             lambda raw: bool(verified.search(raw)) and status()["assetlinks"] > calls,
                             120, "system_did_not_verify_https_app_link")
            report["verified_domain"] = "PASS"
            report["system_link_handling_allowed"] = bool(re.search(r"Verification link handling allowed:\s*true", links, re.I))
            intent = ("--query-flags", "65536", "-a", "android.intent.action.VIEW",
                      "-c", "android.intent.category.BROWSABLE", "-d", fallback + "/callback")
            resolved = adb.shell("cmd", "package", "resolve-activity", "--brief", *intent)
            candidates = adb.shell("cmd", "package", "query-activities", "--brief", *intent)
            receiver = r"(?m)^\s*org\.zapime\.android/(?:org\.zapime\.android)?\.AccountLoginCallbackActivity\s*$"
            report["system_callback_product_candidate"] = bool(re.search(receiver, candidates))
            report["system_callback_resolves"] = ("product" if re.search(receiver, resolved)
                else "stock_browser" if any(name + "/" in resolved for name in BROWSERS)
                else "resolver" if "ResolverActivity" in resolved else "other")
            browser = stock_browser(adb, login)
            report["stock_browser"] = browser
            source = work / "foreground"
            adb.run("shell", "mkdir", "-p", "/sdcard/Download")
            adb.run("push", str(source / "recovery-key.bin"), fixture_file)
            code = (source / "recovery-code.txt").read_bytes()
            automatic_login_round(report, adb, marker, status, verify, mode, browser=browser,
                                  browser_ready=lambda: browser_page(adb, marker, browser),
                                  select_recovery=lambda: recovery_picker(report, adb, marker, status, fixture_file, code, docs))
        finally:
            guard(adb, api)
            for path, key in ((marker, "ui_probe_removed"), (fixture_file, "picker_document_removed")):
                adb.run("shell", "rm", "-f", path, check=False)
                cleanup[key] = adb.run("shell", "test", "!", "-e", path, check=False).returncode == 0
            for package in (PICKER_PROBE, MAIN_PACKAGE):
                if adb.shell("pm", "path", package, check=False):
                    adb.run("uninstall", package, check=False)
                cleanup[package + "_removed"] = not adb.shell("pm", "path", package, check=False)
            counts = status()
            report["issuer_counters"] = {key: counts.get(key, 0) for key in ("assetlinks", "login", "consent",
                "tokenPosts", "accountLookups", "automaticGrants", "callbackOpenedInBrowser", "dataRequests", "uploads")}
            report["issuer_failure_route"] = counts.get("failureRoute")
            report["callback_http_observation"] = {
                "kind": counts.get("callbackHttpKind"), "had_code_and_state": bool(counts.get("callbackHttpContainedCode")),
            }


def main():
    api = int(sys.argv[1])
    report = {"api_level": api, "source_sha": os.environ.get("GITHUB_SHA", ""), "status": "FAIL",
              "verified_domain": "NOT RUN", "verified_browser_callback": "NOT RUN", "picker_cancel": "NOT RUN",
              "picker_selection": "NOT RUN", "picker_wrong_code": "NOT RUN", "cleanup": {},
              "scope": "Stock emulator, synthetic OIDC and PostgreSQL, verified App Link and system ACTION_OPEN_DOCUMENT"}
    try:
        require(api in (35, 36) and os.name == "posix", "modern_a3_api_required")
        adb = Adb([str(shutil.which("adb")), "-s", "emulator-5554"])
        identity = guard(adb, api)
        with tempfile.TemporaryDirectory(prefix="a3-modern-private-") as temporary:
            run(report, adb, api, identity, Path(temporary))
        require(len(report["cleanup"]) == 8 and all(report["cleanup"].values()), "modern_a3_cleanup_incomplete")
        report.pop("stage", None)
        report["status"] = "PASS"
    except Rejected as failure:
        report["failure"] = str(failure)
    except (OSError, ValueError, TypeError, KeyError, subprocess.TimeoutExpired) as failure:
        report["failure"] = type(failure).__name__
    finally:
        result = json.dumps(report, sort_keys=True)
        OUTPUT.mkdir(parents=True, exist_ok=True)
        (OUTPUT / "modern-report.json").write_text(result + "\n", encoding="utf-8")
        print(result)
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
