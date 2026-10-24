#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run every synthetic contrast with both controls; never download in this step."""
import json
from pathlib import Path
import subprocess
import sys
import time

from prepare_preprocessing import ROOT


def signature(result):
    return [
        (entry["source"], entry["normalized_source"], entry["translation"], entry["archive_sed_translation_untrimmed"])
        for entry in result["examples"]
    ]


def main():
    scripts = Path(__file__).resolve().parent
    runs = []
    results = {}
    for preprocessing in ("sentencepiece", "opus-simple"):
        for beam in (1, 6):
            for offline in (False, True):
                command = [sys.executable, str(scripts / "probe.py"), "infer", "--contrast", "--preprocessing", preprocessing, "--beam-size", str(beam)]
                if offline:
                    command.append("--offline")
                started = time.perf_counter()
                process = subprocess.run(command, check=True, capture_output=True, text=True, timeout=120)
                data = json.loads(process.stdout)
                results[(preprocessing, beam, offline)] = data
                runs.append({"preprocessing": preprocessing, "beam_size": beam, "offline": offline, "exit_code": process.returncode, "examples": len(data["examples"]), "whole_subprocess_wall_seconds": time.perf_counter() - started, "peak_process_rss_kib": data.get("peak_process_rss_kib"), "stderr": process.stderr})
                print(json.dumps(runs[-1]), flush=True)
    matches = []
    for preprocessing in ("sentencepiece", "opus-simple"):
        for beam in (1, 6):
            online = results[(preprocessing, beam, False)]
            offline = results[(preprocessing, beam, True)]
            equal = signature(online) == signature(offline) and online["model_files"] == offline["model_files"]
            matches.append({"preprocessing": preprocessing, "beam_size": beam, "source_normalization_translation_and_model_files_identical_online_offline": equal})
            if not equal:
                raise RuntimeError("online/offline result or model identity changed")
    baseline = results[("sentencepiece", 1, True)]
    old_path = scripts.parents[1] / "docs/evidence/english-learning-cpu-20261024/inference-daily-offline.json"
    old = json.loads(old_path.read_text())
    historic_match = [(x["source"], x["translation"]) for x in baseline["examples"][:10]] == [(x["source"], x["translation"]) for x in old["examples"]]
    result = {
        "synthetic_text_only": True,
        "runs": runs,
        "online_offline_matches": matches,
        "original_ten_baseline_source_translation_pairs_match_previous_evidence": historic_match,
        "archive_shell_executed": False,
        "quantitative_quality_score": None,
        "limitations": "Controlled qualitative contrasts only; timings follow warmed file cache and are single passes, not latency statistics. No native Marian, mobile, energy, typing-contention or teaching-quality acceptance.",
    }
    (ROOT / "preprocessing-comparison.json").write_text(json.dumps(result, indent=2) + "\n")
    print("PASS all four online/offline source, normalization, translation and model-file comparisons")


if __name__ == "__main__":
    main()
