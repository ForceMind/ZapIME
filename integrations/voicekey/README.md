# ZapIME / VoiceKey offline integration (experimental)

Source: J3n5en/voicekey pinned in `vendor/voicekey` at
`3ba017826eef7fac405198510b03859eb5b6c8c4`.

This module directly compiles VoiceKey's upstream **wtlocal-only** model,
feature extraction, INT8 kernels and stream decoder source files using Rust
`#[path]` references. Its original `crates/core` (reverse-engineered cloud
service clients, built-in third-party protocol constants, Opus and CPAL)
is **not a dependency of the Android adapter**. The entire original
VoiceKey code remains available in the Git submodule for provenance and study.

## Integrated code

- Real Rust VoiceKey offline inference with model mmap cache.
- Strictly bounded 16-kHz signed PCM input (8 frames of 100ms, 60-second cap).
- Partial hypotheses are preview-only; final text is returned separately.
- Dedicated model/inference workers and cancellation; no cloud fallback.
- Android JNI library `libzapime_voicekey_adapter.so` with per-session owner,
  cross-thread cancellation and a bounded finalization timeout.
- Android `VoiceCapture` uses the existing microphone + focus/epoch + explicit
  confirmation workflow for the selected offline recognizer.
- Android Settings offers **X-ASR (default)** and **VoiceKey WeChat local
  (experimental)**, with a user-initiated local file importer.

## Build

The Git submodule is required:

```bash
git submodule update --init vendor/voicekey vendor/msime
cargo test --manifest-path integrations/voicekey/Cargo.toml
# Android SDK/NDK matching ZapIME AGENTS.md:
bash scripts/build_android.sh arm64-v8a
bash scripts/build_android.sh x86_64
```

A successful Rust build **alone does not validate mobile inference**.
Android JNI, APK packaging, native emulator tests and physical ARM quality
testing must each be recorded in docs/STATUS.md.

## Import and privacy

The VoiceKey mode starts disabled until selected. The model is **not** included
in any ZapIME binary or cloud storage. The user must explicitly select the
original Tencent WeChat offline model package, whose fixed size and MD5 are
from VoiceKey's pinned `crates/wtlocal/src/pack.rs`; import performs bounded
nested-ZIP extraction and runtime format verification, then saves SHA-256
digests of extracted files. Every start rechecks the installed model files.
The package's upstream MD5 is a **legacy identity check, not a substitute for
an independently pinned SHA-256**; that pin and the model's redistribution/
use rights must be established before public release. Do not distribute a
model ZIP derived from VoiceKey without separate rights review.

No network request, credential, background recording, clipboard access or
automatic server sync is introduced by the adapter. ZapIME's existing
`VoiceCommitTransaction` still controls actual text insertion. The user
can switch back to existing X-ASR at any time.

## Known gaps

- Until Android CI is verified, the two-engine APK remains a **candidate**.
- Physical Samsung S25 / natural-speech accuracy, RAM, CPU, battery and
  start-to-final latency have not been measured.
- VoiceKey aarch64 upstream presently assumes CPU dot-product support.
  The experimental ARM64 binary is built for compatible modern CPUs;
  upstream runtime dispatch or scalar fallback is required before broad ARM
  distribution. The optional Android engine must not be marketed as all-ARM.
- The upstream process-wide model cache does not yet have idle release.
- Model importer needs an independent SHA-256 pin and storage interruption
  recovery testing before a stable build. The existing X-ASR path is unaffected.
- Windows, macOS, iOS second-engine activation and authorized cloud services
  are not yet included.

Reference roadmap: `docs/VOICEKEY_INTEGRATION.md` and Issue #72.
