#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Synthetic console-wizard tests. No provider, host or deployment is changed."""

import base64
from contextlib import redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import warnings
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "deploy"))
import console_setup as setup


def public_keys():
    modulus = base64.urlsafe_b64encode(((1 << 2047) + 65537).to_bytes(256, "big")).decode().rstrip("=")
    return {"keys": [{"kty": "RSA", "kid": "synthetic", "n": modulus, "e": "AQAB", "use": "sig", "alg": "RS256"}]}


class Answers:
    def __init__(self, values=()):
        self.values = iter(values)
        self.prompts = []

    def ask(self, prompt, *, secret=False):
        self.prompts.append((prompt, secret))
        try:
            return next(self.values)
        except StopIteration:
            raise AssertionError("unexpected interactive input requested") from None


class ConsoleWizardInputTests(unittest.TestCase):
    def test_dotenv_preserves_dollars_quotes_hash_and_spaces(self):
        for secret in ("", "synthetic$TOKEN", "a#b", " synthetic secret ", "it's synthetic", r"path\to\synthetic", 'a"b', "中英文"):
            with self.subTest(index=len(secret)):
                wire = setup.dotenv_quote(secret)
                self.assertTrue(wire.startswith("'") and wire.endswith("'"))
                self.assertEqual(setup.dotenv_decode(wire), secret)

    def test_dotenv_rejects_multiline_and_nonrepresentable_values(self):
        for secret in ("s\nINJECT=value", "s\rX", "s\x00X", "s\x1bX", "ending\\", "slash\\'quote"):
            with self.subTest(index=len(secret)), self.assertRaises(ValueError):
                setup.dotenv_quote(secret)

    def test_identifier_is_canonical_existing_uuid(self):
        value = "12345678-1234-4234-8234-123456789abc"
        self.assertEqual(setup.exact_uuid(value), value)
        for bad in (value.upper(), value.replace("-", ""), "00000000-0000-0000-0000-000000000000", "guess"):
            with self.assertRaises(ValueError):
                setup.exact_uuid(bad)

    def test_jwks_selects_rsa_public_metadata_without_weakening_trust(self):
        document = public_keys()
        document["keys"][0].update({"x5c": ["synthetic-public-certificate"], "key_ops": ["verify"], "x5t": "public"})
        document["keys"].append({"kty": "EC", "kid": "other-public", "crv": "P-256", "x": "public", "y": "public"})
        normalized = json.loads(setup.compatible_jwks(json.dumps(document).encode()))
        self.assertEqual(normalized, public_keys())

    def test_jwks_refuses_private_duplicate_empty_and_abnormal_data(self):
        cases = [{"keys": []}, {"keys": {}}, {"keys": [{"kty": "EC", "kid": "only-ec"}]},
                 {"keys": [public_keys()["keys"][0], public_keys()["keys"][0]]},
                 {"keys": [dict(public_keys()["keys"][0], d="synthetic-private")]},
                 {"keys": [dict(public_keys()["keys"][0], key_ops="verify")]}, {"keys": [None]}]
        for case in cases:
            with self.assertRaises(ValueError):
                setup.compatible_jwks(json.dumps(case).encode())

    def test_jwks_url_uses_https_and_redirects_are_refused(self):
        for url in ("http://login.example.test/keys", "https://name:secret@login.example.test/keys", "https://login.example.test/keys?token=synthetic"):
            with self.assertRaises(ValueError):
                setup.download_jwks(url)
        with self.assertRaises(ValueError):
            setup.NoRedirect().redirect_request(None, None, None, None, None, None)

    def test_secret_prompt_refuses_getpass_echo_fallback(self):
        def fallback(*_args, **_kwargs):
            warnings.warn("synthetic echo unavailable", setup.getpass.GetPassWarning)
            raise AssertionError("echo fallback should never run")
        with patch.object(sys.stdin, "isatty", return_value=True), patch.object(setup.getpass, "getpass", side_effect=fallback):
            with self.assertRaises(setup.getpass.GetPassWarning):
                setup.Terminal().ask("Synthetic hidden value: ", secret=True)


@unittest.skipUnless(os.name == "posix" and os.geteuid() == 0, "requires private root-owned Linux fixture, never a deployment")
class ConsoleWizardPrivateFilesTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="zapime-console-setup-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "config.env").write_text("ZAPIME_HOST=sync.example.test\nPOSTGRES_PASSWORD=synthetic-preserved\n")
        os.chmod(self.root / "config.env", 0o600)
        self.outputs = io.StringIO()
        self.network = patch.object(setup, "download_jwks", return_value=setup.oidc.json_bytes(public_keys()))
        self.network.start()
        self.addCleanup(self.network.stop)

    def first_answers(self, *, confirm=False, secret="synthetic$#'secret"):
        values = ["https://login.example.test", "https://login.example.test/authorize", "https://login.example.test/token",
                  "https://sync.example.test/device-enrollment", "synthetic-client", "https://login.example.test/keys",
                  "synthetic-admin", secret, "n"]
        return Answers((["y"] if confirm else []) + values)

    def run_setup(self, terminal, **kwargs):
        with redirect_stdout(self.outputs):
            setup.configure(self.root, terminal=terminal, **kwargs)

    def test_fresh_setup_and_repeat_do_not_prompt_or_replace_key(self):
        answers = self.first_answers()
        self.run_setup(answers)
        key = self.root / "console-secrets/master.key"
        before = key.read_bytes()
        self.assertEqual(len(before), 32)
        self.assertEqual((key.stat().st_uid, key.stat().st_gid, key.stat().st_mode & 0o777), (0, 65532, 0o640))
        self.assertEqual((key.parent.stat().st_gid, key.parent.stat().st_mode & 0o777), (65532, 0o750))
        self.assertEqual(json.loads((self.root / setup.KEY_RECEIPT).read_text())["sha256"], hashlib.sha256(before).hexdigest())
        lines, values = setup.settings(self.root)
        self.assertIn("POSTGRES_PASSWORD=synthetic-preserved", lines)
        self.assertEqual(values["ZAPIME_CONSOLE_CLIENT_SECRET"], "synthetic$#'secret")
        self.assertEqual((self.root / "oidc/binding-mode").read_text(), "bootstrap\n")
        self.assertEqual(json.loads((self.root / "oidc/android.json").read_text())["callback"], "https://sync.example.test/account-login/callback")
        self.assertFalse((self.root / setup.PENDING).exists())
        self.assertNotIn("synthetic$#'secret", self.outputs.getvalue())
        self.run_setup(Answers())
        self.assertEqual(key.read_bytes(), before)

    def test_existing_owner_is_confirmed_and_bound_without_bootstrap(self):
        account = "12345678-1234-4234-8234-123456789abc"
        setup.oidc.atomic_write(self.root / "owner.json", setup.oidc.json_bytes({"account_id": account, "token": "synthetic-private"}))
        (self.root / "current").mkdir()
        self.run_setup(self.first_answers(confirm=True))
        self.assertEqual((self.root / "oidc/binding-mode").read_text(), "bind\n")
        self.assertEqual(json.loads((self.root / "oidc/binding.json").read_text())["account"], account)

    def test_existing_server_without_trusted_account_and_restore_fail_closed(self):
        (self.root / "current").mkdir()
        with self.assertRaises(ValueError):
            self.run_setup(Answers())
        (self.root / "current").rmdir()
        with self.assertRaises(ValueError):
            self.run_setup(Answers(), require_existing=True)
        self.assertFalse((self.root / "oidc").exists())

    def test_missing_key_after_origin_removed_is_never_regenerated(self):
        self.run_setup(self.first_answers())
        key = self.root / "console-secrets/master.key"
        key.unlink()
        config = self.root / "config.env"
        config.write_text("\n".join(line for line in config.read_text().splitlines() if not line.startswith("ZAPIME_CONSOLE_ORIGIN=")) + "\n")
        with self.assertRaises(ValueError):
            self.run_setup(Answers())
        self.assertFalse(key.exists())
        config.write_text("\n".join(line for line in config.read_text().splitlines() if not line.startswith("ZAPIME_CONSOLE_")) + "\n")
        with self.assertRaises(ValueError):
            self.run_setup(Answers())
        self.assertFalse(key.exists())

    def test_changed_key_is_rejected_without_rewriting_file_or_receipt(self):
        self.run_setup(self.first_answers())
        receipt = (self.root / setup.KEY_RECEIPT).read_bytes()
        key = self.root / "console-secrets/master.key"
        key.write_bytes(b"Z" * 32)
        with self.assertRaises(ValueError):
            self.run_setup(Answers())
        self.assertEqual(key.read_bytes(), b"Z" * 32)
        self.assertEqual((self.root / setup.KEY_RECEIPT).read_bytes(), receipt)

    def test_existing_config_missing_key_without_receipt_is_never_replaced(self):
        with (self.root / "config.env").open("a") as output:
            output.write("ZAPIME_CONSOLE_SECRET_DIR=" + setup.dotenv_quote(str(self.root / "old-secrets")) + "\n")
        with self.assertRaises(ValueError):
            self.run_setup(Answers())
        self.assertFalse((self.root / "old-secrets/master.key").exists())

    def test_shared_existing_directory_and_key_permissions_are_never_changed(self):
        shared = self.root / "synthetic-shared"
        shared.mkdir(mode=0o755)
        os.chmod(shared, 0o755)
        key = shared / "master.key"
        key.write_bytes(b"S" * 32)
        os.chmod(key, 0o640)
        os.chown(key, 0, 0)
        with (self.root / "config.env").open("a") as output:
            output.write("ZAPIME_CONSOLE_SECRET_DIR=" + setup.dotenv_quote(str(shared)) + "\n")
        before = (shared.stat().st_mode, shared.stat().st_gid, key.stat().st_mode, key.stat().st_gid)
        with self.assertRaises(ValueError):
            self.run_setup(Answers())
        after = (shared.stat().st_mode, shared.stat().st_gid, key.stat().st_mode, key.stat().st_gid)
        self.assertEqual(before, after)
        self.assertEqual(key.read_bytes(), b"S" * 32)

    def test_crash_after_key_before_receipt_reuses_same_bytes_without_input(self):
        original = setup.create_only
        def fail_receipt(path, *args, **kwargs):
            if path.name == setup.KEY_RECEIPT:
                raise OSError("synthetic receipt failure")
            return original(path, *args, **kwargs)
        with patch.object(setup, "create_only", side_effect=fail_receipt), self.assertRaises(OSError):
            self.run_setup(self.first_answers())
        key = self.root / "console-secrets/master.key"
        before = key.read_bytes()
        self.assertFalse((self.root / setup.KEY_RECEIPT).exists())
        self.assertTrue((self.root / setup.PENDING).exists())
        self.run_setup(Answers())
        self.assertEqual(key.read_bytes(), before)
        self.assertFalse((self.root / setup.PENDING).exists())

    def test_bad_inputs_never_leave_a_pending_plan_or_key(self):
        with self.assertRaises(ValueError):
            self.run_setup(self.first_answers(secret="nonrepresentable\\"))
        self.assertFalse((self.root / setup.PENDING).exists())
        self.assertFalse((self.root / "console-secrets").exists())


if __name__ == "__main__":
    unittest.main()
