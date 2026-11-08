# PR 96: second fixed source, Android run 42466801834

[Android run 42466801834](https://github.com/ForceMind/ZapIME/actions/runs/42466801834)
finished **failure**, attempt **1**, for head
`94b66f3e3ac9c0af92952427d0f3f1bfc27bb6a8`. This is a new source run, not a retry
of the earlier `42f456` source. The build log records actual PR merge checkout
`a0add63cc59cd6b3f7956bee4f04c7c805c72c1f`; both GitHub Git objects have tree
`78d00ce936aafd38b7073e1dc688f502128a0e46`. The merge parents are this head and
main `b9cc2f2e17016b49c5636f91a713ea13f8146691`.

## Actual first cause and boundaries

All six Android jobs failed. Build job `128688078544` first failed at
`Offline candidate context, bounded worker and pinned model resources`.
The actual `prepare_candidate_model.py` download emitted these two bounded
retry events, followed by its third attempt's final HTTP error:

| UTC time | Actual download event |
| --- | --- |
| `09:22:37.7779569` | HTTP 504, attempt 1 → 2, wait 1 second |
| `09:22:38.8370363` | HTTP 504, attempt 2 → 3, wait 2 seconds |
| `09:22:40.9655039` | Final `HTTP Error 504: Gateway Time-out`; process exited 1 |

The traceback passes through `prepare_candidate_model.py:157` and the new
`run_with_retry(..., socket_timeout=60, total_timeout=180)` helper. The earlier
retry unit tests intentionally print synthetic retry messages; they are not
counted as production download attempts. The real events above occur after
`PASS reviewed candidate catalog and source notices`.

Both product APK builds, test harness build, and API 28 system keyboard smoke
were **SKIPPED / NOT_RUN**. The original artifact list is empty. All five
downstream job logs report missing `ZapIME-Android-development`; their actual
API 35/36 or VoiceKey API 28/35/36 acceptance steps were **SKIPPED / NOT_RUN**.
Missing upstream artifacts do not establish a downstream product defect.
There is no new JNI, Editable, SAF, touch, system IME, actual service-exit,
ARM phone, or natural-speech acceptance result for this source. Earlier source
successes and failures are not substituted into these missing results.

## Related metadata observations

[Source Alpha run 42466801643](https://github.com/ForceMind/ZapIME/actions/runs/42466801643)
is **completed / success**, attempt 1, for this same head. This is workflow
metadata, not a claim that a new public release or attachment was published or
download-verified. Existing public Alpha tags and assets were not changed by
this read-only capture.

[Sync service run 42466801630](https://github.com/ForceMind/ZapIME/actions/runs/42466801630)
was **in_progress / PENDING**, attempt 1, in the retained snapshot. Its final
outcome is not inferred or awaited here; no database transaction pass is claimed.
The overall workflow list is an observation only. Other workflows' detailed
logs/artifacts are outside this evidence slice.

## Original evidence and recheck

Six complete decoded original job logs are retained as gzip with SHA-256 for
both compressed and decoded bytes. They were fetched specifically for this
run's six job IDs. Run/job/artifact metadata and both Git commit identities are
retained as JSON. No previous-source log fills any file in this directory.

Run `python3 docs/evidence/pr96-android-build-failure-42466801834/recheck.py`.
`validation.json` checks exact source identity, final run and six failed jobs,
actual download retry events, final HTTP 504, required native skips, downstream
artifact errors, and all original log hashes. `retained-file-manifest.json`
identifies every retained file except itself.

No job was rerun during this capture. No APK, model, PCM, recording, database,
recovery code, credential, or temporary signed URL is retained. Common
credential and temporary URL pattern checks found no matches; these checks are
not a universal proof about arbitrary text. The native regression remains
blocked by the observed build-time external download failure.
