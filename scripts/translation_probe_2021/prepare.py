#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Explicit bounded download, checked extraction, and Torch-free CT2 conversion."""
import argparse
import json
import os
import time
import urllib.request
import zipfile

from assets import ARCHIVE, FILES, MODEL, ROOT, SHA256, SIZE, URL, VOCAB, inventory, verify_converted, verify_file, verify_raw, versions

for name in ("OMP_NUM_THREADS", "OMP_THREAD_LIMIT", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ[name] = "1"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify-existing", action="store_true", help="verify already prepared inputs/output and refresh only their manifest; no download/conversion")
    args = parser.parse_args()
    ROOT.mkdir(parents=True, exist_ok=True)
    if not args.verify_existing and (ROOT / "ct2-int8").exists():
        raise RuntimeError("refuse to overwrite an existing converted experiment")
    if args.verify_existing and not (ROOT / "ct2-int8").is_dir():
        raise RuntimeError("no existing conversion to verify")
    archive = ROOT / ARCHIVE
    if not archive.exists():
        if args.verify_existing:
            raise RuntimeError("verification does not download a missing archive")
        temporary = ROOT / (ARCHIVE + ".partial")
        started = time.monotonic()
        count = 0
        with urllib.request.urlopen(URL, timeout=30) as source, temporary.open("xb") as output:
            while block := source.read(1024 * 1024):
                count += len(block)
                if count > SIZE or time.monotonic() - started > 180:
                    raise RuntimeError("download exceeded the bounded experiment")
                output.write(block)
        verify_file(temporary, SIZE, SHA256)
        temporary.rename(archive)
    verify_file(archive, SIZE, SHA256)
    raw = ROOT / "raw"
    if raw.is_symlink():
        raise RuntimeError("refuse a symlink extraction directory")
    raw.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        names = source.namelist()
        if len(names) != len(set(names)):
            raise RuntimeError("duplicate archive members")
        for name, (size, digest) in FILES.items():
            info = source.getinfo(name)
            destination = raw / name
            if "/" in name or "\\" in name or info.is_dir() or info.file_size != size or destination.is_symlink():
                raise RuntimeError("unexpected fixed member shape")
            if not args.verify_existing:
                with source.open(info) as input_file, destination.open("wb") as output:
                    while block := input_file.read(1024 * 1024):
                        output.write(block)
            verify_file(destination, size, digest)
    verify_raw()
    actual_versions = versions()
    import numpy as np
    import yaml
    import sentencepiece
    import ctranslate2
    from ctranslate2.converters.marian import load_vocab
    decoder = yaml.safe_load((raw / "decoder.yml").read_text())
    if decoder["models"] != [MODEL] or decoder["vocabs"] != [VOCAB, VOCAB] or decoder["beam-size"] != 6 or decoder["normalize"] != 1:
        raise RuntimeError("fixed decoder contract differs")
    with np.load(raw / MODEL, allow_pickle=False) as weights:
        config = yaml.safe_load(weights["special:model.yml"][:-1].tobytes())
        if config["type"] != "transformer" or config["enc-depth"] != 6 or config["dec-depth"] != 6 or config["dim-emb"] != 512:
            raise RuntimeError("fixed NPZ architecture differs")
        weight_array_count = len(weights.files)
    # Use the converter's reviewed parser: generic YAML parsing can silently
    # collapse vocabulary keys, and is not a reliable count of converter tokens.
    vocabulary = load_vocab(str(raw / VOCAB))
    if len(vocabulary) != 65000 or config["dim-vocabs"] != [len(vocabulary), len(vocabulary)]:
        raise RuntimeError("fixed shared vocabulary and model dimensions differ")
    spm_sizes = {name: sentencepiece.SentencePieceProcessor(model_file=str(raw / name)).get_piece_size() for name in ("source.spm", "target.spm")}
    manifest = {"url": URL, "archive_bytes": SIZE, "observed_archive_sha256": SHA256,
                "independent_publisher_checksum_or_signature_audited": False,
                "license": "archive LICENSE is CC-BY-4.0; exact bytes match existing third_party notice",
                "member_inventory_scope": "the ten verified fixed allowlisted members only; not every file in the working directory",
                "members": [{"name": name, "bytes": size, "sha256": digest} for name, (size, digest) in FILES.items()],
                "decoder": decoder, "npz_config": config,
                "npz_allow_pickle": False, "npz_array_count": weight_array_count,
                "vocabulary_entries": len(vocabulary), "sentencepiece_sizes": spm_sizes,
                "vocabulary_count_method": "fixed CTranslate2 Marian load_vocab parser, the same parser used by conversion",
                "archive_shell_executed": False, "versions": actual_versions}
    (ROOT / "download-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    if args.verify_existing:
        verify_converted()
        print("PASS fixed existing archive, ten selected members, NPZ/decoder/vocabulary/SPM and converted hashes; no download or conversion")
        return
    started = time.perf_counter()
    ctranslate2.converters.OpusMTConverter(str(raw)).convert(str(ROOT / "ct2-int8"), quantization="int8", force=False)
    verify_converted()
    converted = {"elapsed_seconds": time.perf_counter() - started, "device_for_later_inference": "cpu", "quantization": "int8",
                 "versions": actual_versions, "files": inventory(ROOT / "ct2-int8"),
                 "runtime_dependency": "official fixed CTranslate2 OpusMTConverter/MarianConverter; no Torch or archive shell"}
    (ROOT / "conversion-result.json").write_text(json.dumps(converted, indent=2) + "\n")
    print(json.dumps({"archive_verified": True, "conversion": converted}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
