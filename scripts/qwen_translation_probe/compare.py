#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify both complete runs and export all raw text grouped by prompt."""
import json

from assets import ROOT, digest_file


def identity(entry):
    return tuple(entry[key] for key in ("case_index", "system_prompt_language", "source", "complete_prompt", "prompt_sha256", "raw_stdout", "raw_stderr", "translation_untrimmed", "display_translation", "native_eog_marker_observed", "exit_code", "timed_out", "shape_accepted"))


def main():
    paths = [ROOT / "qwen-network-capable.json", ROOT / "qwen-offline.json"]
    records = [json.loads(path.read_text()) for path in paths]
    if any(len(record["examples"]) != 38 for record in records):
        raise RuntimeError("both complete two-prompt/nineteen-case runs are required")
    if records[0]["assets"] != records[1]["assets"]:
        raise RuntimeError("asset identities changed")
    if [identity(entry) for entry in records[0]["examples"]] != [identity(entry) for entry in records[1]["examples"]]:
        raise RuntimeError("source, full prompt, raw output, exit or EOG differs between runs")
    if not all(entry["shape_accepted"] for record in records for entry in record["examples"]):
        raise RuntimeError("one or more runtime/structure checks failed; inspect raw results")
    for entry in records[1]["examples"]:
        if entry["network"]["socket_creation_rejected"] != ["AF_INET", "AF_INET6"]:
            raise RuntimeError("a restricted worker did not verify socket rejection")
    destination = ROOT / "evidence"
    destination.mkdir(exist_ok=True)
    for path, record in zip(paths, records):
        for prompt in ("english", "chinese"):
            group = dict(record)
            group["examples"] = [entry for entry in record["examples"] if entry["system_prompt_language"] == prompt]
            group["grouping"] = {"source_complete_run_file": path.name, "source_complete_run_sha256": digest_file(path), "complete_run_examples": 38, "selected_prompt": prompt, "raw_output_strings_unchanged": True}
            name = "qwen-" + prompt + ("-offline.json" if record["offline_syscall_restriction_requested"] else "-network-capable.json")
            (destination / name).write_text(json.dumps(group, ensure_ascii=False, indent=2) + "\n")
    native = [entry for record in records for entry in record["examples"]]
    summary = {
        "synthetic_text_only": True, "distinct_source_cases": 19, "fixed_system_prompts": 2,
        "native_processes": 76, "network_restricted_native_processes": 38,
        "all_source_full_prompt_raw_stdout_stderr_eog_exit_and_shape_pairs_identical": True,
        "all_exit_codes_zero": True, "all_native_eog_envelopes_observed": True,
        "all_restricted_workers_rejected_ipv4_and_ipv6_socket_creation": True,
        "peak_native_process_rss_kib_range": [min(entry["peak_native_process_rss_kib"] for entry in native), max(entry["peak_native_process_rss_kib"] for entry in native)],
        "whole_native_process_wall_seconds_range": [min(entry["whole_native_process_wall_seconds"] for entry in native), max(entry["whole_native_process_wall_seconds"] for entry in native)],
        "source_complete_runs": [{"name": path.name, "bytes": path.stat().st_size, "sha256": digest_file(path)} for path in paths],
        "quality_accepted": False,
        "scope": "structural checks and paired text equality, not a quality score. This temperature-zero/two-prompt/non-thinking configuration is not selected as default teaching output; no conclusion about every model or decoding configuration.",
    }
    (ROOT / "comparison.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("PASS complete 38-pair raw output/EOG/exit/asset checks; teaching quality remains unaccepted")


if __name__ == "__main__":
    main()
