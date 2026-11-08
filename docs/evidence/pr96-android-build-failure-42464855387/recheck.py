# SPDX-License-Identifier: GPL-3.0-only
"""Read-only assertions for first-attempt failure and NOT_RUN boundaries."""
import gzip
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
HEAD = "42f4562193f8d13a684aa7b656ca2adaa27de216"
MERGE = "49186be44237965669f314ebbe768f3684297a45"
TREE = "02b21f6d4e1897bbde801b5096c0bd59fa5e258e"


def load(path):
    return json.loads((HERE / path).read_bytes())


def log(path):
    return gzip.decompress((HERE / path).read_bytes()).decode("utf-8")


def steps(job):
    return {row["name"]: row["conclusion"] for row in (job["steps"] or [])}


def observed(identifier, conclusion):
    run = load(f"observed-workflows/{identifier}-run.json")
    assert run["id"] == identifier and run["head_sha"] == HEAD
    assert run["run_attempt"] == 1
    if conclusion is not None:
        assert run["status"] == "completed" and run["conclusion"] == conclusion
    return load(f"observed-workflows/{identifier}-jobs.json")["jobs"]


def main():
    manifest = load("log-manifest.json")
    assert len(manifest) == 10
    assert {row["job_id"] for row in manifest} == {
        128681882345, 128681882225, 128681882293, 128682174828, 128681882621,
        128681882284, 128682174838, 128682174850, 128682174863, 128682175157,
    }
    for row in manifest:
        zipped = (HERE / row["path"]).read_bytes()
        raw = gzip.decompress(zipped)
        assert len(raw) == row["decoded_utf8_bytes"]
        assert hashlib.sha256(raw).hexdigest() == row["decoded_utf8_sha256"]
        assert hashlib.sha256(zipped).hexdigest() == row["gzip_sha256"]
    head, merge = load("git-head.json"), load("git-tested-merge.json")
    assert head["sha"] == HEAD and merge["sha"] == MERGE
    assert head["tree"]["sha"] == merge["tree"]["sha"] == TREE
    assert {row["sha"] for row in merge["parents"]} == {HEAD, "5a0dc56290be9c2458ee6e7e7ddafc51930dd593"}
    run = load("android/workflow-run.json")
    assert run["head_sha"] == HEAD and run["run_attempt"] == 1
    assert run["status"] == "completed" and run["conclusion"] == "failure"
    assert run["head_commit"]["tree_id"] == TREE
    jobs = load("android/workflow-jobs.json")["jobs"]
    assert len(jobs) == 6 and all(j["status"] == "completed" and j["conclusion"] == "failure" for j in jobs)
    build = next(j for j in jobs if j["id"] == 128681882345)
    status = steps(build)
    assert status["Offline candidate context, bounded worker and pinned model resources"] == "failure"
    for name in ("Run bash scripts/build_android.sh arm64-v8a", "Run bash scripts/build_android.sh x86_64",
                 "Run bash scripts/build_android_tests.sh", "Android API 28 native keyboard smoke"):
        assert status[name] == "skipped"
    original = log("android/build-job.log.gz")
    assert MERGE in original and "git log -1 --format=%H" in original
    assert 'scripts/prepare_candidate_model.py", line 150, in download' in original
    assert "urllib.error.HTTPError: HTTP Error 504: Gateway Time-out" in original
    assert load("android/workflow-artifacts.json")["artifacts"] == []
    downstream = {
        128682174828: ("Actual modern Android keyboard and offline voice", "android/downstream-api36-job.log.gz"),
        128682174863: ("Actual modern Android keyboard and offline voice", "android/downstream-api35-job.log.gz"),
        128682174838: ("Actual VoiceKey-only SAF, JNI, Editable and offline system IME lifecycle", "android/downstream-voicekey-api35-job.log.gz"),
        128682174850: ("Actual VoiceKey-only SAF, JNI, Editable and offline system IME lifecycle", "android/downstream-voicekey-api36-job.log.gz"),
        128682175157: ("Actual VoiceKey-only SAF, JNI, Editable and offline system IME lifecycle", "android/downstream-voicekey-api28-job.log.gz"),
    }
    for job in jobs:
        if job["id"] == build["id"]:
            continue
        acceptance, path = downstream[job["id"]]
        assert steps(job)[acceptance] == "skipped"
        assert "Artifact not found for name: ZapIME-Android-development" in log(path)
    focused = observed(42464855342, "failure")
    assert len(focused) == 4 and focused[0]["id"] == 128681882225
    assert all(j["conclusion"] == "skipped" for j in focused[1:])
    original = log("focused-voicekey/build-job.log.gz")
    assert MERGE in original and 'scripts/fetch_resources.py", line 28, in fetch' in original
    assert "HTTP Error 504: Gateway Time-out" in original
    source = observed(42464855401, "failure")
    assert source[1]["name"] == "Publish source-only Alpha prerelease" and source[1]["conclusion"] == "skipped"
    assert steps(source[0])["Test source preparation and retry safety in real Git repositories"] == "success"
    assert steps(source[0])["Verify pinned upstream and shared Rust workspace"] == "success"
    assert steps(source[0])["Prepare exact committed source Alpha"] == "failure"
    original = log("source-alpha/prepare-job.log.gz")
    assert f"SOURCE_COMMIT: {MERGE}" in original
    assert "Source release failed: ReleaseError: Uninspected tracked archive is not a source-only input" in original
    assert "HTTP Error 504" not in original
    shared = observed(42464855424, "failure")
    assert len(shared) == 4
    assert all(j["conclusion"] == "success" for j in shared if j["name"].startswith("Shared core ("))
    assert next(j for j in shared if j["name"] == "Apple native development hosts")["conclusion"] == "failure"
    windows = observed(42464855345, "failure")
    assert len(windows) == 1 and windows[0]["conclusion"] == "failure"
    for path in ("shared/apple-job.log.gz", "windows/tsf-job.log.gz"):
        original = log(path)
        assert "fetch_resources.py" in original and "HTTP Error 504: Gateway Time-out" in original
    observed(42464855374, "success")
    service = load("observed-workflows/42464855498-run.json")
    assert service["head_sha"] == HEAD and service["run_attempt"] == 1
    result = {"schema_version": 1, "head_commit": HEAD, "tested_merge_commit": MERGE, "tree": TREE,
              "attempt": 1, "android_jobs_failed": 6, "android_artifacts": 0,
              "android_apk_build": "NOT_RUN", "android_system_ime_and_service_exit": "NOT_RUN",
              "focused_voicekey_device_sync_and_https": "NOT_RUN",
              "source_publication": "SKIPPED", "source_failure": "uninspected_tracked_archive",
              "android_build_failure": "prepare_candidate_model_http_504",
              "focused_apple_windows_failure": "fetch_resources_http_504",
              "shared_core_platforms_success": 3, "offline_cpu_workflow": "success_metadata_only",
              "service_status": service["status"], "service_conclusion": service["conclusion"],
              "service_transaction_evidence_rechecked": False, "job_logs_hash_verified": 10}
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
