#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Two fixed prompts, the same 19 synthetic cases; no downloads or server."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import sys
import tarfile

from assets import ASSETS, ROOT, RUNTIME_COMMIT, verify_asset, digest_file
from environment import controlled_environment
from prompts import SYSTEM_PROMPTS, render_prompt, template_record


def verify_runtime():
    runtime = ROOT / "runtime/llama-b11669"
    # Expected bytes come from the verified publisher tar, never a mutable manifest.
    names = set()
    with tarfile.open(ROOT / "llama-b11669-bin-ubuntu-x64.tar.gz", "r:gz") as archive:
        for member in archive.getmembers():
            if member.isdir():
                continue
            name = Path(member.name).name
            names.add(name)
            path = runtime / name
            if member.issym():
                if not path.is_symlink() or str(path.readlink()) != member.linkname:
                    raise RuntimeError("extracted runtime symlink differs: " + name)
            elif not member.isfile() or path.is_symlink() or path.stat().st_size != member.size or digest_file(path) != hashlib.sha256(archive.extractfile(member).read()).hexdigest():
                raise RuntimeError("extracted runtime differs: " + name)
    if {path.name for path in runtime.iterdir()} != names:
        raise RuntimeError("unexpected files in extracted runtime")
    env, _ = controlled_environment()
    version = subprocess.run([str(runtime / "llama-completion"), "--version"], capture_output=True, text=True, check=True, timeout=10, env=env)
    if "build 11669, commit 620d2225c" not in version.stderr:
        raise RuntimeError("runtime version does not match reviewed source release")
    return version.stderr


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise RuntimeError("this fixed native runtime is Linux x86-64 only")
    # Verify exact public assets before any runtime invocation; no fallback download.
    assets = [verify_asset(name) for name in ASSETS]
    version = verify_runtime()
    scripts = Path(__file__).resolve().parent
    old_evidence = scripts.parents[1] / "docs/evidence/english-learning-preprocessing-20261024/contrast-sentencepiece-beam1-offline.json"
    texts = [entry["source"] for entry in json.loads(old_evidence.read_text())["examples"]]
    if len(texts) != 19:
        raise RuntimeError("the complete fixed nineteen-case contrast is required")
    prompt_directory = ROOT / "prompts"
    prompt_directory.mkdir(exist_ok=True)
    results = []
    for language, system in SYSTEM_PROMPTS.items():
        for index, source in enumerate(texts, 1):
            prompt = render_prompt(system, source)
            path = prompt_directory / (language + "-" + str(index).zfill(2) + ".txt")
            path.write_text(prompt)
            command = [sys.executable, str(scripts / "worker.py"), str(path)]
            if args.offline:
                command.append("--offline")
            process = subprocess.run(command, capture_output=True, text=True, check=True, timeout=100)
            entry = json.loads(process.stdout)
            entry.update({"case_index": index, "system_prompt_language": language, "source": source, "complete_prompt": prompt, "prompt_sha256": hashlib.sha256(prompt.encode()).hexdigest()})
            results.append(entry)
            print(json.dumps({"prompt": language, "case": index, "offline": args.offline, "shape_accepted": entry["shape_accepted"], "exit_code": entry["exit_code"]}), flush=True)
            # Save all completed results even if a later process or assertion fails.
            result = {
                "synthetic_text_only": True, "platform": platform.platform(), "python": platform.python_version(),
                "runtime_version_output": version, "runtime_source_commit": RUNTIME_COMMIT,
                "assets": assets, "prompt_template": template_record(), "native_temperature": 0,
                "decode_policy": "temperature zero controlled greedy comparison, not Qwen's recommended non-thinking sampling",
                "offline_syscall_restriction_requested": args.offline, "examples": results,
                "quality_accepted": False,
                "limits": "Independent Linux CPU experiment only; fresh native process per case and warmed file cache from hash verification/prior cases. No phone, iOS extension, battery, keyboard contention, broad semantic evaluation or client license audit.",
            }
            (ROOT / ("qwen-offline.json" if args.offline else "qwen-network-capable.json")).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    if not all(entry["shape_accepted"] for entry in results):
        raise SystemExit("One or more structural/runtime checks failed; all raw outputs retained")


if __name__ == "__main__":
    main()
