# SPDX-License-Identifier: GPL-3.0-only
"""Recompute fixed host observations; never run a core/model or change labels."""
from pathlib import Path
from collections import Counter
import hashlib
import json
import sys
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from candidate_evaluation import rank, summarize

INPUTS = {
    "quality-report.json": ("1.24.4", "v2", 34, "3f250fe41d33149e734ff75be25c56eae86e763bb855be8a9e90389b9f1e9e93"),
    "quality-v3-report.json": ("1.24.4", "v3", 64, "70b1aa8209e376b7ca8d9c5aab39b3a4be3bcb129a09ff556d8dd086947593f1"),
    "quality-v3-ort1282-report.json": ("1.28.2", "v3", 64, "70b1aa8209e376b7ca8d9c5aab39b3a4be3bcb129a09ff556d8dd086947593f1"),
}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def require(value):
    if not value:
        raise ValueError("fixed candidate attribution rejected")


def analyze():
    path = HERE / "host-original.zip"
    require(digest(path.read_bytes()) == "046b89fa765266424f535ca56e9e1d37b2e630853c61907e699173a4fbda9cf6")
    reports, result = {}, {}
    with zipfile.ZipFile(path) as archive:
        for filename, (runtime, version, count, corpus_sha) in INPUTS.items():
            raw = archive.read(filename)
            report = json.loads(raw)
            reports[filename] = report
            require(report["completed"] is True and report["runtime_version"] == runtime)
            require(report["corpus_sha256"] == corpus_sha and report["corpus_version"] == version)
            require(digest((ROOT / "integrations/candidate/tests" / f"quality-corpus-{version}.json").read_bytes()) == corpus_sha)
            # Historical source identities stay fixed even after a later
            # product fix changes the live worktree. They were byte-compared
            # with the unchanged PR102 source before this audit was frozen.
            for key, expected_sha in {"runner": "19930216eb40e12a713cda5c8b212a831f02a57bdba4cc6a1a4564898eb6ebd0",
                                      "ranking_source": "2fc002c128f1de7363d89947ac7dcae0db4462c4699c45da0de76220cf2dfb49",
                                      "model_lock": "cc7623268feb96a669a94671cfc406497a52a7b4ce6e6f9f4eecdaa89fbb5b01"}.items():
                require(report["identity"][key] == expected_sha)
            output = dict(report_sha256=digest(raw), runtime=runtime, corpus=version,
                          corpus_sha256=corpus_sha, identity=report["identity"], modes={})
            for mode, data in report["modes"].items():
                samples, details = data["observations"], data["details"]
                require(len(samples) == len(details) == count)
                recomputed = summarize(dict(schema_version=1, synthetic=True, cases=samples))
                for key in ("cases", "target_missing", "metrics", "improved", "regressed", "top1_rescued", "top1_harmed"):
                    require(recomputed[key] == data[key])
                outcomes = Counter(detail["outcome"] for detail in details)
                require(dict(outcomes) == {k: v for k, v in data["outcome_counts"].items() if v})
                require(data["scored_cases"] == sum(detail["model_called"] for detail in details))
                require(data["native_score_calls"] == 2 * data["scored_cases"])
                categories, failures = {}, []
                for category in sorted({d["category"] for d in details}):
                    subset = [sample for sample, detail in zip(samples, details, strict=True) if detail["category"] == category]
                    summary = summarize(dict(schema_version=1, synthetic=True, cases=subset))
                    require(summary["metrics"] == data["by_category"][category]["metrics"])
                    categories[category] = {key: summary[key] for key in ("cases", "target_missing", "metrics", "regressed", "top1_harmed")}
                for sample, detail in zip(samples, details, strict=True):
                    require(sample["id"] == detail["id"])
                    if detail["outcome"] in {"candidate_missing", "rerank_harmed"}:
                        failures.append(dict(id=sample["id"], category=detail["category"], outcome=detail["outcome"],
                                             baseline_rank=rank(sample["baseline"], set(sample["expected"])),
                                             ranked_rank=rank(sample["ranked"], set(sample["expected"])),
                                             target_rankable=detail["target_rankable"], rankable_count=detail["rankable_count"]))
                output["modes"][mode] = {key: recomputed[key] for key in ("cases", "target_missing", "metrics", "improved", "regressed", "top1_rescued", "top1_harmed")}
                output["modes"][mode].update(outcomes=data["outcome_counts"], categories=categories,
                                              retained_misses_and_regressions=failures,
                                              scored_cases=data["scored_cases"], native_score_calls=data["native_score_calls"])
            result[filename] = output
    runtime_differences = {}
    for mode in ("empty_context", "contextual"):
        left = reports["quality-v3-report.json"]["modes"][mode]
        right = reports["quality-v3-ort1282-report.json"]["modes"][mode]
        require(left["metrics"] == right["metrics"])
        runtime_differences[mode] = [a["id"] for a, b in zip(left["observations"], right["observations"], strict=True)
                                     if any(a[key] != b[key] for key in ("id", "expected", "baseline", "ranked"))]
    return dict(schema_version=1, source_head="9b801ff947486f5418e9ddc84a88941d1cf3eda6",
                source_checkout="436b267cbbc5885ecdf58a7515ef0323fbb6f5e8",
                source_tree="3995757340519d1b21fc5dfb3b2b89367673d087",
                host_job=128832759528, host_run=42513949719,
                reaggregation_invokes_inference=False, product_code_changed=False, labels_changed=False,
                phone_accuracy_evaluated=False, reports=result,
                v3_full_order_runtime_differences=runtime_differences)


if __name__ == "__main__":
    result = analyze()
    if sys.argv[1:] == ["--verify"]:
        require(json.loads((HERE / "attribution.json").read_text()) == result)
        print("PASS three fixed reports recomputed; all frozen misses/regressions retained; no inference")
    elif not sys.argv[1:]:
        with (HERE / "attribution.json").open("x") as output:
            json.dump(result, output, ensure_ascii=False, indent=2)
            output.write("\n")
    else:
        raise SystemExit("usage: analyze.py [--verify]")
