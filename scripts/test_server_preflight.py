#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Exercise the Bash entry and the signed artifact on disposable Linux."""

from contextlib import contextmanager
import hashlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import platform
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest


ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.1.0-preview.1"
ARCH = {"x86_64": "amd64", "aarch64": "arm64"}.get(platform.machine())
FILES = ("zapime-preflight.zip", "zapime-preflight.sig", "release-public.pem")
SHORT_ENTRY = 'payload=$(curl -fsSL "$1") && test -n "$payload" && shift && bash -c "$payload" zapime-install "$@"'


@unittest.skipUnless(sys.platform == "linux" and ARCH, "requires a supported Linux CI host")
class SignedPreflightTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="zapime-preflight-test-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.private = cls.root / "test-key.pem"
        cls.certificate = cls.root / "test-tls.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=ZapIME synthetic localhost", "-addext", "subjectAltName=IP:127.0.0.1",
                        "-keyout", str(cls.private), "-out", str(cls.certificate)],
                       check=True, capture_output=True)
        cls.bundle = cls.root / "published" / VERSION / ARCH
        cls.key_hash = cls.build(cls.bundle, ARCH)
        shutil.copyfile(ROOT / "deploy/bootstrap.sh", cls.root / "published" / "install.sh")

    @classmethod
    def build(cls, target, architecture):
        return subprocess.check_output(
            [sys.executable, str(ROOT / "deploy/build_preflight_bundle.py"), "--output", str(target),
             "--version", VERSION, "--architecture", architecture, "--private-key", str(cls.private)],
            text=True,
        ).strip()

    def invoke(self, source, *, version=VERSION, key=None, remote=False, env=None, proxy="existing"):
        command = ["bash", str(ROOT / "deploy/bootstrap.sh"), "--source" if remote else "--bundle",
                   str(source), "--version", version, "--key-sha256", key or self.key_hash,
                   "--", "--offline", "--proxy", proxy, "--json"]
        return subprocess.run(command, text=True, encoding="utf-8", errors="replace", capture_output=True,
                              timeout=60, env=env)

    def copy_bundle(self, name):
        target = self.root / name
        target.mkdir()
        for filename in FILES:
            shutil.copyfile(self.bundle / filename, target / filename)
        return target

    @contextmanager
    def publication(self):
        class Handler(SimpleHTTPRequestHandler):
            def __init__(handler, *args, **kwargs):
                super().__init__(*args, directory=self.root / "published", **kwargs)

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as http:
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.load_cert_chain(self.certificate, self.private)
            http.socket = tls.wrap_socket(http.socket, server_side=True)
            worker = threading.Thread(target=http.serve_forever, daemon=True)
            worker.start()
            env = dict(os.environ, CURL_CA_BUNDLE=str(self.certificate), NO_PROXY="127.0.0.1", no_proxy="127.0.0.1")
            try:
                yield f"https://127.0.0.1:{http.server_port}", env
            finally:
                http.shutdown()
                worker.join(timeout=5)

    def assert_rejected_before_payload(self, result, reason):
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn(reason, result.stderr)
        self.assertNotIn("预检包签名通过", result.stderr)
        self.assertEqual(result.stdout, "")

    def test_valid_bundle_reports_actual_host_and_offline_limits(self):
        result = self.invoke(self.bundle)
        self.assertEqual(result.returncode, 2, result.stderr + result.stdout)
        report = json.loads(result.stdout)
        self.assertEqual(report["系统"]["status"], "PASS")
        self.assertEqual(report["同步端口"]["status"], "PASS")
        self.assertEqual(report["现有反代"]["status"], "PENDING")
        self.assertEqual(report["R2"]["status"], "PENDING")
        self.assertNotIn("HTTP 端口", report)
        print("ZapIME verified Linux preflight sample: " + json.dumps(report, ensure_ascii=False), flush=True)

    def test_https_verified_download_and_missing_release(self):
        with self.publication() as (origin, env):
            result = self.invoke(origin, remote=True, env=env)
            self.assertEqual(result.returncode, 2, result.stderr)
            self.assertEqual(json.loads(result.stdout)["发布源"]["status"], "PASS")
            failure = self.invoke(origin, version="0.1.0-absent", remote=True, env=env)
            self.assert_rejected_before_payload(failure, "下载 release-public.pem 失败")

    def test_one_line_entry_checks_download_before_running_shell(self):
        with self.publication() as (origin, env):
            entry = ["bash", "-c", SHORT_ENTRY, "test"]
            failed = subprocess.run(entry + [origin + "/missing.sh"], capture_output=True, env=env, timeout=15)
            self.assertNotEqual(failed.returncode, 0)
            self.assertEqual(failed.stdout, b"")
            completed = subprocess.run(
                entry + [origin + "/install.sh", "--source", origin, "--version", VERSION,
                         "--key-sha256", self.key_hash, "--", "--offline", "--proxy", "existing", "--json"],
                capture_output=True, env=env, timeout=30,
            )
            self.assertEqual(completed.returncode, 2, completed.stderr)
            self.assertEqual(json.loads(completed.stdout)["发布源"]["status"], "PASS")

    def test_unknown_key_and_changed_payload_are_rejected(self):
        self.assertEqual(self.key_hash, hashlib.sha256((self.bundle / FILES[2]).read_bytes()).hexdigest())
        self.assert_rejected_before_payload(self.invoke(self.bundle, key="0" * 64), "公钥与可信指纹不符")
        altered = self.copy_bundle("altered")
        (altered / FILES[0]).write_bytes(b"invalid archive")
        self.assert_rejected_before_payload(self.invoke(altered), "预检包签名不符")
        bad_sig = self.copy_bundle("bad-signature")
        (bad_sig / FILES[1]).write_bytes(b"invalid signature")
        self.assert_rejected_before_payload(self.invoke(bad_sig), "预检包签名不符")

    def test_wrong_signed_version_and_architecture_are_rejected(self):
        foreign = self.root / "other-architecture"
        self.build(foreign, "arm64" if ARCH == "amd64" else "amd64")
        for bundle, version in ((self.bundle, "0.1.0-wrong"), (foreign, VERSION)):
            self.assert_rejected_before_payload(self.invoke(bundle, version=version), "版本或架构不符")

    def test_port_conflict_is_fatal_without_stopping_the_listener(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as original:
            original.bind(("127.0.0.1", 8787))
            original.listen()
            result = self.invoke(self.bundle)
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertEqual(json.loads(result.stdout)["同步端口"]["status"], "FAIL")
            with socket.create_connection(("127.0.0.1", 8787), timeout=3):
                pass


if __name__ == "__main__":
    unittest.main(verbosity=2)
