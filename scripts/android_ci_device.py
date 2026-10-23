# SPDX-License-Identifier: GPL-3.0-only
"""Read-only selection of a verified preinstalled keyboard for CI teardown."""
import subprocess


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
