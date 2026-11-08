# SPDX-License-Identifier: GPL-3.0-only
"""Read-only recheck of the second fixed source, not a previous-run substitute."""
import gzip
import hashlib
import json
from pathlib import Path
import re

HERE = Path(__file__).resolve().parent
HEAD = "94b66f3e3ac9c0af92952427d0f3f1bfc27bb6a8"
MERGE = "a0add63cc59cd6b3f7956bee4f04c7c805c72c1f"
TREE = "78d00ce936aafd38b7073e1dc688f502128a0e46"
BUILD = 128688078544
DOWNSTREAM = {
    128688487617: "Actual modern Android keyboard and offline voice",
    128688487646: "Actual modern Android keyboard and offline voice",
    128688487652: "Actual VoiceKey-only SAF, JNI, Editable and offline system IME lifecycle",
    128688487680: "Actual VoiceKey-only SAF, JNI, Editable and offline system IME lifecycle",
    128688487808: "Actual VoiceKey-only SAF, JNI, Editable and offline system IME lifecycle",
}


def load(path):
    return json.loads((HERE / path).read_bytes())


def log(path):
    return gzip.decompress((HERE / path).read_bytes()).decode("utf-8")


def steps(job):
    return {row["name"]: row["conclusion"] for row in (job["steps"] or [])}


def main():
    manifest = load("log-manifest.json")
    assert len(manifest) == 6 and {r["job_id"] for r in manifest} == {BUILD, *DOWNSTREAM}
    for row in manifest:
        zipped = (HERE / row["path"]).read_bytes()
        raw = gzip.decompress(zipped)
        assert len(raw) == row["decoded_utf8_bytes"]
        assert hashlib.sha256(raw).hexdigest() == row["decoded_utf8_sha256"]
        assert hashlib.sha256(zipped).hexdigest() == row["gzip_sha256"]
    head, merge = load("git-head.json"), load("git-tested-merge.json")
    assert head["sha"] == HEAD and merge["sha"] == MERGE
    assert head["tree"]["sha"] == merge["tree"]["sha"] == TREE
    assert {p["sha"] for p in merge["parents"]} == {HEAD, "b9cc2f2e17016b49c5636f91a713ea13f8146691"}
    run = load("workflow-run.json")
    assert run["id"] == 42466801834 and run["head_sha"] == HEAD and run["run_attempt"] == 1
    assert run["status"] == "completed" and run["conclusion"] == "failure"
    assert run["head_commit"]["tree_id"] == TREE
    jobs = load("workflow-jobs.json")["jobs"]
    assert len(jobs) == 6 and {j["id"] for j in jobs} == {BUILD, *DOWNSTREAM}
    assert all(j["status"] == "completed" and j["conclusion"] == "failure" for j in jobs)
    build = next(j for j in jobs if j["id"] == BUILD)
    state = steps(build)
    assert state["Offline candidate context, bounded worker and pinned model resources"] == "failure"
    for name in ("Run bash scripts/build_android.sh arm64-v8a", "Run bash scripts/build_android.sh x86_64",
                 "Run bash scripts/build_android_tests.sh", "Android API 28 native keyboard smoke"):
        assert state[name] == "skipped"
    original = log("build-job.log.gz")
    assert f"git log -1 --format=%H\n" in original
    assert MERGE in original
    # The earlier unit tests deliberately emit synthetic retry logs. Count only
    # the actual preparation tail after the successful reviewed-catalog check.
    marker = "PASS reviewed candidate catalog and source notices"
    assert original.count(marker) == 1
    preparation = original.split(marker, 1)[1]
    events = re.findall(r"^([^\n ]+) build_download_retry reason=(\d+) attempt=(\d+) next=(\d+) wait_seconds=(\d+)$",
                        preparation, re.MULTILINE)
    assert events == [("2026-11-08T09:22:37.7779569Z", "504", "1", "2", "1"),
                      ("2026-11-08T09:22:38.8370363Z", "504", "2", "3", "2")]
    assert 'scripts/prepare_candidate_model.py", line 157, in download' in preparation
    assert "run_with_retry(attempt, socket_timeout=60, total_timeout=180)" in preparation
    assert preparation.count("urllib.error.HTTPError: HTTP Error 504: Gateway Time-out") == 1
    assert "2026-11-08T09:22:40.9655039Z urllib.error.HTTPError: HTTP Error 504: Gateway Time-out" in preparation
    assert load("workflow-artifacts.json")["artifacts"] == []
    for job in jobs:
        if job["id"] == BUILD:
            continue
        assert steps(job)[DOWNSTREAM[job["id"]]] == "skipped"
        assert "Artifact not found for name: ZapIME-Android-development" in log(f"downstream/{job['id']}.log.gz")
    source = load("other-workflow-observation/source-alpha-run.json")
    assert source["id"] == 42466801643 and source["head_sha"] == HEAD and source["run_attempt"] == 1
    assert source["status"] == "completed" and source["conclusion"] == "success"
    service = load("other-workflow-observation/service-pending-run.json")
    assert service["id"] == 42466801630 and service["head_sha"] == HEAD and service["run_attempt"] == 1
    assert service["status"] == "in_progress" and service["conclusion"] is None
    print(json.dumps({"schema_version": 1, "run_id": 42466801834, "attempt": 1,
        "head_commit": HEAD, "tested_merge_commit": MERGE, "source_tree": TREE,
        "android_jobs_failed": 6, "artifacts": 0, "original_job_logs_verified": 6,
        "actual_download_retry_events": [{"time": r[0], "http_status": int(r[1]),
            "attempt": int(r[2]), "next": int(r[3]), "wait_seconds": int(r[4])} for r in events],
        "download_final_result": "third_attempt_http_504", "apk_and_harness_build": "NOT_RUN",
        "system_ime_and_actual_service_exit": "NOT_RUN", "downstream_native_acceptance": "NOT_RUN",
        "source_alpha_workflow": "success_metadata_only", "service_observation": "PENDING",
        "previous_source_results_substituted": False}, indent=2))


if __name__ == "__main__":
    main()
