#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fresh process per synthetic case; exactly one native child for RSS scope."""
import argparse
import json
from pathlib import Path
import resource
import subprocess
import time

from assets import ROOT
from environment import controlled_environment
from network import deny_network

EOS_ENVELOPE = " [end of text]\n\n\n"


def extract_output(raw, returncode, timed_out=False):
    eos = raw.endswith(EOS_ENVELOPE)
    body = raw[:-len(EOS_ENVELOPE)] if eos else None
    reasons = []
    if returncode != 0:
        reasons.append("nonzero_runtime_exit")
    if timed_out:
        reasons.append("timeout")
    if not eos:
        reasons.append("no_exact_runtime_eog_envelope_possible_limit_or_unexpected_output")
    if body is not None:
        if not body.strip():
            reasons.append("empty_output")
        if any(token in body for token in ("<think>", "</think>", "<|im_start|>", "<|im_end|>", "<|endoftext|>")):
            reasons.append("unexpected_thinking_or_special_token_text")
        if " [end of text]" in body:
            reasons.append("ambiguous_runtime_envelope_in_body")
    return {"translation_untrimmed": body, "display_translation": body.strip() if body is not None else None, "native_eog_marker_observed": eos, "shape_accepted": not reasons, "shape_rejection_reasons": reasons}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("prompt_file")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    prompt = Path(args.prompt_file).resolve()
    if not prompt.is_relative_to(ROOT / "prompts"):
        raise RuntimeError("only this experiment's fixed synthetic prompt directory is accepted")
    env, config_candidates = controlled_environment()
    runtime = ROOT / "runtime/llama-b11669/llama-completion"
    command = [str(runtime), "--model", str(ROOT / "Qwen3-0.6B-Q8_0.gguf"), "--file", str(prompt), "--no-conversation", "--no-display-prompt", "--simple-io", "--color", "off", "--verbosity", "0", "--offline", "--no-escape", "--no-context-shift", "--no-warmup", "-ngl", "0", "-t", "1", "-tb", "1", "-c", "1024", "-n", "128", "--temp", "0"]
    network = deny_network() if args.offline else {"method": "OS network-capable baseline; native --offline flag still set"}
    started = time.perf_counter()
    timed_out = False
    try:
        process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="strict", timeout=90, env=env, stdin=subprocess.DEVNULL, close_fds=True)
        stdout, stderr, returncode = process.stdout, process.stderr, process.returncode
    except subprocess.TimeoutExpired as error:
        timed_out = True
        stdout = (error.stdout or b"").decode("utf-8", errors="replace") if isinstance(error.stdout, bytes) else (error.stdout or "")
        stderr = (error.stderr or b"").decode("utf-8", errors="replace") if isinstance(error.stderr, bytes) else (error.stderr or "")
        returncode = None
    result = {
        "command": command, "exit_code": returncode, "timed_out": timed_out,
        "whole_native_process_wall_seconds": time.perf_counter() - started,
        "peak_native_process_rss_kib": resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss,
        "rss_scope": "Linux RUSAGE_CHILDREN maximum of this fresh worker's single native child; includes loader, model mapping, context, and generation, not model-only or mobile memory",
        "raw_stdout": stdout, "raw_stderr": stderr, "network": network,
        "external_config_checked_absent": config_candidates,
        "environment": env,
    }
    result.update(extract_output(stdout, returncode, timed_out))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
