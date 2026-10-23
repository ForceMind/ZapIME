#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Build an offline-importable X-ASR bundle; weights never enter Git or the APK."""
import argparse
import json
from pathlib import Path
import zipfile
import voice_probe
from prepare_android_voice import ROOT, model_config, verified


def build(destination):
    model = ROOT / "target/voice-model"
    voice_probe.prepare(model)
    config = model_config()
    (model / "LICENSE.txt").write_bytes((ROOT / "third_party/licenses/X-ASR-Apache-2.0.txt").read_bytes())
    (model / "NOTICE.txt").write_bytes(b"X-ASR by Gilgamesh-J/X-ASR; ONNX conversion by k2-fsa/sherpa-onnx. Apache-2.0.\n")
    destination.parent.mkdir(parents=True, exist_ok=True)
    staged = destination.with_suffix(destination.suffix + ".tmp")
    try:
        with zipfile.ZipFile(staged, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, spec in sorted(config["files"].items()):
                path = model / name
                if not verified(path, spec):
                    raise ValueError("model member failed verification")
                member = zipfile.ZipInfo(name, date_time=(2026, 6, 5, 0, 0, 0))
                member.compress_type = zipfile.ZIP_DEFLATED
                member.external_attr = 0o100444 << 16
                with path.open("rb") as source, archive.open(member, "w") as out:
                    while chunk := source.read(1024 * 1024):
                        out.write(chunk)
        staged.replace(destination)
    finally:
        staged.unlink(missing_ok=True)
    (model / "voice-model.json").write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n")
    print("PASS offline model bundle", destination, destination.stat().st_size)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "target/ZapIME-XASR-model.zip")
    build(parser.parse_args().output)
