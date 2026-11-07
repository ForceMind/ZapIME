# Candidate backend provenance

The ZapIME C++ host and JNI boundary are GPL-3.0-only, following the repository's
[LICENSE](../../LICENSE). They do not modify the pinned `vendor/msime` checkout.

## Adapted algorithm

The causal prefix tree, context feed and candidate-to-predicting-node mapping
come from **chinese-ime-lm**, copyright 2026 chinese-ime-lm contributors:

- Repository: <https://github.com/metasequoiaime/chinese-ime-lm>.
- Fixed source commit: `5b82758eaf3419dff9555f41bbda2744a56e45c4`, the target
  recorded by the `rerank-v2` GitHub release.
- [`neural/choice/model.py`](https://github.com/metasequoiaime/chinese-ime-lm/blob/5b82758eaf3419dff9555f41bbda2744a56e45c4/neural/choice/model.py),
  Git blob `933af7444124970a221b3bf1f10a81599c9170d1`: `lm_step`,
  `context_inputs`, `step_inputs` and their documented tensor contract.
- [`neural/choice/export_step.py`](https://github.com/metasequoiaime/chinese-ime-lm/blob/5b82758eaf3419dff9555f41bbda2744a56e45c4/neural/choice/export_step.py),
  Git blob `d485687c5b46b3014c85c6e6249b856213b07429`: tensor names, dtypes,
  shapes, context length, padding and ORT export semantics.

These Python sources fall under the upstream root GPL v3 license, not the
separate Apache license of its `reference/` Rust directory. ZapIME translates
the feed algorithm to C++, adds bounded batching/cache eviction, validates
UTF-8/UTF-16 and shapes, confines session ownership, and uses the ORT C API.
`tests/upstream_feeds.py` preserves the two original Python feed builders with
a provenance header; it is test code, not part of the APK. The native test
report includes the copied file's SHA-256 in addition to the upstream Git blob.

## Independent test oracle

`tests/generate_oracle_fixture.py` executes those unchanged upstream Python feed
builders against the locked vocabulary and fixed synthetic text. It requires
the copied feed source SHA-256
`2268e105d6da10b7e2baab44ae739f9da0db86c031c8886e95b77d57fa35707d`.
The generated `tests/oracle-fixture.json` is 4,760 bytes, SHA-256
`1687b8e30cf49ba532ad70346447bf6c851ab3562cc97bf04da23ac61d1bc076`.
It contains inputs and provenance, not scores copied from an Android run.

`tests/raw_ort_oracle.cpp` and `tests/oracle_jni.cpp` are separate GPL-3.0-only
test code. They do not import or link the product ranker. They execute the two
frozen tensor calls without tokenization or a prefix-cache implementation,
passing the first graph's raw cache outputs to the second graph. Only the
synthetic candidate test APK includes this library and generated input fixture.
The numerical acceptance threshold remains `0.001` between production and the
independent oracle on the **same** device/model/runtime. The host-only verifier
also compares the raw executor with the original upstream Python execution.
This avoids treating a different runtime build's outputs as a universal golden
value for dynamically quantized kernels. A cross-version numeric difference
alone is not evidence of its specific optimization or kernel cause.

## Model and runtime are separate components

The pinned model is `rerank-v2` / `rerank-6x256.ort`:

- 8,220,336 bytes, SHA-256
  `e036febfb8f7bdf941f1637be127ff539ea95b937a4c2a27f4c6ab897138fc6c`.
- <https://github.com/metasequoiaime/chinese-ime-lm/releases/tag/rerank-v2>.
- Model weights are declared Apache-2.0 by their publisher. The original graph
  attribution and `model.json` are retained without modification. The actual
  resource lock, full license, upstream NOTICE and separate source review are
  in [third_party/candidate-model-lock.json](../../third_party/candidate-model-lock.json)
  and its referenced files.
- The upstream LCCC research-use statement differs from downstream MIT-only
  summaries. This recorded issue is not removed by this implementation or by
  having successfully run the model. See the resource lock's
  `SOURCE_REVIEW.txt`; no authorization conclusion is derived from a test.

ONNX Runtime remains the existing APK's `libonnxruntime.so` (1.28.2), distributed
with the original voice runtime's MIT license and ThirdPartyNotices. Its C API
headers are separately pinned to Microsoft commit
`33ca9628233dc8f002435e868d4c2e9f82766ca1` in the candidate resource lock. They are
build inputs only. The backend requests the compatible API 24 prefix and uses
no newer API functions. It does not link or package another ORT binary.

The JSON parser is the existing nlohmann/json 3.12.0 single header, MIT, already
locked by `third_party/android-voice-lock.json`. Native NDK/runtime notices
remain applicable to the final shared library.

## Changes made for ZapIME

The copied upstream algorithm does not enforce the following product bounds;
they are ZapIME adaptations dated 2026-11-07:

- At most 32 candidates, 64 scalar values each; only the last 64 context scalar
  values, plus BOS. Candidates are rejected rather than truncated.
- At most 128 new prefix nodes per graph call and 512 retained candidate prefix
  nodes. Eviction reuses the context cache and reconstructs candidate prefixes.
- No model messages or user text in native logs; only fixed failure codes cross
  the C++ exception boundary, and JNI exposes failure sentinels.
- A bounded two-handle registry including in-progress opens; owner-thread
  score/clear/close, and cooperative cross-thread cancellation.
- Two ORT CPU threads, no idle spinning, disabled CPU memory arena/pattern
  retention, and no execution-provider probing or networking.

The published graph is **LM-only**: it scores candidate strings conditioned on
context, and does not take raw pinyin. Candidate validity, user preference,
generation checks and actual selection remain the existing input engine's
responsibility. Nothing here is a general chat LLM or arbitrary text generator.
