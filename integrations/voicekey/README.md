# ZapIME / VoiceKey offline adapter

Experimental product integration. Updated 2026-11-06.

## Source and architecture

The complete upstream repository is pinned at `vendor/voicekey`:
`J3n5en/voicekey@3ba017826eef7fac405198510b03859eb5b6c8c4`.

The adapter references the original wtlocal `fbank.rs` and `xnet.rs` through fixed Rust paths. `src/model.rs`, `src/stream.rs` and `src/kernels.rs` are attributed local adaptations: model/stream add cooperative cancellation and exact-zero input handling; the AArch64 numerical implementation replaces unstable `vdotq_s32` with stable NEON multiply/pairwise-add operations. The pristine source and authorship remain available in the unchanged submodule.

The product adapter does not depend on the upstream cloud protocol core, embedded service credentials, CPAL or Opus. Importing the full repository is not equivalent to enabling its online services or adopting its Android accessibility/clipboard injection path.

The Android side has a separate `NativeVoiceKey` JNI library, local engine preference, an explicit model importer and routing through ZapIME's existing AudioRecord and confirmation transaction. X-ASR remains the default recognizer. Partial text is preview-only; the existing editor/epoch/selection/privacy transaction controls insertion.

## Current hardening and acceptance boundaries

The historical `e24f1bd21ecd4fca99d909a972116e5a36e5f6c2` APKs have now been downloaded and independently checked for hashes, signatures, ABI/JNI exports, dynamic dependencies and 16KiB alignment. That audit also found missing adapter-specific dependency license texts in the old core-only notice collection. See the retained [baseline evidence](../../docs/evidence/voicekey-runtime-20261106/baseline-README.md). It does not establish VoiceKey model-driven recognition, AudioRecord or Samsung S25 acceptance.

The new source fixes the selected-engine readiness route, freezes the choice for each recording and retains the existing confirmation transaction. Host regressions cover X-ASR-only, VoiceKey-only, both, neither and corrupted selected-model routing. Absence of a fresh partial no longer clears an existing preview. Audio queue overflow is an explicit failure; only successfully accepted PCM contributes nonzero evidence.

The runtime now uses one physical blocking decoder permit, a bounded audio queue and cooperative checks in the actual model/stream path, rather than aborting a Tokio wait. A timed-out or dropped caller cannot free that permit while its worker is still running. `close` reports whether the real worker completed; it does not claim to forcibly stop a calculation. Cache identity detects app-private atomic model replacement, validation is uncached, and `releaseCache` handles idle/memory-pressure release. Host tests and AArch64 `cargo check` are distinct from running native ARM code, JNI or model inference. New model/JNI/native ARM workflows remain pending until their own results are recorded.

## Reproduction

Follow the toolchain and protected-test rules in the root AGENTS.md:

```bash
git submodule update --init vendor/msime vendor/voicekey
python3 scripts/verify_upstream.py
cargo test --workspace --locked
cargo test --locked --manifest-path integrations/voicekey/Cargo.toml
bash scripts/test_android_voice_engine.sh
bash scripts/test_android_voicekey_models.sh
PYTHONPATH=scripts python3 -m unittest discover -s scripts -p 'test_android_*.py' -v
bash scripts/build_android.sh arm64-v8a
bash scripts/build_android.sh x86_64
```

The independent `Cargo.lock` pins the hardening slice; builds/tests and notice collection use `--locked`. Format all adapter-owned sources without rewriting the fixed vendor files. Formatting and test failures are hard gates. Android packaging collects this workspace's own graph under `assets/voicekey-rust-licenses`, verifies license text digests and lock provenance, and requires eight JNI exports including `releaseCache`, 16KiB alignment and the allowed platform dynamic libraries. The old APK does not meet these new gates.

## Model and privacy boundaries

No VoiceKey third-party acoustic model is bundled or uploaded to personal storage. The fixed official-origin package was independently obtained in metadata audit run [42209579465](https://github.com/ForceMind/ZapIME/actions/runs/42209579465); its package/member sizes and SHA256 values are pinned in [voicekey-model-lock.json](../../third_party/voicekey-model-lock.json) and compiled into the Android catalog. This audit proves observed package integrity only, not inference or redistribution permission. Import authenticates those fixed values before parsing, rejects unknown/duplicate/traversing/symlink/nested/oversized members, validates in staging and preserves the old valid model on failure. The 17 synthetic storage cases are not actual ENOSPC, process-kill, SAF or phone tests.

The project owner has confirmed the friend's permission to integrate the author's source. [LICENSE](LICENSE) records that confirmation and its limits; [GPL-3.0.txt](GPL-3.0.txt) applies to ZapIME-authored adapter code and modifications without relicensing upstream VoiceKey. A standard upstream public distribution license has not been recorded. Source integration permission does not establish rights to third-party models, proprietary runtimes or unofficial service APIs. No automatic network fallback, recording upload or editing-context upload is introduced.

## Execution documents

- [Product goals and stages](../../docs/DEVELOPMENT_PLAN.md)
- [VoiceKey integration contract](../../docs/VOICEKEY_INTEGRATION.md)
- [Verified evidence and acceptance checklist](../../docs/VOICEKEY_ACCEPTANCE.md)
- [Current status](../../docs/STATUS.md)
- [Work-session handoff](../../docs/WORK_HANDOFF_PROMPT.md)

The next deliverable is an independently verified, explicitly experimental Android test package and a focused S25 acceptance checklist, not an untested stable release.
