#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
import hashlib
import json
import re
from pathlib import Path
import subprocess
import sys
import zipfile
from prepare_android_voice import LOCK, model_config


def check_contents(path):
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        entries = set(names)
        assert len(entries) == len(names), "duplicate APK members"
        for name in ["classes.dex", "assets/NOTICE.txt", "assets/licenses/GPL-3.0.txt", "assets/licenses/chinese-ime-lm-NOTICE.txt", "assets/dictionary/msime-pinyin.db", "assets/dictionary/msime-english.db", "assets/dictionary/msime-scowl_Copyright.txt"]:
            assert name in entries, "missing APK entry: " + name
        for name in ["voice-model.json", "voice-licenses/sherpa-onnx-Apache-2.0.txt", "voice-licenses/onnxruntime-MIT.txt", "voice-licenses/onnxruntime-ThirdPartyNotices.txt", "voice-licenses/nlohmann-json-MIT.txt", "voice-licenses/ndk-toolchain.txt", "voice-licenses/ndk-sysroot.txt"]:
            assert "assets/" + name in entries, "missing voice attribution: " + name
            assert apk.getinfo("assets/" + name).file_size > 0, "empty voice attribution"
        cores = [name for name in entries if name.endswith("/libzapime_core.so")]
        assert len(cores) == 1, "one native core ABI required per development APK"
        directory = cores[0].rsplit("/", 1)[0]
        abi = directory.split("/")[-1]
        assert directory == "lib/" + abi and abi in LOCK["libraries"], "unsupported native directory"
        libraries = {directory + "/" + name for name in ["libzapime_core.so", "libzapime_voice.so", "libsherpa-onnx-c-api.so", "libonnxruntime.so"]}
        assert {name for name in entries if name.startswith("lib/")} == libraries, "missing/unexpected packaged runtime"
        for name, spec in LOCK["libraries"][abi].items():
            member = directory + "/" + name
            assert apk.getinfo(member).file_size == spec["size"], "packaged runtime size mismatch"
            with apk.open(member) as source:
                assert hashlib.file_digest(source, "sha256").hexdigest() == spec["sha256"], "packaged runtime checksum mismatch"
        assert json.loads(apk.read("assets/voice-model.json")) == model_config(), "untrusted packaged model catalog"
        assert not any(name.endswith(".onnx") for name in entries), "model weights must remain an explicit import"
    print("PASS packaged APK native hashes, model catalog, notices and explicit model boundary:", abi)


if __name__ == "__main__":
    path = Path(sys.argv[1])
    check_contents(path)
    manifest = subprocess.check_output([sys.argv[2], "dump", "xmltree", str(path), "--file", "AndroidManifest.xml"], text=True)
    assert "org.zapime.android" in manifest
    assert "android.permission.INTERNET" in manifest
    assert re.search(r"android:usesCleartextTraffic[^\n]*(?:\(type 0x12\)0x0|=false)", manifest), "cleartext network must remain disabled"
    assert "android.permission.BIND_INPUT_METHOD" in manifest
    assert "android.permission.RECORD_AUDIO" in manifest
    print("APK package, native IME, notices, explicit network permission and no-cleartext checks passed")
    with path.open("rb") as source:
        print("SHA256", hashlib.file_digest(source, "sha256").hexdigest())
