# SPDX-License-Identifier: GPL-3.0-only
"""Read-only selection of a verified preinstalled keyboard for CI teardown."""
import subprocess
import os
import re


def system_keyboard(command):
    api = int(subprocess.check_output(command + ["shell", "getprop", "ro.build.version.sdk"], text=True).strip())
    methods = subprocess.check_output(command + ["shell", "ime", "list", "-a", "-s"], text=True).splitlines()
    packages = subprocess.check_output(command + ["shell", "pm", "list", "packages", "-s"], text=True).splitlines()
    system = {line.strip().removeprefix("package:") for line in packages if line.startswith("package:")}
    candidates = [line.strip() for line in methods if "/" in line and line.strip().split("/", 1)[0] in system and not line.strip().startswith("org.zapime.")]
    aosp = [method for method in candidates if method.startswith("com.android.")]
    if aosp:
        chosen = aosp[0]
    elif api >= 35 and candidates:
        chosen = candidates[0]
    else:
        raise SystemExit("No permitted system teardown keyboard: API=" + str(api) + " registered=" + repr(methods) + "; API28 requires AOSP")
    proof = {"api_level": api, "selected_ime": chosen, "system_package_verified": True, "source": "pm list packages -s", "aosp": chosen.startswith("com.android.")}
    print("Verified system teardown keyboard:", proof, flush=True)
    return chosen, proof


def dedicated_ci(command):
    return (os.environ.get("GITHUB_ACTIONS") == "true"
            and os.environ.get("GITHUB_RUN_ID", "").isdigit()
            and os.environ.get("GITHUB_RUN_ATTEMPT", "").isdigit()
            and len(command) == 3 and command[1] == "-s"
            and re.fullmatch(r"emulator-[0-9]+", command[2]) is not None)


def diagnostic_args(command):
    if not dedicated_ci(command):
        return []
    return ["-e", "zapime_ci_diagnostics", os.environ["GITHUB_RUN_ID"] + "-" + os.environ["GITHUB_RUN_ATTEMPT"]]


def write_diagnostic(path, value):
    # Limit UTF-8 bytes, not Python characters (CJK/emoji can be 3/4 bytes).
    # Drop a partial first codepoint so saved files remain readable UTF-8.
    path.write_text(value.encode("utf-8", errors="replace")[-256 * 1024:].decode("utf-8", errors="ignore"), encoding="utf-8")


def collect_failure_state(command, output):
    """CI post-instrumentation state; only host ci-* files precede finish()."""
    if not dedicated_ci(command):
        return
    probes = {
        "activity-top.txt": ["shell", "dumpsys", "activity", "top"],
        "activity-state.txt": ["shell", "dumpsys", "activity", "activities"],
        "window-state.txt": ["shell", "dumpsys", "window", "windows"],
        "last-anr.txt": ["shell", "dumpsys", "activity", "lastanr"],
        "system-runtime.txt": ["logcat", "-d", "-v", "threadtime", "-t", "2000"],
    }
    for name, arguments in probes.items():
        try:
            result = subprocess.run(command + arguments, text=True, capture_output=True, timeout=10)
            text = result.stdout + result.stderr
        except (OSError, subprocess.TimeoutExpired) as error:
            text = "diagnostic_unavailable: " + type(error).__name__
        write_diagnostic(output / name, text)


def assert_no_app_anr(command, output):
    if not dedicated_ci(command):
        return
    result = subprocess.run(command + ["logcat", "-d", "-v", "threadtime", "-s", "ActivityManager:E"], text=True, capture_output=True, timeout=10, check=True)
    write_diagnostic(output / "activity-errors.txt", result.stdout)
    if re.search(r"ANR in org\.zapime\.android(?:\.|\s|\(|$)", result.stdout):
        raise SystemExit("ZapIME/test host ANR; inspect activity-errors.txt")
