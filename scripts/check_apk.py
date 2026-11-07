#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
import hashlib
import struct
import tempfile
import json
import re
from pathlib import Path
import subprocess
import sys
import zipfile
from prepare_android_voice import LOCK, model_config, verify_elf
from prepare_candidate_model import load_lock as candidate_lock



VOICEKEY_JNI = {"Java_org_zapime_android_NativeVoiceKey_" + name for name in
                ("allocate", "start", "accept", "finish", "cancel", "close", "checkModel", "releaseCache")}
CORE_JNI = {"Java_org_zapime_android_NativeCore_" + name for name in
            ("open", "request", "queryPersonal", "syncProfile", "close",
             "createLocalBackup", "restoreLocalBackup", "newSyncMaterial",
             "syncMaterialInfo", "syncKeyDescriptor", "syncWorkspaceStatus", "prepareSync",
             "createSyncRecovery", "restoreSyncRecovery", "stageSyncUpload",
             "ackSyncUpload", "syncPullCursor", "applySyncPage")}
CANDIDATE_JNI = {"Java_org_zapime_android_NativeCandidateRanker_" + name for name in
                 ("open", "score", "clear", "cancel", "close")}


def check_notice_inventory(apk, prefix, root_package, target_platform=None):
    raw = apk.read(prefix + "/inventory.json")
    collection = json.loads(apk.read(prefix + "/collection.json"))
    rows = json.loads(raw)
    assert re.fullmatch(r"[0-9a-f]{64}", collection.get("cargo_lock_sha256") or ""), "missing Cargo lock provenance"
    if target_platform is not None:
        assert collection.get("target_platform") == target_platform, "wrong Cargo notice target"
    assert collection["schema_version"] == 1 and collection["root_package"] == root_package, "wrong Cargo notice root"
    assert collection["inventory_sha256"] == hashlib.sha256(raw).hexdigest(), "notice inventory digest mismatch"
    assert isinstance(rows, list) and rows and len(rows) == collection["packages"], "incomplete Cargo notice inventory"
    identities = set()
    for row in rows:
        name, version = row["name"], row["version"]
        assert re.fullmatch(r"[A-Za-z0-9_-]+", name) and re.fullmatch(r"[A-Za-z0-9_.+-]+", version), "invalid notice identity"
        assert (name, version) not in identities, "duplicate Cargo notice identity"
        identities.add((name, version))
        assert row["license"] and row["license"] != "NOASSERTION", "unreviewed Cargo notice license"
        files = row["license_files"]
        # The adapter is independent of core's historical path-crate exception.
        assert isinstance(files, list) and files and len(files) == len(set(files)), "missing Cargo license files"
        assert set(row["license_sha256"]) == set(files), "incomplete license digest list"
        for file in files:
            assert isinstance(file, str) and file not in (".", "..") and "/" not in file and "\\" not in file, "invalid notice member"
            data = apk.read(prefix + "/" + name + "-" + version + "/" + file)
            assert data and hashlib.sha256(data).hexdigest() == row["license_sha256"][file], "missing or changed license text"
    assert any(name == root_package for name, _ in identities), "missing adapter root notice"


def elf_metadata(raw):
    """Read ELF64 dynamic names with bounded offsets; no executing downloaded code."""
    assert len(raw) >= 64 and raw[:6] == b"\x7fELF\x02\x01", "VoiceKey ELF64 little-endian required"
    table = struct.unpack_from("<Q", raw, 40)[0]
    width, count = struct.unpack_from("<HH", raw, 58)
    assert width == 64 and 1 <= count <= 2048 and table + width * count <= len(raw), "invalid ELF section table"
    sections = [struct.unpack_from("<IIQQQQIIQQ", raw, table + n * width) for n in range(count)]
    def data(section):
        offset, size = section[4], section[5]
        assert offset <= len(raw) and size <= len(raw) - offset, "truncated ELF section"
        return raw[offset:offset + size]
    def strings(section):
        assert section[6] < count and sections[section[6]][1] == 3, "invalid ELF string link"
        return data(sections[section[6]])
    def name(text, offset):
        assert offset < len(text), "invalid ELF string offset"
        end = text.find(b"\0", offset)
        assert end != -1, "unterminated ELF name"
        return text[offset:end].decode("ascii")
    needed, exports = set(), set()
    for section in sections:
        if section[1] == 6:
            payload, text = data(section), strings(section)
            assert section[9] == 16 and len(payload) % 16 == 0, "invalid dynamic table"
            for offset in range(0, len(payload), 16):
                tag, value = struct.unpack_from("<qQ", payload, offset)
                if tag == 0: break
                if tag == 1: needed.add(name(text, value))
        if section[1] == 11:
            payload, text = data(section), strings(section)
            assert section[9] == 24 and len(payload) % 24 == 0, "invalid dynsym table"
            for offset in range(0, len(payload), 24):
                st_name, info, other, index, _, _ = struct.unpack_from("<IBBHQQ", payload, offset)
                if index and info >> 4 in (1, 2) and other & 3 in (0, 3):
                    exports.add(name(text, st_name))
    return needed, exports


def check_voicekey_native(raw, abi):
    with tempfile.NamedTemporaryFile(suffix=".so") as file:
        file.write(raw); file.flush()
        verify_elf(Path(file.name), abi)
    needed, exports = elf_metadata(raw)
    assert "libc.so" in needed and needed <= {"libc.so", "libm.so", "libdl.so"}, "unexpected VoiceKey dynamic dependencies"
    assert VOICEKEY_JNI <= exports, "missing VoiceKey JNI dynamic exports"


def check_core_native(raw, abi):
    with tempfile.NamedTemporaryFile(suffix=".so") as file:
        file.write(raw); file.flush()
        verify_elf(Path(file.name), abi)
    _, exports = elf_metadata(raw)
    assert CORE_JNI <= exports, "missing core or stateless sync JNI dynamic exports"


def check_candidate_native(raw, abi):
    with tempfile.NamedTemporaryFile(suffix=".so") as file:
        file.write(raw); file.flush()
        verify_elf(Path(file.name), abi)
    needed, exports = elf_metadata(raw)
    assert "libc.so" in needed and needed <= {"libc.so", "libm.so", "libdl.so"}, "unexpected candidate runtime dependency"
    assert CANDIDATE_JNI <= exports, "missing candidate JNI dynamic exports"


def check_candidate_assets(apk):
    lock = candidate_lock()
    prefix = "assets/" + lock["asset_directory"] + "/"
    names = {name for name in apk.namelist() if name.startswith(prefix) and not name.endswith("/")}
    assert names == {prefix + name for name in lock["files"]}, "incomplete or unexpected candidate model assets"
    for name, spec in lock["files"].items():
        member = prefix + name
        assert apk.getinfo(member).file_size == spec["size"], "candidate model asset size mismatch"
        with apk.open(member) as source:
            assert hashlib.file_digest(source, "sha256").hexdigest() == spec["sha256"], "candidate model asset digest mismatch"
    assert {name for name in apk.namelist() if name.lower().endswith(".ort")} == {prefix + "lm_step.ort"}, "unreviewed ORT model in APK"


def check_contents(path):
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        entries = set(names)
        assert len(entries) == len(names), "duplicate APK members"
        for name in ["classes.dex", "assets/NOTICE.txt", "assets/licenses/GPL-3.0.txt", "assets/licenses/chinese-ime-lm-NOTICE.txt", "assets/dictionary/msime-pinyin.db", "assets/dictionary/msime-english.db", "assets/dictionary/msime-scowl_Copyright.txt"]:
            assert name in entries, "missing APK entry: " + name
        for name in ["voice-model.json", "voice-licenses/voicekey-source.txt", "voice-licenses/sherpa-onnx-Apache-2.0.txt", "voice-licenses/onnxruntime-MIT.txt", "voice-licenses/onnxruntime-ThirdPartyNotices.txt", "voice-licenses/nlohmann-json-MIT.txt", "voice-licenses/ndk-toolchain.txt", "voice-licenses/ndk-sysroot.txt"]:
            assert "assets/" + name in entries, "missing voice attribution: " + name
            assert apk.getinfo("assets/" + name).file_size > 0, "empty voice attribution"
        cores = [name for name in entries if name.endswith("/libzapime_core.so")]
        assert len(cores) == 1, "one native core ABI required per development APK"
        directory = cores[0].rsplit("/", 1)[0]
        abi = directory.split("/")[-1]
        assert directory == "lib/" + abi and abi in LOCK["libraries"], "unsupported native directory"
        libraries = {directory + "/" + name for name in ["libzapime_core.so", "libzapime_voice.so", "libsherpa-onnx-c-api.so", "libonnxruntime.so", "libzapime_voicekey_adapter.so", "libzapime_candidate.so"]}
        assert {name for name in entries if name.startswith("lib/")} == libraries, "missing/unexpected packaged runtime"
        check_core_native(apk.read(cores[0]), abi)
        check_candidate_native(apk.read(directory + "/libzapime_candidate.so"), abi)
        check_candidate_assets(apk)
        for name, spec in LOCK["libraries"][abi].items():
            member = directory + "/" + name
            assert apk.getinfo(member).file_size == spec["size"], "packaged runtime size mismatch"
            with apk.open(member) as source:
                assert hashlib.file_digest(source, "sha256").hexdigest() == spec["sha256"], "packaged runtime checksum mismatch"
        # The optional JNI implementation is bundled for explicit user-selected
        # offline use, but no third-party VoiceKey model or non-public cloud
        # credentials are installed in the product APK.
        optional = apk.getinfo(directory + "/libzapime_voicekey_adapter.so")
        assert optional.file_size > 0, "empty VoiceKey JNI library"
        check_voicekey_native(apk.read(optional), abi)
        check_notice_inventory(apk, "assets/voicekey-rust-licenses", "zapime-voicekey-adapter",
                               {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}[abi])
        attribution = apk.read("assets/voice-licenses/voicekey-source.txt").decode("utf-8")
        assert "https://github.com/J3n5en/voicekey" in attribution and "3ba017826eef7fac405198510b03859eb5b6c8c4" in attribution, "wrong VoiceKey source attribution"
        assert json.loads(apk.read("assets/voice-model.json")) == model_config(), "untrusted packaged model catalog"
        assert not any(name.lower().endswith((".onnx", ".xnet", ".pcm", ".wav")) for name in entries), "voice weights and recordings must remain outside the APK"
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
