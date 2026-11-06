# SPDX-License-Identifier: GPL-3.0-only
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from collect_rust_notices import collect, graph
from check_apk import check_notice_inventory, check_voicekey_native, elf_metadata, VOICEKEY_JNI


def package(directory, name, license_id="MIT", declared=None):
    return {"id": name, "name": name, "version": "1.0.0", "manifest_path": str(directory / "Cargo.toml"),
            "license": license_id, "license_file": declared, "source": "registry+synthetic", "repository": "https://example.invalid/synthetic"}


def metadata(packages, dependencies):
    return {"packages": packages, "resolve": {"nodes": [{"id": p["id"], "deps": dependencies.get(p["id"], [])} for p in packages]}}


def dependency(name, kind):
    return {"pkg": name, "dep_kinds": [{"kind": kind}]}


class RustNoticeTests(unittest.TestCase):
    def test_selected_independent_root_has_runtime_and_build_not_dev_or_unrelated_graph(self):
        pkgs = [package(Path("/synthetic") / name, name) for name in ["adapter", "runtime", "build", "dev", "core"]]
        data = metadata(pkgs, {"adapter": [dependency("runtime", None), dependency("build", "build"), dependency("dev", "dev")]})
        self.assertEqual({p["name"] for p in graph(data, "adapter")}, {"adapter", "runtime", "build"})
        self.assertEqual([p["name"] for p in graph(data, "core")], ["core"])
        with self.assertRaises(ValueError): graph(data, "absent")
        with self.assertRaises(ValueError): graph(metadata(pkgs + [dict(pkgs[0], id="other-adapter")], {}), "adapter")

    def test_declared_custom_file_is_exact_and_missing_unknown_licenses_are_fatal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / "source"; source.mkdir()
            text = b"Synthetic authored integration permission. No third-party model grant.\n"
            (source / "terms.txt").write_bytes(text)
            p = package(source, "adapter", None, "terms.txt")
            rows = collect(metadata([p], {}), "adapter", "x86_64-linux-android", root / "notices")
            self.assertEqual(rows[0]["license"], "LicenseRef-Cargo-LicenseFile")
            self.assertEqual((root / "notices/adapter-1.0.0/terms.txt").read_bytes(), text)
            self.assertEqual(rows[0]["license_sha256"]["terms.txt"], hashlib.sha256(text).hexdigest())
            (source / "terms.txt").unlink()
            with self.assertRaises(ValueError): collect(metadata([p], {}), "adapter", "android", root / "missing-declared")
            (source / "SOURCE.txt").write_bytes(text)
            with self.assertRaises(ValueError): collect(metadata([dict(p, license_file="SOURCE.txt")], {}), "adapter", "android", root / "source-collision")
            unknown = dict(p, license_file=None)
            with self.assertRaises(ValueError): collect(metadata([unknown], {}), "adapter", "android", root / "no-license")
            with self.assertRaises(ValueError): collect(metadata([dict(unknown, license="MIT")], {}), "adapter", "android", root / "missing-text")

    def test_registry_cannot_use_core_exception_and_explicit_license_is_still_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / "LICENSE").write_text("Synthetic MIT text")
            p = package(root, "registry-dependency", None)
            with self.assertRaises(ValueError): collect(metadata([p], {}), p["name"], "android", root / "unknown")

    def test_registry_named_like_core_does_not_inherit_repository_license_exception(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            p = package(root, "zapime-core", "MIT")
            with self.assertRaises(ValueError): collect(metadata([p], {}), "zapime-core", "android", root / "notices")

    def test_notice_inventory_requires_every_exact_license_and_root(self):
        text = b"Synthetic license\n"
        rows = [{"name": "adapter", "version": "1.0.0", "license": "MIT", "license_files": ["LICENSE"], "license_sha256": {"LICENSE": hashlib.sha256(text).hexdigest()}}]
        def archive(payload=text, listed=rows, bad_hash=False):
            raw = json.dumps(listed).encode(); out = io.BytesIO()
            with zipfile.ZipFile(out, "w") as z:
                z.writestr("notices/inventory.json", raw)
                z.writestr("notices/collection.json", json.dumps({"schema_version": 1, "root_package": "adapter", "target_platform": "aarch64-linux-android", "packages": len(listed), "cargo_lock_sha256": "0" * 64, "inventory_sha256": "bad" if bad_hash else hashlib.sha256(raw).hexdigest()}))
                z.writestr("notices/adapter-1.0.0/LICENSE", payload)
            out.seek(0); return zipfile.ZipFile(out)
        with archive() as z: check_notice_inventory(z, "notices", "adapter", "aarch64-linux-android")
        with archive() as z, self.assertRaises(AssertionError): check_notice_inventory(z, "notices", "adapter", "x86_64-linux-android")
        for data in [archive(b"modified"), archive(b""), archive(bad_hash=True), archive(listed=[])]:
            with data, self.assertRaises(AssertionError): check_notice_inventory(data, "notices", "adapter")
        with archive() as z, self.assertRaises(AssertionError): check_notice_inventory(z, "notices", "other-root")
        invalid = [dict(rows[0], license_files=["../secret"], license_sha256={"../secret": "bad"})]
        with archive(listed=invalid) as z, self.assertRaises(AssertionError): check_notice_inventory(z, "notices", "adapter")


def synthetic_elf():
    raw = bytearray(120 + 4 * 64)
    raw[:6] = b"\x7fELF\x02\x01"; struct.pack_into("<Q", raw, 40, 120); struct.pack_into("<HH", raw, 58, 64, 4)
    struct.pack_into("<H", raw, 18, 183); struct.pack_into("<Q", raw, 32, 64); struct.pack_into("<HH", raw, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", raw, 64, 1, 5, 0, 0, 0, 0, 0, 16384)
    names = b"\0"; offsets = {}
    for name in sorted(VOICEKEY_JNI | {"libc.so", "libdl.so", "libm.so"}): offsets[name] = len(names); names += name.encode() + b"\0"
    def section(index, kind, payload, link=0, width=0):
        offset = len(raw); raw.extend(payload); struct.pack_into("<IIQQQQIIQQ", raw, 120 + index * 64, 0, kind, 0, 0, offset, len(payload), link, 0, 1, width)
    section(1, 3, names)
    symbols = bytes(24) + b"".join(struct.pack("<IBBHQQ", offsets[name], 0x12, 0, 1, 1, 0) for name in sorted(VOICEKEY_JNI))
    section(2, 11, symbols, 1, 24)
    dynamic = b"".join(struct.pack("<qQ", 1, offsets[name]) for name in ["libc.so", "libdl.so", "libm.so"]) + bytes(16)
    section(3, 6, dynamic, 1, 16)
    return raw


class VoiceKeyElfTests(unittest.TestCase):
    def test_dynamic_symbols_and_dependencies_read_without_execution(self):
        needed, exports = elf_metadata(synthetic_elf())
        self.assertEqual(needed, {"libc.so", "libdl.so", "libm.so"})
        self.assertEqual(exports, VOICEKEY_JNI)

    def test_native_gate_rejects_missing_jni_dependencies_wrong_abi_and_small_pages(self):
        original = synthetic_elf(); check_voicekey_native(original, "arm64-v8a")
        with self.assertRaises(ValueError): check_voicekey_native(original, "x86_64")
        raw = bytearray(original); struct.pack_into("<Q", raw, 112, 4096)
        with self.assertRaises(ValueError): check_voicekey_native(raw, "arm64-v8a")
        raw = bytearray(original); raw[raw.index(b"libm.so"):raw.index(b"libm.so")+7] = b"evil.so"
        with self.assertRaises(AssertionError): check_voicekey_native(raw, "arm64-v8a")
        raw = bytearray(original); symbols = struct.unpack_from("<Q", raw, 120 + 2*64 + 24)[0]
        struct.pack_into("<H", raw, symbols + 24 + 6, 0)
        with self.assertRaises(AssertionError): check_voicekey_native(raw, "arm64-v8a")

    def test_truncated_offsets_widths_and_string_links_are_rejected(self):
        original = synthetic_elf()
        malformed = [original[:63], original[:128]]
        for location, fmt, value in [(40, "<Q", 2**63), (58, "<H", 63), (60, "<H", 2049), (120 + 2*64 + 40, "<I", 99), (120 + 3*64 + 24, "<Q", 2**63), (120 + 2*64 + 56, "<Q", 23)]:
            raw = bytearray(original); struct.pack_into(fmt, raw, location, value); malformed.append(raw)
        for raw in malformed:
            with self.assertRaises((AssertionError, UnicodeDecodeError)): elf_metadata(raw)


if __name__ == "__main__": unittest.main()
