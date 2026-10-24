#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Explicit public downloads only. Never called by inference or by the IME."""
import json
from pathlib import Path, PurePosixPath
import platform
import tarfile
import time
import urllib.parse
import urllib.request

from assets import ASSETS, ROOT, MODEL_REVISION, RUNTIME_COMMIT, RUNTIME_TAG, digest_file, verify_asset


class HTTPSRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlparse(newurl).scheme != "https":
            raise RuntimeError("refusing a non-HTTPS download redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(name, opener):
    output = ROOT / name
    if output.exists():
        return verify_asset(name)
    url, size, _, _ = ASSETS[name]
    temporary = ROOT / (name + ".part")
    started = time.monotonic()
    count = 0
    with opener.open(url, timeout=45) as response, temporary.open("wb") as stream:
        while True:
            data = response.read(min(1024 * 1024, size + 1 - count))
            if not data:
                break
            count += len(data)
            if count > size or time.monotonic() - started > 240:
                raise RuntimeError("download exceeds fixed size/time bound: " + name)
            stream.write(data)
    if count != size or digest_file(temporary) != ASSETS[name][2]:
        raise RuntimeError("download differs: " + name)
    temporary.replace(output)
    return verify_asset(name)


def extract_runtime():
    destination = ROOT / "runtime"
    destination.mkdir(exist_ok=True)
    with tarfile.open(ROOT / "llama-b11669-bin-ubuntu-x64.tar.gz", "r:gz") as archive:
        for member in archive.getmembers():
            parts = PurePosixPath(member.name).parts
            if not parts or parts[0] != "llama-b11669" or ".." in parts or len(parts) > 2:
                raise RuntimeError("unexpected runtime archive member")
            if not (member.isfile() or member.isdir() or member.issym()) or member.size > 20_000_000:
                raise RuntimeError("unexpected runtime archive type/size")
            if member.issym() and ("/" in member.linkname or member.linkname in (".", "..")):
                raise RuntimeError("unexpected runtime symlink")
        archive.extractall(destination, filter="data")
    runtime = destination / "llama-b11669"
    if digest_file(runtime / "LICENSE") != ASSETS["runtime-LICENSE.txt"][2]:
        raise RuntimeError("runtime archive LICENSE differs from pinned source")
    return [{"name": str(p.relative_to(runtime)), "bytes": p.stat().st_size, "sha256": digest_file(p)} for p in sorted(runtime.iterdir()) if p.is_file() and not p.is_symlink()]


def main():
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise RuntimeError("this fixed runtime experiment is Linux x86-64 only")
    ROOT.mkdir(parents=True, exist_ok=True)
    opener = urllib.request.build_opener(HTTPSRedirect())
    assets = []
    for name in ASSETS:
        entry = download(name, opener)
        entry["url"] = ASSETS[name][0]
        entry["hash_source"] = ASSETS[name][3]
        assets.append(entry)
        print("PASS fixed asset " + name, flush=True)
    result = {
        "model_repository": "Qwen/Qwen3-0.6B-GGUF", "model_revision": MODEL_REVISION,
        "model_license": "Apache-2.0 from this exact GGUF repository's full LICENSE, retained locally",
        "runtime_repository": "ggml-org/llama.cpp", "runtime_tag": RUNTIME_TAG,
        "runtime_commit": RUNTIME_COMMIT, "runtime_main_license": "MIT",
        "runtime_provenance_limit": "publisher release asset digest and version/source association, not an independently reproduced build or full client binary license audit",
        "assets": assets, "extracted_runtime_files": extract_runtime(),
        "client_model_pack": False, "automatic_download_during_inference": False,
    }
    (ROOT / "asset-manifest.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
