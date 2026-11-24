# SPDX-License-Identifier: GPL-3.0-only
"""Report stock API28 browser and App Link verifier availability in disposable CI."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys


SERIAL = "emulator-5554"
URI = "https://example.invalid/"
VIEW = ("-a", "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE", "-d", URI)
VERIFICATION = ("-a", "android.intent.action.INTENT_FILTER_NEEDS_VERIFICATION",
                "-t", "application/vnd.android.package-archive")
COMPONENT = r"[A-Za-z][A-Za-z0-9_.]*/[A-Za-z_.][A-Za-z0-9_.$]*"
VERIFICATION_PERMISSION = "android.permission.INTENT_FILTER_VERIFICATION_AGENT"


def adb(*args):
    result = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=18, check=False)
    if result.returncode:
        raise RuntimeError("adb_command_failed")
    return result.stdout.strip()


def dedicated_api28():
    if (os.environ.get("GITHUB_ACTIONS") != "true" or
            any(re.fullmatch(r"[1-9][0-9]{0,19}", os.environ.get(name, "")) is None
                for name in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"))):
        raise RuntimeError("requires_disposable_github_runner")
    if (adb("get-state") != "device" or adb("shell", "getprop", "ro.kernel.qemu") != "1" or
            adb("shell", "getprop", "ro.build.version.sdk") != "28"):
        raise RuntimeError("requires_api28_emulator")


def queried_components(command, intent):
    output = adb("shell", "cmd", "package", command, "--brief", "--components", "--user", "0", *intent)
    return sorted({line.strip() for line in output.splitlines() if re.fullmatch(COMPONENT, line.strip())})


def system_package(component, packages):
    return component.split("/", 1)[0] in packages


def granted_verifiers(components, packages):
    capable = []
    for component in components:
        if not system_package(component, packages):
            continue
        package = component.split("/", 1)[0]
        output = adb("shell", "dumpsys", "package", package)
        granted = re.search(r"^\s*" + re.escape(VERIFICATION_PERMISSION) + r": granted=true(?:,.*)?$",
                            output, re.MULTILINE)
        if granted:
            capable.append(component)
    return capable


def failed_requirements(browsers, launched, verifiers):
    return [name for name, passed in (("stock_https_browser", bool(browsers)),
            ("implicit_browser_launch", launched in browsers), ("system_link_verifier", bool(verifiers))) if not passed]


def run(report):
    dedicated_api28()
    report["api_level"] = 28
    packages = set(re.findall(r"^package:([A-Za-z0-9_.]+)$",
                              adb("shell", "pm", "list", "packages", "-s", "--user", "0"), re.MULTILINE))
    if not packages:
        raise RuntimeError("system_package_inventory_unavailable")
    browsers = [item for item in queried_components("query-activities", VIEW) if system_package(item, packages)]
    report["system_https_browsers"] = browsers
    receivers = [item for item in queried_components("query-receivers", VERIFICATION) if system_package(item, packages)]
    report["system_verifier_receivers"] = receivers
    verifiers = granted_verifiers(receivers, packages)
    report["permitted_system_link_verifiers"] = verifiers

    resolved = queried_components("resolve-activity", VIEW)
    report["default_https_handler"] = resolved[0] if len(resolved) == 1 else None
    launched = None
    if len(resolved) == 1 and resolved[0] in browsers:
        opened = adb("shell", "am", "start", "-W", *VIEW)
        actual = re.search(r"^Activity:\s+" + "(" + COMPONENT + r")\s*$", opened, re.MULTILINE)
        if actual and re.search(r"^Status:\s+ok\s*$", opened, re.MULTILINE):
            launched = actual.group(1)
    report["implicit_browser_launch"] = launched
    failures = failed_requirements(browsers, launched, verifiers)
    report["failed_capabilities"] = failures
    report["status"] = "BLOCKED" if failures else "PASS"


def main():
    # The workflow archives only this result, never a browser dump, credentials or page content.
    report = {"status": "FAIL", "scope": "stock API28 browser and verifier availability",
              "source_sha": os.environ.get("GITHUB_SHA", ""), "verified_domain_callback": "NOT RUN",
              "oidc_login": "NOT RUN"}
    try:
        run(report)
    except RuntimeError as error:
        report["reason"] = str(error)
    except subprocess.TimeoutExpired:
        report["reason"] = "adb_timeout"
    output = Path("target/a3-api28-login-preflight/report.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    summary = json.dumps(report, sort_keys=True)
    output.write_text(summary + "\n", encoding="utf-8")
    print(summary)
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
