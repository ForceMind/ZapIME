#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Configure account login and Console from the installer's private terminal.

The calling installer holds its installation lock. No provider account,
Tunnel, password reset request, database, or deployment is created here.
The provider must issue RFC9068 RS256 access tokens for the configured
resource/client, with zapime.device_enrollment scope and at most five minutes
of validity. Its browser client must support Authorization Code + PKCE S256.
"""

import argparse
from contextlib import contextmanager
import getpass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import stat
import sys
import tempfile
import urllib.request
import uuid
import warnings

sys.path.insert(0, str(Path(__file__).resolve().parent))
import oidc_setup as oidc
from android_account_login_config import origin as https_origin

MAX_PLAN = 128 * 1024
KEY_RECEIPT = "console-key.json"
PENDING = "console-setup.pending.json"


class Terminal:
    def ask(self, prompt, *, secret=False):
        if secret:
            if not sys.stdin.isatty():
                with open("/dev/tty", "r", encoding="utf-8"):
                    pass  # Fail instead of falling back to echoed credentials.
            with warnings.catch_warnings():
                warnings.simplefilter("error", getpass.GetPassWarning)
                value = getpass.getpass(prompt, stream=sys.stdout)
        elif sys.stdin.isatty():
            value = input(prompt)
        else:
            with open("/dev/tty", "r", encoding="utf-8") as source, open("/dev/tty", "w", encoding="utf-8") as output:
                print(prompt, end="", file=output, flush=True)
                value = source.readline()
                if value == "":
                    raise EOFError("terminal closed")
                value = value.rstrip("\r\n")
        if len(value) > 4096 or any(not c.isprintable() for c in value):
            raise ValueError("terminal input invalid")
        return value


def dotenv_decode(value):
    value = value.strip()
    if not value.startswith("'"):
        if value.startswith('"'):
            return json.loads(value)
        return value.split(" #", 1)[0].rstrip()
    # compose-go single-quoted values preserve backslashes except escaped '.
    quoted = re.fullmatch(r"'((?:[^'\\]|\\.)*)'\s*", value)
    if quoted is None:
        raise ValueError("dotenv quoted value invalid")
    return re.sub(r"\\(.)", lambda token: "'" if token.group(1) == "'" else token.group(0), quoted.group(1))


def dotenv_quote(value):
    if not isinstance(value, str) or len(value) > 8192 or any(not c.isprintable() for c in value):
        raise ValueError("dotenv value invalid")
    encoded = "'" + value.replace("'", "\\'") + "'"
    if dotenv_decode(encoded) != value:
        raise ValueError("dotenv value cannot be represented without changing it")
    return encoded


def settings(root):
    lines, _ = oidc.read_env(root / "config.env")
    values = {}
    for line in lines:
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key == "ZAPIME_HOST" or key.startswith(("ZAPIME_OIDC_", "ZAPIME_CONSOLE_")):
            if key in values:
                raise ValueError("duplicate managed configuration")
            values[key] = dotenv_decode(value)
    return lines, values


def exact_uuid(value):
    parsed = uuid.UUID(value)
    if str(parsed) != value or parsed.int == 0:
        raise ValueError("invalid existing account")
    return value


def private_json(path, limit=MAX_PLAN):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or (os.name == "posix" and (info.st_uid != 0 or info.st_mode & 0o077)):
        raise ValueError("private state permissions invalid")
    return json.loads(oidc.read_limited(path, limit))


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def create_only(path, raw, mode, gid=0):
    descriptor, temporary = tempfile.mkstemp(prefix=".console-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            os.fchmod(output.fileno(), mode)
            os.fchown(output.fileno(), 0, gid)
            output.write(raw)
            output.flush()
            os.fsync(output.fileno())
        os.link(temporary, path)  # Atomic, no overwrite, including symlinks.
        sync_directory(path.parent)
    finally:
        Path(temporary).unlink(missing_ok=True)


def master_key(root, values):
    receipt_path = root / KEY_RECEIPT
    receipt = private_json(receipt_path, 8192) if receipt_path.exists() or receipt_path.is_symlink() else None
    configured = bool(values.get("ZAPIME_CONSOLE_ORIGIN") or values.get("ZAPIME_CONSOLE_MASTER_KEY_FILE") or values.get("ZAPIME_CONSOLE_SECRET_DIR"))
    source = values.get("ZAPIME_CONSOLE_SECRET_DIR") or (receipt or {}).get("source") or str(root / "console-secrets")
    wire = values.get("ZAPIME_CONSOLE_MASTER_KEY_FILE") or (receipt or {}).get("file") or "/run/zapime-console/master.key"
    container_file = PurePosixPath(wire)
    if (not Path(source).is_absolute() or container_file.parent != PurePosixPath("/run/zapime-console")
            or not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", container_file.name)):
        raise ValueError("console key path invalid")
    folder = Path(source)
    name = folder / container_file.name
    if receipt is not None and (set(receipt) != {"format", "source", "file", "sha256"} or receipt["format"] != 1
                                or receipt["source"] != source or receipt["file"] != wire):
        raise ValueError("console key receipt conflicts with configuration")
    if not name.exists() and not name.is_symlink() and (configured or receipt is not None):
        raise ValueError("existing console master key missing; restore the original key")
    created_directory = False
    try:
        folder.mkdir(mode=0o750)
        created_directory = True
    except FileExistsError:
        pass
    if created_directory:
        os.chown(folder, 0, 65532)
        os.chmod(folder, 0o750)
    info = folder.lstat()
    traversable = bool(info.st_mode & 0o001 or info.st_gid == 65532 and info.st_mode & 0o010)
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022 or not traversable:
        raise ValueError("console key directory permissions invalid; existing permissions retained")
    if not name.exists() and not name.is_symlink():
        create_only(name, secrets.token_bytes(32), 0o640, 65532)
    info = name.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_gid != 65532 or info.st_mode & 0o777 != 0o640:
        raise ValueError("console key permissions invalid; existing permissions retained")
    raw = oidc.read_limited(name, 32)
    if len(raw) != 32:
        raise ValueError("console master key length invalid")
    digest = hashlib.sha256(raw).hexdigest()
    if receipt is not None and receipt["sha256"] != digest:
        raise ValueError("console master key differs from its receipt")
    if receipt is None:
        # Publish the key first. An interrupted receipt write can adopt that
        # same valid file; a later missing key with a receipt fails closed.
        create_only(receipt_path, oidc.json_bytes({"format": 1, "source": source, "file": wire, "sha256": digest}), 0o600)
    return {"ZAPIME_CONSOLE_SECRET_DIR": source, "ZAPIME_CONSOLE_MASTER_KEY_FILE": wire}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        raise ValueError("JWKS redirect refused")


def compatible_jwks(raw):
    document = json.loads(raw)
    if not isinstance(document, dict) or set(document) != {"keys"} or not isinstance(document["keys"], list) or not 1 <= len(document["keys"]) <= 64:
        raise ValueError("JWKS shape invalid")
    selected, seen = [], set()
    for key in document["keys"]:
        if not isinstance(key, dict) or set(key) & {"d", "p", "q", "dp", "dq", "qi", "oth", "k"}:
            raise ValueError("JWKS contains non-public key material")
        kid = oidc.identifier(key.get("kid"))
        if kid in seen:
            raise ValueError("JWKS has duplicate key IDs")
        seen.add(kid)
        if key.get("kty") != "RSA" or key.get("alg", "RS256") != "RS256" or key.get("use", "sig") != "sig":
            continue
        operations = key.get("key_ops", ["verify"])
        if not isinstance(operations, list) or not all(isinstance(item, str) for item in operations):
            raise ValueError("JWKS key operations invalid")
        if "verify" not in operations or "sign" in operations:
            continue
        # x5c/thumbprints and provider-specific public metadata are not trust
        # inputs. The pinned n/e still pass the unchanged strict validator.
        selected.append({field: key[field] for field in ("kty", "kid", "alg", "use", "n", "e") if field in key})
    if not 1 <= len(selected) <= 16:
        raise ValueError("JWKS contains no bounded RS256 signing key set")
    return oidc.json_bytes({"keys": selected})


def download_jwks(address):
    https_origin(address)
    opener = urllib.request.build_opener(NoRedirect())
    request = urllib.request.Request(address, headers={"Accept": "application/json"})
    with opener.open(request, timeout=8) as response:
        raw = response.read(65537)
        if response.status != 200 or not raw or len(raw) > 65536:
            raise ValueError("JWKS response invalid")
    return compatible_jwks(raw)


def selected_account(root, terminal, require_existing):
    binding_path = root / "oidc/binding.json"
    if binding_path.exists() or binding_path.is_symlink():
        binding = private_json(binding_path, 4096)
        if set(binding) != {"issuer", "subject", "account"}:
            raise ValueError("OIDC binding invalid")
        exact_uuid(binding["account"])
        oidc.identifier(binding["subject"])
        mode = (root / "oidc/binding-mode").read_text().strip()
        if mode not in {"bind", "bootstrap"}:
            raise ValueError("OIDC binding mode invalid")
        return binding["account"], binding
    owner = root / "owner.json"
    if owner.exists() or owner.is_symlink():
        account = exact_uuid(private_json(owner, 8192)["account_id"])
        print("Existing server owner account: " + account, flush=True)
        if terminal.ask("Use the existing server owner account for sign-in? [y/N]: ").lower() != "y":
            raise ValueError("existing account binding not confirmed")
        return account, None
    if require_existing or (root / "current").exists() or (root / "current").is_symlink():
        raise ValueError("existing installation requires a trusted account UUID; no new account selected")
    return "", None


@contextmanager
def staged_plan(root, plan, signer):
    if set(plan) != {"format", "config", "subject", "jwks", "account", "client_secret", "reset_endpoint", "reset_token", "signer"} or plan["format"] != 1:
        raise ValueError("pending setup invalid")
    if signer and plan["signer"] and signer != plan["signer"]:
        raise ValueError("pending APK signer differs")
    for key in ("subject", "jwks", "account", "client_secret", "reset_endpoint", "reset_token", "signer"):
        if not isinstance(plan[key], str):
            raise ValueError("pending setup field invalid")
    effective_signer = signer or plan["signer"]
    if effective_signer and not re.fullmatch(r"[0-9a-fA-F]{64}", effective_signer.replace(":", "")):
        raise ValueError("APK signer invalid")
    with tempfile.TemporaryDirectory(prefix=".console-input-", dir=root) as temporary:
        temporary = Path(temporary)
        config, jwks, subject = (temporary / name for name in ("android.json", "jwks.json", "subject"))
        config.write_bytes(oidc.json_bytes(plan["config"]))
        jwks.write_bytes(plan["jwks"].encode())
        subject.write_text(plan["subject"], encoding="utf-8")
        android = oidc.load_android_config(config)
        oidc.public_keys(jwks)
        oidc.identifier(plan["subject"])
        if plan["account"]:
            exact_uuid(plan["account"])
        if bool(plan["reset_endpoint"]) != bool(plan["reset_token"]):
            raise ValueError("password reset adapter incomplete")
        if plan["reset_endpoint"]:
            https_origin(plan["reset_endpoint"])
        # Validate encoding before any OIDC configuration becomes visible.
        for value in (plan["client_secret"], plan["reset_token"]):
            dotenv_quote(value)
        yield android, config, jwks, subject


def publish(root, plan, signer, require_existing=False):
    with staged_plan(root, plan, signer) as (android, config, jwks, subject):
        if (not plan["account"] and not (root / "oidc/binding.json").exists()
                and (require_existing or (root / "current").exists() or (root / "current").is_symlink()
                     or (root / "owner.json").exists() or (root / "owner.json").is_symlink())):
            raise ValueError("pending bootstrap cannot adopt an existing account")
        _, before = settings(root)
        keys = master_key(root, before)
        oidc.prepare(root, config, jwks, subject, signer or plan["signer"], plan["account"])
        updates = {**keys, "ZAPIME_CONSOLE_ORIGIN": android["sync_server"].rstrip("/"),
                   "ZAPIME_CONSOLE_AUTHORIZATION_ENDPOINT": android["authorization"],
                   "ZAPIME_CONSOLE_TOKEN_ENDPOINT": android["token"], "ZAPIME_CONSOLE_CLIENT_SECRET": plan["client_secret"],
                   "ZAPIME_CONSOLE_ADMINS": json.dumps([{"issuer": android["issuer"], "subject": plan["subject"]}], separators=(",", ":")),
                   "ZAPIME_CONSOLE_PASSWORD_RESET_ENDPOINT": plan["reset_endpoint"], "ZAPIME_CONSOLE_PASSWORD_RESET_TOKEN": plan["reset_token"]}
        lines, _ = settings(root)
        lines = [line for line in lines if line.split("=", 1)[0].strip() not in updates]
        lines.extend(name + "=" + dotenv_quote(value) for name, value in updates.items())
        oidc.atomic_write(root / "config.env", ("\n".join(lines) + "\n").encode())
    (root / PENDING).unlink(missing_ok=True)
    sync_directory(root)


def configure(root, signer="", terminal=None, require_existing=False):
    terminal = terminal or Terminal()
    if root.is_symlink() or not root.is_dir():
        raise ValueError("installation root invalid")
    root = root.absolute()
    root_info = root.stat()
    config_info = (root / "config.env").lstat()
    if (not stat.S_ISREG(config_info.st_mode) or os.name == "posix" and
            (root_info.st_uid != 0 or root_info.st_mode & 0o022 or config_info.st_uid != 0 or config_info.st_mode & 0o077)):
        raise ValueError("private installation permissions invalid")
    _, values = settings(root)
    host = values.get("ZAPIME_HOST", "")
    origin = "https://" + host
    https_origin(origin)
    if not host or "/" in host:
        raise ValueError("installation host invalid")
    print("Console callback: " + origin + "/console/auth/callback", flush=True)
    pending = root / PENDING
    if pending.exists() or pending.is_symlink():
        publish(root, private_json(pending), signer, require_existing)
        print("Console configuration [READY]", flush=True)
        return
    # Missing keys never cause a new key to replace previously enabled custody.
    if (any(values.get(key) for key in ("ZAPIME_CONSOLE_ORIGIN", "ZAPIME_CONSOLE_MASTER_KEY_FILE", "ZAPIME_CONSOLE_SECRET_DIR"))
            or (root / KEY_RECEIPT).exists() or (root / KEY_RECEIPT).is_symlink()):
        master_key(root, values)
    account, binding = selected_account(root, terminal, require_existing)
    android_path = root / "oidc/android.json"
    android = oidc.load_android_config(android_path) if android_path.exists() else None
    if android is not None and binding is not None:
        if android["issuer"] != binding["issuer"] or android["sync_server"].rstrip("/") != origin:
            raise ValueError("existing identity differs from server")
        jwks_path = Path(values.get("ZAPIME_OIDC_PUBLIC_JWKS", ""))
        public = oidc.public_keys(jwks_path)
        subject = binding["subject"]
        complete = (values.get("ZAPIME_CONSOLE_ORIGIN") == origin
                    and values.get("ZAPIME_CONSOLE_AUTHORIZATION_ENDPOINT") == android["authorization"]
                    and values.get("ZAPIME_CONSOLE_TOKEN_ENDPOINT") == android["token"]
                    and values.get("ZAPIME_OIDC_ISSUER") == android["issuer"]
                    and values.get("ZAPIME_OIDC_CLIENT_ID") == android["client_id"]
                    and values.get("ZAPIME_OIDC_AUDIENCE") == android["resource"]
                    and values.get("ZAPIME_OIDC_JWKS_FILE") == "/run/zapime-oidc.json")
        if complete:
            admins = json.loads(values.get("ZAPIME_CONSOLE_ADMINS", "[]"))
            if not isinstance(admins, list) or {"issuer": binding["issuer"], "subject": subject} not in admins:
                raise ValueError("existing administrator policy differs")
            for admin in admins:
                if not isinstance(admin, dict) or set(admin) != {"issuer", "subject"}:
                    raise ValueError("existing administrator policy invalid")
                https_origin(admin["issuer"])
                oidc.identifier(admin["subject"])
            if bool(values.get("ZAPIME_CONSOLE_PASSWORD_RESET_ENDPOINT")) != bool(values.get("ZAPIME_CONSOLE_PASSWORD_RESET_TOKEN")):
                raise ValueError("existing password reset adapter incomplete")
            if values.get("ZAPIME_CONSOLE_PASSWORD_RESET_ENDPOINT"):
                https_origin(values["ZAPIME_CONSOLE_PASSWORD_RESET_ENDPOINT"])
            if signer:
                with tempfile.TemporaryDirectory(prefix=".console-signer-", dir=root) as folder:
                    source = Path(folder) / "subject"
                    source.write_text(subject, encoding="utf-8")
                    oidc.prepare(root, android_path, jwks_path, source, signer, account)
            print("Console configuration [READY]", flush=True)
            return
    else:
        print("Provider requires RFC9068 RS256 access tokens, zapime.device_enrollment scope, at most 5-minute validity, and browser Code + PKCE S256.", flush=True)
        issuer = binding["issuer"] if binding is not None else terminal.ask("OIDC issuer HTTPS URL: ").strip()
        android = {"issuer": issuer,
                   "authorization": terminal.ask("OIDC authorization HTTPS endpoint: ").strip(),
                   "token": terminal.ask("OIDC token HTTPS endpoint: ").strip(),
                   "resource": terminal.ask("OIDC resource/audience HTTPS URL: ").strip(),
                   "client_id": terminal.ask("OIDC client ID: ").strip(),
                   "sync_server": origin, "callback": origin + "/account-login/callback"}
        public = download_jwks(terminal.ask("Public JWKS HTTPS URL: ").strip())
        subject = binding["subject"] if binding is not None else terminal.ask("Verified administrator subject: ").strip()
    client_secret = terminal.ask("OIDC client secret (Enter for public PKCE client): ", secret=True)
    reset_endpoint = reset_token = ""
    if terminal.ask("Configure password reset adapter? [y/N]: ").lower() == "y":
        reset_endpoint = terminal.ask("Password reset adapter HTTPS endpoint: ").strip()
        reset_token = terminal.ask("Password reset adapter bearer token: ", secret=True)
    plan = {"format": 1, "config": android, "subject": subject, "jwks": public.decode(), "account": account,
            "client_secret": client_secret, "reset_endpoint": reset_endpoint, "reset_token": reset_token, "signer": signer}
    with staged_plan(root, plan, signer):
        pass
    create_only(pending, oidc.json_bytes(plan), 0o600)
    publish(root, plan, signer, require_existing)
    print("Console configuration [READY]", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--android-signer", default="")
    parser.add_argument("--require-existing-account", action="store_true")
    args = parser.parse_args()
    try:
        if sys.platform != "linux" or os.geteuid() != 0:
            raise ValueError("root Linux installer required")
        configure(args.root, args.android_signer, require_existing=args.require_existing_account)
    except (OSError, ValueError, TypeError, KeyError, EOFError, KeyboardInterrupt, getpass.GetPassWarning) as failure:
        print("Console configuration rejected (" + type(failure).__name__ + "). Existing key/configuration retained; check inputs or restore the original key.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
