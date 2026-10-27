#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fixed synthetic whole-sentence diagnostics, with mandatory Linux socket denial.

No microphone or arbitrary recording input. A completed run measures the fixed
eSpeak fixtures; it does not pass natural-speech or handset accuracy acceptance.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import re
import resource
import statistics
import subprocess
import sys
import tempfile
import time
import wave

import voice_probe
from voice_quality_metrics import PROFILES, aggregate, counts, normalize

ROOT = Path(__file__).resolve().parents[1]
DEFINITION = ROOT / "scripts/fixtures/voice-quality-v1.json"


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def definition():
    config = json.loads(DEFINITION.read_text(encoding="utf-8"))
    if config["schema_version"] != 1 or config["kind"] != "synthetic":
        raise ValueError("only the fixed synthetic fixture schema is supported")
    cases = config["cases"]
    if not 1 <= len(cases) <= 32:
        raise ValueError("fixture count exceeds bound")
    ids = set()
    for case in cases:
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,47}", case["id"]) or case["id"] in ids:
            raise ValueError("invalid or duplicate fixture id")
        ids.add(case["id"])
        if case["voice"] not in ("cmn-latn-pinyin", "en-us") or case["speed"] not in (120, 150, 180):
            raise ValueError("unsupported synthesis configuration")
        if not normalize(case["reference"], "speech-v1") or len(case["reference"]) > 256:
            raise ValueError("fixture reference must be nonempty and bounded")
    return config


def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as out:
        temporary = Path(out.name)
        try:
            json.dump(value, out, ensure_ascii=False, indent=2, allow_nan=False)
            out.write("\n")
            out.close()
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)


def generate(directory, espeak):
    config = definition()
    if directory.is_symlink():
        raise ValueError("fixture directory must not be a symlink")
    directory.mkdir(parents=True, exist_ok=True)
    version = subprocess.check_output([espeak, "--version"], text=True).strip()
    files = {}
    with tempfile.TemporaryDirectory(dir=directory) as tmp:
        pending = Path(tmp)
        for case in config["cases"]:
            name = case["id"]+".wav"
            path = pending/name
            voice_probe.synthesize(path, espeak, case["reference"], case["voice"], case["speed"])
            with wave.open(str(path)) as audio:
                samples = audio.getnframes()
                if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 16000):
                    raise ValueError("invalid generated WAV format")
                if not 0 < samples <= 60*16000:
                    raise ValueError("generated fixture exceeds duration bound")
            files[name] = {"size": path.stat().st_size, "sha256": sha256(path), "samples": samples}
        for name in files:
            (pending/name).replace(directory/name)
    # Written last: interruption cannot make partial fixtures validate as a set.
    save_json(directory/"manifest.json", {"schema_version": 1, "kind": "synthetic",
              "definition_sha256": sha256(DEFINITION), "generator": version, "files": files})
    print("COMPLETE fixed synthetic fixtures:", len(files))


def verify_fixtures(directory, config):
    path = directory/"manifest.json"
    if directory.is_symlink() or path.is_symlink() or path.stat().st_size > 65536:
        raise ValueError("invalid fixture manifest")
    manifest = json.loads(path.read_text(encoding="utf-8"))
    expected = {case["id"]+".wav" for case in config["cases"]}
    if (manifest.get("schema_version") != 1 or manifest.get("kind") != "synthetic"
            or manifest.get("definition_sha256") != sha256(DEFINITION)
            or set(manifest.get("files", {})) != expected):
        raise ValueError("fixture definition or member set mismatch")
    for name, spec in manifest["files"].items():
        wav = directory/name
        if not voice_probe.verified(wav, spec) or not 0 < spec["samples"] <= 60*16000:
            raise ValueError("fixture integrity or duration check failed")
        with wave.open(str(wav)) as audio:
            if ((audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 16000)
                    or audio.getnframes() != spec["samples"]
                    or len(audio.readframes(audio.getnframes())) != spec["samples"]*2):
                raise ValueError("fixture PCM header/payload mismatch")
    return manifest


def case_result(case, hypothesis, seconds, timing, include_text=False):
    row = {"id": case["id"], "category": case["category"], "audio_seconds": seconds,
           "reference_sha256": hashlib.sha256(case["reference"].encode()).hexdigest(),
           "hypothesis_sha256": hashlib.sha256(hypothesis.encode()).hexdigest(),
           "metrics": {profile: counts(case["reference"], hypothesis, profile) for profile in PROFILES},
           **timing}
    if include_text:
        row.update(reference=case["reference"], hypothesis=hypothesis)
    return row


def evaluate_synthetic(directory, model, threads, runs, report, include_text):
    started = time.perf_counter()
    config = definition()
    manifest = verify_fixtures(directory, config)
    # Install before numpy/sherpa imports and model initialization; no fallback.
    sys.path.insert(0, str(ROOT))
    from scripts.qwen_translation_probe.network import deny_network
    isolation = deny_network()
    isolation["scope"] = "evaluation process and native threads; specific socket syscalls, not inherited-fd/io_uring isolation, a general sandbox or device airplane mode"
    import numpy as np
    import sherpa_onnx
    if sherpa_onnx.__version__ != "1.13.8":
        raise ValueError("unexpected inference runtime version")
    recognizer, load_seconds = voice_probe.create_recognizer(model, threads)
    result = {"schema_version": 1, "kind": "synthetic", "suite_id": config["id"],
              "definition_sha256": sha256(DEFINITION), "fixtures": manifest,
              "model": voice_probe.LOCK["model"], "model_files": voice_probe.LOCK["files"],
              "runtime": sherpa_onnx.__version__, "numpy": np.__version__,
              "platform": platform.platform(), "processor": platform.machine(),
              "threads": threads, "network_restriction": isolation,
              "recognizer_load_seconds": load_seconds, "rounds": [], "complete": False,
              "source_sha256": {name: sha256(ROOT/name) for name in (
                  "scripts/voice_quality.py", "scripts/voice_quality_metrics.py", "scripts/voice_probe.py",
                  "scripts/qwen_translation_probe/network.py", "third_party/voice-probe-lock.json")},
              "metric": {"unit": "Unicode codepoint, including English letters; not WER",
                         "formula": "(substitutions + deletions + insertions) / reference characters",
                         "aggregation": "sum counts per round/category, not mean sentence CER",
                         "verbatim": "exact codepoints, including punctuation, spaces and case",
                         "speech-v1": "NFKC, lowercase, remove Unicode P* punctuation and whitespace; retain symbols, digits and Chinese numeral differences; no traditional/simplified conversion",
                         "tie_break": "equal-distance substitution, deletion, insertion in that order",
                         "empty_reference_cer": None, "cer_not_capped_at_one": True},
              "scope": "Fixed synthetic eSpeak diagnostics only; no natural speech, representative corpus score, phone/microphone/latency/energy acceptance. Repeated rounds reuse the same clips and do not add independent samples."}
    for iteration in range(1, runs+1):
        rows = []
        for case in config["cases"]:
            audio = voice_probe.read_audio(directory/(case["id"]+".wav"), max_seconds=60)
            hypothesis, timing = voice_probe.decode_audio(recognizer, audio)
            row = case_result(case, hypothesis, len(audio)/16000, timing, include_text)
            rows.append(row)
        categories = sorted({row["category"] for row in rows})
        result["rounds"].append({"iteration": iteration, "cases": rows,
             "aggregate": {profile: aggregate([row["metrics"][profile] for row in rows]) for profile in PROFILES},
             "by_category": {tag: {profile: aggregate([row["metrics"][profile] for row in rows if row["category"] == tag])
                                   for profile in PROFILES} for tag in categories},
             "total_audio_seconds": sum(row["audio_seconds"] for row in rows),
             "total_decode_seconds": sum(row["decode_seconds"] for row in rows),
             "median_clip_rtf": statistics.median(row["rtf"] for row in rows)})
        # Persist finished rounds, labelled incomplete until all requested work ends.
        save_json(report, result)
    result["complete"] = True
    result["requested_rounds"] = runs
    result["transcripts_equal_across_rounds"] = all(
        [row["hypothesis_sha256"] for row in batch["cases"]] ==
        [row["hypothesis_sha256"] for row in result["rounds"][0]["cases"]] for batch in result["rounds"])
    result["evaluation_elapsed_seconds"] = time.perf_counter()-started
    result["process_peak_rss_kib"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    result["resource_scope"] = "Linux Python evaluation process including imports/model; recognizer load excludes prior file hashing; unpaced decoding includes one second zero tail; no handset or end-to-end timing"
    save_json(report, result)
    print("COMPLETE synthetic diagnostics; no accuracy acceptance:", report)
    print(json.dumps([batch["aggregate"]["speech-v1"] for batch in result["rounds"]]))


def evaluate(directory, model, threads, runs, report, include_text):
    # Invalidate a previous success before validation/loading starts. Interrupted
    # or failed work cannot leave a stale complete report at the requested path.
    save_json(report, {"schema_version": 1, "kind": "synthetic", "complete": False,
                       "status": "starting", "requested_rounds": runs})
    try:
        evaluate_synthetic(directory, model, threads, runs, report, include_text)
    except Exception as error:
        partial = json.loads(report.read_text(encoding="utf-8"))
        partial.update(complete=False, status="failed", error_type=type(error).__name__)
        save_json(report, partial)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    synth = commands.add_parser("synthesize")
    synth.add_argument("--directory", type=Path, default=ROOT/"target/voice-quality-fixtures")
    synth.add_argument("--espeak", default="espeak-ng")
    run = commands.add_parser("evaluate")
    run.add_argument("--directory", type=Path, default=ROOT/"target/voice-quality-fixtures")
    run.add_argument("--model", type=Path, default=ROOT/"target/voice-probe")
    run.add_argument("--threads", type=int, choices=range(1, 9), default=2)
    run.add_argument("--runs", type=int, choices=range(1, 4), default=3)
    run.add_argument("--report", type=Path, default=ROOT/"target/voice-quality-report.json")
    run.add_argument("--include-text", action="store_true", help="Publish only the fixed non-private synthetic references and hypotheses")
    args = parser.parse_args()
    if args.command == "synthesize":
        generate(args.directory, args.espeak)
    else:
        evaluate(args.directory, args.model, args.threads, args.runs, args.report, args.include_text)


if __name__ == "__main__":
    main()
