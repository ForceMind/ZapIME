# Main Android regression: run 42462698530

Final GitHub observation: **completed / success**, attempt **1**, all **6 jobs**
successful, updated `2026-11-08T08:44:06Z`.

- Run: <https://github.com/ForceMind/ZapIME/actions/runs/42462698530>
- Tested source: `5a0dc56290be9c2458ee6e7e7ddafc51930dd593`.
- Tested tree: `35654b149be894432d1faaa6469619df8261e3f4`.
- The original build job log independently records that exact checkout SHA.
- This is one successful full regression of this source. It does not resolve the
  historical intermittent configuration restart failure, validate the later
  verified-restart product changes, or replace any earlier failed/offline record.

## Original reports and strict recheck

`strict-validation.json` was produced by `python3
docs/evidence/main-android-42462698530/recheck.py`. The recheck imports the
validators from the **tested commit**, using a temporary `git archive`, rather
than a newer checkout's scripts. It requires exact case identities and successful
case assertions, actual Editable evidence where applicable, system package
verification, selected system IME state, target service absence after teardown,
offline UID rule removal, and target crash/ANR checks.

| API | Native/X-ASR acceptance | VoiceKey-only acceptance |
| --- | --- | --- |
| 28 | 2 clean native input rounds; 65 JNI/local report cases; voice UI; actual service exit | 37 model/commit cases; 5 boundary cases; watchdog; actual service exit |
| 35 | 2 clean native input rounds; 65 JNI/local report cases; voice UI; actual service exit | 37 model/commit cases; 5 boundary cases; watchdog; actual service exit |
| 36 | 2 clean native input rounds; 65 JNI/local report cases; voice UI; actual service exit | 37 model/commit cases; 5 boundary cases; watchdog; actual service exit |

The selected system keyboard was verified using `pm list packages -s`: AOSP
LatinIME on API 28 and the system Google voice IME on APIs 35/36. The latter is a
verified system IME, not an AOSP LatinIME claim. Each normal input round and each
voice run preserves its original selected-IME and service-exit report. VoiceKey
also verifies removal of its shared synthetic document and restoration of the
emulator keyboard policy.

All input/PCM/model test material is synthetic or from the pinned public model.
Natural speech, ARM device behavior, physical phone operation, and release
signing are **NOT RUN** by these reports. Method-level confirmation and emulator
audio do not establish physical touch-to-commit voice acceptance on a phone.

## Download and retention boundaries

`workflow-run.json`, `workflow-jobs.json`, `workflow-artifacts.json`, and
`git-source.json` preserve the final source/run metadata. `download-verification.json`
records six downloaded original artifact archive sizes and SHA-256 digests,
matched to GitHub metadata. `apk-download-verification.json` independently hashes
the ARM and x86 APK members of the original development ZIP, matching the build
log; APK contents are not committed here. The separate 129 MB smoke harness
artifact is listed in GitHub metadata but was not downloaded in this recheck.

The development ZIP, native API 35/36 ZIPs, APKs, model archives, and PCM remain
outside Git. Their safe JSON/text/log records are copied byte-for-byte under
`records/`. Each `member-manifest.json` hashes **all original members** and marks
which members were retained; omitted members are not silently discarded from
the manifest. The three small VoiceKey original ZIPs contain reports, logs, and
synthetic test screenshots, and are preserved as downloaded. `build-job.log.gz`
preserves the decoded original build log; its uncompressed UTF-8 hash is recorded.
No temporary signed download URL, model weights, recording, database, recovery
code, or credential is added here.

`previous-source-observation.json` separately observes successful run
`42460859552`, attempt 1, of source
`331def64ea0e3d5492712ff2066ec559f036015f`. Its results are not substituted for
this run. Neither run was retried by this evidence capture. Existing source Alpha
tags, releases, and the older `026bfb8` evidence remain unchanged.

`retained-file-manifest.json` identifies every retained file other than itself.
