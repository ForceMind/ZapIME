# PR 96: first-attempt Android build failure

This preserves the first run of PR 96, without rerunning a job or changing a
failed artifact. GitHub head `42f4562193f8d13a684aa7b656ca2adaa27de216` was checked
out as PR merge `49186be44237965669f314ebbe768f3684297a45`. The GitHub Git objects
independently confirm that both commits have tree
`02b21f6d4e1897bbde801b5096c0bd59fa5e258e`; the merge parents are the head and
main `5a0dc56290be9c2458ee6e7e7ddafc51930dd593`.

## Observed outcomes

Every run below is attempt **1**. All listed workflows are final, and no job was
retried by this capture. Original workflow/job metadata is retained alongside
ten complete decoded original job logs, compressed without changing their
decoded UTF-8 bytes.

| Run | Final outcome and first cause | Execution boundary |
| --- | --- | --- |
| [42464855387](https://github.com/ForceMind/ZapIME/actions/runs/42464855387) Android full | All 6 jobs failed. Build failed in `prepare_candidate_model.py:150` with HTTP 504. Five downstream jobs could not find `ZapIME-Android-development`. | Both product APKs, test harness builds, API 28 smoke and downstream API 35/36/VoiceKey native acceptance **NOT_RUN**. Artifact list is empty. |
| [42464855342](https://github.com/ForceMind/ZapIME/actions/runs/42464855342) focused VoiceKey | Build failed in `fetch_resources.py:28` with HTTP 504. | All 3 downstream matrix jobs **SKIPPED**: VoiceKey, local sync preparation, and Android HTTPS/PostgreSQL acceptance **NOT_RUN**. |
| [42464855401](https://github.com/ForceMind/ZapIME/actions/runs/42464855401) Source Alpha | Preparation failed with `ReleaseError: Uninspected tracked archive is not a source-only input`. This is a distinct failure, not HTTP 504. | Source preparation/retry tests and shared Rust verification steps succeeded; publication job **SKIPPED**. No release/tag/attachment mutation is established. |
| [42464855424](https://github.com/ForceMind/ZapIME/actions/runs/42464855424) development checks | Three shared core platform jobs succeeded. Apple host build failed in `fetch_resources.py` with HTTP 504. | Apple native build/acceptance did not complete. |
| [42464855345](https://github.com/ForceMind/ZapIME/actions/runs/42464855345) Windows TSF | TSF build/native-boundary step failed in `fetch_resources.py` with HTTP 504. | TSF acceptance did not complete. |
| [42464855374](https://github.com/ForceMind/ZapIME/actions/runs/42464855374) offline speech CPU | Workflow/job **success**. | Metadata observed; CPU artifacts were not independently downloaded or revalidated in this bundle. |
| [42464855498](https://github.com/ForceMind/ZapIME/actions/runs/42464855498) sync service | Workflow/PostgreSQL job **success**, updated `2026-11-08T09:02:10Z`. | Metadata observed; transaction/drill logs were not independently revalidated in this bundle. No skipped database test is counted as a pass here. |

The full Android build's earlier host/JVM steps are preserved in metadata and
logs. Their successes cannot establish JNI, real Editable, system IME, actual
service exit, SAF, physical touch, or emulator acceptance of this product source.
Missing upstream artifacts likewise do not establish a downstream product
failure. The successful main `5a0dc562` regression belongs to a different source
and does not fill these **NOT_RUN** results.

The Source Alpha blocker is independently attributable from this exact tree:
`docs/evidence/tools-touch-stability-20261107/safe-first-failure-originals.tar.gz`
is the tracked tar archive, while the publisher's source-only inspection rejects
uninspected tracked tar archives. This bundle preserves the failure rather than
changing the publisher policy or the original archive. Existing Alpha tags,
source, public attachments, and older failure records remain unchanged.

The companion candidate/configuration acceptance run `42464855505` is recorded
in the overall workflow list and archived separately; this bundle adds no
candidate/configuration emulator success claim.

## Reproducible evidence checks

Run `python3 docs/evidence/pr96-android-build-failure-42464855387/recheck.py`.
`validation.json` checks exact head/merge/tree, attempt 1, six Android failures,
the empty artifact list, specific failure tracebacks, required skipped native
steps, focused downstream skips, the distinct source failure and skipped
publication, and all ten compressed/uncompressed log hashes.

`log-manifest.json` records exact decoded bytes and SHA-256 for each original
GitHub job log, plus the retained gzip SHA-256. `retained-file-manifest.json`
identifies all files other than itself. Metadata was read through GitHub's API;
job logs were supplied by its decoded-log endpoint. No APK, model, PCM, database,
recording, recovery code, credential, or temporary signed URL is retained.
Common credential/temporary URL pattern checks found no matches; these checks
are not a universal proof about arbitrary text.
