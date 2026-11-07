#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Freeze upstream inputs, not observed device outputs, for a test-only oracle.

This imports the verbatim pinned upstream feed builders. It does not execute
CandidateRanker, use its tokenizer/trie/cache, or consume Android score reports.
No numerical expected scores are stored in the fixture.
"""
import argparse
import hashlib
import json
from pathlib import Path

from upstream_feeds import context_inputs, step_inputs

ROOT = Path(__file__).resolve().parents[3]
MODEL_ID = "msime-keystroke-8m-rerank-v2"
FEEDS_SHA256 = "2268e105d6da10b7e2baab44ae739f9da0db86c031c8886e95b77d57fa35707d"
CONTEXT = "朋友见面先说"
WORDS = ["拟好", "逆号", "你好"]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tensor(kind, shape, values):
    flat = []
    for value in values:
        flat.extend(value if isinstance(value, list) else [value])
    return {"type": kind, "shape": shape, "values": flat}


def feed(nodes, positions, visible, candidates, steps, lengths):
    return {
        "nodes": tensor("int64", [len(nodes)], nodes),
        "positions": tensor("int64", [len(positions)], positions),
        "visible": tensor("bool", [len(visible), len(visible[0])], visible),
        "candidates": tensor("int64", [len(candidates), len(candidates[0])], candidates),
        "steps": tensor("int64", [len(steps), len(steps[0])], steps),
        "lengths": tensor("int64", [len(lengths)], lengths),
    }


def generate(model):
    source = Path(__file__).with_name("upstream_feeds.py")
    if digest(source) != FEEDS_SHA256:
        raise ValueError("pinned upstream feed source differs")
    lock = json.loads((ROOT / "third_party/candidate-model-lock.json").read_text())
    if lock["model_id"] != MODEL_ID:
        raise ValueError("unexpected model lock")
    for name in ("lm_step.ort", "model.json", "vocab.json"):
        spec = lock["files"][name]
        if (model / name).stat().st_size != spec["size"] or digest(model / name) != spec["sha256"]:
            raise ValueError("locked model artifact mismatch: " + name)
    meta = json.loads((model / "model.json").read_text())
    vocab = json.loads((model / "vocab.json").read_text())["tokens"]
    ids = {token: index for index, token in enumerate(vocab) if len(token) == 1}
    context = [meta["bos"], *[ids.get(char, meta["unk"]) for char in CONTEXT]]
    bodies = [[ids.get(char, meta["unk"]) for char in word] for word in WORDS]
    prefill = feed(*context_inputs(context))
    nodes, positions, visible, steps, cached = step_inputs(len(context), bodies, [])
    width = max(map(len, bodies))
    candidates = [body + [0] * (width - len(body)) for body in bodies]
    page = feed(nodes, positions, visible, candidates, steps, list(map(len, bodies)))
    if len(context) != 7 or len(cached) != 3:
        raise ValueError("fixed fixture topology changed")
    return {
        "schema_version": 1,
        "fixture_id": "candidate-upstream-8m-two-stage-v1",
        "model_id": MODEL_ID,
        "model_sha256": lock["files"]["lm_step.ort"]["sha256"],
        "metadata_sha256": lock["files"]["model.json"]["sha256"],
        "vocab_sha256": lock["files"]["vocab.json"]["sha256"],
        "upstream": {
            "repository": "https://github.com/metasequoiaime/chinese-ime-lm",
            "commit": "5b82758eaf3419dff9555f41bbda2744a56e45c4",
            "path": "neural/choice/model.py",
            "git_blob_sha1": "933af7444124970a221b3bf1f10a81599c9170d1",
            "copied_feeds_sha256": FEEDS_SHA256,
        },
        "synthetic_context": CONTEXT,
        "synthetic_candidates": WORDS,
        "dimensions": {key: meta[key] for key in ("layers", "heads", "head_dim", "width")},
        "empty_cache_shapes": [
            [meta["layers"], meta["heads"], 0, meta["head_dim"]],
            [meta["layers"], meta["heads"], 0, meta["head_dim"]],
            [0, meta["width"]], [0],
        ],
        "calls": [prefill, page],
        "scope": "Independent upstream tensors; no device-derived expected scores. Both stages must run with unchanged batching on the same model and device runtime as production.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, default=ROOT / "target/candidate-model" / MODEL_ID)
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("oracle-fixture.json"))
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    encoded = (json.dumps(generate(args.model_dir.resolve()), indent=2, ensure_ascii=True) + "\n").encode()
    if args.check:
        if args.output.read_bytes() != encoded:
            raise SystemExit("oracle fixture differs from pinned upstream generation")
    else:
        args.output.write_bytes(encoded)
    print(json.dumps({"fixture": str(args.output), "bytes": len(encoded),
                      "sha256": hashlib.sha256(encoded).hexdigest(), "check": args.check}))


if __name__ == "__main__":
    main()
