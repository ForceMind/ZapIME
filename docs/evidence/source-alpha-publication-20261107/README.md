# Source Alpha publication and independent download verification

[v0.1.0-alpha.1](https://github.com/ForceMind/ZapIME/releases/tag/v0.1.0-alpha.1) was publicly published at 2026-11-08T03:17:38Z (2026-11-07 in the working client's timezone). Release ID438003228 is `draft=false`, `prerelease=true` and targets exact source026bfb8bf467c0d45193a50016e298c3dbb0ac01/tree9aa8d7e0850ba110357dc65886f882f4370529ab. PR #82 merged the development stack into main; integrated stacked PRs #73/#74/#78/#79/#80/#81 were then closed with explicit ancestry records, while #71/#75 were recognized as merged. Unfinished #12/#25/#40 remain open.

## Actual publication attempts

Main [workflow42441322541](https://github.com/ForceMind/ZapIME/actions/runs/42441322541) passed preparation: 17 release-state/real-Git tests, pinned source, workspace/fmt/Clippy, exact archive and artifact upload. First publisher job128611997032 created the correct tag and matching empty draft, then failed when its immediate post-create lookup returned None and `release["draft"]` raised TypeError. The exact visibility cause is unproven; the original safe log is preserved in `publish-first-failed.log.gz`.

Only that failed publishing job was retried, with the same source/tag. Retry job128612858151 recognized the existing draft, uploaded and verified the three source assets, published, and read back the final state successfully. `publish-retry-success.log.gz` preserves the actual success. The first failure is not relabeled successful and no tag was moved. Improving post-create lookup/error handling remains an automation follow-up; publication success does not prove that race was fixed in code.

## Public asset verification

All three assets were downloaded through their public release URLs. Their own SHA256SUMS, GitHub API digests and local digests matched. `validate_bundle` independently recreated the archive and metadata from the exact source commit; all three files matched byte for byte. The archive has 2236 regular files, no submodule payloads, APK or model weight files. The archive itself is not copied into this repository.

| Asset | Bytes | SHA256 |
| --- | ---: | --- |
| ZapIME-0.1.0-alpha.1-source.tar.gz | 8085024 | d78a5f0b5845e1aa8b67dd7671cee4c0cde1a0f40b018e70e0044f990c7f5cc9 |
| metadata.json | 1263 | 1a73bfa8d1041dc96270f061bee73c547c40acf946005e516b28a150b167c176 |
| SHA256SUMS | 181 | c5f1fcca26948d5b42bebce00bcd4f1e89a5d7ecc7a9b2648902fd3d32b03da7 |

`published-release.json` records public metadata; `published-source-metadata.json` is the exact downloaded metadata; `download-verification.json` records the local reconstruction. `manifest.json` hashes these retained records. `main-workflow-status.json` is a post-publication snapshot: source, shared/Apple, Windows, sync and speech CPU succeeded; full Android was still in progress. Query GitHub for later results. This documentation checkpoint does not rebuild the product or change the released tag.

Known API35 composition and API28 SAF recovery failures, ARM/S25, natural speech, signing, licensing and remaining platform acceptance retain their original scopes in [STATUS](../../STATUS.md) and the release notes.
