# Original ONNX Runtime 1.28.2 build headers

These four files are unmodified Microsoft ONNX Runtime source, copyright
Microsoft Corporation, under the accompanying [MIT license](LICENSE.txt).
They are build inputs; this directory does not contain a runtime binary.

The reviewed identity remains the existing
[candidate resource lock](../candidate-model-lock.json):

- Original repository: <https://github.com/microsoft/onnxruntime>.
- Commit: `33ca9628233dc8f002435e868d4c2e9f82766ca1`.
- Original header paths: `include/onnxruntime/core/session/` plus each header name.
- Original license path: `LICENSE`, saved here as `LICENSE.txt`.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| onnxruntime_c_api.h | 394991 | b69a133143c0da61782b2b02cc8a620d21ac139231fd211719776d10385135c1 |
| onnxruntime_ep_c_api.h | 152985 | db86df0f846b8d3bdbf429a5c76c5805293196575ca3c475878612591cd18e51 |
| onnxruntime_error_code.h | 2775 | 5ce3b054e798eced8d14f5b86e98692fd33470463f96194ce0700a2d53dd8721 |
| LICENSE.txt | 1073 | 2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c |

## Recovery on 2026-11-19

The original Microsoft repository and all four locked raw URLs returned HTTP
404 during this investigation. The cause was not established. The old lock's
URLs, version, commit, sizes and hashes have not been changed.

The identical commit was available from
[Intel's ONNX Runtime fork](https://github.com/intel/onnxruntime/commit/33ca9628233dc8f002435e868d4c2e9f82766ca1).
Each file was retrieved over HTTPS from the same original path under
`https://raw.githubusercontent.com/intel/onnxruntime/33ca9628233dc8f002435e868d4c2e9f82766ca1/`
and checked against the **pre-existing** size and SHA-256. All four matched.
The fork was a recovery transport, not a replacement version or a new source
of expected hashes. The original copyright and license bytes are retained.
The [request and hash evidence](../../docs/evidence/ort-source-recovery-20261119/README.md)
also records the original failures.

Normal builds now verify these local originals and atomically publish only
the four files into the build include directory. Missing, changed or symlinked
sources fail, even if a previous build output is valid. There is no header
network fallback. Exact-path Git attributes preserve their original bytes
on Windows checkout. The existing Android runtime, compatible API 24 prefix,
model, source pins and APK notices remain unchanged.
