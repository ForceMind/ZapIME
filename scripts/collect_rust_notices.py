#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Collect the selected Cargo root's actual non-dev graph and declared licenses."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PATTERNS = ("LICENSE*", "LICENCE*", "COPYING*", "NOTICE*", "COPYRIGHT*", "Copyright*", "AUTHORS*", "GPL*", "LGPL*")
# Existing audited repository crates are covered by the separately packaged root
# GPL/upstream notices. New roots and registry crates do not inherit this exception.
ROOT_LICENSES = {"zapime-core", "zapime-data", "msime-engine", "msime-path-trust"}
MANUAL = {
    ("ewts", "0.1.4"): "ewts-MIT.txt",
    ("exmex", "0.21.0"): "exmex-0.21.0-MIT.txt",
    ("jni-sys-macros", "0.4.1"): "jni-sys-macros-0.4.1-MIT.txt",
    ("rink-core", "0.9.0"): "MPL-2.0.txt",
}


def graph(metadata, root_package):
    packages = {p["id"]: p for p in metadata["packages"]}
    nodes = {n["id"]: n for n in metadata["resolve"]["nodes"]}
    roots = [p["id"] for p in packages.values() if p["name"] == root_package]
    if len(roots) != 1:
        raise ValueError("Cargo root package must resolve exactly once: " + root_package)
    queue, seen = list(roots), set()
    while queue:
        item = queue.pop()
        if item in seen:
            continue
        seen.add(item)
        for dep in nodes[item]["deps"]:
            if any(kind["kind"] != "dev" for kind in dep["dep_kinds"]):
                queue.append(dep["pkg"])
    return [packages[item] for item in sorted(seen)]


def collect(metadata, root_package, platform, out, root=ROOT, cargo_lock_sha256=None):
    inventory, destinations = [], set()
    for package in graph(metadata, root_package):
        source = Path(package["manifest_path"]).parent
        destination_name = package["name"] + "-" + package["version"]
        if destination_name in destinations:
            raise ValueError("Duplicate notice destination: " + destination_name)
        destinations.add(destination_name)
        texts = {p for pattern in PATTERNS for p in source.glob(pattern) if p.is_file() and p.stat().st_size > 0}
        declared_file = package.get("license_file")
        if declared_file:
            declared = source / declared_file
            if not declared.is_file() or declared.stat().st_size == 0:
                raise ValueError("Missing declared license file: " + package["name"])
            texts.add(declared)
        destination = out / destination_name
        destination.mkdir(parents=True, exist_ok=True)
        names = set()
        for path in sorted(texts):
            if path.name == "SOURCE.txt":
                raise ValueError("Declared license collides with source notice: " + package["name"])
            if path.name in names:
                raise ValueError("License basename collision: " + package["name"])
            names.add(path.name)
            shutil.copyfile(path, destination / path.name)
        (destination / "SOURCE.txt").write_text(
            package["name"] + " " + package["version"] + "\n" +
            (package.get("repository") or "ZapIME repository and pinned upstream source") + "\n" +
            "Package source is available from the recorded Cargo registry/git source.\n")
        manual = MANUAL.get((package["name"], package["version"]))
        if not texts and manual:
            shutil.copyfile(root / "third_party/licenses" / manual, destination / manual)
            names.add(manual)
        license_id = package.get("license") or (
            "LicenseRef-Cargo-LicenseFile" if declared_file else
            "MIT" if package["name"] == "vi" and package["version"] == "0.8.0" else "NOASSERTION")
        files = sorted(names)
        inventory.append({
            "name": package["name"], "version": package["version"], "license": license_id,
            "source": package.get("source") or "repository-source", "license_files": files,
            "license_sha256": {name: hashlib.sha256((destination / name).read_bytes()).hexdigest() for name in files},
        })
    out.mkdir(parents=True, exist_ok=True)
    raw = (json.dumps(inventory, indent=2) + "\n").encode()
    (out / "inventory.json").write_bytes(raw)
    (out / "collection.json").write_text(json.dumps({
        "schema_version": 1, "root_package": root_package, "target_platform": platform,
        "inventory_sha256": hashlib.sha256(raw).hexdigest(), "packages": len(inventory),
        "cargo_lock_sha256": cargo_lock_sha256,
    }, indent=2) + "\n")
    missing = [p["name"] for p in inventory if not p["license_files"]]
    print("Collected", len(inventory), "dependency entries; no bundled license file:", missing)
    if any(p["license"] == "NOASSERTION" for p in inventory):
        raise ValueError("Unreviewed dependency license")
    unknown = [p["name"] for p in inventory if not p["license_files"] and not (
        p["name"] in ROOT_LICENSES and p["source"] == "repository-source")]
    if unknown:
        raise ValueError("Review missing dependency license files: " + ", ".join(unknown))
    return inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform")
    parser.add_argument("output", type=Path)
    parser.add_argument("--manifest-path", type=Path, default=ROOT / "Cargo.toml")
    parser.add_argument("--root-package", default="zapime-core")
    args = parser.parse_args()
    metadata = json.loads(subprocess.check_output([
        "cargo", "metadata", "--locked", "--format-version", "1", "--filter-platform", args.platform,
        "--manifest-path", str(args.manifest_path.resolve()),
    ], cwd=ROOT, text=True))
    lock = Path(metadata["workspace_root"]) / "Cargo.lock"
    if not lock.is_file():
        raise ValueError("Cargo lockfile required for notice provenance")
    collect(metadata, args.root_package, args.platform, args.output,
            cargo_lock_sha256=hashlib.sha256(lock.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
