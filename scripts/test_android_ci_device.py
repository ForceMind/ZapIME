# SPDX-License-Identifier: GPL-3.0-only
import unittest
import tempfile
from pathlib import Path
from types import SimpleNamespace
import subprocess
from unittest.mock import patch
from android_ci_device import system_keyboard, collect_failure_state, assert_no_app_anr, diagnostic_args


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


class DiagnosticPrivacyTests(unittest.TestCase):
    ci = {"GITHUB_ACTIONS": "true", "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"}

    def test_diagnostics_never_read_global_logs_from_local_or_physical_devices(self):
        with tempfile.TemporaryDirectory() as directory, patch("android_ci_device.subprocess.run") as run:
            for environment, serial in [({}, "emulator-5554"), (self.ci, "real-phone"), ({"GITHUB_ACTIONS": "true"}, "emulator-5554")]:
                with patch.dict("os.environ", environment, clear=True):
                    collect_failure_state(["adb", "-s", serial], Path(directory))
                    assert_no_app_anr(["adb", "-s", serial], Path(directory))
                    self.assertEqual(diagnostic_args(["adb", "-s", serial]), [])
            run.assert_not_called()
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_product_and_test_host_anr_are_fatal_but_launcher_is_distinct(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict("os.environ", self.ci, clear=True):
            for process in ["org.zapime.android", "org.zapime.android.test"]:
                with patch("android_ci_device.subprocess.run", return_value=SimpleNamespace(stdout="ANR in " + process + " (Activity)\n")):
                    with self.assertRaises(SystemExit):
                        assert_no_app_anr(["adb", "-s", "emulator-5554"], Path(directory))
            with patch("android_ci_device.subprocess.run", return_value=SimpleNamespace(stdout="ANR in com.google.android.apps.nexuslauncher\n")):
                assert_no_app_anr(["adb", "-s", "emulator-5554"], Path(directory))

    def test_failure_diagnostics_remain_bounded_and_do_not_hide_a_timeout(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict("os.environ", self.ci, clear=True):
            responses = [subprocess.TimeoutExpired("dumpsys", 10)] + [SimpleNamespace(stdout="x" * (300 * 1024), stderr="") for _ in range(4)]
            with patch("android_ci_device.subprocess.run", side_effect=responses):
                collect_failure_state(["adb", "-s", "emulator-5554"], Path(directory))
            files = list(Path(directory).iterdir())
            self.assertEqual(len(files), 5)
            self.assertTrue(all(p.stat().st_size <= 256 * 1024 for p in files))
            self.assertIn("TimeoutExpired", (Path(directory) / "activity-top.txt").read_text())


if __name__ == "__main__":
    unittest.main()
