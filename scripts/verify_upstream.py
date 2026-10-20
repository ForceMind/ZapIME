#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
def verify():
    lock = json.loads((ROOT / "third_party/upstream-lock.json").read_text())
    upstream = ROOT / "vendor/msime"
    head = subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
    if head != lock["msime"]["commit"]:
        raise SystemExit("Upstream commit mismatch; run git submodule update --init")
    dirty = subprocess.check_output(["git", "-C", str(upstream), "status", "--porcelain", "--untracked-files=no"], text=True)
    if dirty:
        raise SystemExit("Upstream has tracked modifications; refusing an unreproducible build")
    if not (upstream / "LICENSE").is_file():
        raise SystemExit("Missing upstream license")
    print("Verified MSIME source:", head)
if __name__ == "__main__":
    verify()
