#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Observed locks for a developer-only official OPUS 2021 CPU experiment."""
import hashlib
import importlib.metadata
import os
from pathlib import Path
import platform
import sys

ROOT = Path(os.environ.get("ZAPIME_TRANSLATION_2021_WORKDIR", Path(__file__).resolve().parents[2] / "target/translation-probe-2021")).resolve()
URL = "https://object.pouta.csc.fi/Tatoeba-MT-models/zho-eng/opus-2021-02-18.zip"
ARCHIVE = "opus-2021-02-18.zip"
SIZE = 288551434
SHA256 = "cdef260995db149d7d519d930bd30c9515e56848ec6ea58388c5f5d647239e69"
MODEL = "opus.spm32k-spm32k.transformer.model1.npz.best-perplexity.npz"
VOCAB = "opus.spm32k-spm32k.vocab.yml"
FILES = {
    "README.md": (780, "ee8a32d0a07ad7b1b25df0075fa6c3ef6e1073adc39722f9f9d92762e38f78fd"),
    "LICENSE": (18652, "945fbcb37deca05bccd7c3ca0e261679f1f5373515504fdbad68f6f3cf6f49b9"),
    MODEL: (309987327, "18c0b66ce89e0ab47d3f0b889903bee596e1d25f545e3ddfae4d5c3834a14914"),
    VOCAB: (1070591, "aad63d601d67e246aea7570ea062cb4e2360caf3b2ab5251781e5ac192daeca7"),
    "source.spm": (804374, "72600ea476b3e3b76763919c3a30ef5104d9b473b19567d45251f56034264f54"),
    "target.spm": (806320, "06f8a99ad82348acc6a046695cb8d659fbb587d61de1e0fde928956d34008178"),
    "decoder.yml": (259, "aec96eb0d7bb8fe173121f3e53bc50306bd6233cb008cac9aab4f561fed43057"),
    "preprocess.sh": (1265, "0590b23a47f5c7651a75a0cfe579e7cffe1f4b567a803a2c47b866111ebc0508"),
    "postprocess.sh": (80, "039cec87f6d037fa216e918f3b0a7d4fd46517e54f4d422e73e0b4d6fc27ac0d"),
    "config.mk": (563, "8c72d56b3cd886ba72ed5ed7cc34e04376b4a8d3406ef225da6a5bc10f58a0cc"),
}
VERSIONS = {"ctranslate2": "4.8.3", "numpy": "2.5.4", "sentencepiece": "0.2.2", "PyYAML": "6.0.3"}
CONVERTED = {
    "config.json": (221, "152eb448f1020eb55f90971d7b95f61896abee8415d555ca51ae7897ac0a0e68"),
    "model.bin": (82713357, "d0deac7c66d2daa430dc081d6ef08e4700eae54b11918fe438917fa4e1b7ef83"),
    "shared_vocabulary.json": (1303421, "a71b9965455e287b890e965bcb4d5f3c52f1fe5fa7bd9019787d4a3dd1c37acc"),
}
TEXTS = [
    "明天见。", "我正在学习英语。", "谢谢你的帮助。", "我一会儿就到。", "今天有点堵车。",
    "我已经在路上了。", "我没听清楚，你能再说一遍吗？", "我们改天再约吧。", "这个方案还需要进一步讨论。", "先这样吧。",
    "我没说你做错了。", "不是不想去，是今天没时间。", "请在下午３：３０之前回复。", "她说：“明天见！”", "今天气温是２５℃。",
    "我正在学习英语．", "  我   已经在路上了。", "我已经\t在路上了。", "我已经在路上了。\u200b",
]


def hash_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_file(path, size, sha256):
    if path.is_symlink() or not path.is_file() or path.stat().st_size != size or hash_file(path) != sha256:
        raise RuntimeError("fixed research asset differs: " + path.name)


def verify_raw():
    for name, (size, sha256) in FILES.items():
        verify_file(ROOT / "raw" / name, size, sha256)


def verify_converted():
    directory = ROOT / "ct2-int8"
    if directory.is_symlink() or {path.name for path in directory.iterdir()} != set(CONVERTED):
        raise RuntimeError("converted model file set changed")
    for name, (size, sha256) in CONVERTED.items():
        verify_file(directory / name, size, sha256)


def versions():
    if platform.system() != "Linux" or platform.machine() != "x86_64" or sys.version_info[:2] != (3, 12):
        raise RuntimeError("this fixed wheel/measurement experiment requires Linux x86-64 Python 3.12")
    actual = {name: importlib.metadata.version(name) for name in VERSIONS}
    if actual != VERSIONS:
        raise RuntimeError("use the fixed existing translation-probe environment")
    return actual


def inventory(path):
    return [{"name": str(file.relative_to(path)), "bytes": file.stat().st_size, "sha256": hash_file(file)}
            for file in sorted(path.rglob("*")) if file.is_file()]
