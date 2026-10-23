#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Current-user TSF experiment; never runs on local/self-hosted machines."""
import os
from pathlib import Path
import subprocess


def main():
    if not (os.name == "nt" and os.environ.get("GITHUB_ACTIONS") == "true"
            and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted"
            and os.environ.get("GITHUB_RUN_ID", "").isdigit()
            and os.environ.get("GITHUB_RUN_ATTEMPT", "").isdigit()):
        raise SystemExit("Dedicated GitHub-hosted Windows runner required; no profile changes made")
    directory = Path(__file__).resolve().parents[1] / "target/windows-native/Release"
    executable = directory / "ZapIMEWindowsProfileProbe.exe"
    refused = subprocess.run([str(executable)], cwd=directory,
                             env={**os.environ, "RUNNER_ENVIRONMENT": "self-hosted"},
                             capture_output=True, text=True, timeout=15)
    (directory / "profile-probe-guard.log").write_text(refused.stdout + refused.stderr, encoding="utf-8")
    if refused.returncode != 1 or "dedicated_github_hosted_runner_required" not in refused.stderr:
        raise SystemExit("Executable did not reject the self-hosted environment before registry access")
    print("PASS actual executable refusal in self-hosted environment before registry access", flush=True)
    try:
        result = subprocess.run([str(executable)], cwd=directory, capture_output=True, text=True, timeout=90)
    except subprocess.TimeoutExpired as error:
        (directory / "profile-probe.log").write_bytes((error.stdout or b"") + (error.stderr or b""))
        raise SystemExit("TSF profile probe timed out; cleanup is NOT verified")
    (directory / "profile-probe.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    print(result.stdout + result.stderr, end="", flush=True)
    if result.returncode != 0 or "cleanup=PASS" not in result.stdout:
        raise SystemExit("TSF profile registration/activation/cleanup failed; inspect raw profile-probe.log")
    print("PASS disposable-runner profile guard, process activation and cleanup; not real application typing")


if __name__ == "__main__":
    main()
