# ZapIME · 闪电输入法

[中文](README.md) · [Documentation](docs/README.md) · [Roadmap](docs/ROADMAP.md) · [Contributing](CONTRIBUTING.md)

A local-first native Chinese input method with portable dictionaries, phrases, saved text and independent backups.

**Status: Android development Alpha; other platforms are development hosts.** [v0.1.0-alpha.1](https://github.com/ForceMind/ZapIME/releases/tag/v0.1.0-alpha.1) is a source prerelease, with no production-signed installation package. Read the [release notes](docs/releases/v0.1.0-alpha.1.md) for acceptance scope, licensing tasks and known issues.

## Implemented capabilities

- Native Android full and nine-key pinyin, reading selection, personal candidate rules, light/dark themes and system-aware haptics.
- Default-on offline candidate enhancement: pinned MSIME generates candidates; an 8M character language model asynchronously reorders eligible existing candidates. Empty trusted context, failure or timeout preserves the base order.
- Two offline speech paths: X-ASR by default and a selectable VoiceKey adapter. Speech models are separately imported against a fixed catalog; insertion requires user confirmation after preview.
- Local text search, phrases, explicitly saved clipboard entries, permanent favorites and encrypted backups. Temporary clipboard entries remain distinct from favorites.
- Explicit manual encrypted sync on Android, a self-hosted Go/PostgreSQL service and optional R2/S3 ciphertext storage. Pairing, four-platform interoperability and complete disaster recovery remain unfinished.

**Merged:** [PR #104](https://github.com/ForceMind/ZapIME/pull/104) delivers the Android local sync inventory preview on `main`: at most 40 read-only metadata entries per page, cleared when leaving, with no contents/titles, HTTP requests or upload operations. All eight workflows eventually passed on the fixed source, including Android API28/35/36 input and sync gates and Apple/Windows development checks. Earlier HTTP504, API28 fixture and first-run API36 lost-key evidence is retained. [PR #107](https://github.com/ForceMind/ZapIME/pull/107) also patches the independent sync service to Go 1.27.4. See [STATUS](docs/STATUS.md).

An upload count of zero, or a record with no sync history, does not establish complete cloud backup. Locally recorded server revisions are not a live server check. Explicit initial upload of restored or imported records remains unimplemented under [Issue #77](https://github.com/ForceMind/ZapIME/issues/77); see the [read-only preview contract](docs/SYNC_INVENTORY.md).

## Platform status

| Platform | Evidence available | Outstanding acceptance |
| --- | --- | --- |
| Android | Dual-ABI development builds; API 28/35/36 x86_64 emulator input, candidate, speech and lifecycle gates | ARM/S25 runtime, natural speech, sustained quality/energy and durable upgrade signing |
| macOS | Native host build and bridge | System TIS/IMK activation and insertion in another app |
| iOS | App/keyboard-extension builds and bridge tests | Keyboard enablement, physical device and signed distribution |
| Windows | TSF DLL/COM development baseline | Independent-process profile gate, activation and actual app insertion |

[STATUS](docs/STATUS.md) records exact commits, retained failures and raw evidence. The original 34 and additional 64 frozen candidate cases are not population accuracy estimates. The configuration-restart composition fix passed same-source input gates on three APIs and a dedicated API35 experiment; historical failures remain preserved and phone acceptance is separate.

## Get the source

```sh
git clone --recurse-submodules https://github.com/ForceMind/ZapIME.git
cd ZapIME
python3 scripts/verify_upstream.py
cargo test --workspace --locked
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
```

`rust-toolchain.toml` pins Rust 1.97.1. Both submodules must remain at their locked revisions. GitHub source archives omit submodule contents. Android additionally requires JDK 17, API 36, build-tools 35.0.0 and NDK 28.2.13676358:

```sh
export ANDROID_SDK_ROOT=/absolute/path/to/android-sdk
rustup target add aarch64-linux-android x86_64-linux-android
bash scripts/build_android.sh arm64-v8a
```

See [BUILD](docs/BUILD.md) for the separate VoiceKey workspace and platform requirements. Development APK certificates can differ between builds; an upgrade without data loss is not guaranteed. Keep the existing app and data on a signature conflict and consult the [pinned trial guide](docs/CANDIDATE_RANKING_TRYOUT.md). This source release attaches no APK or speech weights.

## Privacy and security

Normal typing, candidate ranking and installed offline speech need no network. Ranking uses bounded context from the current editor; sensitive fields are excluded. Speech is not automatically saved or uploaded.

The main app only connects through explicit identity checks, key-identity registration or a separately confirmed sync round. There is no default upload or background polling. Local SQLite data is not encrypted as a whole. Backup files, recovery material and service credentials serve different purposes; see [SECURITY](SECURITY.md) for export and deletion limits.

## Contribute

Read [CONTRIBUTING](CONTRIBUTING.md), the [Code of Conduct](CODE_OF_CONDUCT.md) and [AGENTS](AGENTS.md). Use synthetic reproductions and distinguish compilation, emulator, device and signing evidence. Never attach real input, recordings, clipboard text, keys or recovery codes to a public issue. Follow [SECURITY](SECURITY.md) for security reports.

[WORK_HANDOFF_PROMPT](docs/WORK_HANDOFF_PROMPT.md) is the current development entry point. Recheck live branches, pull requests and CI before continuing the existing implementation.

## License and provenance

ZapIME-authored code is [GPL-3.0-only](LICENSE). MSIME, VoiceKey, model resources and runtimes retain their own attribution, licenses and notices; this does not relicense all third-party content as GPL.

The VoiceKey author's permission to incorporate source is recorded, while standardized public distribution terms still need documentation. The candidate publisher's Apache-2.0 weight declaration and the LCCC training-source statement remain unresolved. General binary and commercial redistribution are not declared fully cleared. See the [license audit](docs/LICENSE_AUDIT.md), [VoiceKey notice](integrations/voicekey/LICENSE), [third-party NOTICE](third_party/NOTICE.txt) and [upstream lock](third_party/upstream-lock.json).
