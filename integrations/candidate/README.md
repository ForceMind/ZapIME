# Offline candidate scoring

This directory runs the real, pinned 8M `chinese-ime-lm` `lm_step.ort` through
the ONNX Runtime already packaged for Android voice. It is an isolated adapter,
not an update to the fixed MSIME submodule. See [SOURCE.md](SOURCE.md) for the
source commit, component licenses and model attribution.

## Boundary and ownership

The Android class is `org.zapime.android.NativeCandidateRanker` and loads
`libzapime_candidate.so`:

| Method | Success | Failure |
| --- | --- | --- |
| `open(String modelDir, String runtimePath)` | positive `long` handle | `0` |
| `score(long, String context, String[] candidates)` | finite `float[]`, same row order | `null` |
| `clear(long)` | `true` | `false` |
| `cancel(long)` | cooperative cancellation request | no-op for unknown handle |
| `close(long)` | `true` | `false`, including repeated close |

The opening worker exclusively owns score, clear and close. Only cancel may be
called from another thread; cancellation remains set until the owner clears
it. A close detaches the session under a short lock before destroying it on the
worker, so a simultaneous UI cancellation does not wait for ORT destruction.
There can be at most two live or loading handles. Callers catch library-load
errors and retain the ordinary engine order when this backend is unavailable.

`modelDir` must be an absolute directory whose release files were verified by
the platform resource installer. The native host additionally rejects linked,
nonregular, empty or oversized members and validates metadata, vocabulary,
graph names/types/shapes and returned tensor dimensions. It reads only
`lm_step.ort`, `model.json` and `vocab.json`. Hash authentication belongs to the
trusted installer and fixed resource lock, not to values supplied by a model.
`runtimePath` is the absolute APK-native path to its existing
`libonnxruntime.so`. No secondary runtime is downloaded or selected at runtime.

## Scoring and bounds

The model returns each candidate's average character log-probability; larger
is better. The final context node predicts the **first** candidate character,
including single-character candidates. Unknown characters use the published
UNK token. This means the score alone does not validate spelling or justify
overriding a personal word, pinned phrase or different input coverage.

Context is clipped to its last 64 Unicode scalar values. Accept 1–32 nonempty
candidates, each at most 64 scalar values, without truncating a candidate into
different text. JNI converts UTF-16 to real UTF-8; it does not pass Java modified
UTF-8 into the vocabulary. It rejects isolated surrogates in the bounded input.
The C++ entry point bounds the supplied context at 4 KiB; JNI reads only its
bounded tail even if an upstream Java caller supplies a longer string.

Each context is encoded once, and candidate prefixes reuse their K/V/hidden
states across pages. A graph call adds at most 128 nodes. Retained candidate
prefixes are capped at 512, with at most another 65 context nodes. A full cache
is reset to the saved context before adding another bounded batch. This bounds
the attention shape and logical cache, **not total process RSS**: weights,
ORT execution buffers, old/new output tensors and the context snapshot also
occupy memory. Two CPU threads, no idle spinning and disabled arena/pattern
retention are conservative starting settings, not measured phone optima.

Bounded batches use the same causal dependencies as upstream. Dynamic uint8
activation quantization can produce small numeric changes when the batch
changes, so the tests separately report this delta. We do not claim bitwise
equivalence to an arbitrarily large single batch. Cancellation is cooperative;
there is no hard CPU deadline or promise that a timed-out call has stopped.

The host must clear on input-field, privacy or session changes, independently
of whether the context happens to be identical. The host also validates its
editor epoch, composition revision, eligibility, candidate IDs and click state
before using the scores. This library never changes a candidate list or commits
text. Clear discards cache references; it is not a secure-memory-erasure API.

## Real-model verification

The test inputs are fixed synthetic text. Nothing is recorded from an editor.
Prepare the locked model and C API headers with the repository resource script,
and prepare the existing voice JSON header using its existing dependency flow.
On Linux with CPython 3.12 and JDK 17:

```sh
python3 -m venv target/candidate-python
target/candidate-python/bin/python -m pip install -r integrations/candidate/tests/requirements.txt
python3 scripts/prepare_candidate_model.py --headers target/candidate-runtime/include
CANDIDATE_TEST_PYTHON=target/candidate-python/bin/python bash integrations/candidate/tests/test_host.sh
```

`JAVA_HOME` must point at that JDK. The shell entry compiles the C++ backend with
warnings as errors, builds the **production JNI source**, and compiles the
production Java native declaration with a test-only JVM harness. It emits
`target/candidate-host/native-report.json` and `jni-report.json`.

The numerical reference is the original upstream Python feed algorithm,
executing the same real ORT release. Coverage includes first-character scores,
shared and incrementally extended prefixes, repeat/backspace/branch changes,
mixed text, supplementary Unicode, context clipping, rejection limits, owner
isolation, sticky cancellation/recovery, bounded batching and cache eviction.
The JVM harness exercises actual native handles, failure sentinels, UTF-16,
capacity limits, close and reopen; it does not replace the Android probe.

The host dependency versions are fixed in `tests/requirements.txt`. Native
reports include the model, runtime and probe hashes, copied-reference hash and
upstream source commit/blob. A passing report proves numerical/lifecycle
behavior on that host runtime. It does not establish Android 1.28.2 behavior,
ARM performance, real user accuracy, total RSS, energy use or system IME UI
acceptance; those require their own measurements and instrumentation.

### Production-dictionary integration probe

After the host steps above, prepare the existing pinned production dictionary
and build the current shared core. These commands assume the repository root:

```sh
python3 scripts/fetch_resources.py
cargo build -p zapime-core --locked
target/candidate-python/bin/python integrations/candidate/tests/default_pool_probe.py \
  --report target/candidate-host/default-pools-exact-report.json
```

The defaults are resolved from the script's repository root. `--core`,
`--resources`, `--model-dir` and `--probe` can point to prepared artifacts in
other directories. `--runtime` accepts a relocated copy of the pinned Python
ORT 1.24.4 runtime. The probe verifies model and dictionary bytes against the
existing repository locks, starts fresh temporary user databases, and uses
only the fixed synthetic inputs `shijie`, `tianqi` and `nihao` plus the three
contexts written in the script. It downloads nothing and deletes its temporary
databases on exit. Its JSON contains those synthetic candidates and scores.

The checks require at least two eligible candidates per input, exclude known
longer completions from the exact-conversion pool, keep noneligible visible
slots unchanged, and apply the real model's permutation through the real core
C ABI before selecting and committing the resulting candidate ID. A valid
ranking can preserve the original order; it need not increment display
generation. A passing report is labeled `mechanical_passed`, with
`language_accuracy_evaluated: false`. These three fixtures do not estimate
language quality. Reported first/warm page and process-load times are host
observations including pipe/process overhead; they do not establish Android
latency. The report records the executed core, native probe, runtime, model,
locks and script hashes. Build the core first: a source hash alone does not
prove an old shared library contains those sources.

### Independent oracle on the device runtime

The Android synthetic test APK also packages a separate `libzapime_candidate_oracle.so`.
It executes the fixed tensors in `tests/oracle-fixture.json` directly through
the product APK's ORT library. It does not call the production ranker, tokenizer,
prefix planner or cache policy. `generate_oracle_fixture.py` creates its two
inputs using the verbatim pinned upstream `context_inputs` and `step_inputs`
functions. The fixture contains input token IDs and shapes, source/model hashes,
and no numerical expected outputs. The first graph encodes seven context nodes;
its raw cache outputs become the second graph's inputs, ending at ten nodes.
Changing this into token-by-token or per-candidate execution would change
dynamic-quantization batching and would not be the same numerical oracle.

The device compares production scores against this independent execution on
the same model, runtime and CPU, still at the fixed absolute tolerance `0.001`.
The JNI result hashes the actual fixture bytes and marks `session_closed` only
after all per-call ORT values, session and environment have left scope. This is
a resource-lifetime contract, not a measured heap-leak result. Historical
cross-runtime failures remain evidence; they are not replaced with device
observations as expected values.

After the earlier host preparation, verify generation and the raw executor:

```sh
python3 integrations/candidate/tests/generate_oracle_fixture.py --check
target/candidate-python/bin/python integrations/candidate/tests/test_oracle.py \
  --report target/candidate-host/oracle-report.json
```

The host test builds only the independent raw executor, compares it with the
original upstream Python feeds and the already-built production probe, checks
the actual fixture hash, two graph calls, cache shapes and scoped cleanup, and
rejects two malformed fixtures. Optional `--runtime /absolute/path/libonnxruntime.so`
arguments add host runtime comparisons; each production/oracle pair retains
`0.001`, while differences between runtime versions are recorded separately.
For the diagnostic official Linux 1.28.2 library, use Microsoft's
[v1.28.2 CPU release archive](https://github.com/microsoft/onnxruntime/releases/download/v1.28.2/onnxruntime-linux-x64-1.28.2.tgz),
whose reviewed download was 9,128,991 bytes with SHA-256
`d7209b8751b27b862b0c76332c2e20e203396edb5dab700ecf4bb485cf147415`.
This diagnostic host library is never included in the product. The report
records each executed library hash and actual API version; it does not infer
which runtime optimization or CPU kernel caused a cross-version difference.
