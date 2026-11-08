# SPDX-License-Identifier: GPL-3.0-only
"""Invented report values validate rejection only, never model/device evidence."""
import copy
import unittest

from android_candidate_ime_smoke import MODEL, verify_report


class ContextGuardReportTests(unittest.TestCase):
    def report(self):
        report = dict(schema_version=2, scope="production-system-ime-candidate-ranking",
                      run_attempt="123-1", api_level=35, model_id=MODEL,
                      passed=True, synthetic=True, system_ime_executed=True, production_lexicon=True,
                      production_model=True, ranking_enabled=True, ranking_eligible=True,
                      candidate_touch=True, current_ranking_applied=True,
                      empty_context_base_order=True, context_seed_touch=True,
                      product_preferences_written=False, human_touch_or_phone_latency=False,
                      editor_composing=False, scored_before=0, scored_after=1,
                      scoring_input_revision=12, scoring_applied_revision=12,
                      context_seed_text="你好", context_seed_commits=1, selected_text="世界", actual_editable="你好世界",
                      submitted_before=0, submitted_after_empty=0, submitted_after=2,
                      visible_han_candidates=2, touch_keypresses=11, observed_connection_serial=1,
                      attempts=[dict(code="shijie", attempt=1, scored_before=0, scored_after=1,
                                     input_revision=12, applied_revision=12, state="ready",
                                     scored_increased=True, current_ranking_applied=True)])
        return report

    def test_context_and_no_context_proofs_required(self):
        verify_report(self.report(), "123-1", 35)
        mutations = [dict(schema_version=1), dict(empty_context_base_order=False),
                     dict(context_seed_touch=False), dict(submitted_after_empty=1),
                     dict(submitted_after=0), dict(submitted_before=True),
                     dict(context_seed_text=""), dict(actual_editable="世界"),
                     dict(touch_keypresses=6), dict(scored_after=0)]
        for values in mutations:
            report = self.report()
            report.update(values)
            with self.subTest(values=values), self.assertRaises(ValueError):
                verify_report(report, "123-1", 35)

    def test_second_attempt_keeps_all_first_attempt_evidence(self):
        report = self.report()
        failed = copy.deepcopy(report["attempts"][0])
        failed.update(scored_after=0, applied_revision=-1, state="expired",
                      scored_increased=False, current_ranking_applied=False)
        second = copy.deepcopy(report["attempts"][0])
        second.update(code="tianqi", attempt=2)
        report.update(attempts=[failed, second], touch_keypresses=28, context_seed_commits=2,
                      context_seed_text="你好你好", actual_editable="你好你好世界")
        verify_report(report, "123-1", 35)
        report["attempts"][0]["current_ranking_applied"] = True
        with self.assertRaises(ValueError):
            verify_report(report, "123-1", 35)


if __name__ == "__main__":
    unittest.main()
