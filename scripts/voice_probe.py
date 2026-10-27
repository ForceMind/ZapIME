#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Reproducible CPU streaming ASR probe, separate download and inference commands.

No microphone, cloud API, user recording, or APK integration is implied. CI uses
only locally synthesized text, with inference inside a network-less namespace.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import tarfile
import tempfile
import time
import urllib.request
import wave

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / "third_party/voice-probe-lock.json").read_text())
SYNTHETIC_TEXT = "你好，这是一段离线语音输入测试。今天下午三点开会。"


def verified(path, spec):
    if not path.is_file() or path.is_symlink() or path.stat().st_size != spec["size"]:
        return False
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest() == spec["sha256"]


def prepare(directory):
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / "model.tar.bz2"
    if not verified(archive, LOCK["archive"]):
        # Only the pinned upstream model URL; never execute files from the tar.
        with tempfile.NamedTemporaryFile(dir=directory, delete=False) as out:
            temporary = Path(out.name)
            try:
                with urllib.request.urlopen(LOCK["archive"]["url"], timeout=60) as response:
                    remaining = LOCK["archive"]["size"]
                    while chunk := response.read(min(1024*1024, remaining+1)):
                        if len(chunk) > remaining:
                            raise ValueError("oversized model archive")
                        out.write(chunk)
                        remaining -= len(chunk)
                out.close()
                if not verified(temporary, LOCK["archive"]):
                    raise ValueError("model archive checksum/size mismatch")
                temporary.replace(archive)
            finally:
                temporary.unlink(missing_ok=True)
    with tarfile.open(archive) as source:
        # Extract exactly four regular files, no links, paths, code or recordings.
        for name, spec in LOCK["files"].items():
            member = source.getmember(LOCK["archive"]["root"] + "/" + name)
            if not member.isfile() or member.size != spec["size"]:
                raise ValueError("invalid model member")
            with tempfile.NamedTemporaryFile(dir=directory, delete=False) as out:
                temporary = Path(out.name)
                try:
                    with source.extractfile(member) as incoming:
                        shutil.copyfileobj(incoming, out)
                    out.close()
                    if not verified(temporary, spec):
                        raise ValueError("model member checksum mismatch")
                    temporary.replace(directory / name)
                finally:
                    temporary.unlink(missing_ok=True)
    shutil.copyfile(ROOT / "third_party/licenses/X-ASR-Apache-2.0.txt", directory / "LICENSE")
    (directory / "NOTICE.txt").write_text("X-ASR by Gilgamesh-J/X-ASR, converted by k2-fsa/sherpa-onnx. Apache-2.0.\n")
    print("PASS pinned model archive and four inference file checksums")


def synthesize(output, espeak, text=SYNTHETIC_TEXT, voice="cmn-latn-pinyin", speed=150):
    import numpy as np
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        raw = Path(temporary) / "synthetic.wav"
        # cmn's Latin-as-English mode can pronounce dictionary pinyin as English.
        subprocess.run([espeak, "-v", voice, "-s", str(speed), "-w", str(raw), text], check=True)
        with wave.open(str(raw)) as wav:
            if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
                raise ValueError("unexpected synthetic audio format")
            source_rate = wav.getframerate()
            values = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2")
        # Fixture generation only; real microphone capture will use 16 kHz PCM.
        frames = int(len(values)*16000/source_rate)
        values = np.interp(np.arange(frames)*source_rate/16000, np.arange(len(values)), values).astype("<i2")
        with wave.open(str(output), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16000)
            wav.writeframes(values.tobytes())
    print("PASS synthetic Mandarin fixture generated locally")


def read_audio(audio, max_seconds=120):
    import numpy as np
    with wave.open(str(audio)) as wav:
        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
            raise ValueError("expected mono 16 kHz PCM16 WAV")
        if not 0 < wav.getnframes() <= max_seconds*16000:
            raise ValueError("audio duration exceeds probe bound")
        frames = wav.getnframes()
        raw = wav.readframes(frames)
        if len(raw) != frames*2:
            raise ValueError("truncated PCM payload")
        return np.frombuffer(raw, dtype="<i2").astype(np.float32)/32768


def create_recognizer(directory, threads):
    import sherpa_onnx
    for name, spec in LOCK["files"].items():
        if not verified(directory / name, spec):
            raise ValueError("model integrity check failed: " + name)
    initialized = time.perf_counter()
    recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(directory / "tokens.txt"), encoder=str(directory / "encoder.int8.onnx"),
        decoder=str(directory / "decoder.onnx"), joiner=str(directory / "joiner.int8.onnx"),
        num_threads=threads, sample_rate=16000, decoding_method="greedy_search",
        model_type="zipformer2", provider="cpu")
    return recognizer, time.perf_counter() - initialized


def decode_audio(recognizer, samples):
    """Same unpaced 100ms CPU path for the legacy gate and whole-sentence probe."""
    import numpy as np
    stream = recognizer.create_stream()
    started = time.perf_counter()
    first_compute, first_audio = None, None
    for start in range(0, len(samples), 1600):
        stream.accept_waveform(16000, samples[start:start+1600])
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
        if first_audio is None and recognizer.get_result(stream).strip():
            first_audio = min(start+1600, len(samples))/16000
            first_compute = time.perf_counter()-started
    stream.accept_waveform(16000, np.zeros(16000, dtype=np.float32))
    stream.input_finished()
    while recognizer.is_ready(stream):
        recognizer.decode_stream(stream)
    elapsed = time.perf_counter()-started
    return recognizer.get_result(stream).strip(), {
        "decode_seconds": elapsed, "rtf": elapsed/(len(samples)/16000),
        "first_text_compute_seconds": first_compute, "first_text_audio_seconds": first_audio}


def recognize(directory, audio, threads, runs, report, include_text, expected_fragments):
    # This branch has no download/HTTP client calls. Missing or altered models fail.
    import sherpa_onnx
    samples = read_audio(audio)
    recognizer, load_seconds = create_recognizer(directory, threads)
    results = []
    for iteration in range(runs):
        text, timing = decode_audio(recognizer, samples)
        if not text:
            raise ValueError("recognizer produced empty text")
        row = {"iteration": iteration+1, **timing,
               "text_characters": len(text), "text_sha256": hashlib.sha256(text.encode()).hexdigest(),
               "expected_fragments_present": all(fragment in text for fragment in expected_fragments)}
        if include_text:
            row["text"] = text
        results.append(row)
    result = {"model": LOCK["model"], "runtime": sherpa_onnx.__version__, "platform": platform.platform(),
              "processor": platform.machine(), "threads": threads, "audio_seconds": len(samples)/16000,
              "audio_sha256": hashlib.file_digest(audio.open("rb"), "sha256").hexdigest(),
              "load_seconds": load_seconds, "median_decode_seconds": statistics.median(r["decode_seconds"] for r in results),
              "median_rtf": statistics.median(r["rtf"] for r in results), "runs": results,
              "expected_fragments": expected_fragments,
              "scope": "CPU file-fed streaming probe; not handset latency, microphone or accuracy acceptance"}
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n")
    if not all(row["expected_fragments_present"] for row in results):
        raise ValueError("known synthetic phrase mismatch; preserved report: " + str(report))
    print("PASS local CPU ASR: model hashes, nonempty transcript and requested phrase checks; report:", report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    setup = commands.add_parser("prepare")
    setup.add_argument("--directory", type=Path, default=ROOT / "target/voice-probe")
    synth = commands.add_parser("synthesize")
    synth.add_argument("--output", type=Path, default=ROOT / "target/voice-probe/synthetic.wav")
    synth.add_argument("--espeak", default="espeak-ng")
    run = commands.add_parser("recognize")
    run.add_argument("--directory", type=Path, default=ROOT / "target/voice-probe")
    run.add_argument("--wav", type=Path, required=True)
    run.add_argument("--threads", type=int, choices=range(1, 9), default=2)
    run.add_argument("--runs", type=int, choices=range(1, 11), default=3)
    run.add_argument("--report", type=Path, default=ROOT / "target/voice-probe/report.json")
    run.add_argument("--include-text", action="store_true", help="Only use for non-private synthetic fixtures")
    run.add_argument("--expect-fragment", action="append", default=[], help="Optional known synthetic phrase assertion")
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.directory)
    elif args.command == "synthesize":
        synthesize(args.output, args.espeak)
    else:
        recognize(args.directory, args.wav, args.threads, args.runs, args.report, args.include_text, args.expect_fragment)


if __name__ == "__main__":
    main()
