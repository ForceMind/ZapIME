#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Sign the exact deploy files, platform and verified container image IDs."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

from build_preflight_bundle import write_bundle

ROOT = Path(__file__).resolve().parents[1]
RUNTIME_FILES = (
    "deploy/install.sh", "deploy/manage.sh", "deploy/oidc_setup.py", "deploy/preflight.py",
    "scripts/android_account_login_config.py", "services/sync/compose.yml", "services/sync/Caddyfile",
    "services/sync/backup.sh", "services/sync/restore.sh", "services/sync/oidc-disabled.json",
    "services/sync/assetlinks-disabled.json", "LICENSE", "services/sync/LICENSE",
)
SERVICES = {"db": "image: postgres:16", "sync": "build: .", "https": "image: caddy:2"}
IMAGE = re.compile(r"[a-z0-9][a-z0-9./:-]{1,200}(?:@sha256:[0-9a-f]{64})?")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")


def image(source, architecture):
    if not IMAGE.fullmatch(source):
        raise ValueError("invalid image reference")
    raw = subprocess.check_output(["docker", "image", "inspect", source], stderr=subprocess.DEVNULL)
    metadata, = json.loads(raw)
    if metadata["Os"] != "linux" or metadata["Architecture"] != architecture or not DIGEST.fullmatch(metadata["Id"]):
        raise ValueError("image platform or ID does not match the bundle")
    if "@sha256:" in source and source.split("@")[1] not in {item.rsplit("@", 1)[1] for item in metadata["RepoDigests"] or []}:
        raise ValueError("the local image does not match the requested registry digest")
    return {"reference": source, "id": metadata["Id"]}


def build(args):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9]+([.-][a-z0-9]+)*)?", args.version):
        raise ValueError("fixed release version required")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    images = {service: image(getattr(args, service), args.architecture) for service in SERVICES}
    if not args.archive and any("@sha256:" not in item["reference"] for item in images.values()):
        raise ValueError("use registry digest references online, or export an offline image archive")
    archive_sha = ""
    if args.archive:
        digest = hashlib.sha256()
        with args.archive.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        archive_sha = digest.hexdigest()
    manifest = {"format": 1, "purpose": "zapime-runtime", "version": args.version,
                "architecture": args.architecture, "source": commit, "images": images, "archive_sha256": archive_sha}
    payload = {name: subprocess.check_output(["git", "show", f"{commit}:{name}"], cwd=ROOT) for name in RUNTIME_FILES}
    compose = payload["services/sync/compose.yml"].decode()
    for service, selector in SERVICES.items():
        if compose.count("    " + selector + "\n") != 1:
            raise ValueError("unexpected Compose image declaration")
        compose = compose.replace(f"    {selector}\n", f"    image: {images[service]['reference']}\n    pull_policy: never\n")
    payload["services/sync/compose.yml"] = compose.encode()
    return write_bundle(args.output, "zapime-runtime", args.private_key, manifest, payload)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--architecture", required=True, choices=("amd64", "arm64"))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--private-key", required=True, type=Path)
    parser.add_argument("--archive", type=Path, help="optional docker save archive alongside the bundle")
    for service in SERVICES:
        parser.add_argument("--" + service, required=True, help="locally inspected image reference")
    print(build(parser.parse_args()))


if __name__ == "__main__":
    main()
