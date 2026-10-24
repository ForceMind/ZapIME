#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""One fresh 19-sentence CPU process; inference never downloads missing assets."""
import argparse
import json
import os
from pathlib import Path
import platform
import resource
import sys
import time

from assets import ROOT, SHA256, TEXTS, inventory, verify_converted, verify_raw, versions
from preprocessing import ArchivePreprocessing

for name in ("OMP_NUM_THREADS", "OMP_THREAD_LIMIT", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "NUMEXPR_NUM_THREADS", "VECLIB_MAXIMUM_THREADS"):
    os.environ[name] = "1"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--beam-size", choices=(1, 6), type=int, required=True)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    network = {"method": "network available, not isolated in this process"}
    if args.offline:
        # Reuse the reviewed repository filter without changing it. It imports
        # only ctypes/errno/socket; the filter is installed before ML imports.
        sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
        from scripts.qwen_translation_probe.network import deny_network
        network = deny_network()
        network["method"] = "Linux libseccomp plus no_new_privs, installed before ML imports"
        network["scope"] = "this Python process, future threads and fixed Perl child; specific socket syscalls, not inherited-fd/io_uring paths, a full sandbox or device airplane mode"
    actual_versions = versions()
    verify_raw()
    verify_converted()
    converted = json.loads((ROOT / "conversion-result.json").read_text())
    model_files = inventory(ROOT / "ct2-int8")
    if model_files != converted["files"] or converted["versions"] != actual_versions:
        raise RuntimeError("converted asset identity or runtime changed")
    import ctranslate2
    import sentencepiece
    preprocess = ArchivePreprocessing()
    source = sentencepiece.SentencePieceProcessor(model_file=str(ROOT / "raw/source.spm"))
    target = sentencepiece.SentencePieceProcessor(model_file=str(ROOT / "raw/target.spm"))
    started = time.perf_counter()
    translator = ctranslate2.Translator(str(ROOT / "ct2-int8"), device="cpu", compute_type="int8", inter_threads=1, intra_threads=1)
    load_seconds = time.perf_counter() - started
    examples = []
    for text in TEXTS:
        started = time.perf_counter()
        normalized = preprocess(text)
        tokens = source.encode(normalized, out_type=str)
        result = translator.translate_batch([tokens], beam_size=args.beam_size, length_penalty=1, max_decoding_length=64)
        output_tokens = result[0].hypotheses[0]
        examples.append({"source": text, "normalized_source": normalized,
                         "translation": target.decode(output_tokens),
                         "archive_sed_translation_untrimmed": "".join(output_tokens).replace(" ", "").replace("▁", " "),
                         "seconds_including_preprocessing_and_sentencepiece": time.perf_counter() - started,
                         "source_tokens": len(tokens), "output_tokens": len(output_tokens)})
    result = {"synthetic_text_only": True, "source_archive_sha256": SHA256,
              "platform": platform.platform(), "python": platform.python_version(), "versions": actual_versions,
              "device": "cpu", "compute_type_requested": "int8", "compute_type_actual": translator.compute_type,
              "inter_threads": 1, "intra_threads": 1, "beam_size": args.beam_size,
              "length_penalty": 1, "max_decoding_length": 64, "preprocessing": preprocess.description(),
              "postprocessing": "SentencePiece.decode main field; original archive sed untrimmed string retained separately",
              "load_seconds_after_asset_verification_warmed_cache": load_seconds,
              "peak_process_rss_kib": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
              "memory_scope": "Linux fresh Python process including imports/model/19 examples; excludes separate Perl children, not model-only or mobile RSS",
              "network": network, "examples": examples, "model_files": model_files,
              "limitations": "Single passes after file verification/warmed cache; no native Marian numeric equivalence, cold-start/statistical latency, quality score, mobile/energy/keyboard contention, client integration or default-model acceptance"}
    name = "opus2021-beam" + str(args.beam_size) + ("-offline" if args.offline else "-online-capable") + ".json"
    (ROOT / name).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
