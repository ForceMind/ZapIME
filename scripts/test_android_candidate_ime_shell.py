# SPDX-License-Identifier: GPL-3.0-only
"""Exercise settings restoration through a real local shell, without adb."""
import subprocess
import unittest

from android_candidate_ime_smoke import restore_setting_args


class CandidateImeShellTest(unittest.TestCase):
    def shell_arguments(self, name, value):
        arguments = restore_setting_args(name, value)
        self.assertEqual(arguments[0], "shell")
        self.assertEqual(len(arguments), 2)
        # This shell function only emits its arguments. It cannot reach a
        # device or alter settings, while retaining the real shell parser.
        script = 'settings() { printf "%s\\0" "$@"; }; ' + arguments[1]
        result = subprocess.run(["/bin/sh", "-c", script], check=True,
                                capture_output=True, timeout=5)
        self.assertEqual(result.stderr, b"")
        return result.stdout.split(b"\0")[:-1]

    def test_api35_enabled_imes_with_subtype_delimiter(self):
        value = ("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME;1594443099:"
                 "com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService")
        self.assertEqual(self.shell_arguments("enabled_input_methods", value),
                         [b"put", b"secure", b"enabled_input_methods", value.encode()])

    def test_metacharacters_remain_one_literal_setting_value(self):
        for value in ("one; printf unexpected", "single'quote", 'double"quote',
                      "$(printf unexpected)", "`printf unexpected`", "space and\nnewline", "back\\slash * ?"):
            with self.subTest(value=value):
                self.assertEqual(self.shell_arguments("enabled_input_methods", value),
                                 [b"put", b"secure", b"enabled_input_methods", value.encode()])

    def test_absent_setting_keeps_delete_semantics(self):
        for value in (None, "null", ""):
            with self.subTest(value=value):
                self.assertEqual(self.shell_arguments("show_ime_with_hard_keyboard", value),
                                 [b"delete", b"secure", b"show_ime_with_hard_keyboard"])

    def test_existing_simple_setting_keeps_exact_value(self):
        self.assertEqual(self.shell_arguments("show_ime_with_hard_keyboard", "0"),
                         [b"put", b"secure", b"show_ime_with_hard_keyboard", b"0"])


if __name__ == "__main__":
    unittest.main()
