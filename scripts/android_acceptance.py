#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run both isolated gates and retain each result; neither can mask the other."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from android_offline_uid import offline_product

if os.environ.get("GITHUB_ACTIONS") != "true" or len(sys.argv) != 3 or not re.fullmatch(r"emulator-[0-9]+",sys.argv[2]):
    raise SystemExit("Ephemeral GitHub emulator required")
root=Path(__file__).resolve().parents[1]
results={}
output=root/"target/voice-android"
with offline_product([sys.argv[1],"-s",sys.argv[2]], output):
    for script in ("android_smoke_repeat.py","android_voice_smoke.py"):
        try:
            result=subprocess.run([sys.executable,str(root/"scripts"/script),*sys.argv[1:]],cwd=root,timeout=900,check=False)
            results[script]=result.returncode
        except subprocess.TimeoutExpired:
            results[script]=124
(output/"combined-gates.json").write_text(json.dumps(results,indent=2)+"\n")
print("Android gate exit codes:",json.dumps(results),flush=True)
raise SystemExit(0 if all(code==0 for code in results.values()) else 1)
