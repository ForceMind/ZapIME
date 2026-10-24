#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run four independent local processes and retain every synthetic output."""
import json
from pathlib import Path
import subprocess
import sys
import time

from assets import ROOT, TEXTS


def signature(result):
    return [(item["source"], item["normalized_source"], item["translation"], item["archive_sed_translation_untrimmed"])
            for item in result["examples"]]


def main():
    runs = []
    results = {}
    for beam in (1, 6):
        for offline in (False, True):
            command = [sys.executable, str(Path(__file__).with_name("run.py")), "--beam-size", str(beam)]
            if offline:
                command.append("--offline")
            started = time.perf_counter()
            process = subprocess.run(command, capture_output=True, check=True, text=True, timeout=120)
            result = json.loads(process.stdout)
            if [item["source"] for item in result["examples"]] != TEXTS:
                raise RuntimeError("the fixed 19-sentence set changed")
            results[(beam, offline)] = result
            runs.append({"beam_size": beam, "offline": offline, "exit_code": process.returncode,
                         "whole_subprocess_wall_seconds": time.perf_counter() - started,
                         "peak_process_rss_kib": result["peak_process_rss_kib"], "stderr": process.stderr})
            print(json.dumps(runs[-1]), flush=True)
    matches = []
    for beam in (1, 6):
        online, offline = results[(beam, False)], results[(beam, True)]
        equal = signature(online) == signature(offline) and online["model_files"] == offline["model_files"]
        matches.append({"beam_size": beam, "all_sources_normalizations_translations_and_model_files_identical": equal})
        if not equal:
            raise RuntimeError("network-capable/restricted outputs or model identities differed")
    report = {"runs": runs, "online_offline_matches": matches, "synthetic_sentences_per_process": len(TEXTS),
              "quality_score": None, "default_model_accepted": False, "archive_shell_executed": False,
              "limitations": "Complete four-process qualitative contrast, not statistical timing/teaching/mobile acceptance"}
    (ROOT / "comparison.json").write_text(json.dumps(report, indent=2) + "\n")
    print("PASS two beam-width pairs have identical sources, normalizations, translations and model identities")


if __name__ == "__main__":
    main()
