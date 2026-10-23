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
    for name in ["voice-model.json", "voice-licenses/sherpa-onnx-Apache-2.0.txt", "voice-licenses/onnxruntime-MIT.txt", "voice-licenses/onnxruntime-ThirdPartyNotices.txt", "voice-licenses/nlohmann-json-MIT.txt", "voice-licenses/ndk-toolchain.txt", "voice-licenses/ndk-sysroot.txt"]:
        assert "assets/" + name in entries, "missing voice attribution: " + name
    for core in [name for name in entries if name.endswith("/libzapime_core.so")]:
        directory=core.rsplit("/",1)[0]
        for name in ["libzapime_voice.so", "libsherpa-onnx-c-api.so", "libonnxruntime.so"]:
            assert directory+"/"+name in entries, "missing voice runtime: " + name
    assert not any(name.endswith(".onnx") for name in entries), "model weights must remain an explicit import"
manifest = subprocess.check_output([sys.argv[2], "dump", "xmltree", str(path), "--file", "AndroidManifest.xml"], text=True)
assert "org.zapime.android" in manifest
assert "android.permission.INTERNET" not in manifest
assert "android.permission.BIND_INPUT_METHOD" in manifest
assert "android.permission.RECORD_AUDIO" in manifest
print("APK package, native IME, notices and no-INTERNET checks passed")
print("SHA256", hashlib.sha256(path.read_bytes()).hexdigest())
