#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run frozen synthetic labels against production dictionary/core/native ranker.

Linux host only. Does not download, log input text, or measure Android UI latency.
The frozen corpus is intentionally small and is not a population accuracy set.
"""
import argparse
import datetime
import json
import math
import os
from pathlib import Path
import platform
import select
import subprocess
import sys
import tempfile
import time

import onnxruntime

from default_pool_probe import Core, MODEL_ID, HOST_ORT_VERSION, ROOT, digest, verified

sys.path.insert(0, str(ROOT / "scripts"))
from candidate_evaluation import summarize  # noqa: E402

CORPUS = Path(__file__).with_name("quality-corpus-v1.json")
CORPUS_SHA256 = "c78d359e6e396befcddbf16eab286b8055bd8b16a748545aebc3856ac89109da"
FROZEN_COMMIT = "75ae851acb570abbd5295aafa52eda41b33f9c2b"


def require(condition):
    if not condition:
        raise ValueError("candidate_quality_contract_failed")


def memory(pid):
    fields = {}
    for line in Path(f"/proc/{pid}/status").read_text().splitlines():
        if line.startswith(("VmRSS:", "VmHWM:", "Threads:")):
            key, value = line.split(":", 1)
            fields[key] = int(value.split()[0])
    require(set(fields) == {"VmRSS", "VmHWM", "Threads"})
    return fields


class Probe:
    """Bounded wire reads and deadlines; process exit is always reaped."""
    def __init__(self, binary, model, runtime):
        self.buffer = b""
        started = time.perf_counter()
        self.process = subprocess.Popen([str(binary), str(model), str(runtime)],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, bufsize=0)
        try:
            require(self.receive() == {"ready": True})
            self.load_ms = (time.perf_counter() - started) * 1000
            self.loaded_memory = memory(self.process.pid)
        except BaseException:
            self.abort()
            raise

    def receive(self):
        deadline = time.monotonic() + 30
        while b"\n" not in self.buffer:
            remaining = deadline - time.monotonic()
            require(remaining > 0)
            require(bool(select.select([self.process.stdout], [], [], remaining)[0]))
            chunk = os.read(self.process.stdout.fileno(), 65536)
            require(bool(chunk))
            self.buffer += chunk
            require(len(self.buffer) <= 65536)
        line, self.buffer = self.buffer.split(b"\n", 1)
        return json.loads(line)

    def call(self, value):
        data = json.dumps(value, ensure_ascii=True).encode() + b"\n"
        # Fixed corpus produces <4KiB messages; never let stdin fill a pipe.
        require(len(data) <= 4096)
        started = time.perf_counter()
        require(self.process.stdin.write(data) == len(data))
        result = self.receive()
        require(isinstance(result, dict) and result.get("ok") is True)
        return result, (time.perf_counter() - started) * 1000

    def abort(self):
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait(timeout=10)
        self.process.stdin.close()
        self.process.stdout.close()

    def close(self):
        self.process.stdin.close()
        try:
            require(self.process.wait(timeout=10) == 0)
            require(not self.buffer and self.process.stdout.read(1) == b"")
        finally:
            self.abort()


def apply_order(core, handle, visible, result):
    rows = visible["ranking"]["candidates"]
    scores = result["scores"]
    require(len(scores) == len(rows) and all(type(x) in (int, float) and math.isfinite(x) for x in scores))
    order = sorted(range(len(rows)), key=lambda i: (-scores[i], i))
    ids = [rows[i]["id"] for i in order]
    ranked = core.request(handle, dict(op="candidate_rank", model=MODEL_ID,
                                      generation=visible["generation"], ids=ids))
    require(ranked["ranking"]["applied"] and ranked["preedit"] == visible["preedit"]
            and ranked["composition_revision"] == visible["composition_revision"])
    original_ids = visible["candidate_ids"]
    require(len(set(original_ids)) == len(original_ids)
            and set(ranked["candidate_ids"]) == set(original_ids))
    rankable = set(ids)
    require([x for x in ranked["candidate_ids"] if x in rankable] == ids)
    original_text = dict(zip(original_ids, visible["candidates"], strict=True))
    for index, (identifier, text) in enumerate(zip(ranked["candidate_ids"], ranked["candidates"], strict=True)):
        require(original_text[identifier] == text)
        if identifier not in rankable:
            require(original_ids[index] == identifier)
    return ranked


def observation(case, baseline, ranked, elapsed):
    expected = [identifier for identifier, text in zip(baseline["candidate_ids"], baseline["candidates"], strict=True)
                if text in case["expected"]]
    # An opaque absent ID keeps misses in the denominator without emitting text.
    return dict(id=case["id"], expected=expected or ["absent-target"],
                baseline=baseline["candidate_ids"], ranked=ranked["candidate_ids"], latency_ms=elapsed)


def load_corpus(path):
    require(digest(path) == CORPUS_SHA256)
    corpus = json.loads(path.read_text())
    require(corpus["synthetic"] is True and len(corpus["cases"]) == 34)
    require(len({(x.get("layout", "qwerty"), x["code"], x["context"]) for x in corpus["cases"]}) == 34)
    return corpus


def run_case(core, probe, resources, local, case, context):
    handle = core.open(resources, local)
    try:
        if case.get("layout") == "nine_key":
            core.request(handle, dict(op="layout", name="nine_key"))
        for character in case["code"]:
            visible = core.request(handle, dict(op="key", text=character))
        require(visible["ranking"]["enabled"] and visible["ranking"]["model"] == MODEL_ID)
        rows = visible["ranking"]["candidates"]
        require(len(rows) <= 32)
        elapsed, warm_ms = 0, None
        ranked = visible
        if len(rows) >= 2:
            probe.call(dict(op="clear"))
            request = dict(context=context, candidates=[row["text"] for row in rows])
            result, elapsed = probe.call(request)
            warm, warm_ms = probe.call(request)
            require(warm["scores"] == result["scores"] and warm["new_nodes"] == 0)
            require(result["context_nodes"] <= 65 and result["prefix_nodes"] <= 512)
            ranked = apply_order(core, handle, visible, result)
        sample = observation(case, visible, ranked, elapsed)
        return sample, dict(id=case["id"], category=case["category"],
                            model_called=len(rows) >= 2, rankable_count=len(rows),
                            visible_count=len(visible["candidates"]),
                            target_rankable=any(row["text"] in case["expected"] for row in rows),
                            warm_roundtrip_ms=warm_ms, worker_memory=memory(probe.process.pid))
    finally:
        core.close(handle)


def distribution(values):
    if not values:
        return dict(samples=0)
    values = sorted(values)
    return dict(samples=len(values), p50=values[math.ceil(len(values) * .5) - 1],
                p95=values[math.ceil(len(values) * .95) - 1], maximum=values[-1], method="nearest_rank")


def evaluate(core, probe, resources, corpus, reports):
    with tempfile.TemporaryDirectory(prefix="candidate-quality-", dir=ROOT / "target") as temporary:
        for mode in ("empty_context", "contextual"):
            samples, details = [], []
            progress = dict(completed=False, observations=samples, details=details)
            reports[mode] = progress
            for index, case in enumerate(corpus["cases"]):
                local = Path(temporary) / mode / str(index)
                local.mkdir(parents=True)
                try:
                    sample, detail = run_case(core, probe, resources, local, case,
                                              case["context"] if mode == "contextual" else "")
                except Exception:
                    progress["failed_case_id"] = case["id"]
                    raise
                samples.append(sample)
                details.append(detail)
            report = summarize(dict(schema_version=1, synthetic=True, cases=samples))
            # This runner, unlike the generic aggregator, executes the model.
            report["completed"] = True
            report["model_executed"] = True
            report["latency_scope"] = "all-case cache-cleared host IPC score; no-model cases are zero; no debounce/core/UI time"
            report["score_roundtrip_ms"] = distribution([s["latency_ms"] for s, d in zip(samples, details, strict=True) if d["model_called"]])
            report["warm_roundtrip_ms"] = distribution([d["warm_roundtrip_ms"] for d in details if d["model_called"]])
            report["model_calls"] = sum(d["model_called"] for d in details)
            report["by_category"] = {category: summarize(dict(schema_version=1, synthetic=True,
                                       cases=[s for s, d in zip(samples, details, strict=True) if d["category"] == category]))
                                     for category in sorted({x["category"] for x in details})}
            report["observations"] = samples
            report["details"] = details
            reports[mode] = report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core", type=Path, default=ROOT / "target/release/libzapime_core.so")
    parser.add_argument("--resources", type=Path, default=ROOT / "target/resources")
    parser.add_argument("--model-dir", type=Path, default=ROOT / "target/candidate-model" / MODEL_ID)
    parser.add_argument("--probe", type=Path, default=ROOT / "target/candidate-host/ranker_probe")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    require(platform.system() == "Linux" and onnxruntime.__version__ == HOST_ORT_VERSION)
    corpus = load_corpus(CORPUS)
    runtime = next((Path(onnxruntime.__file__).parent / "capi").glob("libonnxruntime.so*")).resolve()
    lock_path = ROOT / "third_party/candidate-model-lock.json"
    lock = json.loads(lock_path.read_text())
    for name, spec in lock["files"].items():
        verified(args.model_dir / name, spec)
    dictionary_lock_path = ROOT / "vendor/msime/resources/editions/pinyin.lock.json"
    dictionary_lock = json.loads(dictionary_lock_path.read_text())
    wanted = {"msime-pinyin.db", "msime-english.db", "msime-scowl_Copyright.txt", "msime-dictionary-manifest.json"}
    specs = {x["name"]: x for x in dictionary_lock["artifacts"] if x["name"] in wanted}
    require(set(specs) == wanted)
    for name, spec in specs.items():
        verified(args.resources / name, spec)
    require(json.loads((args.resources / "msime-dictionary-manifest.json").read_text())["licensing"]["includes_unlicensed_inputs"] is False)
    report = dict(schema_version=1, scope="frozen_synthetic_linux_host_quality_not_android_or_population_accuracy",
                  completed=False, phone_performance_evaluated=False, energy_evaluated=False,
                  utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  corpus_sha256=CORPUS_SHA256, corpus_frozen_commit=FROZEN_COMMIT,
                  host=dict(os=platform.system(), kernel=platform.release(), machine=platform.machine(),
                            logical_cpus=os.cpu_count(), python=platform.python_version()),
                  runtime_version=HOST_ORT_VERSION, model_id=MODEL_ID,
                  configured_threads=dict(intra_op=2, inter_op=1, spinning=False),
                  identity={name: digest(path) for name, path in {
                      "core_binary": args.core, "native_probe": args.probe,
                      "runtime": runtime, "model": args.model_dir / "lm_step.ort",
                      "dictionary": args.resources / "msime-pinyin.db", "model_lock": lock_path,
                      "dictionary_lock": dictionary_lock_path, "runner": Path(__file__),
                      "ranking_source": ROOT / "crates/zapime-core/src/ranking.rs"}.items()})
    # Create-only before work prevents an old success being mistaken for this run.
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open("x", encoding="utf-8") as output:
        probe = None
        try:
            core = Core(args.core.resolve())
            probe = Probe(args.probe.resolve(), args.model_dir.resolve(), runtime)
            report["model_process_load_ms"] = probe.load_ms
            report["loaded_worker_memory_kib"] = probe.loaded_memory
            report["modes"] = {}
            evaluate(core, probe, args.resources.resolve(), corpus, report["modes"])
            report["final_worker_memory_kib"] = memory(probe.process.pid)
            probe.close()
            probe = None
            report["completed"] = True
        except Exception:
            report["failure"] = "candidate_quality_execution_failed"
            raise
        finally:
            try:
                if probe is not None:
                    probe.abort()
            finally:
                output.write(json.dumps(report, indent=2) + "\n")
    print(json.dumps(dict(completed=True, cases=34, model_executed=True, phone_performance_evaluated=False)))


if __name__ == "__main__":
    main()
