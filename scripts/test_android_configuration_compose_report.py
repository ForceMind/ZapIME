# SPDX-License-Identifier: GPL-3.0-only
import unittest
from unittest.mock import patch

from android_configuration_compose_report import verify_configuration_report
import android_candidate_ime_smoke


def complete():
    report = {"schema_version": 1, "scope": "production-system-ime-configuration-restart",
              "run_attempt": "42-1", "api_level": 35,
              "classification": "same_editor_composition_preserved",
              "before_connection_count": 2, "after_connection_count": 2,
              "before_command_connection": 1, "after_command_connection": 1,
              "before_restarts": 0, "after_restarts": 2,
              "before_setting": 1, "inverse_readback": 0, "trigger_restored_readback": 1,
              "insets_observations": 2, "touch_keypresses": 8, "context_seed_commits": 1,
              "context_seed_text": "你好", "before_text": "你好sh", "after_i_text": "你好shi",
              "selected_text": "是", "actual_editable": "你好是"}
    for field in ("passed", "synthetic", "setting_restored", "same_editor_continuity",
                  "framework_restart_observed", "service_trace_complete", "before_composing",
                  "after_i_composing", "candidate_touch", "context_seed_touch"):
        report[field] = True
    for field in ("input_finished_after_before", "editor_composing", "product_preferences_written",
                  "human_touch_or_phone_latency", "model_execution_claimed"):
        report[field] = False
    for field in ("visibility_hidden_observations", "window_focus_losses", "editor_focus_losses"):
        report[field] = 0
    return report


class ConfigurationReportTests(unittest.TestCase):
    def test_exact_fresh_same_connection_sequence(self):
        verify_configuration_report(complete(), "42-1", 35)

    def test_schema_version_rejects_boolean_float_and_string(self):
        for value in (True, False, 1.0, "1", None):
            changed = complete(); changed["schema_version"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                verify_configuration_report(changed, "42-1", 35)

    def test_interruption_never_counts_as_same_editor_reproduction(self):
        variants = [("classification", "interrupted_not_same_editor"),
                    ("classification", "same_editor_composition_failure"),
                    ("visibility_hidden_observations", 1), ("window_focus_losses", 1),
                    ("editor_focus_losses", 1), ("input_finished_after_before", True),
                    ("after_connection_count", 3), ("after_command_connection", 2),
                    ("service_trace_complete", False), ("same_editor_continuity", False)]
        for field, value in variants:
            with self.subTest(field=field):
                changed = complete(); changed[field] = value
                with self.assertRaises(ValueError):
                    verify_configuration_report(changed, "42-1", 35)

    def test_missing_or_false_actual_proofs_fail_closed(self):
        for field in complete():
            with self.subTest(field=field):
                changed = complete(); del changed[field]
                with self.assertRaises(ValueError):
                    verify_configuration_report(changed, "42-1", 35)
        for field, value in (("after_restarts", 0), ("before_setting", True),
                             ("inverse_readback", 1), ("trigger_restored_readback", 0),
                             ("insets_observations", 0), ("touch_keypresses", 9),
                             ("context_seed_commits", 2), ("after_i_text", "你好i"),
                             ("actual_editable", "你好sh是"), ("model_execution_claimed", True)):
            changed = complete(); changed[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_configuration_report(changed, "42-1", 35)

    def test_original_disposable_device_guard_precedes_any_mutation(self):
        with patch.object(android_candidate_ime_smoke.sys, "argv", ["probe", "adb", "serial"]), \
                patch.object(android_candidate_ime_smoke, "dedicated_ci", return_value=False), \
                patch.object(android_candidate_ime_smoke.subprocess, "run") as run:
            with self.assertRaises(SystemExit):
                android_candidate_ime_smoke.main(configuration_restart=True)
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
