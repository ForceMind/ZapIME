# ZapIME / VoiceKey offline adapter

Experimental product integration. Updated 2026-11-06.

## Source and architecture

The complete upstream repository is pinned at `vendor/voicekey`:
`J3n5en/voicekey@3ba017826eef7fac405198510b03859eb5b6c8c4`.

The adapter uses four original wtlocal source modules through Rust path references: feature extraction, model loading, stream decoding and XNET parsing. `src/kernels.rs` is an attributed local adaptation of the upstream numerical kernels. Its AArch64 implementation replaces the unstable `vdotq_s32` intrinsic with stable NEON multiply/pairwise-add operations. The pristine source and authorship remain available in the submodule.

The product adapter does not depend on the upstream cloud protocol core, embedded service credentials, CPAL or Opus. Importing the full repository is not equivalent to enabling its online services or adopting its Android accessibility/clipboard injection path.

The Android side has a separate `NativeVoiceKey` JNI library, local engine preference, an explicit model importer and routing through ZapIME's existing AudioRecord and confirmation transaction. X-ASR remains the default recognizer. Partial text is preview-only; the existing editor/epoch/selection/privacy transaction controls insertion.

## Verified baseline versus remaining acceptance

Code commit `e24f1bd21ecd4fca99d909a972116e5a36e5f6c2` has successful isolated adapter CI and Android dual-ABI builds. Android run `42199407235` also passed the existing API28/35/36 regression jobs. These results do not establish VoiceKey-specific model-driven recognition or natural-speech performance on Samsung S25.

A statically confirmed blocker remains at that baseline: `ZapInputMethodService.startVoice()` still tests X-ASR model readiness irrespective of the selected engine. Fix and test VoiceKey-only installation before treating the UI as a complete second-engine user flow.

Cancellation of an async task must not be assumed to terminate its blocking decoder. Audit task ownership, timeout/close, blank PCM/partial results, backpressure, model cache invalidation/release and JNI exception boundaries. The stable ARM patch requires independent numerical and performance measurements; it is not a claim of equal speed to upstream.

## Reproduction

Follow the toolchain and protected-test rules in the root AGENTS.md:

```bash
git submodule update --init vendor/msime vendor/voicekey
python3 scripts/verify_upstream.py
cargo test --workspace --locked
cargo test --manifest-path integrations/voicekey/Cargo.toml
bash scripts/build_android.sh arm64-v8a
bash scripts/build_android.sh x86_64
```

Commit an independently reviewed adapter lockfile and enforce `--locked` for that workspace as part of the next hardening slice. Do not silently upgrade pinned upstream source or reformat pristine vendor files. Format/test failures must not be hidden by continue-on-error.

## Model and privacy boundaries

No VoiceKey third-party acoustic model is bundled by this adapter or uploaded to personal storage. The candidate importer checks the upstream package size/MD5 and writes per-file SHA256 values after installation. Those generated checksums are not an independent trust anchor. Before stable use, pin a verifiable package and per-member SHA256/size allowlist from a documented source, reject malformed or untrusted packages before parsing, and test interrupted publication and old-model recovery.

The project owner has confirmed the friend's permission to integrate the author's source. Preserve attribution and record the terms for distribution; do not treat this as permission for third-party models, proprietary runtimes or unofficial service APIs. No automatic network fallback, recording upload or editing-context upload is introduced by the intended product flow.

## Execution documents

- [Product goals and stages](../../docs/DEVELOPMENT_PLAN.md)
- [VoiceKey integration contract](../../docs/VOICEKEY_INTEGRATION.md)
- [Verified evidence and acceptance checklist](../../docs/VOICEKEY_ACCEPTANCE.md)
- [Current status](../../docs/STATUS.md)
- [Work-session handoff](../../docs/WORK_HANDOFF_PROMPT.md)

The next deliverable is an independently verified, explicitly experimental Android test package and a focused S25 acceptance checklist, not an untested stable release.
