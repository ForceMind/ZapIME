#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Build-time only: verify pinned native ASR dependencies and select bounded members."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / "third_party/android-voice-lock.json").read_text())


def verified(path, spec):
    if not path.is_file() or path.is_symlink() or path.stat().st_size != spec["size"]:
        return False
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest() == spec["sha256"]


def publish(incoming, destination, spec):
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as out:
        temporary = Path(out.name)
        try:
            remaining = spec["size"]
            while chunk := incoming.read(min(1024 * 1024, remaining + 1)):
                if len(chunk) > remaining:
                    raise ValueError("oversized dependency member")
                out.write(chunk)
                remaining -= len(chunk)
            out.close()
            if not verified(temporary, spec):
                raise ValueError("dependency checksum/size mismatch")
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)


def download(spec, destination):
    if verified(destination, spec):
        return
    if not spec["url"].startswith("https://"):
        raise ValueError("HTTPS dependency required")
    with urllib.request.urlopen(spec["url"], timeout=60) as source:
        publish(source, destination, spec)


def verify_elf(path, abi):
    with path.open("rb") as source:
        header = source.read(64)
        if len(header) != 64 or header[:6] != b"\x7fELF\x02\x01":
            raise ValueError("ELF64 little-endian required")
        if struct.unpack_from("<H", header, 18)[0] != {"arm64-v8a": 183, "x86_64": 62}[abi]:
            raise ValueError("native architecture mismatch")
        offset = struct.unpack_from("<Q", header, 32)[0]
        width, count = struct.unpack_from("<HH", header, 54)
        if width != 56 or not 1 <= count <= 64:
            raise ValueError("invalid ELF program table")
        loads = 0
        for index in range(count):
            source.seek(offset + width * index)
            program = source.read(width)
            if len(program) != width:
                raise ValueError("truncated ELF program table")
            if struct.unpack_from("<I", program)[0] == 1:
                file_offset, address = struct.unpack_from("<QQ", program, 8)
                alignment = struct.unpack_from("<Q", program, 48)[0]
                if alignment < 16384 or alignment & (alignment - 1) or file_offset % alignment != address % alignment:
                    raise ValueError("native library lacks 16 KiB LOAD alignment")
                loads += 1
        if not loads:
            raise ValueError("native library has no LOAD segments")


def model_config():
    probe = json.loads((ROOT / "third_party/voice-probe-lock.json").read_text())
    license_bytes = (ROOT / "third_party/licenses/X-ASR-Apache-2.0.txt").read_bytes()
    notice = b"X-ASR by Gilgamesh-J/X-ASR; ONNX conversion by k2-fsa/sherpa-onnx. Apache-2.0.\n"
    files = dict(probe["files"])
    for name, data in (("LICENSE.txt", license_bytes), ("NOTICE.txt", notice)):
        files[name] = {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    return {"schema_version": 1, "id": "x-asr-streaming-int8-2026-06-05", "files": files,
            "manifest": {"kind": "online_transducer", "model_type": "zipformer2", "hotwords": "none",
                         "files": {"encoder": "encoder.int8.onnx", "decoder": "decoder.onnx",
                                   "joiner": "joiner.int8.onnx", "tokens": "tokens.txt"}}}


def prepare(abi, destination):
    archive = ROOT / "target/voice-runtime/android" / LOCK["archive"]["name"]
    download(LOCK["archive"], archive)
    with zipfile.ZipFile(archive) as source:
        for name, spec in LOCK["libraries"][abi].items():
            member = source.getinfo(f"jni/{abi}/{name}")
            if member.is_dir() or member.file_size != spec["size"]:
                raise ValueError("invalid native member")
            with source.open(member) as incoming:
                publish(incoming, destination / name, spec)
            verify_elf(destination / name, abi)
    json_archive = ROOT / "target/voice-json.tar.xz"
    download(LOCK["json"], json_archive)
    with tarfile.open(json_archive) as source:
        for name, spec in LOCK["json"]["files"].items():
            member = source.getmember("json/" + name)
            if not member.isfile() or member.size != spec["size"]:
                raise ValueError("invalid JSON dependency member")
            with source.extractfile(member) as incoming:
                publish(incoming, ROOT / "target/voice-json" / name, spec)
    print(f"PASS pinned {abi} voice libraries: SHA256, architecture and 16 KiB LOAD alignment")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("abi", choices=("arm64-v8a", "x86_64"))
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    prepare(args.abi, args.destination)
