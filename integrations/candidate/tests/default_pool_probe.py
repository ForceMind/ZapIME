#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run three fixed synthetic inputs through the real dictionary, core and model.

This is a mechanical integration probe, not a language-accuracy benchmark.
It downloads nothing, creates fresh temporary user databases and records only
the fixed fixtures below. Timings include host process/pipe overhead; they are
not Android, ARM, release-core or end-user latency measurements.
"""
import argparse
import ctypes
import hashlib
import json
import math
from pathlib import Path
import statistics
import subprocess
import tempfile
import time

import onnxruntime


ROOT = Path(__file__).resolve().parents[3]
MODEL_ID = "msime-keystroke-8m-rerank-v2"
HOST_ORT_VERSION = "1.24.4"
CASES = (
    ("shijie", "我们一起探索", ("世界上", "世界杯", "世界各地")),
    ("tianqi", "今天的", ("天气预报", "天气变化", "天气情况")),
    ("nihao", "朋友见面先说", ("你好吗", "你好啊", "你好呀")),
)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verified(path, spec):
    require(path.is_file() and path.stat().st_size == spec["size"]
            and digest(path) == spec["sha256"], f"locked artifact mismatch: {path.name}")


class Buffer(ctypes.Structure):
    _fields_ = [("data", ctypes.POINTER(ctypes.c_uint8)), ("len", ctypes.c_size_t)]


class Core:
    def __init__(self, path):
        self.library = ctypes.CDLL(str(path))
        self.library.zapime_abi_version.restype = ctypes.c_uint32
        self.library.zapime_open.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
        self.library.zapime_open.restype = ctypes.c_uint64
        self.library.zapime_request.argtypes = [ctypes.c_uint64, ctypes.c_void_p, ctypes.c_size_t]
        self.library.zapime_request.restype = Buffer
        self.library.zapime_buffer_free.argtypes = [Buffer]
        self.library.zapime_buffer_free.restype = None
        self.library.zapime_close.argtypes = [ctypes.c_uint64]
        self.library.zapime_close.restype = ctypes.c_bool
        require(self.library.zapime_abi_version() == 1, "unsupported core ABI")

    @staticmethod
    def encoded(value):
        data = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
        return ctypes.create_string_buffer(data), len(data)

    def open(self, resources, local):
        data, length = self.encoded({"resources": str(resources), "data": str(local / "data"),
                                     "cache": str(local / "cache"), "private": False})
        handle = self.library.zapime_open(data, length)
        require(bool(handle), "real core open failed")
        return handle

    def request(self, handle, value):
        data, length = self.encoded(value)
        result = self.library.zapime_request(handle, data, length)
        try:
            require(bool(result.data) and 0 < result.len < 1_048_576, "invalid core response")
            response = json.loads(ctypes.string_at(result.data, result.len))
            require("error" not in response, f"core rejected operation: {response.get('error')}")
            return response
        finally:
            self.library.zapime_buffer_free(result)

    def close(self, handle):
        require(self.library.zapime_close(handle), "core close failed")


class Scorer:
    def __init__(self, binary, model, runtime):
        started = time.perf_counter()
        self.process = subprocess.Popen([str(binary), str(model), str(runtime)],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True)
        try:
            require(json.loads(self.process.stdout.readline()) == {"ready": True}, "native open")
        except BaseException:
            self.process.kill()
            self.process.communicate()
            raise
        self.load_ms = (time.perf_counter() - started) * 1000

    def call(self, value):
        started = time.perf_counter()
        self.process.stdin.write(json.dumps(value, ensure_ascii=False) + "\n")
        self.process.stdin.flush()
        response = json.loads(self.process.stdout.readline())
        elapsed = (time.perf_counter() - started) * 1000
        require(response["ok"], "real native score/clear failed")
        return response, elapsed

    def close(self):
        self.process.stdin.close()
        try:
            require(self.process.wait(timeout=20) == 0, "native process exit")
            require(not self.process.stderr.read(), "unexpected native diagnostics")
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait()


def run_case(core, scorer, resources, local, pinyin, context, completions):
    started = time.perf_counter()
    handle = core.open(resources, local)
    open_ms = (time.perf_counter() - started) * 1000
    try:
        key_ms, pool_counts = [], []
        for character in pinyin:
            started = time.perf_counter()
            visible = core.request(handle, {"op": "key", "text": character})
            key_ms.append((time.perf_counter() - started) * 1000)
            pool_counts.append(len(visible["ranking"]["candidates"]))
        ranking = visible["ranking"]
        require(ranking["enabled"] and ranking["model"] == MODEL_ID, "default model eligibility")
        rows = ranking["candidates"]
        require(2 <= len(rows) <= 32, "expected multiple bounded production candidates")
        require(all(text not in [row["text"] for row in rows] for text in completions),
                "completion leaked into exact-conversion ranking")
        require(all(text in visible["candidates"] for text in completions),
                "completion was removed from ordinary candidates")
        scorer.call({"op": "clear"})
        page = {"context": "", "candidates": [row["text"] for row in rows]}
        result, first_ms = scorer.call(page)
        require(len(result["scores"]) == len(rows) and all(map(math.isfinite, result["scores"])),
                "invalid real model scores")
        order = sorted(range(len(rows)), key=lambda index: (-result["scores"][index], index))
        warm_ms = []
        for _ in range(5):
            warm, elapsed = scorer.call(page)
            require(warm["scores"] == result["scores"] and warm["new_nodes"] == 0,
                    "cached page did not reuse equivalent real scores")
            warm_ms.append(elapsed)
        contextual, context_ms = scorer.call(page | {"context": context})
        require(len(contextual["scores"]) == len(rows)
                and all(map(math.isfinite, contextual["scores"])), "invalid contextual scores")
        context_order = sorted(range(len(rows)), key=lambda index: (-contextual["scores"][index], index))
        ordered_ids = [rows[index]["id"] for index in order]
        ranked = core.request(handle, {"op": "candidate_rank", "generation": visible["generation"],
                                       "model": MODEL_ID, "ids": ordered_ids})
        require(ranked["ranking"]["applied"], "real order not applied")
        require(ranked["composition_revision"] == visible["composition_revision"],
                "rank changed composition identity")
        eligible_ids = {row["id"] for row in rows}
        require([identifier for identifier in ranked["candidate_ids"] if identifier in eligible_ids]
                == ordered_ids, "visible order differs from real scores")
        for index, identifier in enumerate(visible["candidate_ids"]):
            if identifier not in eligible_ids:
                require(ranked["candidate_ids"][index] == identifier
                        and ranked["candidates"][index] == visible["candidates"][index],
                        "noneligible candidate slot moved")
        selected_id, selected_text = rows[order[0]]["id"], rows[order[0]]["text"]
        committed = core.request(handle, {"op": "select", "id": selected_id,
                                          "index": ranked["candidate_ids"].index(selected_id),
                                          "generation": ranked["generation"],
                                          "composition_revision": ranked["composition_revision"]})
        require(committed["commit"] == selected_text and not committed["preedit"],
                "selected ID did not commit its full candidate")
        return {"pinyin": pinyin, "preedit": visible["preedit"],
                "visible_count": len(visible["candidates"]), "visible": visible["candidates"],
                "rankable_count": len(rows), "rankable": rows, "per_key_rankable_counts": pool_counts,
                "host_core_open_ms": open_ms, "host_core_per_key_ms": key_ms,
                "empty_context_scores": result["scores"],
                "empty_context_order": [rows[index]["text"] for index in order],
                "empty_context_order_changed": order != list(range(len(rows))),
                "native_first_page_ms": first_ms, "native_warm_page_ms": warm_ms,
                "native_warm_page_median_ms": statistics.median(warm_ms),
                "fixed_synthetic_context": context, "context_scores": contextual["scores"],
                "context_order": [rows[index]["text"] for index in context_order],
                "context_page_ms": context_ms, "rank_applied": True,
                "generation_changed": ranked["generation"] != visible["generation"],
                "noneligible_slots_unchanged": True, "selected_commit": committed["commit"],
                "selected_cleared_full_input": True, "mechanical_passed": True}
    finally:
        core.close(handle)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core", type=Path, default=ROOT / "target/debug/libzapime_core.so")
    parser.add_argument("--resources", type=Path, default=ROOT / "target/resources")
    parser.add_argument("--model-dir", type=Path, default=ROOT / "target/candidate-model" / MODEL_ID)
    parser.add_argument("--probe", type=Path, default=ROOT / "target/candidate-host/ranker_probe")
    parser.add_argument("--runtime", type=Path, help="copy of the pinned Python ORT runtime, if relocated")
    parser.add_argument("--report", type=Path,
                        default=ROOT / "target/candidate-host/default-pools-exact-report.json")
    args = parser.parse_args()
    require(onnxruntime.__version__ == HOST_ORT_VERSION, "use the pinned host requirements.txt")
    installed_runtime = next((Path(onnxruntime.__file__).parent / "capi").glob("libonnxruntime.so*"))
    runtime = (args.runtime or installed_runtime).resolve()
    require(digest(runtime) == digest(installed_runtime), "runtime differs from pinned host environment")
    core_path, resources, model, binary = (args.core.resolve(), args.resources.resolve(),
                                          args.model_dir.resolve(), args.probe.resolve())
    model_lock_path = ROOT / "third_party/candidate-model-lock.json"
    dictionary_lock_path = ROOT / "vendor/msime/resources/editions/pinyin.lock.json"
    model_lock = json.loads(model_lock_path.read_text())
    require(model_lock["model_id"] == MODEL_ID, "unsupported model lock")
    for name, spec in model_lock["files"].items():
        verified(model / name, spec)
    dictionary_lock = json.loads(dictionary_lock_path.read_text())
    required_resources = {"msime-pinyin.db", "msime-english.db", "msime-scowl_Copyright.txt",
                          "msime-dictionary-manifest.json"}
    specs = {item["name"]: item for item in dictionary_lock["artifacts"]
             if item["name"] in required_resources}
    require(set(specs) == required_resources, "incomplete dictionary lock")
    for name, spec in specs.items():
        verified(resources / name, spec)
    manifest = json.loads((resources / "msime-dictionary-manifest.json").read_text())
    require(manifest["licensing"]["includes_unlicensed_inputs"] is False, "unlicensed dictionary")
    report = {"scope": "fixed_synthetic_production_dictionary_host_mechanical_integration",
              "mechanical_passed": False, "language_accuracy_evaluated": False,
              "android_performance_evaluated": False, "personal_user_data_used": False,
              "core_shared_sha256": digest(core_path), "core_path": str(core_path),
              "core_build": "caller must build the current sources; binary hash identifies executed version",
              "ranking_source_sha256": digest(ROOT / "crates/zapime-core/src/ranking.rs"),
              "probe_sha256": digest(binary), "script_sha256": digest(Path(__file__)),
              "model_id": MODEL_ID, "model_sha256": digest(model / "lm_step.ort"),
              "model_lock_sha256": digest(model_lock_path),
              "dictionary_sha256": digest(resources / "msime-pinyin.db"),
              "dictionary_lock_sha256": digest(dictionary_lock_path),
              "runtime_version": HOST_ORT_VERSION, "runtime_sha256": digest(runtime),
              "timing_scope": "Linux host wall clock including pipe/process overhead; not Android latency",
              "cases": []}
    report_path = args.report.resolve()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    scorer = None
    try:
        core = Core(core_path)
        scorer = Scorer(binary, model, runtime)
        report["native_model_process_load_ms"] = scorer.load_ms
        (ROOT / "target").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="candidate-default-pools-", dir=ROOT / "target") as temporary:
            for index, (pinyin, context, completions) in enumerate(CASES):
                local = Path(temporary) / str(index)
                local.mkdir()
                report["cases"].append(run_case(core, scorer, resources, local, pinyin, context, completions))
        scorer.close()
        scorer = None
        report["mechanical_passed"] = True
    except Exception as error:
        report["failure"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        try:
            if scorer is not None:
                scorer.close()
        finally:
            report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"report": str(report_path), "mechanical_passed": True,
                      "language_accuracy_evaluated": False,
                      "rankable_counts": {case["pinyin"]: case["rankable_count"] for case in report["cases"]}},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
