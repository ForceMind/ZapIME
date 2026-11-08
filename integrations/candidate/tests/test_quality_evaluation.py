# SPDX-License-Identifier: GPL-3.0-only
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from quality_evaluation import apply_order, distribution, load_corpus, CORPUS, observation, selection_cost, evaluate


class CoreResult:
    def __init__(self, result):
        self.result = result
        self.requested = None

    def request(self, handle, request):
        self.requested = request
        return self.result


class QualityTests(unittest.TestCase):
    def snapshots(self):
        original = dict(generation=2, composition_revision=4, preedit="synthetic",
                        candidate_ids=["fixed", "a", "b"], candidates=["甲", "乙", "丙"],
                        ranking=dict(candidates=[dict(id="a", text="乙"), dict(id="b", text="丙")]))
        ranked = copy.deepcopy(original)
        ranked.update(generation=3, candidate_ids=["fixed", "b", "a"], candidates=["甲", "丙", "乙"])
        ranked["ranking"]["applied"] = True
        return original, ranked

    def test_frozen_corpus_identity_and_unique_inputs(self):
        self.assertEqual(len(load_corpus(CORPUS)["cases"]), 34)
        with tempfile.TemporaryDirectory() as root:
            changed = Path(root) / "changed.json"
            changed.write_bytes(CORPUS.read_bytes() + b" ")
            with self.assertRaises(ValueError):
                load_corpus(changed)

    def test_permutation_applies_via_real_core_boundary_preserving_fixed_slot(self):
        original, ranked = self.snapshots()
        core = CoreResult(ranked)
        self.assertEqual(apply_order(core, 1, original, dict(scores=[-2., -1.])), ranked)
        self.assertEqual(core.requested["ids"], ["b", "a"])

    def test_nonfinite_and_changed_identity_are_rejected(self):
        original, ranked = self.snapshots()
        for scores in ([float("nan"), 1], [0, float("inf")], [1], [True, 2]):
            with self.subTest(scores=scores), self.assertRaises(ValueError):
                apply_order(CoreResult(ranked), 1, original, dict(scores=scores))
        for field, value in (("composition_revision", 5), ("preedit", "changed"),
                             ("candidate_ids", ["b", "fixed", "a"]),
                             ("candidate_ids", ["fixed", "b", "b"]),
                             ("candidates", ["甲", "丁", "乙"])):
            changed = copy.deepcopy(ranked)
            changed[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                apply_order(CoreResult(changed), 1, original, dict(scores=[-2., -1.]))

    def test_equal_scores_keep_original_ids_and_missing_labels_stay_missing(self):
        original, unchanged = self.snapshots()
        unchanged = copy.deepcopy(original)
        unchanged["ranking"]["applied"] = True
        core = CoreResult(unchanged)
        apply_order(core, 1, original, dict(scores=[-0., 0.]))
        self.assertEqual(core.requested["ids"], ["a", "b"])
        sample = observation(dict(id="sample", expected=["丁"]), original, unchanged, 0)
        self.assertEqual(sample["expected"], ["absent-target"])
        self.assertFalse(set(sample["expected"]) & set(sample["baseline"]))
        self.assertNotIn("丁", str(sample))

    def test_selection_cost_does_not_hide_missing_targets(self):
        samples = [dict(expected=["a"], baseline=["b", "a"], ranked=["a", "b"]),
                   dict(expected=["absent"], baseline=["a", "b"], ranked=["b", "a"])]
        cost = selection_cost(samples)
        self.assertEqual(cost["baseline"], dict(covered_cases=1, missing_cases=1, mean_visible_index=1))
        self.assertEqual(cost["ranked"]["mean_visible_index"], 0)
        self.assertIsNone(selection_cost(samples[1:])["ranked"]["mean_visible_index"])

    def test_failed_case_keeps_completed_observations_without_success(self):
        reports = {}
        cases = [dict(id="first", context=""), dict(id="second", context="")]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "target").mkdir()
            with patch("quality_evaluation.ROOT", root), patch("quality_evaluation.run_case") as run:
                run.side_effect = [(dict(id="first"), dict(category="synthetic")), ValueError("fixed")]
                with self.assertRaises(ValueError):
                    evaluate(None, None, None, dict(cases=cases), reports)
        self.assertFalse(reports["empty_context"]["completed"])
        self.assertEqual(reports["empty_context"]["failed_case_id"], "second")
        self.assertEqual(len(reports["empty_context"]["observations"]), 1)
        self.assertNotIn("contextual", reports)

    def test_inference_distribution_does_not_count_noop_cases(self):
        self.assertEqual(distribution([]), dict(samples=0))
        self.assertEqual(distribution([9, 3, 1])["p95"], 9)
        self.assertEqual(distribution([9, 3, 1])["p50"], 3)


if __name__ == "__main__":
    unittest.main()
