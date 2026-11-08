# SPDX-License-Identifier: GPL-3.0-only
"""Read-only, fail-closed verification of this fixed CI evidence directory."""
from pathlib import Path
import gzip
import hashlib
import json
import re

HEAD = "fb62ad923f376edb7f1c3ab274fc8095718b7ea7"
TREE = "f99bbff5cc9ca044e5e9fe190f0f58c243434930"
CHECKOUT = "0647d0bc6734e915821ccc5ddc9bf8f87bf3c299"
DOWNLOAD_JOBS = {128687392403, 128687392256, 128687392745, 128687392394}
DESCENDANTS = {128687821018, 128687821072, 128687821086, 128687821099, 128687821106}
SHARED_JOBS = {128687392092, 128687393145, 128687393170, 128687393218, 128687393261}


def require(value, message):
    if not value:
        raise ValueError(message)


def exact(value, expected, label):
    require(type(value) is type(expected) and value == expected, label)
    if type(expected) is dict:
        for key in expected:
            exact(value[key], expected[key], label)
    elif type(expected) in (list, tuple):
        for actual, wanted in zip(value, expected):
            exact(actual, wanted, label)
    elif type(expected) is set:
        require(all(any(type(actual) is type(wanted) and actual == wanted
                        for wanted in expected) for actual in value), label)


def keys(value, expected, label):
    require(type(value) is dict and set(value) == set(expected.split()), label)


def load(path):
    def unique(pairs):
        obj = {}
        for key, value in pairs:
            require(key not in obj, "duplicate JSON field")
            obj[key] = value
        return obj
    return json.loads(path.read_text(), object_pairs_hook=unique)


def manifest(root):
    declared = {}
    for line in (root / "SHA256SUMS").read_text().splitlines():
        require(re.fullmatch(r"[0-9a-f]{64}  [A-Za-z0-9_./-]+", line), "manifest row")
        digest, name = line.split("  ", 1)
        require(name not in declared and ".." not in Path(name).parts
                and not Path(name).is_absolute(), "manifest path")
        declared[name] = digest
    found = {}
    for path in root.rglob("*"):
        require(not path.is_symlink(), "symlink evidence")
        if path.is_file() and path != root / "SHA256SUMS" and "__pycache__" not in path.parts:
            found[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    exact(found, declared, "manifest file set or SHA")


def log(root, group, job, raw_sha, raw_bytes):
    path = root / group / f"job-{job}.log.gz"
    require(path.stat().st_size < 512_000, "compressed log size")
    with gzip.open(path, "rb") as stream:
        raw = stream.read(1_000_001)
    require(len(raw) <= 1_000_000, "decoded log size")
    exact(len(raw), raw_bytes, "raw log length")
    exact(hashlib.sha256(raw).hexdigest(), raw_sha, "raw log SHA")
    text = raw.decode("utf-8-sig")
    require(CHECKOUT in text, "actual checkout in log")
    return text


def retries(text, marker):
    require(marker in text, "real phase marker")
    actual = text.split(marker, 1)[1]
    lines = [line for line in actual.splitlines() if "build_download_retry reason=" in line]
    require(len(lines) == 2, "exactly two real retry decisions")
    require(lines[0].endswith("reason=504 attempt=1 next=2 wait_seconds=1")
            and lines[1].endswith("reason=504 attempt=2 next=3 wait_seconds=2"), "real retry sequence")
    require("urllib.error.HTTPError: HTTP Error 504: Gateway Time-out" in actual, "final original HTTP504")
    return lines


def verify(root):
    manifest(root)
    meta = load(root / "download/source-metadata.json")
    keys(meta, "pr head tree parents runs checkout jobs artifacts verified_source_files", "metadata schema")
    exact(meta["pr"], 98, "PR identity")
    exact(meta["head"], HEAD, "head identity")
    exact(meta["tree"], TREE, "tree identity")
    exact(meta["checkout"]["sha"], CHECKOUT, "checkout identity")
    exact(meta["checkout"]["tree"], TREE, "checkout tree")
    reviewed_sources = {
        "scripts/build_download_retry.py": ("53881224395b51a3c301a2d1ca4db868cf714223", "775893b681aa75f2725bba55371a54d44a2c79331ca10e6590e317b215967f31"),
        "scripts/fetch_resources.py": ("b15dd88896518625bee6cfc140b716b7856aa9a2", "c7887fdba5abf1265e8bea7cc7986a8f6527060a51ecc77e92cb4c1aea7ff560"),
        "scripts/prepare_candidate_model.py": ("0377daa71b0d2baf6d13de89493ffb4c53d9eab9", "4b8c142bea8c305d55dab35a5f0b97106e2ca420953c76710b177f54d1620a44"),
        "scripts/voice_probe.py": ("0ea907a5a71bb28fd0ea5a6b3696dcd72a0dde6e", "a72916f5f814841ddc67196cd454cc90be4b1fcea65cd5a6aaec9b9edf8f0a5f")}
    exact(meta["verified_source_files"], {name: {"blob_sha": row[0], "sha256": row[1],
          "matches_reviewed_local_82cc600": True} for name, row in reviewed_sources.items()}, "reviewed source hash identity")
    exact({r["id"] for r in meta["runs"]}, {42466578708, 42466578721, 42466578712}, "run set")
    exact(len(meta["runs"]), 3, "no duplicate runs")
    for row in meta["runs"]:
        exact(row["head_sha"], HEAD, "run head")
        exact(row["head_tree"], TREE, "run tree")
        exact(row["run_attempt"], 1, "first attempt")
        exact(row["status"], "completed", "final run status")
        exact(row["conclusion"], "failure", "failed workflow preserved")
    require(all(v == [] for v in meta["artifacts"].values()), "no downstream artifacts")
    expected_jobs = {"42466578708": {128687392256: "failure", 128687392403: "failure", 128687713831: "skipped"},
                     "42466578721": {128687392394: "failure"},
                     "42466578712": {job: "failure" for job in DESCENDANTS | {128687392745}}}
    exact(set(meta["jobs"]), set(expected_jobs), "job run set")
    for run, expected in expected_jobs.items():
        exact(len(meta["jobs"][run]), len(expected), "no duplicate jobs")
        exact({row["id"]: row["conclusion"] for row in meta["jobs"][run]}, expected, "job conclusions")
        for row in meta["jobs"][run]:
            exact(row["status"], "completed", "job completed")

    summary = load(root / "download/strict-failure-summary.json")
    keys(summary, "head tree checkout checkout_tree_equals_head run_attempt rerun_requested new_28_tests_executed_in_three_workflows synthetic_success_never_substitutes_for_real_download source_code_matches_reviewed_local_commit failed_download_cases downstream_missing_artifacts artifacts_in_three_failed_workflows logs network_root_cause integrity_fallback_or_mirror_used source_shared_checks", "download summary schema")
    for name, value in {"head": HEAD, "tree": TREE, "checkout": CHECKOUT,
                        "checkout_tree_equals_head": True, "run_attempt": 1,
                        "rerun_requested": False, "new_28_tests_executed_in_three_workflows": True,
                        "synthetic_success_never_substitutes_for_real_download": True,
                        "integrity_fallback_or_mirror_used": False,
                        "artifacts_in_three_failed_workflows": []}.items():
        exact(summary[name], value, "download summary " + name)
    exact(set(summary["logs"]), {f"job-{job}.log" for job in DOWNLOAD_JOBS | DESCENDANTS}, "nine log set")
    texts = {}
    for filename, row in summary["logs"].items():
        keys(row, "raw_bytes raw_sha256 gzip_sha256 complete_job_log retrieval", "download log schema")
        exact(row["complete_job_log"], True, "complete original log")
        exact(row["retrieval"], "github.fetch_workflow_job_logs", "original retrieval")
        job = int(filename.removeprefix("job-").removesuffix(".log"))
        texts[job] = log(root, "download", job, row["raw_sha256"], row["raw_bytes"])
        raw_gzip = (root / "download" / (filename + ".gz")).read_bytes()
        exact(hashlib.sha256(raw_gzip).hexdigest(), row["gzip_sha256"], "gzip original SHA")
    exact({r["job_id"] for r in summary["failed_download_cases"]}, DOWNLOAD_JOBS, "four real download cases")
    exact(len(summary["failed_download_cases"]), 4, "no duplicate download cases")
    markers = {128687392403: "##[group]Run python3 scripts/prepare_candidate_model.py --headers",
               128687392394: "Ran 16 tests",
               128687392256: "PASS reviewed candidate catalog and source notices",
               128687392745: "PASS reviewed candidate catalog and source notices"}
    identities = {128687392403: (42466578708, "candidate-host", "scripts/prepare_candidate_model.py"),
                  128687392256: (42466578708, "candidate-build", "scripts/prepare_candidate_model.py"),
                  128687392745: (42466578712, "android-build", "scripts/prepare_candidate_model.py"),
                  128687392394: (42466578721, "voice-cpu", "scripts/voice_probe.py")}
    for row in summary["failed_download_cases"]:
        keys(row, "job_id run_id label script new_28_synthetic_tests_passed unit_test_run_counts real_retry_lines final_status final_failure_preserved top_level_attempts_inferred_from_fixed_code_and_retry_markers wire_get_count_observed specific_model_or_header_member_proven real_partial_cleanup_directory_observed downstream_not_run", "download case schema")
        job = row["job_id"]
        exact((row["run_id"], row["label"], row["script"]), identities[job], "case identity")
        for name, value in {"final_status": "HTTP504", "final_failure_preserved": True,
                            "top_level_attempts_inferred_from_fixed_code_and_retry_markers": 3,
                            "wire_get_count_observed": False, "specific_model_or_header_member_proven": False,
                            "real_partial_cleanup_directory_observed": False}.items():
            exact(row[name], value, "download case " + name)
        exact(row["real_retry_lines"], retries(texts[job], markers[job]), "real retry lines")
        passed_28 = bool(re.search(r"Ran 28 tests[^\n]*\n[^\n]*\n[^\n]*OK", texts[job]))
        exact(passed_28, job != 128687392256, "three executed 28-test runs")
        exact(row["new_28_synthetic_tests_passed"], passed_28, "reported synthetic execution")
        exact(row["unit_test_run_counts"], [int(n) for n in re.findall(r"Ran (\d+) tests", texts[job])], "unit-test counts")
        require(type(row["downstream_not_run"]) is list and row["downstream_not_run"], "unexecuted scope retained")
    exact({r["job_id"] for r in summary["downstream_missing_artifacts"]}, DESCENDANTS, "five descendant failures")
    exact(len(summary["downstream_missing_artifacts"]), 5, "no duplicate descendant cases")
    for row in summary["downstream_missing_artifacts"]:
        keys(row, "job_id first_failure missing_artifact system_ime_voice_execution", "descendant schema")
        exact(row["first_failure"], "missing_input_artifact", "descendant first failure")
        exact(row["missing_artifact"], "ZapIME-Android-development", "missing artifact identity")
        exact(row["system_ime_voice_execution"], "NOT_RUN", "no substituted system acceptance")
        require("Unable to download artifact(s): Artifact not found for name: ZapIME-Android-development" in texts[row["job_id"]], "descendant original failure")

    shared = load(root / "source-shared/summary.json")
    keys(shared, "schema_version source_head actual_checkout verified_tree head_and_checkout_trees_equal jobs scope_limits windows_noncompiled_unix_tests apple_failure_source_check", "shared schema")
    exact(shared["schema_version"], 1, "shared schema version")
    exact(shared["source_head"], HEAD, "shared head")
    exact(shared["actual_checkout"], CHECKOUT, "shared checkout")
    exact(shared["verified_tree"], TREE, "shared tree")
    exact(shared["head_and_checkout_trees_equal"], True, "same source")
    exact({r["id"] for r in shared["jobs"]}, SHARED_JOBS | {128687751174}, "source/shared job set")
    exact(len(shared["jobs"]), 6, "no duplicate source/shared jobs")
    rust = {128687392092: 134, 128687393145: 131, 128687393218: 134, 128687393261: 134}
    for row in shared["jobs"]:
        exact(row["status"], "completed", "source/shared completed")
        exact(row["run_id"], 42466578724 if row["id"] in {128687392092, 128687751174} else 42466578822, "source/shared run")
        if row["id"] == 128687751174:
            keys(row, "id name run_id status conclusion", "publish job schema")
            exact(row["conclusion"], "skipped", "PR publication skipped")
            continue
        common = "id name run_id status conclusion log checkout "
        extra = ("rust_workspace source_release_tests prepared_source_commit publication"
                 if row["id"] == 128687392092 else
                 "first_failure" if row["id"] == 128687393170 else "rust_workspace")
        keys(row, common + extra, "source/shared job schema")
        exact(row["checkout"], CHECKOUT, "source/shared checkout")
        job, record = row["id"], row["log"]
        keys(record, "path bytes sha256 bom_preserved crlf_count", "source/shared log schema")
        text = log(root, "source-shared", job, record["sha256"], record["bytes"])
        exact(record["path"], f"job-{job}.log", "original log name")
        raw = gzip.decompress((root / "source-shared" / (record["path"] + ".gz")).read_bytes())
        exact(record["bom_preserved"], raw.startswith(b"\xef\xbb\xbf"), "original BOM")
        exact(record["crlf_count"], raw.count(b"\r\n"), "original CRLF")
        if job in rust:
            exact(row["conclusion"], "success", "shared/source success")
            keys(row["rust_workspace"], "passed failed ignored measured filtered result_groups", "Rust counts schema")
            for name, value in {"passed": rust[job], "failed": 0, "ignored": 0,
                                "measured": 0, "filtered": 0, "result_groups": 25}.items():
                exact(row["rust_workspace"][name], value, "reported Rust " + name)
            groups = re.findall(r"test result: ok\. (\d+) passed; (\d+) failed; (\d+) ignored; (\d+) measured; (\d+) filtered out", text)
            exact([sum(int(g[i]) for g in groups) for i in range(5)], [rust[job], 0, 0, 0, 0], "actual Rust totals")
            exact(len(groups), 25, "Rust result groups")
            if job == 128687392092:
                require("Ran 30 tests" in text, "source release 30 tests")
                exact(row["source_release_tests"], {"passed": 30, "failed": 0, "skipped": 0}, "reported source release counts")
                exact(row["prepared_source_commit"], CHECKOUT, "prepared source identity")
                exact(row["publication"], "not_run_PR_event_publish_job_skipped", "not public release")
        else:
            exact(row["conclusion"], "failure", "Apple failure preserved")
            keys(row["first_failure"], "stage observed code attempts retried_successfully product_compilation_reached specific_resource_not_identified_by_log downstream_ios_build_and_XCTest", "Apple failure schema")
            retries(text, "##[group]Run bash scripts/build_macos.sh")
            require("scripts/fetch_resources.py" in text, "Apple actual dictionary failure")
            exact(row["first_failure"]["attempts"], 3, "Apple bounded top-level attempts")
            exact(row["first_failure"]["retried_successfully"], False, "Apple still failed")
            exact(row["first_failure"]["code"], "HTTP_504", "Apple failure code")
            exact(row["first_failure"]["stage"], "Compile real InputMethodKit bundle", "Apple step identity")
            exact(row["first_failure"]["specific_resource_not_identified_by_log"], True, "unidentified member")
            exact(row["first_failure"]["product_compilation_reached"], False, "no native compilation claim")
            exact(row["first_failure"]["downstream_ios_build_and_XCTest"], "not_run", "iOS not executed")
    return "PASS fixed source; 14 original logs; three 28-test runs; five bounded HTTP504 failures; source/shared success; native NOT_RUN preserved"


if __name__ == "__main__":
    print(verify(Path(__file__).resolve().parent))
