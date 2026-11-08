#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""只获取固定资源锁里的拼音与英文数据；所有字节校验通过后原子安装。"""
import hashlib
import json
from pathlib import Path
import tempfile
import urllib.parse
import urllib.request

from build_download_retry import BudgetedReader, HttpsRedirect, network_call, run_with_retry, safe_directory

ROOT = Path(__file__).resolve().parents[1]
WANTED = {"msime-pinyin.db", "msime-english.db", "msime-scowl_Copyright.txt", "msime-dictionary-manifest.json"}
OPENER = urllib.request.build_opener(HttpsRedirect())

def fetch(out):
    lock = json.loads((ROOT / "vendor/msime/resources/editions/pinyin.lock.json").read_text())
    entries = {entry["name"]: entry for entry in lock["artifacts"] if entry["name"] in WANTED}
    if set(entries) != WANTED:
        raise ValueError("resource_lock_incomplete")
    out = safe_directory(out)
    for name, entry in entries.items():
        target = out / name
        if target.is_symlink() or (target.exists() and not target.is_file()):
            raise ValueError("resource destination is not a regular file")
        def valid(path):
            if not path.is_file() or path.is_symlink() or path.stat().st_size != entry["size"]:
                return False
            with path.open("rb") as source:
                return hashlib.file_digest(source, "sha256").hexdigest() == entry["sha256"]
        if valid(target):
            continue
        if urllib.parse.urlparse(entry["url"]).scheme != "https":
            raise ValueError("HTTPS resource required")
        def attempt(timeout, remaining):
            temporary = None
            try:
                with network_call(OPENER.open, entry["url"], timeout=timeout) as response, tempfile.NamedTemporaryFile(dir=out, delete=False) as dest:
                    temporary = Path(dest.name)
                    source = BudgetedReader(response, remaining)
                    left = entry["size"]
                    while chunk := source.read(min(1024 * 1024, left + 1)):
                        if len(chunk) > left:
                            raise ValueError("oversized fixed resource")
                        dest.write(chunk)
                        left -= len(chunk)
                if not valid(temporary):
                    raise ValueError("resource_integrity_failed: " + name)
                remaining()
                temporary.replace(target)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
        run_with_retry(attempt, socket_timeout=120, total_timeout=360)
    manifest = json.loads((out / "msime-dictionary-manifest.json").read_text())
    if manifest["licensing"]["includes_unlicensed_inputs"] is not False:
        raise ValueError("unlicensed_dictionary_rejected")
    print("Verified pinned pinyin/English resources; unlicensed inputs excluded")

if __name__ == "__main__":
    fetch(ROOT / "target/resources")
