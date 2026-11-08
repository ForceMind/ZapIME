# SPDX-License-Identifier: GPL-3.0-only
import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import prepare_candidate_host_runtime as runtime


class HostRuntimeTests(unittest.TestCase):
    def archive(self, directory, *, duplicate=False, linked=False, wrong_size=False, wrong_bytes=False):
        payloads = {"libonnxruntime.so.1.28.2": b"synthetic runtime", "LICENSE.txt": b"synthetic MIT"}
        members = {name: dict(member="reviewed/" + name, size=len(data),
                              sha256=hashlib.sha256(data).hexdigest()) for name, data in payloads.items()}
        archive = directory / runtime.ARCHIVE_NAME
        with tarfile.open(archive, "w:gz") as output:
            for name, data in payloads.items():
                info = tarfile.TarInfo(members[name]["member"])
                info.size = len(data)
                if linked:
                    info.type, info.linkname, info.size = tarfile.SYMTYPE, "../../outside", 0
                output.addfile(info, io.BytesIO(b"x" * len(data) if wrong_bytes else data))
                if duplicate:
                    output.addfile(info, io.BytesIO(data))
        if wrong_size:
            members["LICENSE.txt"]["size"] += 1
        spec = dict(url=runtime.ARCHIVE["url"], size=archive.stat().st_size,
                    sha256=hashlib.sha256(archive.read_bytes()).hexdigest())
        return spec, members, payloads

    def test_valid_cached_archive_extracts_only_named_verified_regular_members(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            spec, members, payloads = self.archive(root)
            with patch.object(runtime, "ARCHIVE", spec), patch.object(runtime, "MEMBERS", members):
                result = runtime.prepare(root, offline=True)
                self.assertEqual(result.read_bytes(), payloads[result.name])
                self.assertEqual((root / "LICENSE.txt").read_bytes(), payloads["LICENSE.txt"])
                # A damaged extracted copy is repaired from the fixed cache.
                result.write_bytes(b"broken")
                runtime.prepare(root, offline=True)
                self.assertEqual(result.read_bytes(), payloads[result.name])
            self.assertEqual({p.name for p in root.iterdir()},
                             {runtime.ARCHIVE_NAME, "libonnxruntime.so.1.28.2", "LICENSE.txt"})

    def test_bad_archive_digest_preserves_existing_host_library(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            spec, members, _ = self.archive(root)
            library = root / "libonnxruntime.so.1.28.2"
            library.write_bytes(b"existing")
            (root / runtime.ARCHIVE_NAME).write_bytes(b"bad")
            with patch.object(runtime, "ARCHIVE", spec), patch.object(runtime, "MEMBERS", members):
                with self.assertRaises(ValueError):
                    runtime.prepare(root, offline=True)
            self.assertEqual(library.read_bytes(), b"existing")

    def test_links_duplicates_sizes_and_member_hash_mismatches_fail_closed(self):
        for options in (dict(linked=True), dict(duplicate=True), dict(wrong_size=True), dict(wrong_bytes=True)):
            with self.subTest(options=options), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                spec, members, _ = self.archive(root, **options)
                with patch.object(runtime, "ARCHIVE", spec), patch.object(runtime, "MEMBERS", members):
                    with self.assertRaises(ValueError):
                        runtime.prepare(root, offline=True)
                self.assertFalse((root / "libonnxruntime.so.1.28.2").exists())

    def test_linked_destination_and_linked_member_output_never_touch_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output, linked = root / "output", root / "linked"
            output.mkdir()
            linked.symlink_to(output, target_is_directory=True)
            with self.assertRaises(ValueError):
                runtime.prepare(linked, offline=True)
            spec, members, _ = self.archive(output)
            target = root / "outside"
            target.write_bytes(b"untouched")
            (output / "libonnxruntime.so.1.28.2").symlink_to(target)
            with patch.object(runtime, "ARCHIVE", spec), patch.object(runtime, "MEMBERS", members):
                with self.assertRaises(ValueError):
                    runtime.prepare(output, offline=True)
            self.assertEqual(target.read_bytes(), b"untouched")


if __name__ == "__main__":
    unittest.main()
