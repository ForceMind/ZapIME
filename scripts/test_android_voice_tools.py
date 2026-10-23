# SPDX-License-Identifier: GPL-3.0-only
import hashlib
import io
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build_voice_model
from prepare_android_voice import publish, verified, verify_elf


def spec(data):
    return {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


class VoiceDependencyTests(unittest.TestCase):
    def test_bad_members_never_replace_the_previous_verified_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "member"
            destination.write_bytes(b"old")
            for payload in (b"ab", b"abcd", b"bad"):
                with self.assertRaises(ValueError):
                    publish(io.BytesIO(payload), destination, spec(b"abc"))
                self.assertEqual(destination.read_bytes(), b"old")
                self.assertEqual(list(Path(tmp).iterdir()), [destination])
            publish(io.BytesIO(b"abc"), destination, spec(b"abc"))
            self.assertTrue(verified(destination, spec(b"abc")))

    def test_dependency_symlinks_are_not_accepted_as_verified_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "real"
            target.write_bytes(b"abc")
            link = Path(tmp) / "link"
            link.symlink_to(target)
            self.assertFalse(verified(link, spec(b"abc")))

    def test_elf_boundary_rejects_wrong_architecture_small_pages_and_truncation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "library.so"
            image = bytearray(120)
            image[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<H", image, 18, 183)
            struct.pack_into("<Q", image, 32, 64)
            struct.pack_into("<HH", image, 54, 56, 1)
            struct.pack_into("<I", image, 64, 1)
            struct.pack_into("<Q", image, 112, 16384)
            path.write_bytes(image)
            verify_elf(path, "arm64-v8a")
            with self.assertRaises(ValueError):
                verify_elf(path, "x86_64")
            for alignment in (0, 4096, 24576):
                bad = bytearray(image)
                struct.pack_into("<Q", bad, 112, alignment)
                path.write_bytes(bad)
                with self.assertRaises(ValueError):
                    verify_elf(path, "arm64-v8a")
            path.write_bytes(image[:80])
            with self.assertRaises(ValueError):
                verify_elf(path, "arm64-v8a")

    def test_model_bundle_is_reproducible_and_hash_failure_preserves_old_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            license_path = root / "third_party/licenses/X-ASR-Apache-2.0.txt"
            license_path.parent.mkdir(parents=True)
            license_path.write_bytes(b"synthetic license")
            payloads = {"encoder.int8.onnx": b"synthetic encoder", "decoder.onnx": b"decoder",
                        "joiner.int8.onnx": b"joiner", "tokens.txt": b"tokens",
                        "LICENSE.txt": b"synthetic license",
                        "NOTICE.txt": b"X-ASR by Gilgamesh-J/X-ASR; ONNX conversion by k2-fsa/sherpa-onnx. Apache-2.0.\n"}
            config = {"files": {name: spec(data) for name, data in payloads.items()}}

            def prepare(directory):
                directory.mkdir(parents=True, exist_ok=True)
                for name, data in payloads.items():
                    (directory / name).write_bytes(data)

            first, second = root / "first.zip", root / "second.zip"
            with patch.object(build_voice_model, "ROOT", root), patch.object(build_voice_model, "model_config", return_value=config), patch.object(build_voice_model.voice_probe, "prepare", side_effect=prepare):
                build_voice_model.build(first)
                build_voice_model.build(second)
                self.assertEqual(first.read_bytes(), second.read_bytes())
                with zipfile.ZipFile(first) as archive:
                    self.assertEqual(archive.namelist(), sorted(payloads))
                    for name, data in payloads.items():
                        self.assertEqual(archive.read(name), data)
                        self.assertEqual(archive.getinfo(name).date_time, (2026, 6, 5, 0, 0, 0))
                old = first.read_bytes()
                config["files"]["encoder.int8.onnx"] = spec(b"different")
                with self.assertRaises(ValueError):
                    build_voice_model.build(first)
                self.assertEqual(first.read_bytes(), old)
                self.assertFalse(first.with_suffix(".zip.tmp").exists())


if __name__ == "__main__":
    unittest.main()
