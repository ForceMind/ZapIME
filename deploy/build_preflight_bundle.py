#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Produce the detached-signature preflight package with an operator-held key."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


def build(output, version, architecture, key):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9]+([.-][a-z0-9]+)*)?", version):
        raise ValueError("Use an explicit version such as 0.1.0-preview.1")
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    archive = output / "zapime-preflight.zip"
    public = output / "release-public.pem"
    manifest = {"format": 1, "purpose": "zapime-preflight", "version": version, "architecture": architecture}
    with ZipFile(archive, "x", compression=ZIP_DEFLATED) as package:
        for name, payload in (
            ("manifest.json", (json.dumps(manifest, sort_keys=True) + "\n").encode()),
            ("preflight.py", Path(__file__).with_name("preflight.py").read_bytes()),
        ):
            package.writestr(ZipInfo(name), payload, compress_type=ZIP_DEFLATED)
    subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-out", str(public)], check=True)
    subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key), "-out",
                    str(output / "zapime-preflight.sig"), str(archive)], check=True)
    return hashlib.sha256(public.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--architecture", required=True, choices=("amd64", "arm64"))
    parser.add_argument("--private-key", required=True, type=Path)
    args = parser.parse_args()
    print(build(args.output, args.version, args.architecture, args.private_key))


if __name__ == "__main__":
    main()
