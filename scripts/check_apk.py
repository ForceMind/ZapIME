#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
import hashlib
from pathlib import Path
import subprocess
import sys
import zipfile

path = Path(sys.argv[1])
with zipfile.ZipFile(path) as apk:
    entries = set(apk.namelist())
    for name in ["classes.dex", "assets/NOTICE.txt", "assets/licenses/GPL-3.0.txt", "assets/licenses/chinese-ime-lm-NOTICE.txt", "assets/dictionary/msime-pinyin.db", "assets/dictionary/msime-english.db", "assets/dictionary/msime-scowl_Copyright.txt"]:
        assert name in entries, "missing APK entry: " + name
manifest = subprocess.check_output([sys.argv[2], "dump", "xmltree", str(path), "--file", "AndroidManifest.xml"], text=True)
assert "org.zapime.android" in manifest
assert "android.permission.INTERNET" not in manifest
assert "android.permission.BIND_INPUT_METHOD" in manifest
print("APK package, native IME, notices and no-INTERNET checks passed")
print("SHA256", hashlib.sha256(path.read_bytes()).hexdigest())
