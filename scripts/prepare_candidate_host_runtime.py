#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Prepare only the previously reviewed official Linux ORT1.28.2 host diagnostic.

This library never enters an Android product. Archive size/SHA were already
reviewed in integrations/candidate/README.md. Extract only the two named,
hash-verified regular files; never extract archive paths or follow links.
The default transport is the official release. An explicit recovery transport
can fetch only the same pinned archive; it is never an automatic fallback.
"""
import argparse
from pathlib import Path
import tarfile

from prepare_candidate_model import download, publish, real_directory

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE_NAME = "onnxruntime-linux-x64-1.28.2.tgz"
ARCHIVE = dict(
    url="https://github.com/microsoft/onnxruntime/releases/download/v1.28.2/" + ARCHIVE_NAME,
    size=9128991,
    sha256="d7209b8751b27b862b0c76332c2e20e203396edb5dab700ecf4bb485cf147415")
# Transport only: ARCHIVE remains the original publisher identity and trust pin.
# The archive and both extracted members matched these pre-existing pins before
# this fixed recovery URL was added. No caller-provided URL or runtime is accepted.
RECOVERY_TRANSPORT_URL = (
    "https://registry.npmmirror.com/-/binary/onnxruntime/v1.28.2/" + ARCHIVE_NAME)
TRANSPORTS = ("official", "npmmirror-recovery")
MEMBERS = {
    "libonnxruntime.so.1.28.2": dict(
        member="onnxruntime-linux-x64-1.28.2/lib/libonnxruntime.so.1.28.2", size=24277040,
        sha256="088f24b1fc56714d3efaaeb3ac2ee486a5d7b50ccfbb3dd26fd1a612534a05fb"),
    "LICENSE.txt": dict(
        member="onnxruntime-linux-x64-1.28.2/LICENSE", size=1073,
        sha256="2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c"),
}


def prepare(destination, offline=False, *, transport="official"):
    if transport not in TRANSPORTS:
        raise ValueError("unsupported candidate host runtime transport")
    download_spec = dict(ARCHIVE)
    if transport == "npmmirror-recovery":
        download_spec["url"] = RECOVERY_TRANSPORT_URL
    real_directory(destination)
    archive = destination / ARCHIVE_NAME
    download(download_spec, archive, offline)
    with tarfile.open(archive, "r:gz") as source:
        selected = {}
        for name, spec in MEMBERS.items():
            matches = [member for member in source.getmembers() if member.name == spec["member"]]
            if len(matches) != 1 or not matches[0].isfile() or matches[0].size != spec["size"]:
                raise ValueError("candidate_host_runtime_member_invalid")
            selected[name] = matches[0]
        for name, spec in MEMBERS.items():
            with source.extractfile(selected[name]) as incoming:
                publish(incoming, destination / name, spec)
    return destination / "libonnxruntime.so.1.28.2"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, default=ROOT / "target/candidate-host/ort1282")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--transport", choices=TRANSPORTS, default="official",
                        help="explicit transport for the same size/SHA-pinned archive; no fallback")
    args = parser.parse_args()
    prepare(args.destination, args.offline, transport=args.transport)
    print("PASS fixed official ORT1.28.2 archive, host library and MIT license; "
          "transport=" + args.transport)


if __name__ == "__main__":
    main()
