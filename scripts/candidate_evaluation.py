#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Summarize paired, pre-labelled synthetic candidate trials without logging text.

Input JSON: {schema_version: 1, synthetic: true, cases: [...]}.
Each case has id, expected (acceptable opaque IDs), baseline and ranked (ordered
IDs), and latency_ms (one measured reranking duration). One row is one unique
input, not another timing repetition. Candidate membership must be unchanged.
This measures supplied observations; it does not run a model or prove phone
performance. Never feed real user input to this public-CI tool.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path

MAX_BYTES = 4 * 1024 * 1024
MAX_CASES = 10000


def require(condition):
    if not condition:
        raise ValueError("invalid synthetic candidate evaluation")


def identifiers(value, maximum):
    require(isinstance(value, list) and 0 < len(value) <= maximum)
    require(all(isinstance(item, str) and 0 < len(item) <= 128 for item in value))
    require(len(set(value)) == len(value))
    return value


def rank(order, expected):
    return next((i + 1 for i, item in enumerate(order) if item in expected), None)


def summarize(data):
    require(isinstance(data, dict) and type(data.get("schema_version")) is int
            and data["schema_version"] == 1 and data.get("synthetic") is True)
    cases = data.get("cases")
    require(isinstance(cases, list) and 0 < len(cases) <= MAX_CASES)
    seen, times = set(), []
    metrics = {key: dict(top1=0, top3=0, reciprocal_rank=0.0)
               for key in ("baseline", "ranked")}
    improved = regressed = rescued = harmed = covered = 0
    for case in cases:
        require(isinstance(case, dict))
        identifier = identifiers([case.get("id")], 1)[0]
        require(identifier not in seen)
        seen.add(identifier)
        expected = set(identifiers(case.get("expected"), 32))
        baseline = identifiers(case.get("baseline"), 256)
        ranked = identifiers(case.get("ranked"), 256)
        require(set(baseline) == set(ranked))
        elapsed = case.get("latency_ms")
        require(type(elapsed) in (int, float) and math.isfinite(elapsed) and elapsed >= 0)
        times.append(elapsed)
        before, after = rank(baseline, expected), rank(ranked, expected)
        covered += before is not None
        for name, position in (("baseline", before), ("ranked", after)):
            metrics[name]["top1"] += position == 1
            metrics[name]["top3"] += position is not None and position <= 3
            metrics[name]["reciprocal_rank"] += 1 / position if position else 0
        # Missing targets count as misses in every metric, never disappear
        # from the denominator. Equal pools imply equal target coverage.
        if before is not None:
            improved += after < before
            regressed += after > before
        rescued += before != 1 and after == 1
        harmed += before == 1 and after != 1
    count = len(cases)
    for values in metrics.values():
        values["top1_rate"] = values["top1"] / count
        values["top3_rate"] = values["top3"] / count
        values["mrr"] = values.pop("reciprocal_rank") / count
    times.sort()
    return dict(schema_version=1, scope="supplied_unique_synthetic_paired_observations",
                model_executed=False, phone_performance_evaluated=False,
                cases=count, target_covered=covered, target_missing=count - covered,
                metrics=metrics, improved=improved, regressed=regressed,
                unchanged=count - improved - regressed, top1_rescued=rescued,
                top1_harmed=harmed,
                latency_ms=dict(samples=count, method="nearest_rank",
                                p50=times[math.ceil(count * .50) - 1],
                                p95=times[math.ceil(count * .95) - 1], maximum=times[-1]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("observations", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    try:
        with args.observations.open("rb") as stream:
            raw = stream.read(MAX_BYTES + 1)
        require(len(raw) <= MAX_BYTES)
        report = summarize(json.loads(raw))
    except (OSError, ValueError, TypeError, RecursionError):
        # Never echo a failing observation, path, candidate or parser excerpt.
        parser.exit(2, "candidate evaluation rejected; no report written\n")
    report["observations_sha256"] = hashlib.sha256(raw).hexdigest()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    # Do not overwrite an earlier accepted report after a mistaken rerun.
    with args.report.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
