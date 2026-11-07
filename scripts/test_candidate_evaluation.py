# SPDX-License-Identifier: GPL-3.0-only
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from candidate_evaluation import summarize


class EvaluationTests(unittest.TestCase):
    def data(self):
        return dict(schema_version=1, synthetic=True, cases=[
            dict(id="private_fixture_identifier", expected=["a"], baseline=["b", "a"],
                 ranked=["a", "b"], latency_ms=1),
            dict(id="harm", expected=["a"], baseline=["a", "b"],
                 ranked=["b", "a"], latency_ms=9),
            dict(id="missing", expected=["c"], baseline=["a", "b"],
                 ranked=["b", "a"], latency_ms=3)])

    def test_paired_denominators_missing_and_percentiles(self):
        result = summarize(self.data())
        self.assertEqual(result["cases"], 3)
        self.assertEqual(result["target_missing"], 1)
        self.assertEqual(result["improved"], 1)
        self.assertEqual(result["regressed"], 1)
        self.assertEqual(result["top1_rescued"], 1)
        self.assertEqual(result["top1_harmed"], 1)
        self.assertEqual(result["metrics"]["ranked"]["top1_rate"], 1 / 3)
        self.assertEqual(result["metrics"]["ranked"]["mrr"], .5)
        self.assertEqual(result["latency_ms"]["p50"], 3)
        self.assertEqual(result["latency_ms"]["p95"], 9)
        self.assertNotIn("private_fixture_identifier", str(result))

    def test_cli_aggregate_only_and_create_only(self):
        script = Path(__file__).with_name("candidate_evaluation.py")
        with tempfile.TemporaryDirectory() as directory:
            source, report = Path(directory) / "input.json", Path(directory) / "report.json"
            source.write_text(json.dumps(self.data()))
            command = [sys.executable, str(script), str(source), "--report", str(report)]
            result = subprocess.run(command, capture_output=True)
            self.assertEqual(result.returncode, 0)
            original = report.read_bytes()
            self.assertNotIn(b"private_fixture_identifier", original)
            self.assertEqual(len(json.loads(original)["observations_sha256"]), 64)
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual(report.read_bytes(), original)
            source.write_text('{"secret_text": INVALID_PRIVATE_BODY}')
            report.unlink()
            result = subprocess.run(command, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn(b"INVALID_PRIVATE_BODY", result.stderr)
            self.assertFalse(report.exists())
            source.write_bytes(b"x" * (4 * 1024 * 1024 + 1))
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertFalse(report.exists())

    def test_multiple_acceptable_targets(self):
        data = self.data()
        data["cases"][0]["expected"] = ["a", "b"]
        result = summarize(data)
        self.assertEqual(result["improved"], 0)

    def test_reject_nonpaired_duplicate_and_invalid_observations(self):
        mutations = [
            lambda d: d.update(synthetic=False),
            lambda d: d.update(schema_version=True),
            lambda d: d.update(cases=[]),
            lambda d: d["cases"].append(copy.deepcopy(d["cases"][0])),
            lambda d: d["cases"][0].update(ranked=["a", "c"]),
            lambda d: d["cases"][0].update(ranked=["a", "a"]),
            lambda d: d["cases"][0].update(expected=[]),
            lambda d: d["cases"][0].update(latency_ms=float("nan")),
            lambda d: d["cases"][0].update(latency_ms=float("inf")),
            lambda d: d["cases"][0].update(latency_ms=-1),
            lambda d: d["cases"][0].update(latency_ms=True),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                data = self.data()
                mutate(data)
                with self.assertRaises(ValueError):
                    summarize(data)


if __name__ == "__main__":
    unittest.main()
