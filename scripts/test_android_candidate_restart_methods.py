# SPDX-License-Identifier: GPL-3.0-only
import copy
import unittest
from android_candidate_ranking_smoke import verify_restart_methods


class RestartMethodReportTests(unittest.TestCase):
    def test_actual_editable_cases_cannot_be_omitted_or_replaced(self):
        report = {"synthetic": True, "api_level": 35, "cases": [
            {"id": name, "passed": True} for name in (
                "commit_finish_reentry", "compose_finish_reentry", "begin_batch_finish_guard", "delete_getter_finish_guard",
                "ordinary_begin_finish_reentry", "ordinary_region_finish_reentry", "ordinary_end_finish_reentry")]}
        verify_restart_methods(report, 35)
        for field, value in (("synthetic", False), ("api_level", 36), ("cases", [])):
            changed = copy.deepcopy(report); changed[field] = value
            with self.assertRaises(ValueError): verify_restart_methods(changed, 35)
        for index in range(len(report["cases"])):
            changed = copy.deepcopy(report); del changed["cases"][index]
            with self.subTest(omitted=report["cases"][index]["id"]), self.assertRaises(ValueError):
                verify_restart_methods(changed, 35)
        for mode in ("failure", "duplicate", "unknown"):
            changed = copy.deepcopy(report)
            if mode == "failure": changed["cases"][0]["passed"] = False
            else: changed["cases"][0]["id"] = "unknown" if mode == "unknown" else changed["cases"][1]["id"]
            with self.assertRaises(ValueError): verify_restart_methods(changed, 35)


if __name__ == "__main__": unittest.main()
