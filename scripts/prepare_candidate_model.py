#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Build-time preparation of the pinned offline candidate model and ORT C headers.

No product runtime downloads are involved. Source pins are reviewed in
third_party/candidate-model-lock.json; the Java catalog is generated from that
lock, never from a downloaded or installed model. --offline requires an intact
cache. --check-catalog and --write-catalog do not access the network.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile
import urllib.parse
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "third_party/candidate-model-lock.json"
CATALOG = ROOT / "platforms/android/java/org/zapime/android/CandidateModelCatalog.java"
CHUNK = 1024 * 1024
MODEL_FILES = {"lm_step.ort", "model.json", "vocab.json"}
NOTICE_FILES = {"LICENSE.txt", "NOTICE.txt", "SOURCE_REVIEW.txt"}


class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        if urllib.parse.urlparse(newurl).scheme != "https":
            raise ValueError("candidate dependency redirect must remain HTTPS")
        return super().redirect_request(request, response, code, message, headers, newurl)


OPENER = urllib.request.build_opener(HttpsRedirect())


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def real_directory(path):
    """Reject symlink destinations/ancestors before creating build output."""
    path = path.absolute()
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink():
            raise ValueError("candidate output contains a symbolic link")
        if ancestor.exists() and not ancestor.is_dir():
            raise ValueError("candidate output ancestor is not a directory")
    path.mkdir(parents=True, exist_ok=True)


def valid_spec(name, spec, maximum=16 * CHUNK):
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+", name) or name in {".", ".."}:
        raise ValueError("invalid candidate dependency name")
    if type(spec.get("size")) is not int or not 0 < spec["size"] <= maximum:
        raise ValueError("invalid candidate dependency size")
    if not isinstance(spec.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", spec["sha256"]):
        raise ValueError("invalid candidate dependency SHA256")


def verified(path, spec):
    return (not path.is_symlink() and path.is_file()
            and path.stat().st_size == spec["size"] and sha256(path) == spec["sha256"])


def load_lock(path=LOCK):
    lock = json.loads(path.read_text(encoding="utf-8"))
    if lock.get("schema_version") != 1 or lock.get("model_id") != "msime-keystroke-8m-rerank-v2":
        raise ValueError("unsupported candidate model identity")
    if lock.get("asset_directory") != "candidate-model":
        raise ValueError("unsupported candidate asset directory")
    upstream = lock["upstream"]
    if (upstream["repository"] != "metasequoiaime/chinese-ime-lm"
            or upstream["release_tag"] != "rerank-v2" or upstream["license"] != "Apache-2.0"):
        raise ValueError("unsupported candidate model source")
    if set(lock["files"]) != MODEL_FILES | NOTICE_FILES:
        raise ValueError("incomplete candidate model file set")
    release = "https://github.com/metasequoiaime/chinese-ime-lm/releases/download/rerank-v2/"
    assets = {"lm_step.ort": "rerank-6x256.ort", "model.json": "rerank-6x256.model.json",
              "vocab.json": "rerank-6x256.vocab.json"}
    for name, spec in lock["files"].items():
        valid_spec(name, spec)
        if name in MODEL_FILES:
            if set(spec) != {"size", "sha256", "url"} or spec["url"] != release + assets[name]:
                raise ValueError("candidate model URL differs from the reviewed release")
        else:
            if set(spec) != {"size", "sha256", "source"}:
                raise ValueError("candidate notice must come from reviewed local source")
            source = Path(spec["source"])
            if (source.is_absolute() or source.parts[:2] != ("third_party", "licenses")
                    or len(source.parts) != 3 or ".." in source.parts):
                raise ValueError("candidate notice path escapes the license directory")
    headers = lock["build_headers"]
    if (headers["runtime_version"] != "1.28.2" or headers["repository"] != "microsoft/onnxruntime"
            or headers["source_commit"] != "33ca9628233dc8f002435e868d4c2e9f82766ca1"
            or headers["license"] != "MIT"):
        raise ValueError("unsupported candidate build headers")
    paths = {"onnxruntime_c_api.h": "include/onnxruntime/core/session/onnxruntime_c_api.h",
             "onnxruntime_ep_c_api.h": "include/onnxruntime/core/session/onnxruntime_ep_c_api.h",
             "onnxruntime_error_code.h": "include/onnxruntime/core/session/onnxruntime_error_code.h",
             "LICENSE.txt": "LICENSE"}
    if set(headers["files"]) != set(paths):
        raise ValueError("incomplete ORT header file set")
    prefix = "https://raw.githubusercontent.com/microsoft/onnxruntime/" + headers["source_commit"] + "/"
    for name, spec in headers["files"].items():
        valid_spec(name, spec, CHUNK)
        if set(spec) != {"size", "sha256", "url"} or spec["url"] != prefix + paths[name]:
            raise ValueError("ORT header URL differs from the reviewed commit")
    return lock


def publish(incoming, destination, spec):
    """Bound, hash and close a staged file before replacing any previous file."""
    real_directory(destination.parent)
    if destination.is_symlink() or (destination.exists() and not destination.is_file()):
        raise ValueError("candidate dependency destination is not a regular file")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as output:
            temporary = Path(output.name)
            remaining = spec["size"]
            digest = hashlib.sha256()
            while data := incoming.read(min(CHUNK, remaining + 1)):
                if len(data) > remaining:
                    raise ValueError("oversized candidate dependency")
                output.write(data)
                digest.update(data)
                remaining -= len(data)
        if remaining or digest.hexdigest() != spec["sha256"]:
            raise ValueError("candidate dependency checksum/size mismatch")
        temporary.replace(destination)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def download(spec, destination, offline=False):
    real_directory(destination.parent)
    if destination.is_symlink():
        raise ValueError("candidate dependency is a symbolic link")
    if verified(destination, spec):
        return
    if offline:
        raise ValueError("candidate dependency missing or invalid in offline cache: " + destination.name)
    if urllib.parse.urlparse(spec["url"]).scheme != "https":
        raise ValueError("candidate dependency requires HTTPS")
    with OPENER.open(spec["url"], timeout=60) as incoming:
        publish(incoming, destination, spec)


def copy_verified(source, destination, spec):
    real_directory(source.parent)
    if not verified(source, spec):
        raise ValueError("candidate source checksum/size mismatch: " + source.name)
    with source.open("rb") as incoming:
        publish(incoming, destination, spec)


def revision(lock):
    rows = [{"name": name, "size": spec["size"], "sha256": spec["sha256"]}
            for name, spec in sorted(lock["files"].items())]
    return hashlib.sha256(json.dumps(rows, separators=(",", ":"), sort_keys=True).encode()).hexdigest()


def generate_catalog(lock):
    quote = json.dumps
    lines = ["// SPDX-License-Identifier: GPL-3.0-only",
             "// Generated by scripts/prepare_candidate_model.py --write-catalog from reviewed pins.",
             "// Any model or notice byte change must change REVISION; never reuse its install directory.",
             "package org.zapime.android;", "", "import java.util.Arrays;",
             "import java.util.Collections;", "import java.util.List;", "",
             "final class CandidateModelCatalog {",
             "    static final String MODEL_ID = " + quote(lock["model_id"]) + ";",
             "    static final String ASSET_DIRECTORY = " + quote(lock["asset_directory"]) + ";",
             "    static final String REVISION = " + quote(revision(lock)) + ";",
             "    static final long TOTAL_BYTES = " + str(sum(s["size"] for s in lock["files"].values())) + "L;",
             "    static final List<Entry> FILES = Collections.unmodifiableList(Arrays.asList("]
    entries = ["            new Entry(" + quote(name) + ", " + str(spec["size"]) + "L, "
               + quote(spec["sha256"]) + ")" for name, spec in lock["files"].items()]
    lines.append(",\n".join(entries) + "));")
    lines += ["", "    static final class Entry {", "        final String name;",
              "        final long size;", "        final String sha256;", "",
              "        private Entry(String name, long size, String sha256) {",
              "            this.name = name;", "            this.size = size;",
              "            this.sha256 = sha256;", "        }", "    }", "",
              "    private CandidateModelCatalog() {}", "}", ""]
    return "\n".join(lines)


def verify_notices(lock, root=ROOT):
    for name in NOTICE_FILES:
        spec = lock["files"][name]
        source = root / spec["source"]
        real_directory(source.parent)
        if not verified(source, spec):
            raise ValueError("reviewed candidate notice changed: " + name)


def verify_model(directory, lock):
    expected = lock["model"]
    metadata = json.loads((directory / "model.json").read_text(encoding="utf-8"))
    for key in ("layers", "heads", "head_dim", "width", "max_positions", "context_chars", "bos", "unk"):
        if type(metadata.get(key)) is not int or metadata[key] != expected[key]:
            raise ValueError("candidate model metadata mismatch: " + key)
    if metadata.get("self_normalized") is not True or metadata.get("license") != "Apache-2.0":
        raise ValueError("candidate model format/license mismatch")
    attribution = metadata.get("attribution")
    if (not isinstance(attribution, str)
            or hashlib.sha256(attribution.encode()).hexdigest() != expected["attribution_sha256"]):
        raise ValueError("candidate model attribution mismatch")
    config = metadata.get("config", {})
    for key, value in (("vocab", expected["vocab_size"]), ("n_layer", expected["layers"]),
                       ("n_head", expected["heads"]), ("n_embd", expected["width"]),
                       ("max_positions", expected["max_positions"])):
        if type(config.get(key)) is not int or config[key] != value:
            raise ValueError("candidate graph config mismatch: " + key)
    if config.get("self_normalized") is not True or config.get("gelu_tanh") is not True:
        raise ValueError("candidate graph variant mismatch")
    vocab = json.loads((directory / "vocab.json").read_text(encoding="utf-8"))
    tokens = vocab.get("tokens")
    if (not isinstance(tokens, list) or len(tokens) != expected["vocab_size"]
            or any(not isinstance(token, str) or not token for token in tokens)
            or len(set(tokens)) != len(tokens)
            or tokens[expected["bos"]] != "<bos>" or tokens[expected["unk"]] != "<unk>"):
        raise ValueError("candidate vocabulary mismatch")
    with (directory / "lm_step.ort").open("rb") as graph:
        if graph.read(8)[4:] != b"ORTM":
            raise ValueError("candidate graph is not ORT format")


def verified_directory(directory, specs):
    if directory.is_symlink() or not directory.is_dir():
        return False
    return (set(p.name for p in directory.iterdir()) == set(specs)
            and all(verified(directory / name, spec) for name, spec in specs.items()))


def publish_directory(source, destination, specs):
    """Stage a complete verified set; a failed copy leaves previous output intact."""
    real_directory(destination.parent)
    if destination.is_symlink() or (destination.exists() and not destination.is_dir()):
        raise ValueError("candidate asset destination is not a directory")
    if verified_directory(destination, specs):
        return
    if destination.exists() and any(p.name not in specs or p.is_symlink() or not p.is_file()
                                    for p in destination.iterdir()):
        raise ValueError("candidate destination contains unrelated or symbolic entries")
    stage = Path(tempfile.mkdtemp(prefix=".candidate-stage-", dir=destination.parent))
    backup = None
    published = False
    try:
        for name, spec in specs.items():
            copy_verified(source / name, stage / name, spec)
        if not verified_directory(stage, specs):
            raise ValueError("candidate staged directory mismatch")
        if destination.exists():
            backup = Path(tempfile.mkdtemp(prefix=".candidate-previous-", dir=destination.parent))
            backup.rmdir()
            destination.replace(backup)
        try:
            stage.replace(destination)
            published = True
        except BaseException:
            if backup is not None:
                backup.replace(destination)
                backup = None
            raise
    finally:
        if stage.exists():
            shutil.rmtree(stage)
        # If both publish and rollback fail, preserve the previous resource set
        # in its backup location for recovery instead of deleting the last copy.
        if backup is not None and published:
            shutil.rmtree(backup)


def prepare(lock, cache, destination=None, headers=None, offline=False, root=ROOT):
    verify_notices(lock, root)
    real_directory(cache)
    if any(p.name not in lock["files"] for p in cache.iterdir()):
        raise ValueError("candidate cache contains unrelated entries")
    for name, spec in lock["files"].items():
        if "url" in spec:
            download(spec, cache / name, offline)
        else:
            copy_verified(root / spec["source"], cache / name, spec)
    if not verified_directory(cache, lock["files"]):
        raise ValueError("candidate model cache is incomplete")
    verify_model(cache, lock)
    if headers is not None:
        real_directory(headers)
        for name, spec in lock["build_headers"]["files"].items():
            download(spec, headers / name, offline)
    if destination is not None and destination.absolute() != cache.absolute():
        publish_directory(cache, destination, lock["files"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, help="APK assets/candidate-model directory")
    parser.add_argument("--headers", type=Path, help="also prepare pinned ORT C headers and MIT license here")
    parser.add_argument("--offline", action="store_true", help="use verified cache only; never connect")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--check-catalog", action="store_true")
    modes.add_argument("--write-catalog", action="store_true")
    args = parser.parse_args()
    if (args.check_catalog or args.write_catalog) and (args.destination or args.headers or args.offline):
        parser.error("catalog-only operations cannot be combined with resource preparation")
    lock = load_lock()
    verify_notices(lock)
    generated = generate_catalog(lock)
    if args.write_catalog:
        CATALOG.write_text(generated, encoding="utf-8")
        print("PASS generated reviewed candidate model catalog")
        return
    if not CATALOG.is_file() or CATALOG.read_text(encoding="utf-8") != generated:
        raise SystemExit("CandidateModelCatalog differs from the reviewed lock; regenerate and review it")
    if args.check_catalog:
        print("PASS reviewed candidate catalog and source notices")
        return
    cache = ROOT / "target/candidate-model" / lock["model_id"]
    prepare(lock, cache, args.destination, args.headers, args.offline)
    print("PASS candidate model bytes, metadata, vocabulary and notices: " + str(args.destination or cache))
    if args.headers:
        print("PASS pinned ONNX Runtime 1.28.2 headers and MIT license: " + str(args.headers))


if __name__ == "__main__":
    main()
