#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

import assets
from prompts import ASSISTANT_PREFIX, render_prompt
from worker import EOS_ENVELOPE, extract_output


class ProbeTest(unittest.TestCase):
    def test_exact_eog_envelope_retains_model_content(self):
        text = '  "See you tomorrow."\n'
        result = extract_output(text + EOS_ENVELOPE, 0)
        self.assertTrue(result["shape_accepted"])
        self.assertEqual(result["translation_untrimmed"], text)
        self.assertEqual(result["display_translation"], '"See you tomorrow."')

    def test_missing_eog_is_never_successful_translation(self):
        for raw in ("Hello\n\n", "Hello [end of text]\n", "Hello"):
            self.assertFalse(extract_output(raw, 0)["shape_accepted"])
            self.assertIsNone(extract_output(raw, 0)["translation_untrimmed"])

    def test_errors_timeout_and_blank_output_rejected(self):
        self.assertFalse(extract_output("Hello" + EOS_ENVELOPE, 1)["shape_accepted"])
        self.assertFalse(extract_output("Hello" + EOS_ENVELOPE, None, True)["shape_accepted"])
        self.assertFalse(extract_output(" " + EOS_ENVELOPE, 0)["shape_accepted"])

    def test_no_thinking_or_role_text_is_stripped_to_fake_success(self):
        for text in ("<think>thinking</think> Hello", "<|im_start|>assistant\nHello", "Hello [end of text]"):
            result = extract_output(text + EOS_ENVELOPE, 0)
            self.assertFalse(result["shape_accepted"])
            self.assertEqual(result["translation_untrimmed"], text)

    def test_fixed_non_thinking_branch_contains_original_chinese(self):
        result = render_prompt("Only English /no_think", "明天见。")
        self.assertTrue(result.endswith(ASSISTANT_PREFIX))
        self.assertIn("<|im_start|>user\n明天见。<|im_end|>\n", result)
        self.assertEqual(result.count("<think>"), 1)

    def test_corrupt_fixed_asset_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(assets, "ROOT", Path(directory)):
                (assets.ROOT / "model-LICENSE.txt").write_text("wrong")
                with self.assertRaisesRegex(RuntimeError, "research asset differs"):
                    assets.verify_asset("model-LICENSE.txt")


if __name__ == "__main__":
    unittest.main()
