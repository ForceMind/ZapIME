# ZapIME / VoiceKey engine adapter

This package integrates VoiceKey at the **recognizer boundary** without replacing
ZapIME's existing Android IME, native X-ASR recognizer, keyboard safety guards,
personal data model, or default offline behavior.

## Source provenance

The entire VoiceKey repository is pinned as a Git submodule at
vendor/voicekey (J3n5en/voicekey, commit 3ba017826eef7fac405198510b03859eb5b6c8c4).
All original authorship and history stay upstream. No third-party acoustic
model, device identity, remote endpoint credentials or proprietary runtime
binary is copied into the ZapIME distribution.

## What runs now

- Real Rust dependency imports of voicekey-core and voicekey-wtlocal.
- VoiceKey-compatible, offline WeChat local engine adapter with prewarm.
- 16 kHz / mono / PCM16 frame ingestion, 60-second hard sample budget,
  finite ingress + partial queues, cancel and final text output.
- No microphone capture, network request, editor manipulation or downloaded
  model occurs automatically. The caller must supply a legitimate model dir.
- Unit tests use synthetic samples with a fake recognizer. They do **not**
  establish smartphone accuracy, speed, model licensing, JNI or production APK.

Build (requires Rust, CMake and system ALSA headers on Linux):

    git submodule update --init vendor/voicekey
    cargo test --manifest-path integrations/voicekey/Cargo.toml

## Not yet connected to the Android keyboard

Native compilation and Rust test success alone do not make an engine available
in ZapIME's Android settings. A separate JNI/Android worker integration,
trusted model installer, UI, and microphone + editor lifecycle QA are required.
Existing Android voice remains the only current product speech implementation.

## Privacy, consent and licensing

The adapter exposes offline recognition only. It does not invoke VoiceKey's
unofficial cloud integrations. Third-party model distribution/use rights are
distinct from the friend's permission to use his own code. Before shipping,
collect the VoiceKey author's written GPL-compatible redistribution grant and
independently clear third-party models/libraries; do not treat this development
submodule as a claim that all upstream code is GPL.

Some VoiceKey original code includes client-protocol research and embedded
service constants. These are kept upstream for provenance and are NOT activated
in the production build. Any future cloud adapter must use authorized APIs
with explicit user opt-in and a reviewable privacy disclosure.
