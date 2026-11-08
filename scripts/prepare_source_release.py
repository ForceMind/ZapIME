#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Prepare the fixed source Alpha; publish only an unchanged, verified bundle.

The archive is made by git archive, never from the working directory. It omits
submodule contents. Publishing needs the workflow's ephemeral GITHUB_TOKEN;
preparation and the regression tests need no GitHub credentials or network.
"""

import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tempfile
import time
import zipfile


PLAN_PATH = ".github/release-plan.json"
PLAN = {
    "schema_version": 1,
    "repository": "ForceMind/ZapIME",
    "tag": "v0.1.0-alpha.1",
    "name": "ZapIME v0.1.0-alpha.1 — Source Alpha",
    "notes_path": "docs/releases/v0.1.0-alpha.1.md",
    "prerelease": True,
    "source_only": True,
    "include_submodules": False,
}
SUBMODULES = {
    "vendor/msime": {
        "commit": "97d652a49cabb0887355b84186af2aa9032d3814",
        "url": "https://github.com/metasequoiaime/msime.git",
        "included": False,
    },
    "vendor/voicekey": {
        "commit": "3ba017826eef7fac405198510b03859eb5b6c8c4",
        "url": "https://github.com/J3n5en/voicekey.git",
        "included": False,
    },
}
ARCHIVE_NAME = "ZapIME-0.1.0-alpha.1-source.tar.gz"
PREFIX = "ZapIME-0.1.0-alpha.1-source/"
ASSET_NAMES = {ARCHIVE_NAME, "metadata.json", "SHA256SUMS"}
MAX_RELEASE_PAGES = 100
RELEASE_READ_DELAYS = (0, 1, 2, 4)
SHA = re.compile(r"[0-9a-f]{40}\Z")
FORBIDDEN_SUFFIXES = (
    ".apk", ".aab", ".ipa", ".ort", ".onnx", ".onnx.data", ".xnet",
    ".safetensors", ".gguf", ".pt", ".pth", ".ckpt", ".tflite", ".bin",
    ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key",
    ".mobileprovision", ".keychain", ".keychain-db",
)
LIMITATIONS = [
    "Root repository Git archive only; both pinned submodule contents are omitted.",
    "Use the public Git repository and git submodule update --init to obtain the pinned upstream sources.",
    "No APK, acoustic/candidate model weights, build output or signing keys are distributed as release assets.",
    "This source Alpha is not a stable product, production-signed binary, or completed platform acceptance.",
    "Source availability and integrity do not resolve the recorded VoiceKey distribution terms or model training-source license questions.",
]


class ReleaseError(RuntimeError):
    pass


def run(command, *, cwd=None, input_data=None):
    return subprocess.run(
        command, cwd=cwd, input=input_data, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=True,
    ).stdout


def git(repo, *arguments):
    return run(["git", "-C", str(repo), *arguments])


def require_sha(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise ReleaseError("A full lowercase 40-character Git commit SHA is required")
    return value


def read_blob(repo, commit, path):
    return git(repo, "show", f"{commit}:{path}")


def load_plan(repo, commit):
    try:
        plan = json.loads(read_blob(repo, commit, PLAN_PATH))
    except (ValueError, subprocess.CalledProcessError) as error:
        raise ReleaseError("The committed release plan is missing or invalid") from error
    # Compare types as well: JSON true must not masquerade as schema_version 1.
    if (not isinstance(plan, dict) or set(plan) != set(PLAN)
            or any(type(plan[key]) is not type(value) or plan[key] != value
                   for key, value in PLAN.items())):
        raise ReleaseError("Release plan must exactly match the fixed source Alpha policy")
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+-alpha\.[1-9][0-9]*", plan["tag"]):
        raise ReleaseError("Invalid source Alpha tag")
    return plan


def check_path(path):
    parts = PurePosixPath(path).parts
    if (not path or path.startswith("/") or any(c in path for c in "\r\n\\")
            or ".." in parts or ".git" in parts):
        raise ReleaseError("Unsafe archive path")
    if "target" in parts or path.lower().endswith(FORBIDDEN_SUFFIXES):
        raise ReleaseError(f"Source-only release rejects binary/model/key path: {path}")


def check_zip(data, *, depth=0):
    """Existing evidence ZIPs may contain reports, never nested product payloads."""
    if depth > 3:
        raise ReleaseError("Evidence archive nesting exceeds the source-only limit")
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if sum(entry.file_size for entry in archive.infolist()) > 256 * 1024 * 1024:
                raise ReleaseError("Evidence archive is too large to inspect")
            for entry in archive.infolist():
                check_path(entry.filename)
                if entry.flag_bits & 1:
                    raise ReleaseError("Encrypted evidence archives are not source-reviewable")
                if entry.filename.lower().endswith(".zip"):
                    check_zip(archive.read(entry), depth=depth + 1)
                elif entry.filename.lower().endswith((".tar", ".tar.gz", ".tgz", ".7z")):
                    raise ReleaseError("Uninspected nested evidence archive")
    except (zipfile.BadZipFile, RuntimeError) as error:
        raise ReleaseError("Invalid evidence ZIP") from error


def repository_details(repo, commit):
    require_sha(commit)
    resolved = git(repo, "rev-parse", "--verify", f"{commit}^{{commit}}").decode().strip()
    if resolved != commit:
        raise ReleaseError("Requested source object is not the exact commit")
    plan = load_plan(repo, commit)
    links = {}
    for entry in git(repo, "ls-tree", "-rz", "--full-tree", commit).split(b"\0"):
        if not entry:
            continue
        descriptor, raw_path = entry.split(b"\t", 1)
        mode, kind, object_sha = descriptor.decode("ascii").split()
        path = raw_path.decode("utf-8")
        check_path(path)
        if mode == "160000" and kind == "commit":
            links[path] = object_sha
        elif kind != "blob" or mode not in {"100644", "100755", "120000"}:
            raise ReleaseError("Unexpected root Git tree entry")
        elif path.lower().endswith(".zip"):
            check_zip(read_blob(repo, commit, path))
        elif path.lower().endswith((".tar", ".tar.gz", ".tgz", ".7z")):
            raise ReleaseError("Uninspected tracked archive is not a source-only input")
    if links != {path: value["commit"] for path, value in SUBMODULES.items()}:
        raise ReleaseError("The source Alpha requires exactly the two audited gitlinks")
    for path, value in SUBMODULES.items():
        url = git(repo, "config", "--blob", f"{commit}:.gitmodules", "--get",
                  f"submodule.{path}.url").decode().strip()
        if url != value["url"]:
            raise ReleaseError("Committed submodule URL differs from the audited source")
    notes = read_blob(repo, commit, plan["notes_path"]).decode("utf-8")
    if not notes.strip() or len(notes.encode()) > 512 * 1024:
        raise ReleaseError("Committed release notes must be nonempty and bounded")
    metadata = {
        "schema_version": 1,
        "tag": plan["tag"],
        "repository": plan["repository"],
        "source_commit": commit,
        "source_tree": git(repo, "rev-parse", f"{commit}^{{tree}}").decode().strip(),
        "archive": ARCHIVE_NAME,
        "archive_format": "git archive tar, gzip mtime=0",
        "submodules": SUBMODULES,
        "limitations": LIMITATIONS,
    }
    return plan, metadata, notes


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode()


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def prepare(repo, commit, output):
    plan, metadata, _ = repository_details(repo, commit)
    output = Path(output)
    if output.exists():
        raise ReleaseError("Output path already exists; refusing to replace a release bundle")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".source-alpha-", dir=output.parent) as temporary:
        staging = Path(temporary)
        tar = staging / "source.tar"
        git(repo, "archive", "--format=tar", f"--prefix={PREFIX}", f"--output={tar}", commit)
        with tar.open("rb") as source, (staging / ARCHIVE_NAME).open("wb") as destination:
            with gzip.GzipFile(filename="", fileobj=destination, mode="wb", mtime=0) as compressed:
                while block := source.read(1024 * 1024):
                    compressed.write(block)
        tar.unlink()
        (staging / "metadata.json").write_bytes(json_bytes(metadata))
        checksums = "".join(f"{digest(staging / name)}  {name}\n"
                            for name in sorted((ARCHIVE_NAME, "metadata.json")))
        (staging / "SHA256SUMS").write_text(checksums, encoding="ascii")
        os.rename(staging, output)
    return plan, metadata


def validate_bundle(repo, commit, output):
    output = Path(output)
    if set(path.name for path in output.iterdir()) != ASSET_NAMES:
        raise ReleaseError("Release bundle must contain exactly the three source assets")
    if any(not path.is_file() or path.is_symlink() for path in output.iterdir()):
        raise ReleaseError("Release assets must be regular files")
    # Recreate from the committed tree, including evidence payload checks. Do not
    # trust a downloaded artifact's self-reported hashes or source metadata.
    with tempfile.TemporaryDirectory(prefix="verify-source-alpha-") as temporary:
        expected = Path(temporary) / "bundle"
        plan, metadata = prepare(repo, commit, expected)
        if any(digest(output / name) != digest(expected / name) for name in ASSET_NAMES):
            raise ReleaseError("Release bundle differs from the exact committed source")
    return plan, metadata


def plan_changed(repo, before, commit):
    require_sha(before)
    require_sha(commit)
    load_plan(repo, commit)
    if before == "0" * 40:
        return True
    result = subprocess.run(
        ["git", "-C", str(repo), "diff", "--quiet", before, commit, "--", PLAN_PATH],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if result.returncode not in {0, 1}:
        raise ReleaseError("Cannot determine whether the committed release plan changed")
    return result.returncode == 1


def gh_json(arguments, *, optional=False, runner=run):
    try:
        return json.loads(runner(["gh", "api", *arguments]))
    except subprocess.CalledProcessError as error:
        if optional and b"(HTTP 404)" in (error.stderr or b""):
            return None
        raise ReleaseError("GitHub API request failed; existing releases were not overwritten") from error
    except (ValueError, UnicodeError) as error:
        raise ReleaseError("GitHub API response was not valid JSON; no response body is logged") from error


def release_id(value, tag):
    if (not isinstance(value, dict) or value.get("tag_name") != tag
            or type(value.get("id")) is not int or value["id"] <= 0):
        raise ReleaseError("Release response has no valid ID for the fixed source tag")
    return value["id"]


def fetch_release(repository, tag, *, runner=run):
    """Tag lookup covers published releases; visible drafts need list + ID."""
    prefix = f"repos/{repository}/releases"
    published = gh_json([f"{prefix}/tags/{tag}"], optional=True, runner=runner)
    if published is not None:
        release_id(published, tag)
        return published
    matches = []
    for page in range(1, MAX_RELEASE_PAGES + 1):
        entries = gh_json([f"{prefix}?per_page=100&page={page}"], runner=runner)
        if not isinstance(entries, list) or len(entries) > 100:
            raise ReleaseError("Invalid release listing; cannot safely locate a draft")
        for entry in entries:
            if not isinstance(entry, dict):
                raise ReleaseError("Invalid release listing entry")
            if entry.get("tag_name") == tag:
                identifier = entry.get("id")
                if type(identifier) is not int or identifier <= 0:
                    raise ReleaseError("Matching release has no valid release ID")
                matches.append(identifier)
        if len(entries) < 100:
            break
    else:
        raise ReleaseError("Release listing pagination limit reached before completion")
    if len(matches) > 1:
        raise ReleaseError("Multiple visible releases use the fixed tag; refusing an ambiguous draft")
    if not matches:
        return None
    value = gh_json([f"{prefix}/{matches[0]}"], runner=runner)
    if release_id(value, tag) != matches[0]:
        raise ReleaseError("Release ID no longer matches the fixed source tag")
    return value


def confirm_release(repository, tag, *, identifier=None, stage, runner=run, sleeper=time.sleep):
    """Bounded read-only reconciliation; never repeat a creation/upload/edit."""
    for delay in RELEASE_READ_DELAYS:
        if delay:
            sleeper(delay)
        if identifier is None:
            value = fetch_release(repository, tag, runner=runner)
        else:
            value = gh_json([f"repos/{repository}/releases/{identifier}"],
                            optional=True, runner=runner)
        if value is not None:
            found = release_id(value, tag)
            if identifier is not None and found != identifier:
                raise ReleaseError("Release response ID changed during confirmation")
            return value
    raise ReleaseError(
        f"{stage} could not be confirmed after {len(RELEASE_READ_DELAYS)} read-only checks; "
        "no further mutation was attempted. Preserve the tag/draft and rerun the same source bundle")


def create_draft(repository, tag, payload, *, runner=run, sleeper=time.sleep):
    """Keep the REST creation ID instead of depending on immediate list visibility.

    A lost creation response can mean the server committed the request. Reconcile
    by tag/list only; never issue a second POST in this invocation. Authentication
    failures and malformed non-null objects fail immediately without guessing.
    """
    try:
        created = gh_json(["--method", "POST", f"repos/{repository}/releases", "--input", "-"],
                          runner=lambda command: runner(command, input_data=json_bytes(payload)))
    except ReleaseError as error:
        cause = error.__cause__
        if isinstance(cause, subprocess.CalledProcessError):
            if any(status in (cause.stderr or b"") for status in (b"(HTTP 401)", b"(HTTP 403)")):
                raise
        elif not isinstance(cause, (ValueError, UnicodeError)):
            raise
        created = None
    identifier = release_id(created, tag) if created is not None else None
    return confirm_release(repository, tag, identifier=identifier, stage="Draft creation",
                           runner=runner, sleeper=sleeper)


def remote_tag(repository, tag, *, runner=run):
    ref = gh_json([f"repos/{repository}/git/ref/tags/{tag}"], optional=True, runner=runner)
    if ref is None:
        return None
    if not isinstance(ref, dict) or ref.get("ref") != f"refs/tags/{tag}":
        raise ReleaseError("Remote tag reference does not match the fixed release tag")
    obj = ref.get("object")
    for _ in range(8):
        if not isinstance(obj, dict):
            raise ReleaseError("Remote tag response has no valid Git object")
        require_sha(obj.get("sha"))
        if obj.get("type") == "commit":
            return obj["sha"]
        if obj.get("type") != "tag":
            break
        annotated = gh_json([f"repos/{repository}/git/tags/{obj['sha']}"], runner=runner)
        obj = annotated.get("object") if isinstance(annotated, dict) else None
    raise ReleaseError("Remote tag does not resolve to a commit")


def publish(repo, commit, output, *, repository, runner=run, sleeper=time.sleep):
    plan, metadata = validate_bundle(repo, commit, output)
    if repository != plan["repository"]:
        raise ReleaseError("Publishing is restricted to the fixed release repository")
    output = Path(output).resolve()
    tag = plan["tag"]
    release = fetch_release(repository, tag, runner=runner)
    source = remote_tag(repository, tag, runner=runner)
    if source is not None and source != commit:
        raise ReleaseError("Existing tag points to another commit; refusing to move it")
    if release is not None and source is None:
        raise ReleaseError("Existing release has no verifiable source tag")
    if source is None:
        payload = json_bytes({"ref": f"refs/tags/{tag}", "sha": commit})
        runner(["gh", "api", "--method", "POST", f"repos/{repository}/git/refs",
                "--input", "-"], input_data=payload)
        if remote_tag(repository, tag, runner=runner) != commit:
            raise ReleaseError("New source tag did not resolve to the exact commit")
    _, _, notes = repository_details(repo, commit)
    marker = f"<!-- zapime-source-alpha commit={commit} tree={metadata['source_tree']} -->"
    body = notes.rstrip() + "\n\n" + marker + "\n"
    if release is None:
        release = create_draft(repository, tag, {
            "tag_name": tag, "target_commitish": commit, "name": plan["name"],
            "body": body, "draft": True, "prerelease": True, "make_latest": "false",
        }, runner=runner, sleeper=sleeper)

    def validate_release(value, *, complete=None):
        release_id(value, tag)
        if (value.get("target_commitish") != commit
                or value.get("name") != plan["name"] or value.get("prerelease") is not True
                or not isinstance(value.get("body"), str) or value["body"].rstrip() != body.rstrip()
                or type(value.get("draft")) is not bool):
            raise ReleaseError("Existing release is not the same committed source Alpha")
        if complete is None:
            complete = not value["draft"]
        assets = value.get("assets")
        if (not isinstance(assets, list) or any(not isinstance(asset, dict)
                or not isinstance(asset.get("name"), str) for asset in assets)):
            raise ReleaseError("Release response has no valid source asset list")
        names = [asset["name"] for asset in assets]
        if (len(set(names)) != len(names) or not set(names).issubset(ASSET_NAMES)
                or (complete and set(names) != ASSET_NAMES)):
            raise ReleaseError("Existing release assets do not match the source-only bundle")
        with tempfile.TemporaryDirectory(prefix="source-alpha-asset-") as temporary:
            for asset in assets:
                downloaded = Path(temporary) / asset["name"]
                runner(["gh", "release", "download", tag, "--repo", repository,
                        "--pattern", asset["name"], "--output", str(downloaded)])
                if not downloaded.is_file() or digest(downloaded) != digest(output / asset["name"]):
                    raise ReleaseError("Existing release asset differs; refusing to overwrite it")
        return set(names)

    existing = validate_release(release)
    if not release["draft"]:
        if remote_tag(repository, tag, runner=runner) != commit:
            raise ReleaseError("Published release tag changed during verification")
        return "Already published; verified without modifying the release"
    missing = sorted(ASSET_NAMES - existing)
    if missing:
        runner(["gh", "release", "upload", tag, "--repo", repository,
                *[str(output / name) for name in missing]])
    identifier = release_id(release, tag)
    current = confirm_release(repository, tag, identifier=identifier, stage="Asset upload",
                              runner=runner, sleeper=sleeper)
    validate_release(current, complete=True)
    if current["draft"] is not True:
        raise ReleaseError("Release was published concurrently; refusing further mutations")
    if remote_tag(repository, tag, runner=runner) != commit:
        raise ReleaseError("Tag changed before publication; leaving the release as a draft")
    runner(["gh", "release", "edit", tag, "--repo", repository,
            "--draft=false", "--prerelease", "--latest=false"])
    published = confirm_release(repository, tag, identifier=identifier, stage="Publication",
                                runner=runner, sleeper=sleeper)
    validate_release(published, complete=True)
    if published["draft"] or remote_tag(repository, tag, runner=runner) != commit:
        raise ReleaseError("Published release state could not be verified")
    return "Published verified source Alpha prerelease"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "changed", "publish"))
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", type=Path, default=Path("target/source-release"))
    parser.add_argument("--before")
    parser.add_argument("--repository", default="")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            prepare(args.repo, args.commit, args.output)
            print(f"Prepared {PLAN['tag']} from {args.commit}")
        elif args.command == "changed":
            changed = plan_changed(args.repo, args.before, args.commit)
            result = f"changed={'true' if changed else 'false'}\n"
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as destination:
                    destination.write(result)
            print(result, end="")
        else:
            print(publish(args.repo, args.commit, args.output, repository=args.repository))
    except (ReleaseError, subprocess.CalledProcessError, OSError, UnicodeError, ValueError) as error:
        # Never print command environments, tokens, remote API bodies or notes.
        parser.exit(1, f"Source release failed: {type(error).__name__}: "
                    f"{error if isinstance(error, ReleaseError) else 'validation or command failure'}\n")


if __name__ == "__main__":
    main()
