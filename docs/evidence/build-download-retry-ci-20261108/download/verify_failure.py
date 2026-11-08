"""Strictly separate real fixed-source failures from synthetic unit-test output."""
from pathlib import Path
import gzip
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parent
HEAD = "fb62ad923f376edb7f1c3ab274fc8095718b7ea7"
TREE = "f99bbff5cc9ca044e5e9fe190f0f58c243434930"
CHECKOUT = "0647d0bc6734e915821ccc5ddc9bf8f87bf3c299"
meta = json.loads((ROOT / "source-metadata.json").read_text())
assert meta["head"] == HEAD and meta["tree"] == TREE
assert meta["checkout"]["sha"] == CHECKOUT and meta["checkout"]["tree"] == TREE
assert all(row["head_sha"] == HEAD and row["head_tree"] == TREE
           and row["run_attempt"] == 1 and row["conclusion"] == "failure"
           for row in meta["runs"])
assert all(value == [] for value in meta["artifacts"].values())

cases = [
    (128687392403, 42466578708, "candidate-host", "scripts/prepare_candidate_model.py",
     "##[group]Run python3 scripts/prepare_candidate_model.py --headers", True,
     ["native model scores", "JNI host", "paired quality inference", "dictionary fetch"]),
    (128687392256, 42466578708, "candidate-build", "scripts/prepare_candidate_model.py",
     "PASS reviewed candidate catalog and source notices", False,
     ["APK build", "candidate matrix API28/35/36"]),
    (128687392745, 42466578712, "android-build", "scripts/prepare_candidate_model.py",
     "PASS reviewed candidate catalog and source notices", True,
     ["APK build", "offline import ZIP", "API28 system IME", "modern/VoiceKey system acceptance"]),
    (128687392394, 42466578721, "voice-cpu", "scripts/voice_probe.py",
     "Ran 16 tests", True,
     ["synthetic WAV generation", "CPU offline recognition", "voice quality inference"]),
]
rows, logs = [], {}
for job, run, label, script, marker, new_tests_expected, not_run in cases:
    path = ROOT / f"job-{job}.log"
    raw = path.read_bytes()
    text = raw.decode()
    assert CHECKOUT in text
    assert marker in text
    actual = text.split(marker, 1)[1]
    retry_lines = [line for line in actual.splitlines()
                   if "build_download_retry reason=" in line]
    assert len(retry_lines) == 2, (job, retry_lines)
    assert retry_lines[0].endswith("reason=504 attempt=1 next=2 wait_seconds=1")
    assert retry_lines[1].endswith("reason=504 attempt=2 next=3 wait_seconds=2")
    assert "urllib.error.HTTPError: HTTP Error 504: Gateway Time-out" in actual
    assert 'scripts/' + script.split('/')[-1] in actual
    has_new_28 = bool(re.search(r"Ran 28 tests[^\n]*\n[^\n]*\n[^\n]*OK", text))
    assert has_new_28 is new_tests_expected, job
    rows.append(dict(job_id=job, run_id=run, label=label, script=script,
                     new_28_synthetic_tests_passed=has_new_28,
                     unit_test_run_counts=[int(n) for n in re.findall(r"Ran (\d+) tests", text)],
                     real_retry_lines=retry_lines,
                     final_status="HTTP504", final_failure_preserved=True,
                     top_level_attempts_inferred_from_fixed_code_and_retry_markers=3,
                     wire_get_count_observed=False,
                     specific_model_or_header_member_proven=False,
                     real_partial_cleanup_directory_observed=False,
                     downstream_not_run=not_run))

downstream = []
for job in (128687821018, 128687821072, 128687821086, 128687821099, 128687821106):
    text = (ROOT / f"job-{job}.log").read_text()
    assert CHECKOUT in text
    error = "Unable to download artifact(s): Artifact not found for name: ZapIME-Android-development"
    assert error in text
    downstream.append(dict(job_id=job, first_failure="missing_input_artifact",
                           missing_artifact="ZapIME-Android-development",
                           system_ime_voice_execution="NOT_RUN"))

for path in sorted(ROOT.glob("job-*.log")):
    raw = path.read_bytes()
    compressed = gzip.compress(raw, mtime=0)
    path.with_suffix(".log.gz").write_bytes(compressed)
    assert gzip.decompress(compressed) == raw
    logs[path.name] = dict(raw_bytes=len(raw), raw_sha256=hashlib.sha256(raw).hexdigest(),
                          gzip_sha256=hashlib.sha256(compressed).hexdigest(),
                          complete_job_log=True, retrieval="github.fetch_workflow_job_logs")
summary = dict(head=HEAD, tree=TREE, checkout=CHECKOUT, checkout_tree_equals_head=True,
               run_attempt=1, rerun_requested=False,
               new_28_tests_executed_in_three_workflows=True,
               synthetic_success_never_substitutes_for_real_download=True,
               source_code_matches_reviewed_local_commit="82cc600b07821340e882341e4a6037b1e8f4fffa",
               failed_download_cases=rows, downstream_missing_artifacts=downstream,
               artifacts_in_three_failed_workflows=[], logs=logs,
               network_root_cause="not proven: fixed HTTPS GETs returned 504 after bounded retries",
               integrity_fallback_or_mirror_used=False,
               source_shared_checks="separate verifier owns full source/shared/Apple evidence")
(ROOT / "strict-failure-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
print("PASS strict source, three 28-test executions, four bounded 504 failures, five NOT_RUN descendants, nine original log SHA pairs")
