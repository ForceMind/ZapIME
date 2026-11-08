# SPDX-License-Identifier: GPL-3.0-only
"""Synthetic real caller publication tests; never fetch models or execute tar data."""
from contextlib import contextmanager
import errno
import hashlib
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

import fetch_resources
import prepare_candidate_model as candidate
import voice_probe


def spec(data, url="https://example.invalid/fixed"):
    return dict(size=len(data), sha256=hashlib.sha256(data).hexdigest(), url=url)


def unavailable():
    return urllib.error.HTTPError("https://example.invalid/fixed", 504, "synthetic gateway", {}, None)


class Response(io.BytesIO):
    def __init__(self, data, url="https://example.invalid/fixed"):
        super().__init__(data)
        self.url = url
        self.status = 200

    def geturl(self):
        return self.url

    def getcode(self):
        return self.status


class PartialThenReset(Response):
    def __init__(self, prefix=b"discarded partial"):
        super().__init__(prefix)
        self.prefix = prefix
        self.reads = 0

    def read(self, count=-1):
        self.reads += 1
        if self.reads == 1:
            return self.prefix
        raise ConnectionResetError("synthetic interrupted response")

    def read1(self, count=-1):
        return self.read(count)


@contextmanager
def fake_retry_clock():
    # Before the fix, the old callers execute directly and fail on the first
    # 504. After integration they import this module and use a controlled clock.
    retry = sys.modules.get("build_download_retry")
    now = [10.0]
    if retry is None:
        yield now
        return

    def sleep(seconds):
        now[0] += seconds

    with patch.object(retry.time, "monotonic", side_effect=lambda: now[0]), \
            patch.object(retry.time, "sleep", side_effect=sleep):
        yield now


def fake_network(module, **options):
    """Keep the old-baseline seam while exercising the new HTTPS-only opener."""
    opener = getattr(module, "OPENER", None)
    if opener is not None:
        return patch.object(opener, "open", **options)
    return patch.object(module.urllib.request, "urlopen", **options)


class CallerRetryTests(unittest.TestCase):
    def test_candidate_504_then_valid_response_retains_exact_fixed_url(self):
        raw = b"synthetic locked candidate"
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "model"
            target.write_bytes(b"existing invalid cache")
            row = spec(raw)
            with patch.object(candidate.OPENER, "open", side_effect=[unavailable(), Response(raw)]) as network, fake_retry_clock():
                candidate.download(row, target)
            self.assertEqual(target.read_bytes(), raw)
            self.assertEqual(network.call_count, 2)
            self.assertTrue(all(call.args[0] == row["url"] for call in network.call_args_list))
            self.assertEqual({p.name for p in target.parent.iterdir()}, {"model"})

    def test_candidate_partial_response_is_discarded_before_same_get_restarts(self):
        raw = b"replacement verified from byte zero"
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "model"
            target.write_bytes(b"previous cache")
            with patch.object(candidate.OPENER, "open", side_effect=[PartialThenReset(), Response(raw)]) as network, fake_retry_clock():
                candidate.download(spec(raw), target)
            self.assertEqual(target.read_bytes(), raw)
            self.assertEqual(network.call_count, 2)
            self.assertEqual({p.name for p in target.parent.iterdir()}, {"model"})

    def test_candidate_final_network_failure_keeps_old_file_and_no_partials(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "model"
            old = b"previous cache must remain"
            target.write_bytes(old)
            def fail(*args, **kwargs):
                raise unavailable()
            with patch.object(candidate.OPENER, "open", side_effect=fail) as network, fake_retry_clock():
                with self.assertRaises(urllib.error.HTTPError):
                    candidate.download(spec(b"new expected"), target)
            self.assertEqual(network.call_count, 3)
            self.assertEqual(target.read_bytes(), old)
            self.assertEqual({p.name for p in target.parent.iterdir()}, {"model"})

    def test_candidate_checksum_failure_and_offline_miss_never_retry(self):
        raw = b"synthetic expected bytes"
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "model"
            target.write_bytes(b"old")
            for payload in (b"x" * len(raw), raw[:-1], raw + b"?"):
                with self.subTest(bytes=len(payload)), patch.object(candidate.OPENER, "open", return_value=Response(payload)) as network, fake_retry_clock():
                    with self.assertRaises(ValueError):
                        candidate.download(spec(raw), target)
                    self.assertEqual(network.call_count, 1)
            self.assertEqual(target.read_bytes(), b"old")
            with patch.object(candidate.OPENER, "open") as network:
                with self.assertRaises(ValueError):
                    candidate.download(spec(raw), target, offline=True)
                network.assert_not_called()
            self.assertEqual({p.name for p in target.parent.iterdir()}, {"model"})

    def test_candidate_partial_failure_exhausts_without_prefix_append_or_leaked_stage(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "model"
            target.write_bytes(b"previous cache")
            with patch.object(candidate.OPENER, "open", side_effect=lambda *args, **kwargs: PartialThenReset()) as network, fake_retry_clock():
                with self.assertRaises(ConnectionResetError):
                    candidate.download(spec(b"expected body long enough to receive a partial prefix"), target)
            self.assertEqual(network.call_count, 3)
            self.assertEqual(target.read_bytes(), b"previous cache")
            self.assertEqual({p.name for p in target.parent.iterdir()}, {"model"})

    def test_candidate_read_that_exhausts_budget_does_not_publish_or_retry(self):
        raw = b"synthetic fully delivered but late bytes"
        with tempfile.TemporaryDirectory() as temporary, fake_retry_clock() as now:
            class SlowResponse(Response):
                def read1(self, count=-1):
                    value = super().read1(count)
                    now[0] += 181
                    return value

            target = Path(temporary) / "model"
            target.write_bytes(b"previous cache")
            with patch.object(candidate.OPENER, "open", return_value=SlowResponse(raw)) as network:
                with self.assertRaises(TimeoutError):
                    candidate.download(spec(raw), target)
            self.assertEqual(network.call_count, 1)
            self.assertEqual(target.read_bytes(), b"previous cache")
            self.assertEqual({p.name for p in target.parent.iterdir()}, {"model"})

    def test_file_publication_timeout_is_not_treated_as_network_for_any_caller(self):
        for name in ("candidate", "dictionary", "voice"):
            with self.subTest(caller=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                if name == "candidate":
                    module, target = candidate, root / "model"
                    raw = b"synthetic locked candidate"
                    context = patch.object(candidate, "ROOT", root)
                    response = lambda *args, **kwargs: Response(raw)
                    operation = lambda: candidate.download(spec(raw), target)
                elif name == "dictionary":
                    module, target = fetch_resources, root / "output/msime-pinyin.db"
                    payloads = self.dictionary(root)
                    context = patch.object(fetch_resources, "ROOT", root)
                    response = lambda url, **kwargs: Response(payloads[url.rsplit("/", 1)[1]], url)
                    operation = lambda: fetch_resources.fetch(root / "output")
                else:
                    module, target = voice_probe, root / "model/model.tar.bz2"
                    data, lock, _ = self.model_fixture(root)
                    context = patch.object(voice_probe, "LOCK", lock)
                    response = lambda *args, **kwargs: Response(data)
                    operation = lambda: voice_probe.prepare(root / "model")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b"previous cache")
                failure = TimeoutError(errno.ETIMEDOUT, "synthetic filesystem timeout")
                with context, fake_network(module, side_effect=response) as network, \
                        patch.object(Path, "replace", side_effect=failure), fake_retry_clock():
                    with self.assertRaises(TimeoutError):
                        operation()
                self.assertEqual(network.call_count, 1)
                self.assertEqual(target.read_bytes(), b"previous cache")
                self.assertEqual({p.name for p in target.parent.iterdir()}, {target.name})

    def dictionary(self, root):
        manifest = json.dumps(dict(licensing=dict(includes_unlicensed_inputs=False))).encode()
        contents = {"msime-pinyin.db": b"synthetic pinyin", "msime-english.db": b"synthetic english",
                    "msime-scowl_Copyright.txt": b"synthetic notice",
                    "msime-dictionary-manifest.json": manifest}
        entries = [dict(name=name, **spec(data, "https://example.invalid/" + name)) for name, data in contents.items()]
        lock = root / "vendor/msime/resources/editions/pinyin.lock.json"
        lock.parent.mkdir(parents=True)
        lock.write_text(json.dumps(dict(artifacts=entries)))
        return contents

    def test_dictionary_504_then_success_keeps_four_checksums_and_manifest_policy(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payloads = self.dictionary(root)
            calls = []

            def open_response(url, **kwargs):
                calls.append(url)
                if len(calls) == 1:
                    raise unavailable()
                return Response(payloads[url.rsplit("/", 1)[1]], url)

            with patch.object(fetch_resources, "ROOT", root), fake_network(fetch_resources, side_effect=open_response), fake_retry_clock():
                fetch_resources.fetch(root / "output")
            self.assertEqual(len(calls), 5)
            self.assertEqual(calls[0], calls[1])
            self.assertEqual({p.name for p in (root / "output").iterdir()}, set(payloads))
            for name, data in payloads.items():
                self.assertEqual((root / "output" / name).read_bytes(), data)

    def test_dictionary_integrity_error_is_single_get_and_preserves_previous_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.dictionary(root)
            output = root / "output"
            output.mkdir()
            target = output / "msime-pinyin.db"
            target.write_bytes(b"previous")
            with patch.object(fetch_resources, "ROOT", root), fake_network(fetch_resources, return_value=Response(b"corrupt")) as network, fake_retry_clock():
                with self.assertRaises(ValueError):
                    fetch_resources.fetch(output)
            self.assertEqual(network.call_count, 1)
            self.assertEqual(target.read_bytes(), b"previous")
            self.assertEqual({p.name for p in output.iterdir()}, {target.name})

    def test_dictionary_partial_response_is_discarded_before_verified_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payloads = self.dictionary(root)
            calls = []

            def open_response(url, **kwargs):
                calls.append(url)
                if len(calls) == 1:
                    return PartialThenReset(prefix=b"x")
                return Response(payloads[url.rsplit("/", 1)[1]], url)

            output = root / "output"
            with patch.object(fetch_resources, "ROOT", root), fake_network(fetch_resources, side_effect=open_response), fake_retry_clock():
                fetch_resources.fetch(output)
            self.assertEqual(len(calls), 5)
            self.assertEqual(calls[0], calls[1])
            self.assertEqual({p.name for p in output.iterdir()}, set(payloads))
            for name, data in payloads.items():
                self.assertEqual((output / name).read_bytes(), data)

    def test_complete_dictionary_cache_does_not_connect(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payloads = self.dictionary(root)
            output = root / "output"
            output.mkdir()
            for name, data in payloads.items():
                (output / name).write_bytes(data)
            with patch.object(fetch_resources, "ROOT", root), fake_network(fetch_resources, side_effect=AssertionError("cache must not connect")) as network:
                fetch_resources.fetch(output)
                network.assert_not_called()

    def model_fixture(self, root):
        payloads = {"encoder.int8.onnx": b"synthetic encoder", "decoder.onnx": b"synthetic decoder",
                    "joiner.int8.onnx": b"synthetic joiner", "tokens.txt": b"synthetic tokens"}
        encoded = io.BytesIO()
        with tarfile.open(fileobj=encoded, mode="w:bz2") as archive:
            for name, data in payloads.items():
                member = tarfile.TarInfo("synthetic-model/" + name)
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
        data = encoded.getvalue()
        lock = dict(archive=dict(root="synthetic-model", **spec(data)),
                    files={name: spec(raw) for name, raw in payloads.items()})
        license = root / "third_party/licenses/X-ASR-Apache-2.0.txt"
        license.parent.mkdir(parents=True)
        license.write_bytes(b"synthetic license")
        return data, lock, payloads

    def test_offline_import_archive_504_then_success_checks_all_regular_members(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data, lock, payloads = self.model_fixture(root)
            model = root / "model"
            with patch.object(voice_probe, "ROOT", root), patch.object(voice_probe, "LOCK", lock), \
                    fake_network(voice_probe, side_effect=[unavailable(), Response(data)]) as network, fake_retry_clock():
                voice_probe.prepare(model)
            self.assertEqual(network.call_count, 2)
            self.assertEqual((model / "model.tar.bz2").read_bytes(), data)
            for name, raw in payloads.items():
                self.assertEqual((model / name).read_bytes(), raw)
            self.assertEqual({p.name for p in model.iterdir()}, set(payloads) | {"model.tar.bz2", "LICENSE", "NOTICE.txt"})

    def test_valid_offline_import_archive_cache_does_not_connect(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data, lock, payloads = self.model_fixture(root)
            model = root / "model"
            model.mkdir()
            (model / "model.tar.bz2").write_bytes(data)
            with patch.object(voice_probe, "ROOT", root), patch.object(voice_probe, "LOCK", lock), \
                    fake_network(voice_probe, side_effect=AssertionError("cache must not connect")) as network:
                voice_probe.prepare(model)
                network.assert_not_called()
            for name, raw in payloads.items():
                self.assertEqual((model / name).read_bytes(), raw)

    def test_offline_import_archive_partial_failure_preserves_old_archive_without_stages(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, lock, _ = self.model_fixture(root)
            model = root / "model"
            model.mkdir()
            archive = model / "model.tar.bz2"
            archive.write_bytes(b"previous cache")
            with patch.object(voice_probe, "ROOT", root), patch.object(voice_probe, "LOCK", lock), \
                    fake_network(voice_probe, side_effect=lambda *args, **kwargs: PartialThenReset()) as network, fake_retry_clock():
                with self.assertRaises(ConnectionResetError):
                    voice_probe.prepare(model)
            self.assertEqual(network.call_count, 3)
            self.assertEqual(archive.read_bytes(), b"previous cache")
            self.assertEqual({p.name for p in model.iterdir()}, {archive.name})

    def test_offline_import_archive_checksum_error_is_not_retried(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data, lock, _ = self.model_fixture(root)
            model = root / "model"
            with patch.object(voice_probe, "ROOT", root), patch.object(voice_probe, "LOCK", lock), \
                    fake_network(voice_probe, return_value=Response(b"x" * len(data))) as network, fake_retry_clock():
                with self.assertRaises(ValueError):
                    voice_probe.prepare(model)
            self.assertEqual(network.call_count, 1)
            self.assertEqual(list(model.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
