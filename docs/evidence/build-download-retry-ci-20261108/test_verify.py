# SPDX-License-Identifier: GPL-3.0-only
"""Meaningful mutations of archived results, with a refreshed integrity manifest."""
from pathlib import Path
import hashlib
import json
import shutil
import tempfile
import unittest

import verify


def refresh(root):
    rows = []
    for path in sorted(root.rglob("*")):
        if path.is_file() and path != root / "SHA256SUMS" and "__pycache__" not in path.parts:
            rows.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.relative_to(root).as_posix())
    (root / "SHA256SUMS").write_text("\n".join(rows) + "\n")


class EvidenceTests(unittest.TestCase):
    def test_originals_verify_read_only(self):
        root = Path(__file__).resolve().parent
        before = {p: p.read_bytes() for p in root.rglob("*") if p.is_file()}
        self.assertTrue(verify.verify(root).startswith("PASS "))
        self.assertEqual(before, {p: p.read_bytes() for p in before})

    def test_semantic_mutations_rejected_even_after_manifest_refresh(self):
        changes = [
            ("download/strict-failure-summary.json", lambda d: d["failed_download_cases"][0].update(wire_get_count_observed=0), "wire_get_count_observed"),
            ("download/strict-failure-summary.json", lambda d: d["failed_download_cases"][0].update(top_level_attempts_inferred_from_fixed_code_and_retry_markers=4), "top_level_attempts"),
            ("download/strict-failure-summary.json", lambda d: d["downstream_missing_artifacts"][0].update(system_ime_voice_execution="PASS"), "no substituted"),
            ("download/strict-failure-summary.json", lambda d: d.update(unapproved_payload="synthetic"), "summary schema"),
            ("download/strict-failure-summary.json", lambda d: d["failed_download_cases"].append(d["failed_download_cases"][0]), "duplicate download"),
            ("download/source-metadata.json", lambda d: d.update(tree="0" * 40), "tree identity"),
            ("source-shared/summary.json", lambda d: d["jobs"][3]["first_failure"].update(product_compilation_reached=True), "no native compilation"),
            ("source-shared/summary.json", lambda d: d["jobs"][0]["source_release_tests"].update(passed=999), "reported source release counts"),
            ("source-shared/summary.json", lambda d: d["jobs"][0]["rust_workspace"].update(passed=999, ignored=99), "reported Rust"),
            ("download/strict-failure-summary.json", lambda d: d["failed_download_cases"][0].update(unit_test_run_counts=[float(n) for n in d["failed_download_cases"][0]["unit_test_run_counts"]]), "unit-test counts"),
        ]
        original = Path(__file__).resolve().parent
        for name, change, error in changes:
            with self.subTest(error=error), tempfile.TemporaryDirectory() as directory:
                root = Path(directory) / "evidence"
                shutil.copytree(original, root, ignore=shutil.ignore_patterns("__pycache__"))
                path = root / name
                data = json.loads(path.read_text())
                change(data)
                path.write_text(json.dumps(data))
                refresh(root)
                with self.assertRaisesRegex(ValueError, error):
                    verify.verify(root)

    def test_duplicate_json_fields_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.json"
            path.write_text('{"passed": true, "passed": false}')
            with self.assertRaisesRegex(ValueError, "duplicate JSON field"):
                verify.load(path)

    def test_changed_original_log_rejected_after_manifest_refresh(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "evidence"
            shutil.copytree(Path(__file__).resolve().parent, root, ignore=shutil.ignore_patterns("__pycache__"))
            log = root / "download/job-128687392403.log.gz"
            log.write_bytes(__import__("gzip").compress(b"synthetic replacement", mtime=0))
            refresh(root)
            with self.assertRaisesRegex(ValueError, "raw log length"):
                verify.verify(root)


if __name__ == "__main__":
    unittest.main()
