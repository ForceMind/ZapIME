# SPDX-License-Identifier: GPL-3.0-only
"""Fail-closed, per-product-UID network isolation on dedicated ephemeral CI emulators."""
from contextlib import contextmanager
import json
import re
import subprocess
import time
from android_ci_device import dedicated_ci, write_diagnostic


@contextmanager
def offline_product(command, output):
    if not dedicated_ci(command):
        raise SystemExit("Dedicated ephemeral GitHub emulator required for UID isolation")
    def run(*args, check=True):
        return subprocess.run(command + list(args), text=True, capture_output=True, check=check, timeout=30)
    # The emulator can briefly go offline while adbd restarts. Preserve its
    # exact root command outcome, even when the root gate fails before any
    # network isolation or Android instrumentation starts.
    output.mkdir(parents=True, exist_ok=True)
    root_trace = []
    for attempt in range(1, 4):
        try:
            result = run("root", check=False)
        except (OSError, subprocess.TimeoutExpired) as failure:
            root_trace.append(f"attempt={attempt} exception={type(failure).__name__}: {failure}")
            write_diagnostic(output / "offline-uid-root.txt", "\n".join(root_trace) + "\n")
            raise
        root_trace.append(
            f"attempt={attempt} exit_code={result.returncode}\n"
            f"stdout={result.stdout[-2048:]}\nstderr={result.stderr[-2048:]}"
        )
        write_diagnostic(output / "offline-uid-root.txt", "\n".join(root_trace) + "\n")
        if result.returncode == 0:
            break
        combined = result.stdout + result.stderr
        # Only a transport startup race is retryable; unsupported root,
        # permission and security failures must fail closed immediately.
        transient = re.search(
            r"device offline|device '.+' not found|no devices/emulators found",
            combined, re.IGNORECASE,
        )
        if not transient or attempt == 3:
            raise SystemExit("CI emulator adb root failed; inspect offline-uid-root.txt")
        time.sleep(attempt)
    run("wait-for-device")
    if run("shell", "id", "-u").stdout.strip() != "0":
        raise SystemExit("CI emulator root required; offline UID gate cannot be skipped")
    package = run("shell", "pm", "list", "packages", "-U", "org.zapime.android").stdout
    matches = re.findall(r"^package:org\.zapime\.android\s+uid:(\d+)\s*$", package, re.M)
    if len(matches) != 1 or int(matches[0]) < 10000:
        raise SystemExit("Exact installed product UID required")
    uid = matches[0]
    created = []
    proof = {"package": "org.zapime.android", "uid": int(uid), "ipv4_reject": False, "ipv6_reject": False, "removed": False}
    try:
        for binary, field in (("iptables", "ipv4_reject"), ("ip6tables", "ipv6_reject")):
            rule = ["OUTPUT", "-m", "owner", "--uid-owner", uid, "-j", "REJECT"]
            if run("shell", binary, "-w", "5", "-C", *rule, check=False).returncode == 0:
                raise SystemExit("Pre-existing UID rule must not be taken over")
            run("shell", binary, "-w", "5", "-I", *rule)
            created.append((binary, rule))
            run("shell", binary, "-w", "5", "-C", *rule)
            proof[field] = True
        output.mkdir(parents=True, exist_ok=True)
        (output / "offline-uid.json").write_text(json.dumps(proof, indent=2)+"\n")
        print("Verified kernel IPv4/IPv6 REJECT for exact product UID", uid, flush=True)
        yield
    finally:
        failed = False
        for binary, rule in reversed(created):
            if run("shell", binary, "-w", "5", "-D", *rule, check=False).returncode != 0:
                failed = True
            if run("shell", binary, "-w", "5", "-C", *rule, check=False).returncode == 0:
                failed = True
        proof["removed"] = not failed
        output.mkdir(parents=True, exist_ok=True)
        (output / "offline-uid.json").write_text(json.dumps(proof, indent=2)+"\n")
        if failed:
            raise SystemExit("UID isolation cleanup failed")
