# SPDX-License-Identifier: GPL-3.0-only
"""Synthetic integrity tests; these are not model inference or Android acceptance."""
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_candidate_model as prepare


def spec(data, **extra):
    return {"size": len(data), "sha256": hashlib.sha256(data).hexdigest(), **extra}


class CandidateResourceTest(unittest.TestCase):
    def test_checked_in_catalog_and_license_files_match_reviewed_lock(self):
        lock = prepare.load_lock()
        prepare.verify_notices(lock)
        self.assertEqual(prepare.CATALOG.read_text(encoding="utf-8"), prepare.generate_catalog(lock))
        self.assertEqual(set(lock["files"]), prepare.MODEL_FILES | prepare.NOTICE_FILES)

    def test_any_resource_or_notice_change_changes_install_revision(self):
        lock = prepare.load_lock()
        previous = prepare.revision(lock)
        for name in lock["files"]:
            changed = copy.deepcopy(lock)
            changed["files"][name]["sha256"] = "0" * 64
            with self.subTest(name=name):
                self.assertNotEqual(previous, prepare.revision(changed))
        reordered = copy.deepcopy(lock)
        reordered["files"] = dict(reversed(list(lock["files"].items())))
        self.assertEqual(previous, prepare.revision(reordered))

    def test_unreviewed_urls_file_sets_and_path_escape_rejected(self):
        lock = prepare.load_lock()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "lock.json"
            for mutation in ("http", "alternate_host", "escape", "missing_notice", "missing_header"):
                changed = copy.deepcopy(lock)
                if mutation == "http":
                    changed["files"]["lm_step.ort"]["url"] = "http://github.com/model"
                if mutation == "alternate_host":
                    changed["files"]["lm_step.ort"]["url"] = "https://example.invalid/model"
                if mutation == "escape":
                    changed["files"]["NOTICE.txt"]["source"] = "third_party/licenses/../secret"
                if mutation == "missing_notice":
                    del changed["files"]["NOTICE.txt"]
                if mutation == "missing_header":
                    del changed["build_headers"]["files"]["onnxruntime_error_code.h"]
                path.write_text(json.dumps(changed), encoding="utf-8")
                with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                    prepare.load_lock(path)

    def test_invalid_sizes_names_and_hashes_rejected(self):
        good = spec(b"synthetic integrity bytes")
        for name, overrides in (("../escape", {}), ("..", {}), ("model", {"size": True}),
                                ("model", {"size": -1}), ("model", {"size": 16 * 1024 * 1024 + 1}),
                                ("model", {"sha256": "f" * 63})):
            with self.subTest(name=name, overrides=overrides), self.assertRaises(ValueError):
                prepare.valid_spec(name, {**good, **overrides})

    def test_corrupt_truncated_or_oversized_download_never_replaces_previous_file(self):
        raw = b"synthetic known model bytes"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model"
            for content in (raw[:-1], raw + b"?", b"?" + raw[1:]):
                path.write_bytes(b"previous file remains until verification")
                with self.subTest(length=len(content)), self.assertRaises(ValueError):
                    prepare.publish(io.BytesIO(content), path, spec(raw))
                self.assertEqual(path.read_bytes(), b"previous file remains until verification")
                self.assertEqual([p.name for p in Path(directory).iterdir()], ["model"])
            prepare.publish(io.BytesIO(raw), path, spec(raw))
            self.assertEqual(path.read_bytes(), raw)

    def test_stream_failure_removes_stage_and_preserves_previous_file(self):
        class BrokenStream:
            def read(self, _size):
                raise OSError("synthetic interrupted transfer")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model"
            path.write_bytes(b"old")
            with self.assertRaises(OSError):
                prepare.publish(BrokenStream(), path, spec(b"new"))
            self.assertEqual(path.read_bytes(), b"old")
            self.assertEqual([p.name for p in Path(directory).iterdir()], ["model"])

    def test_offline_cache_hit_and_miss_never_connect(self):
        raw = b"synthetic pinned resource"
        with tempfile.TemporaryDirectory() as directory, patch.object(prepare.OPENER, "open") as network:
            path = Path(directory) / "model"
            row = spec(raw, url="https://example.invalid/resource")
            path.write_bytes(raw)
            prepare.download(row, path, offline=True)
            path.write_bytes(b"broken")
            with self.assertRaises(ValueError):
                prepare.download(row, path, offline=True)
            self.assertEqual(path.read_bytes(), b"broken")
            network.assert_not_called()

    def test_https_downgrade_redirect_rejected(self):
        with self.assertRaises(ValueError):
            prepare.HttpsRedirect().redirect_request(None, None, 302, "", {}, "http://example.invalid/model")

    def test_symlink_file_and_parent_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            real = root / "real"
            real.mkdir()
            target = real / "model"
            target.write_bytes(b"untouched")
            link = root / "model-link"
            link.symlink_to(target)
            parent = root / "parent-link"
            parent.symlink_to(real, target_is_directory=True)
            for path in (link, parent / "model"):
                with self.subTest(path=path.name), self.assertRaises(ValueError):
                    prepare.publish(io.BytesIO(b"new"), path, spec(b"new"))
            self.assertEqual(target.read_bytes(), b"untouched")
            self.assertFalse(prepare.verified(link, spec(b"untouched")))

    def test_failed_later_member_keeps_whole_previous_asset_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "source", root / "output"
            source.mkdir()
            output.mkdir()
            (source / "a").write_bytes(b"new-a")
            (source / "b").write_bytes(b"bad-b")
            (output / "a").write_bytes(b"old-a")
            (output / "b").write_bytes(b"old-b")
            with self.assertRaises(ValueError):
                prepare.publish_directory(source, output, {"a": spec(b"new-a"), "b": spec(b"new-b")})
            self.assertEqual((output / "a").read_bytes(), b"old-a")
            self.assertEqual((output / "b").read_bytes(), b"old-b")
            self.assertEqual({p.name for p in root.iterdir()}, {"source", "output"})

    def test_failed_directory_publish_restores_previous_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "source", root / "output"
            source.mkdir()
            output.mkdir()
            (source / "model").write_bytes(b"new")
            (output / "model").write_bytes(b"old")
            original_replace = Path.replace

            def fail_stage(path, target):
                if path.name.startswith(".candidate-stage-"):
                    raise OSError("synthetic publication failure")
                return original_replace(path, target)

            with patch.object(Path, "replace", fail_stage), self.assertRaises(OSError):
                prepare.publish_directory(source, output, {"model": spec(b"new")})
            self.assertEqual((output / "model").read_bytes(), b"old")
            self.assertEqual({p.name for p in root.iterdir()}, {"source", "output"})

    def test_successful_complete_publish_is_idempotent_and_rejects_unrelated_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "source", root / "output"
            source.mkdir()
            (source / "model").write_bytes(b"complete")
            pins = {"model": spec(b"complete")}
            prepare.publish_directory(source, output, pins)
            self.assertTrue(prepare.verified_directory(output, pins))
            before = (output / "model").stat().st_mtime_ns
            prepare.publish_directory(source, output, pins)
            self.assertEqual(before, (output / "model").stat().st_mtime_ns)
            (output / "unrelated").write_text("do not remove", encoding="utf-8")
            with self.assertRaises(ValueError):
                prepare.publish_directory(source, output, pins)
            self.assertTrue((output / "unrelated").is_file())

    def test_publish_and_rollback_failure_keep_previous_resource_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "source", root / "output"
            source.mkdir()
            output.mkdir()
            (source / "model").write_bytes(b"new")
            (output / "model").write_bytes(b"old")
            original_replace = Path.replace

            def fail_publish_and_restore(path, target):
                if path.name.startswith((".candidate-stage-", ".candidate-previous-")):
                    raise OSError("synthetic publish and rollback failure")
                return original_replace(path, target)

            with patch.object(Path, "replace", fail_publish_and_restore), self.assertRaises(OSError):
                prepare.publish_directory(source, output, {"model": spec(b"new")})
            backups = list(root.glob(".candidate-previous-*"))
            self.assertEqual(len(backups), 1)
            self.assertEqual((backups[0] / "model").read_bytes(), b"old")

    def test_model_interface_and_attribution_are_validated(self):
        attribution = "synthetic metadata fixture; never used for inference"
        expected = {"layers": 6, "heads": 4, "head_dim": 64, "width": 256,
                    "max_positions": 256, "context_chars": 64, "bos": 2, "unk": 1,
                    "vocab_size": 4, "attribution_sha256": hashlib.sha256(attribution.encode()).hexdigest()}
        metadata = {key: value for key, value in expected.items() if key not in {"vocab_size", "attribution_sha256"}}
        metadata.update(self_normalized=True, license="Apache-2.0", attribution=attribution,
                        config={"vocab": 4, "n_layer": 6, "n_head": 4, "n_embd": 256,
                                "max_positions": 256, "self_normalized": True, "gelu_tanh": True})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "lm_step.ort").write_bytes(b"\x14\x00\x00\x00ORTMsynthetic-header-only")
            (root / "vocab.json").write_text(json.dumps({"tokens": ["<pad>", "<unk>", "<bos>", "好"]}))
            for key, value in ((None, None), ("layers", True), ("context_chars", 256),
                               ("self_normalized", False), ("license", "unknown"), ("attribution", "altered")):
                changed = copy.deepcopy(metadata)
                if key is not None:
                    changed[key] = value
                (root / "model.json").write_text(json.dumps(changed))
                with self.subTest(key=key):
                    if key is None:
                        prepare.verify_model(root, {"model": expected})
                    else:
                        with self.assertRaises(ValueError):
                            prepare.verify_model(root, {"model": expected})
            (root / "model.json").write_text(json.dumps(metadata))
            (root / "vocab.json").write_text(json.dumps({"tokens": ["<pad>", "<unk>", "<bos>", "<bos>"]}))
            with self.assertRaises(ValueError):
                prepare.verify_model(root, {"model": expected})


if __name__ == "__main__":
    unittest.main()
