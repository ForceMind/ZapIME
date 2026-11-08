# SPDX-License-Identifier: GPL-3.0-only
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from quality_evaluation import (apply_order, attribution, distribution, load_corpus,
                                CORPUS, observation, selection_cost, evaluate,
                                run_case, validate_expansion)


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

    def test_expansion_is_separate_frozen_set_with_checked_encodings(self):
        corpus = load_corpus(CORPUS.with_name("quality-corpus-v3.json"))
        self.assertEqual(len(corpus["cases"]), 64)
        self.assertEqual(sum(case.get("layout") == "nine_key" for case in corpus["cases"]), 8)
        self.assertEqual(sum("personal" in case for case in corpus["cases"]), 6)
        self.assertEqual(sum(len(case["context"]) == 64 for case in corpus["cases"]), 4)
        # Scalar counts, rather than UTF-16 units, define the native bound.
        supplementary = next(case for case in corpus["cases"] if case["id"] == "x05")
        self.assertEqual(len(supplementary["context"].encode("utf-16-le")) // 2, 121)
        legacy = load_corpus(CORPUS)
        # IDs are scoped to a corpus; input identities, not their short names,
        # distinguish new authored fixtures from the retained legacy sample.
        identity = lambda case: (case.get("layout", "qwerty"), case["code"], case["context"])
        self.assertFalse(set(map(identity, corpus["cases"])) & set(map(identity, legacy["cases"])))

    def test_expansion_rejects_bad_phonetics_and_resource_boundaries_before_inference(self):
        corpus = load_corpus(CORPUS.with_name("quality-corpus-v3.json"))
        mutations = [("n01", "code", "1"), ("h01", "pinyin", "shi shi"),
                     ("h01", "context", "甲" * 65), ("x01", "context", "非空"),
                     ("x03", "context", "甲" * 63), ("h01", "expected", []),
                     ("r01", "personal", dict(type="candidate_rule", code="shishi", text="实施",
                                              scheme="pinyin", position=True))]
        for identifier, key, value in mutations:
            changed = copy.deepcopy(corpus)
            next(case for case in changed["cases"] if case["id"] == identifier)[key] = value
            with self.subTest(identifier=identifier, key=key), self.assertRaises(ValueError):
                validate_expansion(changed)

    def test_attribution_keeps_missing_protected_and_model_harm_distinct(self):
        def sample(expected, before, after):
            return dict(expected=[expected], baseline=before, ranked=after)
        fixtures = [
            (sample("absent", ["a", "b"], ["b", "a"]), False, "candidate_missing"),
            (sample("a", ["a", "b"], ["a", "b"]), False, "target_protected_or_unrankable"),
            (sample("a", ["b", "a"], ["a", "b"]), True, "rerank_improved"),
            (sample("a", ["a", "b"], ["b", "a"]), True, "rerank_harmed"),
            (sample("a", ["a", "b"], ["a", "b"]), True, "rerank_unchanged")]
        self.assertEqual({attribution(s, eligible) for s, eligible, _ in fixtures},
                         {expected for _, _, expected in fixtures})
        for supplied, eligible, expected in fixtures:
            self.assertEqual(attribution(supplied, eligible), expected)
        with self.assertRaises(ValueError):
            attribution(sample("a", ["a", "b"], ["b", "a"]), False)
        with self.assertRaises(ValueError):
            attribution(sample("a", ["b", "a"], ["b", "c"]), True)
        self.assertEqual(attribution(sample("a", ["a"], ["a"]), True, False),
                         "not_scored_insufficient_pool")

    def test_noop_only_corpus_cannot_claim_model_acceptance(self):
        reports = {}
        sample = dict(id="single", expected=["a"], baseline=["a"], ranked=["a"], latency_ms=0)
        detail = dict(id="single", category="synthetic", model_called=False,
                      warm_roundtrip_ms=None, outcome="not_scored_insufficient_pool")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "target").mkdir()
            with patch("quality_evaluation.ROOT", root), patch("quality_evaluation.run_case", return_value=(sample, detail)):
                with self.assertRaises(ValueError):
                    evaluate(None, None, None, dict(cases=[dict(id="single", context="")]), reports)
        empty = reports["empty_context"]
        self.assertFalse(empty["completed"])
        self.assertFalse(empty["model_executed"])
        self.assertEqual(empty["native_score_calls"], 0)
        self.assertEqual(empty["outcome_counts"]["not_scored_insufficient_pool"], 1)
        self.assertEqual(empty["observations"], [sample])
        self.assertNotIn("contextual", reports)

    def test_personal_fixture_must_actually_exist_and_stay_outside_scoring_pool(self):
        original, ranked = self.snapshots()
        original["ranking"].update(enabled=True, model="msime-keystroke-8m-rerank-v2")
        personal = dict(type="candidate_rule", code="nihao", text="甲", scheme="pinyin", position=1)
        case = dict(id="personal", category="personal_rule", code="nihao", expected=["甲"], personal=personal)

        class FixtureCore(CoreResult):
            def __init__(self, visible):
                super().__init__(ranked)
                self.visible, self.requests, self.closed = visible, [], False

            def open(self, resources, local):
                return 1

            def request(self, handle, request):
                self.requests.append(request)
                return super().request(handle, request) if request["op"] == "candidate_rank" else self.visible

            def close(self, handle):
                self.closed = True

        class FixtureProbe:
            process = type("Process", (), dict(pid=1))()

            def call(self, request):
                return dict(scores=[-2., -1.], new_nodes=0, context_nodes=1, prefix_nodes=2), 1

        core = FixtureCore(original)
        with patch("quality_evaluation.memory", return_value={}):
            sample, detail = run_case(core, FixtureProbe(), None, None, case, "context")
        self.assertEqual(core.requests[0]["op"], "personal_edit")
        self.assertEqual(core.requests[0]["content"], personal)
        self.assertEqual(detail["outcome"], "target_protected_or_unrankable")
        self.assertEqual(sample["baseline"][0], sample["ranked"][0])
        self.assertTrue(core.closed)
        for text in ("不存在", "乙"):
            bad_case = copy.deepcopy(case)
            bad_case["personal"]["text"] = text
            bad_core = FixtureCore(original)
            with self.subTest(text=text), self.assertRaises(ValueError):
                run_case(bad_core, FixtureProbe(), None, None, bad_case, "context")
            self.assertTrue(bad_core.closed)

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
