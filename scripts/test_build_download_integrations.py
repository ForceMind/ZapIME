# SPDX-License-Identifier: GPL-3.0-only
"""Synthetic real caller publication tests; never fetch models or execute tar data."""
from contextlib import contextmanager
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

    def geturl(self):
        return self.url


class PartialThenReset(Response):
    def __init__(self):
        super().__init__(b"discarded partial")
        self.reads = 0

    def read(self, count=-1):
        self.reads += 1
        if self.reads == 1:
            return b"discarded partial"
        raise ConnectionResetError("synthetic interrupted response")

    def read1(self, count=-1):
        return self.read(count)


@contextmanager
def fake_retry_clock():
    # Before the fix, the old callers execute directly and fail on the first
    # 504. After integration they import this module and use a controlled clock.
    retry = sys.modules.get("build_download_retry")
    if retry is None:
        yield
        return
    now = [10.0]

    def sleep(seconds):
        now[0] += seconds

    with patch.object(retry.time, "monotonic", side_effect=lambda: now[0]), \
            patch.object(retry.time, "sleep", side_effect=sleep):
        yield


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
            with patch.object(candidate.OPENER, "open", side_effect=lambda *args, **kwargs: unavailable()) as network, fake_retry_clock():
                # HTTPError must be raised, not returned as a response.
                def fail(*args, **kwargs):
                    raise unavailable()
                network.side_effect = fail
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
            with patch.object(candidate.OPENER, "open", return_value=Response(b"x" * len(raw))) as network, fake_retry_clock():
                with self.assertRaises(ValueError):
                    candidate.download(spec(raw), target)
                self.assertEqual(network.call_count, 1)
            self.assertEqual(target.read_bytes(), b"old")
            with patch.object(candidate.OPENER, "open") as network:
                with self.assertRaises(ValueError):
                    candidate.download(spec(raw), target, offline=True)
                network.assert_not_called()
            self.assertEqual({p.name for p in target.parent.iterdir()}, {"model"})

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

            with patch.object(fetch_resources, "ROOT", root), patch.object(fetch_resources.urllib.request, "urlopen", side_effect=open_response), fake_retry_clock():
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
            with patch.object(fetch_resources, "ROOT", root), patch.object(fetch_resources.urllib.request, "urlopen", return_value=Response(b"corrupt")) as network, fake_retry_clock():
                with self.assertRaises(ValueError):
                    fetch_resources.fetch(output)
            self.assertEqual(network.call_count, 1)
            self.assertEqual(target.read_bytes(), b"previous")
            self.assertEqual({p.name for p in output.iterdir()}, {target.name})

    def test_offline_import_archive_504_then_success_checks_all_regular_members(self):
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
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            license = root / "third_party/licenses/X-ASR-Apache-2.0.txt"
            license.parent.mkdir(parents=True)
            license.write_bytes(b"synthetic license")
            model = root / "model"
            with patch.object(voice_probe, "ROOT", root), patch.object(voice_probe, "LOCK", lock), \
                    patch.object(voice_probe.urllib.request, "urlopen", side_effect=[unavailable(), Response(data)]) as network, fake_retry_clock():
                voice_probe.prepare(model)
            self.assertEqual(network.call_count, 2)
            self.assertEqual((model / "model.tar.bz2").read_bytes(), data)
            for name, raw in payloads.items():
                self.assertEqual((model / name).read_bytes(), raw)
            self.assertEqual({p.name for p in model.iterdir()}, set(payloads) | {"model.tar.bz2", "LICENSE", "NOTICE.txt"})


if __name__ == "__main__":
    unittest.main()
