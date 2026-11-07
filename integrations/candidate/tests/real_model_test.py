#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Compare the real native backend to pinned upstream feeds, with synthetic text.

No model/network downloads happen here. The build-time resource preparer must
have verified the release first. This is numerical/lifecycle coverage, not a
user accuracy or Android latency benchmark. Reports never include input text.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random
import subprocess
import time

import numpy as np
import onnxruntime as ort

from upstream_feeds import context_inputs, step_inputs

ROOT = Path(__file__).resolve().parents[3]
CACHE_NAMES = ["cache_k", "cache_v", "cache_h", "cache_z"]
CASES = [
    ("greeting", "朋友见面先说", ["拟好", "逆号", "你好"]),
    ("single_character", "下雨了记得带", ["伞", "散", "三"]),
    ("shared_prefix", "我们下一站去", ["北京市", "北京站", "北京大学", "背景色", "上海市", "上海站"]),
    ("mixed_ascii", "开发工具里输入", ["Python", "拼音", "拼音输入法", "Pinyin"]),
    ("unicode", "测试😀中文", ["你好", "𠮷", "😀", "a\u0301"]),
    ("empty_context", "", ["你", "你好", "拟好"]),
    ("context_tail", "仅用于合成测试的前缀" * 12 + "朋友见面先说", ["拟好", "逆号", "你好"]),
    ("different_lengths", "西北方向有", ["西安", "现", "先", "西安市"]),
]
LONG_CANDIDATES = [
    "今天我们准备一起前往图书馆阅读新出版的科学书籍然后在附近的公园散步聊天",
    "明天老师安排同学们参观自然博物馆观察各种植物和动物并记录自己的发现",
    "周末家人打算先到菜市场购买新鲜蔬菜再回家共同准备一顿丰盛的晚餐",
    "下午工程师将检查新的设备运行情况整理测试数据并向项目成员说明结果",
    "晚上朋友们决定在社区活动中心练习音乐演奏随后讨论下一次公开表演",
    "上午护士认真核对病房里的物资清单为接下来需要使用的工具做好准备",
    "昨天邻居帮助我们修好了阳台上的旧花架今天终于可以把花盆重新摆放整齐",
    "下周团队会把已经完成的设计方案交给评审小组再根据反馈逐步修改细节",
]


def require(condition, name):
    if not condition:
        raise AssertionError(name)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


class Reference:
    def __init__(self, model):
        self.meta = json.loads((model / "model.json").read_text())
        tokens = json.loads((model / "vocab.json").read_text())["tokens"]
        self.ids = {token: i for i, token in enumerate(tokens) if len(token) == 1}
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.enable_cpu_mem_arena = False
        options.enable_mem_pattern = False
        options.add_session_config_entry("session.intra_op.allow_spinning", "0")
        options.add_session_config_entry("session.inter_op.allow_spinning", "0")
        options.log_severity_level = 4
        self.session = ort.InferenceSession(str(model / "lm_step.ort"), options,
                                            providers=["CPUExecutionProvider"])
        self.reset()

    def reset(self):
        self.context = None
        self.cached = []
        self.cache = None

    def encode(self, text):
        return [self.ids.get(char, self.meta["unk"]) for char in text]

    def score(self, context, candidates):
        context = [self.meta["bos"], *self.encode(context[-64:])]
        if self.context != context:
            self.context, self.cached = context, []
            m = self.meta
            empty = {
                "cache_k": np.zeros((m["layers"], m["heads"], 0, m["head_dim"]), np.float32),
                "cache_v": np.zeros((m["layers"], m["heads"], 0, m["head_dim"]), np.float32),
                "cache_h": np.zeros((0, m["width"]), np.float32),
                "cache_z": np.zeros((0,), np.float32),
            }
            nodes, positions, visible, rows, steps, lengths = context_inputs(context)
            feed = self.feed(nodes, positions, visible, rows, steps, lengths)
            result = self.session.run(None, empty | feed)
            self.cache = dict(zip(CACHE_NAMES, result[1:], strict=True))
        bodies = [self.encode(candidate) for candidate in candidates]
        nodes, positions, visible, steps, self.cached = step_inputs(len(context), bodies, self.cached)
        width = max(map(len, bodies))
        rows = [body + [0] * (width - len(body)) for body in bodies]
        feed = self.feed(nodes, positions, visible, rows, steps, list(map(len, bodies)))
        feed["visible"] = feed["visible"].reshape(len(nodes), len(context) + len(self.cached))
        result = self.session.run(None, self.cache | feed)
        self.cache = dict(zip(CACHE_NAMES, result[1:], strict=True))
        return result[0]

    @staticmethod
    def feed(nodes, positions, visible, rows, steps, lengths):
        return {"nodes": np.asarray(nodes, np.int64), "positions": np.asarray(positions, np.int64),
                "visible": np.asarray(visible, bool), "candidates": np.asarray(rows, np.int64),
                "steps": np.asarray(steps, np.int64), "lengths": np.asarray(lengths, np.int64)}


class Probe:
    def __init__(self, binary, model, runtime):
        self.process = subprocess.Popen([str(binary), str(model), str(runtime)], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        ready = self.process.stdout.readline()
        require(ready and json.loads(ready) == {"ready": True}, "native_open")

    def call(self, **request):
        self.process.stdin.write(json.dumps(request, ensure_ascii=True) + "\n")
        self.process.stdin.flush()
        result = self.process.stdout.readline()
        require(bool(result), "native_response")
        return json.loads(result)

    def close(self):
        self.process.stdin.close()
        require(self.process.wait(timeout=20) == 0, "native_clean_exit")
        require(not self.process.stderr.read(), "native_no_input_diagnostics")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--runtime", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    model, binary = args.model_dir.resolve(), args.probe.resolve()
    runtime = args.runtime or next((Path(ort.__file__).parent / "capi").glob("libonnxruntime.so*"))
    runtime = runtime.resolve()
    lock = json.loads((ROOT / "third_party/candidate-model-lock.json").read_text())
    for name in ("lm_step.ort", "model.json", "vocab.json"):
        expected = lock["files"][name]
        require((model / name).stat().st_size == expected["size"] and digest(model / name) == expected["sha256"],
                "fixed_release_" + name)

    reference, probe = Reference(model), Probe(binary, model, runtime)
    checked = []
    maximum_error = 0.0
    timings = []
    try:
        for name, context, candidates in CASES:
            require(probe.call(op="clear")["ok"], "clear_" + name)
            reference.reset()
            expected = reference.score(context, candidates)
            started = time.monotonic()
            result = probe.call(context=context, candidates=candidates)
            timings.append((time.monotonic() - started) * 1000)
            require(result["ok"], "score_" + name)
            actual = np.asarray(result["scores"], np.float32)
            error = float(np.max(np.abs(actual - expected)))
            maximum_error = max(maximum_error, error)
            require(np.allclose(actual, expected, rtol=0, atol=2e-5), "upstream_scores_" + name)
            require(result["context_nodes"] <= 65 and result["prefix_nodes"] <= 512, "cache_bounds")
            if name == "greeting":
                require(int(np.argmax(actual)) == 2, "synthetic_greeting_promoted")
            if name == "single_character":
                require(float(np.ptp(actual)) > 0.1, "first_character_is_scored")
            checked.append(name)

        # Real keystroke prefixes, repeated pages, backspace, and changed branches
        # are compared to the unmodified upstream incremental feed builders.
        context = "我们下一站去"
        completed = ["北京大学", "北京大学生", "背景颜色", "北方大学", "上海大学", "上海大学生"]
        pages = [list(dict.fromkeys(c[:n] for c in completed)) for n in range(1, 6)]
        pages += [pages[-1], pages[1], ["北方", "北京人", "上海市"]]
        probe.call(op="clear")
        reference.reset()
        for index, candidates in enumerate(pages):
            expected = reference.score(context, candidates)
            result = probe.call(context=context, candidates=candidates)
            require(result["ok"] and np.allclose(result["scores"], expected, rtol=0, atol=2e-5),
                    "incremental_" + str(index))
            if index in (5, 6):
                require(result["new_nodes"] == 0, "cached_and_backspace_zero_nodes")
        checked.append("incremental_repeat_backspace_branch")

        # A foreign caller must fail without evicting the owner's warm cache.
        require(probe.call(op="foreign")["ok"], "foreign_owner_rejected")
        warm = probe.call(context=context, candidates=pages[-1])
        require(warm["ok"] and warm["new_nodes"] == 0, "foreign_keeps_owner_cache")
        checked.append("owner_isolation")

        probe.call(op="cancel")
        for _ in range(2):
            require(not probe.call(context=context, candidates=pages[-1])["ok"], "sticky_cancel")
        require(probe.call(op="clear")["ok"], "clear_cancel")
        require(probe.call(context=context, candidates=pages[-1])["ok"], "score_after_clear")
        checked.append("cross_thread_cancel_clear")

        suffix = "单独输入框的合成上下文" * 8 + "朋友见面先说"
        suffix = suffix[-64:]
        require(len(suffix) == 64, "context_clipping_fixture_length")
        a = probe.call(context=suffix, candidates=["你好", "拟好"])
        b = probe.call(context="不应参与模型的旧文字😀" * 6 + suffix, candidates=["你好", "拟好"])
        require(a["ok"] and b["ok"] and a["scores"] == b["scores"] and b["new_nodes"] == 0,
                "last_64_unicode_context")
        checked.append("context_clipping")

        for name, candidates in [("empty_pool", []), ("empty_row", [""]),
                                 ("row_count", ["你"] * 33), ("row_length", ["你" * 65]),
                                 ("invalid_unicode", ["\ud800"])]:
            require(not probe.call(context="", candidates=candidates)["ok"], name)
        require(not probe.call(context="x" * 4097, candidates=["你"])["ok"], "context_input_limit")
        require(probe.call(context="", candidates=["你"])["ok"], "invalid_input_recovery")
        checked.append("invalid_input_bounds_and_recovery")

        # A long natural-language page forces bounded batches; uint8 activation
        # quantization can differ slightly from upstream's single large batch.
        # Fix the numerical ceiling before executing, and report the observed
        # delta rather than claiming exact full-page equivalence.
        probe.call(op="clear")
        reference.reset()
        result = probe.call(context="以下是合成的行程安排：", candidates=LONG_CANDIDATES)
        expected = reference.score("以下是合成的行程安排：", LONG_CANDIDATES)
        batch_delta = float(np.max(np.abs(np.asarray(result.get("scores", [])) - expected)))
        require(result["ok"] and result["batches"] >= 3, "bounded_batches_exercised")
        require(batch_delta <= 0.1, "bounded_batch_quantization_delta")
        checked.append("bounded_batch_reference")

        # Maximum-size synthetic input, followed by a different large page,
        # forces eviction without letting a page or retained cache grow freely.
        random_source = random.Random(20261107)
        alphabet = "天地人和春夏秋冬东西南北山川河流日月星辰风雨雪云红黄蓝绿文理工农音体美英"
        heavy = ["".join(random_source.choice(alphabet) for _ in range(64)) for _ in range(32)]
        heavy_result = probe.call(context="合成压力测试", candidates=heavy)
        require(heavy_result["ok"] and len(heavy_result["scores"]) == 32 and
                all(np.isfinite(heavy_result["scores"])) and heavy_result["evictions"] > 0 and
                heavy_result["prefix_nodes"] <= 512 and heavy_result["context_nodes"] <= 65,
                "bounded_cache_eviction")
        checked.append("maximum_page_cache_eviction")

        report = {
            "schema_version": 1, "scope": "synthetic_real_model_native_cpu_not_android_accuracy",
            "passed": True, "model_id": lock["model_id"],
            "model_sha256": lock["files"]["lm_step.ort"]["sha256"],
            "native_probe_sha256": digest(binary), "runtime_file_sha256": digest(runtime),
            "reference_source": {
                "repository": "https://github.com/metasequoiaime/chinese-ime-lm",
                "commit": "5b82758eaf3419dff9555f41bbda2744a56e45c4",
                "path": "neural/choice/model.py",
                "git_blob_sha1": "933af7444124970a221b3bf1f10a81599c9170d1",
                "copied_feeds_sha256": digest(Path(__file__).with_name("upstream_feeds.py")),
            },
            "python_ort_version": ort.__version__, "numpy_version": np.__version__,
            "checks": checked, "normal_feed_max_abs_error": maximum_error,
            "bounded_batch_max_abs_error": batch_delta,
            "maximum_page": {key: heavy_result[key] for key in
                             ("context_nodes", "prefix_nodes", "new_nodes", "batches", "evictions")},
            "illustrative_host_roundtrip_ms": {"samples": len(timings), "max": max(timings)},
            "not_validated": ["Android_ORT_1.28.2", "ARM_device", "input_accuracy", "energy", "RSS"],
        }
    finally:
        probe.close()
    # Publish success only after the actual native process has exited cleanly.
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
