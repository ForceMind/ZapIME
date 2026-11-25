#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Assemble a signed, transferable ZapIME runtime release on a Docker builder."""

import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile

from build_runtime_bundle import ROOT, build


def run(*args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)


def prepare(args):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9]+([.-][a-z0-9]+)*)?", args.version):
        raise ValueError("use a fixed release version such as 0.1.0-server.1")
    output = args.output.resolve()
    if output.exists():
        raise ValueError("choose a new output directory for this release")
    key = args.private_key.resolve(strict=True)
    run("openssl", "pkey", "-in", str(key), "-noout")
    commit = run("git", "rev-parse", "--verify", "HEAD^{commit}", capture_output=True, text=True).stdout.strip()
    tag = f"{args.version}-{args.architecture}-{commit[:12]}"
    if len(tag) > 128:
        raise ValueError("release version is too long for a Docker tag")
    images = {role: f"zapime-release/{role}:{tag}" for role in ("sync", "db", "https")}
    platform = "linux/" + args.architecture
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".zapime-runtime-", dir=output.parent) as directory:
        work = Path(directory)
        source = work / "source"
        source.mkdir()
        with (work / "source.tar").open("wb") as archive:
            run("git", "archive", commit, "services/sync", stdout=archive)
        run("tar", "-xf", str(work / "source.tar"), "-C", str(source))

        run("docker", "build", "--platform", platform, "-t", images["sync"], str(source / "services/sync"))
        for role, base in (("db", "postgres:16"), ("https", "caddy:2")):
            run("docker", "pull", "--platform", platform, base)
            run("docker", "tag", base, images[role])

        package = work / "package"
        package.mkdir()
        archive = package / "images.tar"
        run("docker", "save", "-o", str(archive), *images.values())
        pin = build(argparse.Namespace(
            version=args.version, architecture=args.architecture, output=package / "runtime",
            private_key=key, archive=archive, commit=commit, **images,
        ))
        launcher = run("git", "show", f"{commit}:deploy/bootstrap.sh", capture_output=True).stdout
        (package / "bootstrap.sh").write_bytes(launcher)
        bootstrap_digest = hashlib.sha256(launcher).hexdigest()
        command = (
            f"( set -e; printf '%s  %s\\n' {bootstrap_digest} bootstrap.sh | sha256sum -c;"
            ' sudo bash -c "$(cat bootstrap.sh)" zapime-install --install'
            f' --bundle "$PWD/runtime" --version {args.version} --key-sha256 {pin}'
            ' -- --image-archive "$PWD/images.tar" )'
        )
        (package / "INSTALL.txt").write_text(command + "\n", encoding="utf-8", newline="\n")
        output.mkdir(mode=0o700)
        for name in ("runtime", "images.tar", "bootstrap.sh", "INSTALL.txt"):
            (package / name).rename(output / name)
    print(f"Source commit: {commit}\nBootstrap SHA-256: {bootstrap_digest}\nRelease public key SHA-256: {pin}")
    print(f"Release directory: {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--architecture", required=True, choices=("amd64", "arm64"))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--private-key", required=True, type=Path)
    prepare(parser.parse_args())


if __name__ == "__main__":
    main()
