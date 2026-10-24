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
    fixture = Path(__file__).resolve().parent / "windows_profile_language_fixture.ps1"
    powershell = Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    fixture_command = [str(powershell), "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(fixture)]
    language_refused = subprocess.run(fixture_command, cwd=directory,
                                     env={**os.environ, "RUNNER_ENVIRONMENT": "self-hosted"},
                                     capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=15)
    (directory / "profile-language-guard.log").write_text(language_refused.stdout + language_refused.stderr, encoding="utf-8")
    if language_refused.returncode != 1 or "dedicated_github_hosted_runner_required_language_fixture" not in language_refused.stderr:
        raise SystemExit("Language fixture did not reject self-hosted before current-user settings access")
    print("PASS actual language-fixture refusal in self-hosted environment before language settings access", flush=True)
    try:
        result = subprocess.run(fixture_command, cwd=directory, capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=120)
    except subprocess.TimeoutExpired as error:
        (directory / "profile-probe.log").write_bytes((error.stdout or b"") + (error.stderr or b""))
        raise SystemExit("TSF language/profile fixture timed out; cleanup and language restoration are NOT verified")
    (directory / "profile-probe.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    print(result.stdout + result.stderr, end="", flush=True)
    if (result.returncode != 0 or "cleanup=PASS" not in result.stdout
            or "PASS temporary Chinese user-language fixture restored and verified" not in result.stdout):
        raise SystemExit("TSF profile registration/activation/cleanup failed; inspect raw profile-probe.log")
    print("PASS disposable-runner profile guard, process activation and cleanup; not real application typing")


if __name__ == "__main__":
    main()
