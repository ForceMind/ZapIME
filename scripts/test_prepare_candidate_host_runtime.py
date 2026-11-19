# SPDX-License-Identifier: GPL-3.0-only
import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

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

    def test_transport_selects_reviewed_url_without_changing_pins_or_cached_bytes(self):
        for transport in runtime.TRANSPORTS:
            with self.subTest(transport=transport), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                spec, members, payloads = self.archive(root)
                original = dict(spec)
                with patch.object(runtime, "ARCHIVE", spec), patch.object(runtime, "MEMBERS", members), \
                        patch.object(runtime, "download", wraps=runtime.download) as download, \
                        patch("prepare_candidate_model.OPENER.open") as network:
                    # A verified cache works offline under either explicit transport.
                    result = runtime.prepare(root, offline=True, transport=transport)
                    self.assertEqual(result.read_bytes(), payloads[result.name])
                    network.assert_not_called()
                    selected = download.call_args.args[0]
                    self.assertEqual(selected["url"], spec["url"] if transport == "official"
                                     else runtime.RECOVERY_TRANSPORT_URL)
                    self.assertEqual(selected["size"], original["size"])
                    self.assertEqual(selected["sha256"], original["sha256"])
                    self.assertEqual(spec, original)

    def test_404_from_selected_transport_never_switches_source(self):
        for transport in runtime.TRANSPORTS:
            with self.subTest(transport=transport), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                spec, members, _ = self.archive(root)
                archive = root / runtime.ARCHIVE_NAME
                archive.unlink()
                expected = spec["url"] if transport == "official" else runtime.RECOVERY_TRANSPORT_URL
                with patch.object(runtime, "ARCHIVE", spec), patch.object(runtime, "MEMBERS", members), \
                        patch("prepare_candidate_model.OPENER.open",
                              side_effect=HTTPError(expected, 404, "Not Found", {}, None)) as network:
                    with self.assertRaises(HTTPError):
                        runtime.prepare(root, transport=transport)
                    network.assert_called_once()
                    self.assertEqual(network.call_args.args[0], expected)
                self.assertFalse(archive.exists())
                self.assertFalse((root / "libonnxruntime.so.1.28.2").exists())

    def test_changed_recovery_bytes_never_replace_existing_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            spec, members, _ = self.archive(root)
            archive = root / runtime.ARCHIVE_NAME
            original = archive.read_bytes()
            archive.unlink()
            library = root / "libonnxruntime.so.1.28.2"
            library.write_bytes(b"existing")
            # The transport supplies the correct byte count but a wrong digest.
            changed = bytes([original[0] ^ 1]) + original[1:]
            with patch.object(runtime, "ARCHIVE", spec), patch.object(runtime, "MEMBERS", members), \
                    patch("prepare_candidate_model.OPENER.open", return_value=io.BytesIO(changed)) as network:
                with self.assertRaisesRegex(ValueError, "checksum/size mismatch"):
                    runtime.prepare(root, transport="npmmirror-recovery")
                network.assert_called_once()
            self.assertEqual(library.read_bytes(), b"existing")
            self.assertFalse(archive.exists())
            self.assertEqual(list(root.iterdir()), [library])

    def test_unknown_transport_rejected_before_output_creation(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "not-created"
            for transport in ("auto", "latest", "https://example.test/runtime.tgz", "", None):
                with self.subTest(transport=transport), patch("prepare_candidate_model.OPENER.open") as network:
                    with self.assertRaisesRegex(ValueError, "unsupported.*transport"):
                        runtime.prepare(output, transport=transport)
                    network.assert_not_called()
                    self.assertFalse(output.exists())

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
