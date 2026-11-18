# ZapIME: frozen offline voice polishing corpus v1

This is a strictly offline, synthetic-only evaluation baseline for Issue #113. It does not contain user recordings or chat histories, and it does not run a language model.

The UTF-8 corpus.v1.jsonl and the adjacent pinned SHA-256 form an immutable v1 input. Each synthetic case has an id, category, raw speech-like text, one example reference rewrite, and required literal facts. The reference is only one possible acceptable sentence, NOT a complete set of semantically correct rewrites.

From the repo root, with a local JDK installed, run:

    python3 scripts/evaluate_voice_polish.py
    python3 -m unittest discover -s scripts -p 'test_voice_polish_evaluation.py' -v

The default evaluation uses synthetic reference rewrites, not model outputs. To test a future OFFLINE model, supply a local JSONL containing exactly one object with id and candidate text for each of the 24 frozen IDs:

    python3 scripts/evaluate_voice_polish.py --candidate-jsonl /local/model-output.jsonl --report /local/metadata-only.json

The evaluator compiles the ACTUAL product VoicePolishSafety.java using javac and sends the candidates to that local Java process via standard input. It never opens a network connection and never writes or prints the actual raw, model, or reference text in its report. Output contains aggregate categories, fixed synthetic IDs and refusal reasons only. Unknown, missing or duplicate IDs, invalid JSON, unencodable Unicode, oversized inputs and changed corpus SHA are rejected.

Honest current baseline: All 24 synthetic references preserve their explicitly tagged literal facts. The conservative product guard admits only 21/24 references; three harmless-looking references fail closed due to duplicated numbers/negations or a negation-shaped word. This is 3 false rejections in a synthetic reference test, not model accuracy. Earlier product guard code could miss USB-C → USB-D and relied on the separate corpus-specific required-term check to catch it. PR #119 adds exact-case immutable acronym/mixed-case technical token comparison (including a token adjacent to Chinese characters), so **both** checks now reject that example. The frozen reference baseline still admits 21/24; the original three fail-closed reference rejections remain. This is a necessary technical-identity check, not named-entity recognition; ordinary names or semantic changes may still be missed. Passing both remains NOT proof of full semantic fidelity.

Human review, exact model license/revision/quantization and device-specific latency/RAM/cancellation/semantic-error measurement are required before enabling any real semantic voice rewrite. #114 remains the only user-facing deterministic voice cleanup. No cloud fallback or automatic insertion is authorized by these tests.
