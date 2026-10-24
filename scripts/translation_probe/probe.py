#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Disposable CPU feasibility probe, not a product translator or benchmark."""
import argparse
import ctypes
import ctypes.util
import errno
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import socket
import time

ROOT = Path(os.environ.get("ZAPIME_TRANSLATION_WORKDIR", Path(__file__).resolve().parents[2] / "target" / "translation-probe")).resolve()
for name in ("OMP_NUM_THREADS", "OMP_THREAD_LIMIT", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "NUMEXPR_NUM_THREADS", "VECLIB_MAXIMUM_THREADS"):
    os.environ[name] = "1"


def hash_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def deny_network():
    """Stack a more restrictive filter on this process only; no privilege gain."""
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(38, 1, 0, 0, 0) != 0:  # PR_SET_NO_NEW_PRIVS
        raise OSError(ctypes.get_errno(), "cannot restrict this process")
    library = ctypes.util.find_library("seccomp")
    if not library:
        raise RuntimeError("libseccomp unavailable; no offline claim")
    seccomp = ctypes.CDLL(library, use_errno=True)
    seccomp.seccomp_init.argtypes = [ctypes.c_uint32]
    seccomp.seccomp_init.restype = ctypes.c_void_p
    seccomp.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]
    seccomp.seccomp_syscall_resolve_name.restype = ctypes.c_int
    seccomp.seccomp_rule_add.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint]
    seccomp.seccomp_rule_add.restype = ctypes.c_int
    seccomp.seccomp_load.argtypes = [ctypes.c_void_p]
    seccomp.seccomp_load.restype = ctypes.c_int
    seccomp.seccomp_release.argtypes = [ctypes.c_void_p]
    context = seccomp.seccomp_init(0x7FFF0000)  # SCMP_ACT_ALLOW
    if not context:
        raise RuntimeError("cannot allocate network denial filter")
    blocked = ["socket", "socketpair", "connect", "accept", "accept4", "bind", "listen", "sendto", "sendmsg", "sendmmsg", "recvfrom", "recvmsg", "recvmmsg", "shutdown"]
    try:
        for name in blocked:
            number = seccomp.seccomp_syscall_resolve_name(name.encode("ascii"))
            if number < 0 or seccomp.seccomp_rule_add(context, 0x00050000 | errno.EPERM, number, 0) != 0:
                raise RuntimeError("cannot deny network syscall " + name)
        if seccomp.seccomp_load(context) != 0:
            raise RuntimeError("network denial filter not installed; no offline claim")
    finally:
        seccomp.seccomp_release(context)
    rejected = []
    for family in (socket.AF_INET, socket.AF_INET6):
        try:
            test = socket.socket(family, socket.SOCK_STREAM)
        except OSError as error:
            if error.errno != errno.EPERM:
                raise
            rejected.append(family.name)
        else:
            test.close()
            raise RuntimeError("network denial self-check failed")
    return {"method": "Linux libseccomp filter with no_new_privs, installed before ML imports", "blocked_syscalls": blocked, "socket_creation_rejected": rejected, "scope": "this process and future threads only; not a device airplane-mode test"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["convert", "infer"])
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--daily", action="store_true", help="also run every additional synthetic daily-language example")
    args = parser.parse_args()
    network = deny_network() if args.offline else {"method": "network not isolated during this pass"}
    import ctranslate2
    import sentencepiece

    versions = {name: importlib.metadata.version(name) for name in ("ctranslate2", "numpy", "sentencepiece", "PyYAML")}
    if args.mode == "convert":
        started = time.perf_counter()
        converter = ctranslate2.converters.OpusMTConverter(str(ROOT / "raw"))
        destination = converter.convert(str(ROOT / "ct2-int8"), quantization="int8", force=False)
        result = {"mode": "convert", "elapsed_seconds": time.perf_counter() - started, "output": str(destination), "versions": versions}
        (ROOT / "conversion-result.json").write_text(json.dumps(result, indent=2))
        print(json.dumps(result))
        return

    source = sentencepiece.SentencePieceProcessor(model_file=str(ROOT / "raw/source.spm"))
    target = sentencepiece.SentencePieceProcessor(model_file=str(ROOT / "raw/target.spm"))
    started = time.perf_counter()
    translator = ctranslate2.Translator(str(ROOT / "ct2-int8"), device="cpu", compute_type="int8", inter_threads=1, intra_threads=1)
    load_seconds = time.perf_counter() - started
    examples = []
    texts = ["明天见。", "我正在学习英语。", "谢谢你的帮助。"]
    if args.daily:
        texts += ["我一会儿就到。", "今天有点堵车。", "我已经在路上了。", "我没听清楚，你能再说一遍吗？", "我们改天再约吧。", "这个方案还需要进一步讨论。", "先这样吧。"]
    for text in texts:
        started = time.perf_counter()
        tokens = source.encode(text, out_type=str)
        translated = translator.translate_batch([tokens], beam_size=1, max_decoding_length=64)
        english = target.decode(translated[0].hypotheses[0])
        elapsed = time.perf_counter() - started
        examples.append({"source": text, "translation": english, "seconds_including_sentencepiece": elapsed, "source_tokens": len(tokens), "output_tokens": len(translated[0].hypotheses[0])})
    result = {"mode": "inference", "synthetic_text_only": True, "versions": versions, "platform": platform.platform(), "python": platform.python_version(), "device": "cpu", "compute_type_requested": "int8", "compute_type_actual": getattr(translator, "compute_type", "not exposed"), "inter_threads": 1, "intra_threads": 1, "beam_size": 1, "max_decoding_length": 64, "preprocessing": "bundled SentencePiece only; external Moses punctuation/control-character preprocessing in archive not applied", "load_seconds": load_seconds, "network": network, "examples": examples, "model_files": [{"name": str(p.relative_to(ROOT / "ct2-int8")), "bytes": p.stat().st_size, "sha256": hash_file(p)} for p in sorted((ROOT / "ct2-int8").rglob("*")) if p.is_file()], "limitations": "Single cloud-container x86-64 CPU probe, not translation quality evaluation, mobile latency/memory/battery, keyboard integration, or four-platform acceptance."}
    output = ROOT / (("inference-daily-offline.json" if args.offline else "inference-daily-online-capable.json") if args.daily else ("inference-offline.json" if args.offline else "inference-online-capable.json"))
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
