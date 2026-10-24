#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Explicit developer-only download; inference never downloads these helpers."""
import hashlib
import json
import os
from pathlib import Path
import urllib.parse
import urllib.request

ROOT = Path(os.environ.get("ZAPIME_TRANSLATION_WORKDIR", Path(__file__).resolve().parents[2] / "target" / "translation-probe")).resolve()
MOSES_REVISION = "08e782040189e0abb3b43a4ec9245e6179e39eca"
MOSES_FILES = {
    "replace-unicode-punctuation.perl": ("scripts/tokenizer/replace-unicode-punctuation.perl", 872, "2a4bf9b58a66fd58931ce54b89b6e9912e3177552b6bfedf3d7ce8a89aaa71de"),
    "remove-non-printing-char.perl": ("scripts/tokenizer/remove-non-printing-char.perl", 549, "a725fcee901f14658a8a6e90ea5ea20d988932a8091ee0f43e1a5f0fbb74c2b7"),
    "COPYING": ("COPYING", 24426, "83a9cdbbac61c1fa33b055dafcb6f0b0aa4379d70f7c1ce15b660d1df05b4696"),
}


class HTTPSOnlyRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlparse(newurl)
        if parsed.scheme != "https" or parsed.hostname != "raw.githubusercontent.com":
            raise RuntimeError("unexpected helper download redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def checked(data, size, digest):
    if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
        raise RuntimeError("helper differs from reviewed upstream source")


def verify_helpers(root):
    entries = []
    for name, (upstream_path, size, digest) in MOSES_FILES.items():
        path = root / "moses" / name
        # A small fixed byte limit applies even to existing, user-modified files.
        with path.open("rb") as stream:
            checked(stream.read(size + 1), size, digest)
        entries.append({"name": name, "upstream_path": upstream_path, "bytes": size, "sha256": digest})
    return entries


def main():
    destination = ROOT / "moses"
    destination.mkdir(parents=True, exist_ok=True)
    opener = urllib.request.build_opener(HTTPSOnlyRedirect())
    for name, (upstream_path, size, digest) in MOSES_FILES.items():
        output = destination / name
        if output.exists():
            with output.open("rb") as stream:
                checked(stream.read(size + 1), size, digest)
            continue
        url = "https://raw.githubusercontent.com/moses-smt/mosesdecoder/" + MOSES_REVISION + "/" + upstream_path
        with opener.open(url, timeout=30) as response:
            data = response.read(size + 1)
        checked(data, size, digest)
        temporary = output.with_suffix(output.suffix + ".part")
        temporary.write_bytes(data)
        temporary.replace(output)
    result = {
        "upstream": "https://github.com/moses-smt/mosesdecoder",
        "revision": MOSES_REVISION,
        "license": "LGPL-2.1-or-later, file headers and repository COPYING; original source and COPYING retained in ignored work directory",
        "hashes": "observed HTTPS source hashes locked after review, not independent publisher-signed checksums",
        "files": verify_helpers(ROOT),
        "archive_shell_executed": False,
        "client_integration": False,
    }
    (ROOT / "preprocessing-manifest.json").write_text(json.dumps(result, indent=2) + "\n")
    print("PASS hash-pinned Moses helper source and COPYING; no archive shell execution")


if __name__ == "__main__":
    main()
