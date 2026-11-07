#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify the independent raw-tensor oracle, then compare runtimes without fitting outputs.

Builds only the test executor. Production ranker_probe and the fixed model must
already exist. Additional --runtime values are diagnostic runtime files, never
product dependencies. Each production comparison keeps the 0.001 tolerance.
Cross-runtime differences are recorded, not treated as same-runtime failures.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

import onnxruntime

from generate_oracle_fixture import generate
from real_model_test import Reference

ROOT = Path(__file__).resolve().parents[3]
TOLERANCE = 0.001


def require(condition, name):
    if not condition:
        raise AssertionError(name)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def difference(left, right):
    require(len(left) == len(right), "score vector length")
    return max(abs(a - b) for a, b in zip(left, right, strict=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path,
                        default=ROOT / "target/candidate-model/msime-keystroke-8m-rerank-v2")
    parser.add_argument("--production-probe", type=Path, default=ROOT / "target/candidate-host/ranker_probe")
    parser.add_argument("--runtime", type=Path, action="append", default=[],
                        help="additional host runtime; the installed pinned baseline is always checked")
    parser.add_argument("--android-report", type=Path,
                        help="optional preserved failed report, for descriptive comparison only")
    parser.add_argument("--report", type=Path, default=ROOT / "target/candidate-host/oracle-report.json")
    args = parser.parse_args()
    require(onnxruntime.__version__ == "1.24.4", "run with pinned host requirements")
    model = args.model_dir.resolve()
    fixture_path = Path(__file__).with_name("oracle-fixture.json")
    encoded = (json.dumps(generate(model), indent=2, ensure_ascii=True) + "\n").encode()
    require(fixture_path.read_bytes() == encoded, "fixture differs from independent upstream generation")
    fixture = json.loads(encoded)
    baseline = next((Path(onnxruntime.__file__).parent / "capi").glob("libonnxruntime.so*")).resolve()
    runtimes = list(dict.fromkeys([baseline, *[path.resolve() for path in args.runtime]]))
    output = args.report.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    raw_probe = output.parent / "raw_ort_oracle"
    source = Path(__file__).with_name("raw_ort_oracle.cpp")
    command = [os.environ.get("CXX", "g++"), "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror", "-pedantic",
               "-DZAPIME_CANDIDATE_ORACLE_MAIN", "-I" + str(ROOT / "target/candidate-runtime/include"),
               "-I" + str(ROOT / "target/voice-json/single_include"), str(source), "-ldl", "-pthread", "-o", str(raw_probe)]
    subprocess.run(command, check=True)
    reference = Reference(model)
    python_scores = reference.score(fixture["synthetic_context"], fixture["synthetic_candidates"]).tolist()
    production = args.production_probe.resolve()
    report = {"schema_version": 1, "scope": "host_independent_upstream_tensors_and_same_runtime_numerics",
              "mechanical_passed": False, "android_acceptance": False, "language_accuracy_evaluated": False,
              "score_tolerance": TOLERANCE, "model_sha256": digest(model / "lm_step.ort"),
              "fixture_sha256": digest(fixture_path), "fixture_bytes": len(encoded),
              "upstream": fixture["upstream"], "generator_sha256": digest(Path(__file__).with_name("generate_oracle_fixture.py")),
              "oracle_source_sha256": digest(source), "oracle_binary_sha256": digest(raw_probe),
              "production_probe_sha256": digest(production), "test_script_sha256": digest(Path(__file__)),
              "python_reference_version": onnxruntime.__version__, "python_reference_scores": python_scores,
              "runtime_runs": [], "negative_checks": []}
    try:
        for runtime in runtimes:
            raw = subprocess.run([str(raw_probe), str(model / "lm_step.ort"), str(runtime), str(fixture_path)],
                                 check=True, text=True, capture_output=True, timeout=60)
            require(not raw.stderr, "unexpected oracle diagnostics")
            result = json.loads(raw.stdout)
            require(result["executed"] and result["session_closed"] and result["graph_calls"] == 2,
                    "oracle execution and scoped cleanup")
            require(result["context_cache_nodes"] == 7 and result["candidate_cache_nodes"] == 10,
                    "oracle preserves exact upstream batching")
            require(result["fixture_sha256"] == digest(fixture_path), "native SHA of actual fixture bytes")
            request = {"context": fixture["synthetic_context"], "candidates": fixture["synthetic_candidates"]}
            actual = subprocess.run([str(production), str(model), str(runtime)],
                                    input=json.dumps(request) + "\n", text=True, capture_output=True,
                                    check=True, timeout=60)
            lines = actual.stdout.splitlines()
            require(len(lines) == 2 and json.loads(lines[0]) == {"ready": True} and not actual.stderr,
                    "production process lifecycle")
            scores = json.loads(lines[1])
            require(scores["ok"], "production score completed")
            delta = difference(result["scores"], scores["scores"])
            require(delta <= TOLERANCE, "production vs independent oracle same-runtime tolerance")
            if runtime == baseline:
                require(difference(result["scores"], python_scores) <= TOLERANCE,
                        "oracle vs original upstream Python same-runtime tolerance")
            report["runtime_runs"].append({"runtime_path": str(runtime), "runtime_sha256": digest(runtime),
                                            "oracle": result, "production_scores": scores["scores"],
                                            "same_runtime_max_abs_error": delta,
                                            "versus_python_1_24_4_max_abs_error": difference(result["scores"], python_scores)})
        with tempfile.TemporaryDirectory(prefix="candidate-oracle-negative-", dir=ROOT / "target") as temporary:
            invalid = Path(temporary) / "invalid.json"
            changed = json.loads(encoded)
            changed["calls"][0]["nodes"]["values"].pop()
            for name, contents in [("node_shape_value_mismatch", json.dumps(changed)), ("malformed_json", "{")]:
                invalid.write_text(contents)
                attempt = subprocess.run([str(raw_probe), str(model / "lm_step.ort"), str(baseline), str(invalid)],
                                         text=True, capture_output=True, timeout=60)
                require(attempt.returncode != 0 and not attempt.stdout, "invalid fixture accepted")
                report["negative_checks"].append(name)
        if args.android_report:
            historical = json.loads(args.android_report.read_text())
            require(historical["model_sha256"] == report["model_sha256"], "historical model identity")
            report["historical_android_observation"] = {
                "source_sha256": digest(args.android_report), "source_status": historical["status"],
                "runtime_sha256": historical["runtime_sha256"], "scores": historical["scores"],
                "used_as_expected_values": False,
                "diagnostic_differences": [{"host_runtime": item["oracle"]["runtime_version"],
                                             "max_abs_error": difference(item["oracle"]["scores"], historical["scores"])}
                                            for item in report["runtime_runs"]]}
        report["mechanical_passed"] = True
    except Exception as error:
        report["failure"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"report": str(output), "mechanical_passed": True,
                      "same_runtime_tolerance": TOLERANCE,
                      "runtime_versions": [item["oracle"]["runtime_version"] for item in report["runtime_runs"]]}))


if __name__ == "__main__":
    main()
