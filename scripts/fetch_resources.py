#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""只获取固定资源锁里的拼音与英文数据；所有字节校验通过后原子安装。"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
WANTED = {"msime-pinyin.db", "msime-english.db", "msime-scowl_Copyright.txt", "msime-dictionary-manifest.json"}

def fetch(out):
    lock = json.loads((ROOT / "vendor/msime/resources/editions/pinyin.lock.json").read_text())
    entries = {entry["name"]: entry for entry in lock["artifacts"] if entry["name"] in WANTED}
    if set(entries) != WANTED:
        raise ValueError("resource_lock_incomplete")
    out.mkdir(parents=True, exist_ok=True)
    for name, entry in entries.items():
        target = out / name
        def valid(path):
            return path.is_file() and path.stat().st_size == entry["size"] and hashlib.sha256(path.read_bytes()).hexdigest() == entry["sha256"]
        if valid(target):
            continue
        temp = out / (name + ".incoming")
        try:
            with urllib.request.urlopen(entry["url"], timeout=120) as source, temp.open("wb") as dest:
                shutil.copyfileobj(source, dest)
            if not valid(temp):
                raise ValueError("resource_integrity_failed: " + name)
            os.replace(temp, target)
        finally:
            temp.unlink(missing_ok=True)
    manifest = json.loads((out / "msime-dictionary-manifest.json").read_text())
    if manifest["licensing"]["includes_unlicensed_inputs"] is not False:
        raise ValueError("unlicensed_dictionary_rejected")
    print("Verified pinned pinyin/English resources; unlicensed inputs excluded")

if __name__ == "__main__":
    fetch(ROOT / "target/resources")
