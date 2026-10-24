#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Requires the explicitly prepared, hash-verified local research assets."""
from pathlib import Path
import shutil
import tempfile
import unittest

from prepare_preprocessing import ROOT, verify_helpers
from preprocessing import OpusSimple, archive_postprocess


class PreprocessingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.normalize = OpusSimple(ROOT)

    def test_punctuation_and_full_width_numbers(self):
        self.assertEqual(self.normalize('  她说：“明天见！”  ３：３０，２５％  '), '她说:"明天见!" 3:30,25%')

    def test_control_characters_are_spaces_not_silently_concatenated(self):
        self.assertEqual(self.normalize("我已经\t在路上了。\u200b"), "我已经 在路上了.")

    def test_joiner_removal_is_visible_research_limitation(self):
        # This is upstream preprocessing behavior, not product text eligibility.
        self.assertEqual(self.normalize("家👨‍👩‍👧‍👦。"), "家👨 👩 👧 👦.")

    def test_only_ascii_spaces_are_collapsed(self):
        self.assertEqual(self.normalize("\u00a0我  已经\u00a0"), "\u00a0我 已经\u00a0")

    def test_one_line_boundary(self):
        for text in ("你好\n再见", "你好\r再见"):
            with self.assertRaises(ValueError):
                self.normalize(text)

    def test_changed_external_helper_refused(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            shutil.copytree(ROOT / "moses", root / "moses")
            (root / "moses" / "replace-unicode-punctuation.perl").write_text("exit 0;\n")
            with self.assertRaisesRegex(RuntimeError, "reviewed upstream"):
                verify_helpers(root)

    def test_archive_postprocess_retains_dummy_prefix_and_unknown(self):
        self.assertEqual(archive_postprocess(["▁Hello", "▁world", "!", "<unk>"]), " Hello world!<unk>")
        self.assertEqual(archive_postprocess(["▁A B"]), " AB")


if __name__ == "__main__":
    unittest.main()
