#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fixed public research assets, independent from all product model packs."""
import hashlib
import os
from pathlib import Path

ROOT = Path(os.environ.get("ZAPIME_QWEN_PROBE_WORKDIR", Path(__file__).resolve().parents[2] / "target" / "qwen-translation-probe")).resolve()
MODEL_REVISION = "23749fefcc72300e3a2ad315e1317431b06b590a"
TOKENIZER_REVISION = "c1899de289a04d12100db370d81485cdf75e47ca"
RUNTIME_COMMIT = "620d2225cc436ab0b001e3200e590e3b286ef9ab"
RUNTIME_TAG = "b11669"
ASSETS = {
    "Qwen3-0.6B-Q8_0.gguf": (
        "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/" + MODEL_REVISION + "/Qwen3-0.6B-Q8_0.gguf",
        639446688, "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031", "publisher Hugging Face LFS SHA256",
    ),
    "model-LICENSE.txt": (
        "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/" + MODEL_REVISION + "/LICENSE",
        11544, "5de36594c10839788a8c589443a8ef9d8b8d17c65a1b5807206ae037fc36c6bd", "observed reviewed HTTPS bytes",
    ),
    "model-README.md": (
        "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/" + MODEL_REVISION + "/README.md",
        6236, "44adabae2153c3a7bb741b86fbffd48736bc95118831935fdfc4d3cc1854f2f5", "observed reviewed HTTPS bytes",
    ),
    "tokenizer_config.json": (
        "https://huggingface.co/Qwen/Qwen3-0.6B/resolve/" + TOKENIZER_REVISION + "/tokenizer_config.json",
        9732, "d5d09f07b48c3086c508b30d1c9114bd1189145b74e982a265350c923acd8101", "observed reviewed HTTPS bytes",
    ),
    "llama-b11669-bin-ubuntu-x64.tar.gz": (
        "https://github.com/ggml-org/llama.cpp/releases/download/b11669/llama-b11669-bin-ubuntu-x64.tar.gz",
        17851611, "cf07c1c112d709bd67e522017471de57ed0ec8bea031d732c5422351c07f65ac", "publisher GitHub release asset digest",
    ),
    "runtime-LICENSE.txt": (
        "https://raw.githubusercontent.com/ggml-org/llama.cpp/" + RUNTIME_COMMIT + "/LICENSE",
        1078, "94f29bbed6a22c35b992c5c6ebf0e7c92f13b836b90f36f461c9cf2f0f1d010d", "observed reviewed HTTPS bytes",
    ),
    "completion.cpp": (
        "https://raw.githubusercontent.com/ggml-org/llama.cpp/" + RUNTIME_COMMIT + "/tools/completion/completion.cpp",
        38472, "0dde1fed7b17e9b37ad0cf3e125210dd1c316364cf2924f24d0f41c38538677b", "observed reviewed HTTPS bytes",
    ),
    "arg.cpp": (
        "https://raw.githubusercontent.com/ggml-org/llama.cpp/" + RUNTIME_COMMIT + "/common/arg.cpp",
        223600, "a10b6e6af910366c5c1abb89e47a7c6711fc1f10708b5fbfd8b59ba1a11d0f4a", "observed reviewed HTTPS bytes",
    ),
}


def digest_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_asset(name):
    _, size, digest, _ = ASSETS[name]
    path = ROOT / name
    if path.stat().st_size != size or digest_file(path) != digest:
        raise RuntimeError("research asset differs: " + name)
    return {"name": name, "bytes": size, "sha256": digest}
