# SPDX-License-Identifier: GPL-3.0-only
import unittest
from unittest.mock import patch
from android_ci_device import system_keyboard


class SystemKeyboardTests(unittest.TestCase):
    def select(self, api, methods, packages):
        with patch("android_ci_device.subprocess.check_output", side_effect=[str(api), methods, packages]):
            return system_keyboard(["adb", "-s", "emulator-5554"])

    def test_api28_keeps_verified_aosp_requirement(self):
        chosen, proof = self.select(28, "com.android.inputmethod.latin/.LatinIME\n", "package:com.android.inputmethod.latin\n")
        self.assertEqual(chosen, "com.android.inputmethod.latin/.LatinIME")
        self.assertTrue(proof["system_package_verified"])
        with self.assertRaises(SystemExit):
            self.select(28, "com.google.android.inputmethod.latin/.LatinIME\n", "package:com.google.android.inputmethod.latin\n")

    def test_modern_uses_verified_preinstalled_google_when_aosp_absent(self):
        chosen, proof = self.select(35, "com.google.android.inputmethod.latin/.LatinIME\norg.zapime.android/.ZapInputMethodService\n", "package:com.google.android.inputmethod.latin\npackage:org.zapime.android\n")
        self.assertEqual(chosen, "com.google.android.inputmethod.latin/.LatinIME")
        self.assertFalse(proof["aosp"])
        self.assertEqual(proof["api_level"], 35)

    def test_modern_prefers_aosp_and_excludes_unverified_namespace(self):
        chosen, _ = self.select(36, "com.android.fake/.IME\ncom.google.android.inputmethod.latin/.LatinIME\ncom.android.inputmethod.latin/.LatinIME\n", "package:com.google.android.inputmethod.latin\npackage:com.android.inputmethod.latin\n")
        self.assertEqual(chosen, "com.android.inputmethod.latin/.LatinIME")

    def test_non_system_or_zapime_cannot_satisfy_teardown(self):
        with self.assertRaises(SystemExit):
            self.select(36, "com.android.fake/.IME\norg.zapime.android/.ZapInputMethodService\n", "package:org.zapime.android\n")


if __name__ == "__main__":
    unittest.main()
