#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Small independent boundary checks, not a translation-quality test."""
import os
import unittest

from preprocessing import ArchivePreprocessing


class NormalizationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.normalize = ArchivePreprocessing()

    def test_punctuation_and_time(self):
        self.assertEqual(self.normalize("她说：“明天见！”"), '她说:"明天见!"')
        self.assertEqual(self.normalize("请在下午３：３０之前回复。"), "请在下午3:30之前回复.")

    def test_original_fullwidth_one_bug_is_not_silently_corrected(self):
        self.assertEqual(self.normalize("１２０％"), '"20%')

    def test_ascii_spacing_and_category_c(self):
        self.assertEqual(self.normalize("  我   已经\t在路上了。\u200b"), "我 已经 在路上了.")

    def test_emoji_zwj_limitation_is_visible(self):
        self.assertEqual(self.normalize("一家人👨‍👩‍👧‍👦"), "一家人👨 👩 👧 👦")

    def test_perl_configuration_not_inherited(self):
        old = os.environ.get("PERL5OPT")
        try:
            os.environ["PERL5OPT"] = "-MZAPIME_MODULE_THAT_DOES_NOT_EXIST"
            self.assertEqual(self.normalize("中文。"), "中文.")
        finally:
            if old is None:
                os.environ.pop("PERL5OPT", None)
            else:
                os.environ["PERL5OPT"] = old

    def test_bounded_single_line(self):
        for text in ("第一行\n第二行", "回车\r", "字" * 4097):
            with self.assertRaises(ValueError):
                self.normalize(text)


if __name__ == "__main__":
    unittest.main()
