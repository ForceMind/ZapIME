#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Real Git/archive fixtures and an offline GitHub release state machine."""

import copy
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile

import prepare_source_release as release


class FakeGitHub:
    """Record mutations and serve downloaded bytes, never contact GitHub."""

    def __init__(self):
        self.tag_sha = None
        self.release = None
        self.assets = {}
        self.mutations = []
        self.requests = []
        self.fail_once_on_upload = False

    def snapshot(self):
        result = copy.deepcopy(self.release)
        result["assets"] = [{"name": name} for name in sorted(self.assets)]
        return result

    def __call__(self, command, *, input_data=None):
        assert command[0] == "gh"
        args = command[1:]
        self.requests.append(args)
        if args[0] == "api":
            if args[1:3] == ["--method", "POST"]:
                payload = json.loads(input_data)
                if args[3].endswith("/releases"):
                    assert self.release is None
                    assert payload["draft"] is True and payload["prerelease"] is True
                    assert payload["make_latest"] == "false"
                    assert payload["target_commitish"] == self.tag_sha
                    assert payload["tag_name"] == release.PLAN["tag"]
                    self.release = {"id": 123, **payload}
                    self.mutations.append("create-draft")
                    return release.json_bytes(self.snapshot())
                assert payload["ref"] == "refs/tags/" + release.PLAN["tag"]
                assert self.tag_sha is None
                self.tag_sha = payload["sha"]
                self.mutations.append("create-tag")
                return b"{}"
            endpoint = args[1]
            if "/git/ref/tags/" in endpoint:
                if self.tag_sha is not None:
                    return release.json_bytes({
                        "ref": "refs/tags/" + release.PLAN["tag"],
                        "object": {"sha": self.tag_sha, "type": "commit"},
                    })
            elif "/releases/tags/" in endpoint:
                # GitHub's tag endpoint cannot retrieve an unpublished draft.
                if self.release is not None and not self.release["draft"]:
                    return release.json_bytes(self.snapshot())
            elif "/releases?per_page=100&page=" in endpoint:
                page = int(endpoint.rsplit("=", 1)[1])
                return release.json_bytes([self.snapshot()] if self.release is not None and page == 1 else [])
            elif endpoint.endswith("/releases/123") and self.release is not None:
                return release.json_bytes(self.snapshot())
            else:
                raise AssertionError(command)
            raise subprocess.CalledProcessError(1, command, stderr=b"gh: Not Found (HTTP 404)")
        assert args[0] == "release"
        action = args[1]
        assert args[2] == release.PLAN["tag"]
        if action == "upload":
            assert "--clobber" not in args
            if self.fail_once_on_upload:
                self.fail_once_on_upload = False
                raise subprocess.CalledProcessError(1, command, stderr=b"synthetic interrupted upload")
            for filename in args[5:]:
                path = Path(filename)
                assert path.name not in self.assets
                self.assets[path.name] = path.read_bytes()
            self.mutations.append("upload")
        elif action == "download":
            name = args[args.index("--pattern") + 1]
            Path(args[args.index("--output") + 1]).write_bytes(self.assets[name])
        elif action == "edit":
            assert args[-3:] == ["--draft=false", "--prerelease", "--latest=false"]
            self.release["draft"] = False
            self.mutations.append("publish")
        else:
            raise AssertionError(command)
        return b""


class SourceReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.repo = self.base / "repo"
        self.repo.mkdir()
        self.git("init", "-q")
        self.git("config", "user.name", "Synthetic release fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.write(release.PLAN_PATH, release.json_bytes(release.PLAN))
        self.write(release.PLAN["notes_path"], b"# Source Alpha\n\nSynthetic source-only prerelease.\n")
        self.write("README.md", b"committed source\n")
        self.write(".gitignore", b"/target/\n")
        self.write(".gitmodules", "".join(
            f'[submodule "{path}"]\n\tpath = {path}\n\turl = {value["url"]}\n'
            for path, value in release.SUBMODULES.items()).encode())
        self.git("add", ".")
        for path, value in release.SUBMODULES.items():
            self.git("update-index", "--add", "--cacheinfo", f"160000,{value['commit']},{path}")
        self.commit = self.commit_all()
        self.output = self.base / "bundle"

    def git(self, *args):
        return release.git(self.repo, *args)

    def write(self, path, data):
        destination = self.repo / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)

    def commit_all(self):
        # Leave synthetic, deliberately unpopulated gitlinks in the index.
        self.git("add", "--all", "--", ".", ":(exclude)vendor/msime", ":(exclude)vendor/voicekey")
        self.git("commit", "-qm", "synthetic fixture")
        return self.git("rev-parse", "HEAD").decode().strip()

    def prepare(self):
        release.prepare(self.repo, self.commit, self.output)

    def publish(self, fake):
        return release.publish(self.repo, self.commit, self.output,
                               repository=release.PLAN["repository"], runner=fake, sleeper=lambda _: None)

    def test_archive_exact_commit_not_worktree_and_no_submodule_payload(self):
        self.write("README.md", b"dirty worktree must not ship\n")
        self.write("target/signing.keystore", b"untracked synthetic key")
        self.write("vendor/voicekey/private-model.onnx", b"untracked synthetic model")
        self.prepare()
        metadata = json.loads((self.output / "metadata.json").read_bytes())
        self.assertEqual(metadata["source_commit"], self.commit)
        self.assertEqual(metadata["source_tree"], self.git("rev-parse", self.commit + "^{tree}").decode().strip())
        self.assertEqual(metadata["submodules"], release.SUBMODULES)
        with tarfile.open(self.output / release.ARCHIVE_NAME, "r:gz") as archive:
            self.assertEqual(archive.extractfile(release.PREFIX + "README.md").read(), b"committed source\n")
            names = archive.getnames()
            self.assertFalse(any("target/" in name or name.endswith(".onnx") for name in names))
            self.assertFalse(any(member.isfile() and "/vendor/" in member.name for member in archive))
        release.validate_bundle(self.repo, self.commit, self.output)

    def test_deterministic_archive_and_create_only_output(self):
        self.prepare()
        second = self.base / "second"
        release.prepare(self.repo, self.commit, second)
        self.assertEqual({name: (self.output / name).read_bytes() for name in release.ASSET_NAMES},
                         {name: (second / name).read_bytes() for name in release.ASSET_NAMES})
        with self.assertRaisesRegex(release.ReleaseError, "already exists"):
            self.prepare()

    def test_plan_exact_fields_and_types_no_shell_strings(self):
        for key, value in (("tag", "v0.1.0-alpha.1; touch unexpected"),
                           ("include_submodules", True), ("schema_version", True),
                           ("notes_path", "../other.md"), ("unknown", "extra")):
            with self.subTest(key=key):
                plan = {**release.PLAN, key: value}
                self.write(release.PLAN_PATH, release.json_bytes(plan))
                commit = self.commit_all()
                with self.assertRaises(release.ReleaseError):
                    release.prepare(self.repo, commit, self.output)
        with self.assertRaises(release.ReleaseError):
            release.prepare(self.repo, "HEAD; echo unsafe", self.output)

    def test_missing_or_changed_gitlink_fails(self):
        self.git("update-index", "--cacheinfo", "160000," + "a" * 40 + ",vendor/voicekey")
        self.commit = self.commit_all()
        with self.assertRaisesRegex(release.ReleaseError, "gitlinks"):
            self.prepare()

    def test_submodule_url_is_audited_from_committed_gitmodules(self):
        self.write(".gitmodules", (self.repo / ".gitmodules").read_bytes().replace(
            b"https://github.com/J3n5en/voicekey.git", b"https://example.invalid/other.git"))
        self.commit = self.commit_all()
        with self.assertRaisesRegex(release.ReleaseError, "submodule URL"):
            self.prepare()

    def test_tracked_models_apks_keys_and_target_are_rejected(self):
        for path in ("test.apk", "weights/model.ort", "weights/model.onnx", "private.key", "target/build.txt"):
            with self.subTest(path=path):
                self.write(path, b"synthetic forbidden payload")
                self.git("add", "-f", path)
                self.git("commit", "-qm", "synthetic forbidden fixture")
                commit = self.git("rev-parse", "HEAD").decode().strip()
                with self.assertRaises(release.ReleaseError):
                    release.prepare(self.repo, commit, self.output)
                self.git("reset", "--hard", self.commit)

    def test_evidence_zip_inspection_rejects_hidden_apk(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("report.json", '{"synthetic":true}')
        self.write("docs/evidence/report.zip", data.getvalue())
        valid = self.commit_all()
        release.prepare(self.repo, valid, self.output)
        with zipfile.ZipFile(data := io.BytesIO(), "w") as archive:
            archive.writestr("nested/product.apk", b"synthetic apk")
        self.write("docs/evidence/report.zip", data.getvalue())
        invalid = self.commit_all()
        with self.assertRaises(release.ReleaseError):
            release.prepare(self.repo, invalid, self.base / "invalid")

    def test_plan_change_only_not_every_main_push(self):
        self.assertTrue(release.plan_changed(self.repo, "0" * 40, self.commit))
        self.assertFalse(release.plan_changed(self.repo, self.commit, self.commit))
        self.write("README.md", b"documentation update\n")
        second = self.commit_all()
        self.assertFalse(release.plan_changed(self.repo, self.commit, second))
        self.write(release.PLAN_PATH, release.json_bytes(release.PLAN) + b"\n")
        third = self.commit_all()
        self.assertTrue(release.plan_changed(self.repo, second, third))
        with self.assertRaises(release.ReleaseError):
            release.plan_changed(self.repo, "malformed", third)

    def test_new_release_draft_upload_publish_and_repeat_no_mutations(self):
        self.prepare()
        fake = FakeGitHub()
        self.assertIn("Published", self.publish(fake))
        self.assertEqual(fake.mutations, ["create-tag", "create-draft", "upload", "publish"])
        self.assertEqual(set(fake.assets), release.ASSET_NAMES)
        fake.mutations.clear()
        fake.requests.clear()
        self.assertIn("Already published", self.publish(fake))
        self.assertEqual(fake.mutations, [])
        self.assertFalse(any("/releases?" in request[1] for request in fake.requests if request[0] == "api"))

    def test_tag_mismatch_is_rejected_without_mutation(self):
        self.prepare()
        fake = FakeGitHub()
        fake.tag_sha = "f" * 40
        with self.assertRaisesRegex(release.ReleaseError, "another commit"):
            self.publish(fake)
        self.assertEqual(fake.mutations, [])

    def test_same_source_draft_resumes_only_missing_assets(self):
        self.prepare()
        fake = FakeGitHub()
        self.publish(fake)
        fake.release["draft"] = True
        del fake.assets["metadata.json"]
        fake.mutations.clear()
        self.publish(fake)
        self.assertEqual(fake.mutations, ["upload", "publish"])

    def test_draft_creation_interrupted_before_upload_resumes_via_list_and_id(self):
        self.prepare()
        fake = FakeGitHub()
        fake.fail_once_on_upload = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.publish(fake)
        self.assertTrue(fake.release["draft"])
        self.assertEqual(fake.mutations, ["create-tag", "create-draft"])
        fake.requests.clear()
        self.publish(fake)
        self.assertEqual(fake.mutations, ["create-tag", "create-draft", "upload", "publish"])
        self.assertTrue(any("/releases?per_page=100&page=1" in request[1]
                            for request in fake.requests if request[0] == "api"))
        self.assertTrue(any(request[1].endswith("/releases/123")
                            for request in fake.requests if request[0] == "api"))

    def test_created_response_id_does_not_depend_on_tag_or_list_visibility(self):
        self.prepare()
        fake = FakeGitHub()

        def hidden_listing(command, **kwargs):
            if command[1] == "api" and "/releases?" in command[2]:
                fake.requests.append(command[1:])
                return b"[]"
            return fake(command, **kwargs)

        self.assertIn("Published", self.publish(hidden_listing))
        self.assertEqual(fake.mutations, ["create-tag", "create-draft", "upload", "publish"])
        self.assertEqual(sum("/releases?" in item[1] for item in fake.requests if item[0] == "api"), 1)
        self.assertTrue(any(item[1].endswith("/releases/123")
                            for item in fake.requests if item[0] == "api"))

    def test_creation_id_get_missing_is_bounded_read_only_then_recovers(self):
        self.prepare()
        fake = FakeGitHub()
        missing = 2
        delays = []

        def delayed_id(command, **kwargs):
            nonlocal missing
            if command[1:3] == ["api", "repos/ForceMind/ZapIME/releases/123"] and missing:
                missing -= 1
                raise subprocess.CalledProcessError(1, command, stderr=b"gh: Not Found (HTTP 404)")
            return fake(command, **kwargs)

        result = release.publish(self.repo, self.commit, self.output,
                                 repository=release.PLAN["repository"], runner=delayed_id,
                                 sleeper=delays.append)
        self.assertIn("Published", result)
        self.assertEqual(delays, [1, 2])
        self.assertEqual(fake.mutations.count("create-draft"), 1)

    def test_created_id_stays_missing_clear_failure_and_next_invocation_resumes(self):
        self.prepare()
        fake = FakeGitHub()
        id_gets = 0

        def missing_id(command, **kwargs):
            nonlocal id_gets
            if command[1:3] == ["api", "repos/ForceMind/ZapIME/releases/123"]:
                id_gets += 1
                raise subprocess.CalledProcessError(1, command, stderr=b"gh: Not Found (HTTP 404)")
            return fake(command, **kwargs)

        with self.assertRaisesRegex(release.ReleaseError, "Draft creation could not be confirmed.*read-only"):
            self.publish(missing_id)
        self.assertEqual(id_gets, len(release.RELEASE_READ_DELAYS))
        self.assertEqual(fake.mutations, ["create-tag", "create-draft"])
        self.assertTrue(fake.release["draft"])
        self.assertIn("Published", self.publish(fake))
        self.assertEqual(fake.mutations, ["create-tag", "create-draft", "upload", "publish"])

    def test_lost_create_response_reconciles_without_second_post(self):
        self.prepare()
        for response in (b"null", b"not-json: synthetic secret must not be logged", "lost"):
            with self.subTest(response=response):
                fake = FakeGitHub()

                def lost(command, **kwargs):
                    value = fake(command, **kwargs)
                    if command[1:4] == ["api", "--method", "POST"] and command[4].endswith("/releases"):
                        if response == "lost":
                            raise subprocess.CalledProcessError(1, command, stderr=b"synthetic lost response")
                        return response
                    return value

                self.assertIn("Published", self.publish(lost))
                self.assertEqual(fake.mutations.count("create-draft"), 1)

    def test_ambiguous_creation_with_no_visible_draft_never_reposts_and_redacts(self):
        self.prepare()
        fake = FakeGitHub()
        posts = 0

        def lost_invisible(command, **kwargs):
            nonlocal posts
            if command[1:4] == ["api", "--method", "POST"] and command[4].endswith("/releases"):
                posts += 1
                raise subprocess.CalledProcessError(1, command, stderr=b"synthetic private response")
            return fake(command, **kwargs)

        with self.assertRaisesRegex(release.ReleaseError, "Draft creation could not be confirmed") as failure:
            self.publish(lost_invisible)
        self.assertNotIn("private response", str(failure.exception))
        self.assertEqual(posts, 1)
        self.assertEqual(fake.mutations, ["create-tag"])

    def test_create_response_invalid_or_foreign_id_fails_before_assets(self):
        self.prepare()
        for response in ([], {}, {"id": True, "tag_name": release.PLAN["tag"]},
                         {"id": 123, "tag_name": "foreign"}):
            with self.subTest(response=response):
                fake = FakeGitHub()

                def malformed(command, **kwargs):
                    value = fake(command, **kwargs)
                    if command[1:4] == ["api", "--method", "POST"] and command[4].endswith("/releases"):
                        return release.json_bytes(response)
                    return value

                with self.assertRaisesRegex(release.ReleaseError, "no valid ID"):
                    self.publish(malformed)
                self.assertEqual(fake.mutations, ["create-tag", "create-draft"])

    def test_confirmation_authorization_failure_is_not_retried(self):
        self.prepare()
        fake = FakeGitHub()
        reads = 0

        def denied_id(command, **kwargs):
            nonlocal reads
            if command[1:3] == ["api", "repos/ForceMind/ZapIME/releases/123"]:
                reads += 1
                raise subprocess.CalledProcessError(1, command, stderr=b"synthetic token (HTTP 403)")
            return fake(command, **kwargs)

        with self.assertRaisesRegex(release.ReleaseError, "GitHub API request failed") as failure:
            self.publish(denied_id)
        self.assertNotIn("synthetic token", str(failure.exception))
        self.assertEqual(reads, 1)
        self.assertEqual(fake.mutations, ["create-tag", "create-draft"])

    def test_creation_authorization_failure_is_not_reconciled_or_retried(self):
        self.prepare()
        for status in (401, 403):
            with self.subTest(status=status):
                fake = FakeGitHub()
                posts = 0

                def denied(command, **kwargs):
                    nonlocal posts
                    if command[1:4] == ["api", "--method", "POST"] and command[4].endswith("/releases"):
                        posts += 1
                        raise subprocess.CalledProcessError(1, command,
                                                            stderr=f"private (HTTP {status})".encode())
                    return fake(command, **kwargs)

                with self.assertRaisesRegex(release.ReleaseError, "GitHub API request failed"):
                    self.publish(denied)
                self.assertEqual(posts, 1)
                self.assertEqual(fake.mutations, ["create-tag"])
                self.assertEqual(sum("/releases?" in item[1]
                                     for item in fake.requests if item[0] == "api"), 1)

    def test_created_id_recheck_rejects_changed_identity_before_assets(self):
        self.prepare()
        for change in ({"id": 124}, {"tag_name": "foreign"}, {"target_commitish": "f" * 40},
                       {"body": "foreign notes"}):
            with self.subTest(change=change):
                fake = FakeGitHub()

                def changed(command, **kwargs):
                    value = fake(command, **kwargs)
                    if command[1:3] == ["api", "repos/ForceMind/ZapIME/releases/123"]:
                        return release.json_bytes({**json.loads(value), **change})
                    return value

                with self.assertRaises(release.ReleaseError):
                    self.publish(changed)
                self.assertEqual(fake.mutations, ["create-tag", "create-draft"])

    def test_lost_creation_response_cannot_resume_foreign_draft(self):
        self.prepare()
        fake = FakeGitHub()

        def foreign(command, **kwargs):
            value = fake(command, **kwargs)
            if command[1:4] == ["api", "--method", "POST"] and command[4].endswith("/releases"):
                fake.release["body"] = "foreign draft must remain untouched"
                raise subprocess.CalledProcessError(1, command, stderr=b"synthetic response lost")
            return value

        with self.assertRaisesRegex(release.ReleaseError, "not the same committed source"):
            self.publish(foreign)
        self.assertEqual(fake.mutations, ["create-tag", "create-draft"])
        self.assertEqual(fake.release["body"], "foreign draft must remain untouched")

    def test_partial_upload_failure_next_invocation_verifies_and_fills_only_missing(self):
        self.prepare()
        fake = FakeGitHub()
        failed = False

        def partial(command, **kwargs):
            nonlocal failed
            if command[1:3] == ["release", "upload"] and not failed:
                failed = True
                path = Path(command[6])
                fake.assets[path.name] = path.read_bytes()
                raise subprocess.CalledProcessError(1, command, stderr=b"synthetic partial upload")
            return fake(command, **kwargs)

        with self.assertRaises(subprocess.CalledProcessError):
            self.publish(partial)
        self.assertEqual(len(fake.assets), 1)
        retained = copy.deepcopy(fake.assets)
        self.assertIn("Published", self.publish(fake))
        self.assertTrue(all(fake.assets[name] == data for name, data in retained.items()))
        self.assertEqual(fake.mutations, ["create-tag", "create-draft", "upload", "publish"])

    def test_missing_or_malformed_release_after_mutation_is_explicit_failure(self):
        self.prepare()
        for mutation in ("upload", "publish"):
            with self.subTest(mutation=mutation):
                fake = FakeGitHub()

                def missing(command, **kwargs):
                    if mutation in fake.mutations and command[1:3] == ["api", "repos/ForceMind/ZapIME/releases/123"]:
                        return b"null"
                    return fake(command, **kwargs)

                with self.assertRaisesRegex(release.ReleaseError, "could not be confirmed.*read-only"):
                    self.publish(missing)
                self.assertEqual(fake.mutations.count(mutation), 1)
                self.assertEqual(fake.mutations.count("create-draft"), 1)

    def test_malformed_release_fields_cannot_raise_type_or_key_errors(self):
        self.prepare()
        for field, value in (("body", None), ("draft", "false"), ("assets", None),
                             ("assets", [{"name": []}]), ("assets", [None]), ("id", True)):
            with self.subTest(field=field, value=value):
                fake = FakeGitHub()
                self.publish(fake)
                original_snapshot = fake.snapshot

                def broken_snapshot():
                    return {**original_snapshot(), field: value}

                fake.snapshot = broken_snapshot
                fake.mutations.clear()
                with self.assertRaises(release.ReleaseError):
                    self.publish(fake)
                self.assertEqual(fake.mutations, [])

    def test_release_listing_must_complete_and_reject_ambiguous_drafts(self):
        missing = subprocess.CalledProcessError(1, ["gh"], stderr=b"gh: Not Found (HTTP 404)")
        requests = []

        def unbounded(command, **_):
            requests.append(command)
            if "/tags/" in command[2]:
                raise missing
            return release.json_bytes([{"id": i + 1, "tag_name": "unrelated"} for i in range(100)])

        with mock.patch.object(release, "MAX_RELEASE_PAGES", 2):
            with self.assertRaisesRegex(release.ReleaseError, "pagination limit"):
                release.fetch_release(release.PLAN["repository"], release.PLAN["tag"], runner=unbounded)
        self.assertEqual(len(requests), 3)

        def ambiguous(command, **_):
            if "/tags/" in command[2]:
                raise missing
            return release.json_bytes([{"id": i, "tag_name": release.PLAN["tag"], "draft": True}
                                       for i in (1, 2)])

        with self.assertRaisesRegex(release.ReleaseError, "Multiple visible releases"):
            release.fetch_release(release.PLAN["repository"], release.PLAN["tag"], runner=ambiguous)

    def test_published_missing_asset_is_never_repaired(self):
        self.prepare()
        fake = FakeGitHub()
        self.publish(fake)
        del fake.assets["metadata.json"]
        fake.mutations.clear()
        with self.assertRaisesRegex(release.ReleaseError, "source-only bundle"):
            self.publish(fake)
        self.assertEqual(fake.mutations, [])

    def test_draft_asset_conflict_or_foreign_notes_cannot_be_overwritten(self):
        self.prepare()
        for conflict in ("asset", "notes", "extra-asset"):
            with self.subTest(conflict=conflict):
                fake = FakeGitHub()
                self.publish(fake)
                fake.release["draft"] = True
                if conflict == "asset":
                    fake.assets["metadata.json"] = b"foreign data"
                elif conflict == "notes":
                    fake.release["body"] = "Different source Alpha"
                else:
                    fake.assets["unexpected.apk"] = b"synthetic binary"
                fake.mutations.clear()
                with self.assertRaises(release.ReleaseError):
                    self.publish(fake)
                self.assertEqual(fake.mutations, [])

    def test_downloaded_bundle_tampering_and_wrong_repository_fail_closed(self):
        self.prepare()
        fake = FakeGitHub()
        with self.assertRaisesRegex(release.ReleaseError, "fixed release repository"):
            release.publish(self.repo, self.commit, self.output, repository="other/repository", runner=fake)
        (self.output / release.ARCHIVE_NAME).write_bytes(b"tampered")
        with self.assertRaisesRegex(release.ReleaseError, "exact committed source"):
            self.publish(fake)
        self.assertEqual(fake.mutations, [])

    def test_api_authorization_failure_is_not_treated_as_missing_release(self):
        self.prepare()
        calls = []

        def forbidden(command, **_):
            calls.append(command)
            raise subprocess.CalledProcessError(1, command, stderr=b"gh: Forbidden (HTTP 403)")

        with self.assertRaisesRegex(release.ReleaseError, "GitHub API request failed"):
            release.publish(self.repo, self.commit, self.output,
                            repository=release.PLAN["repository"], runner=forbidden)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][1], "api")


if __name__ == "__main__":
    unittest.main()
