#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Prepare an operator-verified OIDC issuer and Android App Link."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.parse import urlsplit
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from android_account_login_config import load as load_android_config

RELATION = "delegate_permission/common.handle_all_urls"


def read_limited(path, limit):
    with path.open("rb") as source:
        raw = source.read(limit + 1)
    if not raw or len(raw) > limit:
        raise ValueError("configuration file size invalid")
    return raw


def json_bytes(value):
    return (json.dumps(value, separators=(",", ":"), sort_keys=True) + "\n").encode()


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[!-~]{1,255}", value):
        raise ValueError("invalid OIDC identifier")
    return value


def public_keys(path):
    document = json.loads(read_limited(path, 65536))
    keys = document.get("keys") if isinstance(document, dict) and set(document) == {"keys"} else None
    if not isinstance(keys, list) or not 1 <= len(keys) <= 16:
        raise ValueError("invalid JWKS")
    seen = set()
    for key in keys:
        if (not isinstance(key, dict) or key.get("kty") != "RSA" or key.get("alg", "RS256") != "RS256"
                or key.get("use", "sig") != "sig" or not set(key) <= {"kty", "kid", "alg", "use", "n", "e"}):
            raise ValueError("JWKS accepts RSA public signing keys only")
        kid = identifier(key.get("kid"))
        if kid in seen:
            raise ValueError("duplicate key ID")
        seen.add(kid)
        components = [key.get(name) for name in ("n", "e")]
        if not all(isinstance(v, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,1024}", v) for v in components):
            raise ValueError("invalid RSA public numbers")
        modulus, exponent = (int.from_bytes(base64.b64decode(v + "=" * (-len(v) % 4), altchars=b"-_", validate=True), "big")
                             for v in components)
        if modulus.bit_length() < 2048 or exponent < 3 or exponent % 2 == 0:
            raise ValueError("invalid RSA public key")
    return json_bytes(document)


def atomic_write(path, raw, mode=0o600):
    descriptor, temp = tempfile.mkstemp(dir=path.parent, prefix=".oidc-")
    try:
        with os.fdopen(descriptor, "wb") as output:
            os.fchmod(output.fileno(), mode)
            output.write(raw)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        Path(temp).unlink(missing_ok=True)


def read_env(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    values = dict(line.split("=", 1) for line in lines if "=" in line and not line.startswith("#"))
    return lines, values


def prepare(root, config, jwks, subject_file, signer, existing_account):
    config = load_android_config(config)
    settings = root / "config.env"
    lines, values = read_env(settings)
    host = values.get("ZAPIME_HOST", "")
    if (not host or config["sync_server"].rstrip("/") != "https://" + host
            or urlsplit(config["callback"]).netloc != host):
        raise ValueError("sync and App Link must use configured HTTPS host")
    if not re.fullmatch(r"[A-Za-z0-9._:/@~+-]{1,255}", config["client_id"]):
        raise ValueError("invalid client ID")
    # Keep values directly usable in Compose dotenv without interpolation.
    subject = identifier(read_limited(subject_file, 1024).decode().strip())
    public = public_keys(jwks)
    state = root / "oidc"
    state.mkdir(mode=0o700, exist_ok=True)
    selected = state / "binding.json"
    if selected.exists():
        previous = json.loads(read_limited(selected, 4096))
        account = previous["account"]
        binding = {"issuer": config["issuer"], "subject": subject, "account": account}
        if previous != binding or (existing_account and account != existing_account):
            raise ValueError("OIDC account binding already exists")
    else:
        account = str(uuid.UUID(existing_account)) if existing_account else str(uuid.uuid4())
        if account == str(uuid.UUID(int=0)):
            raise ValueError("invalid account ID")
        binding = {"issuer": config["issuer"], "subject": subject, "account": account}
    public_path = state / ("jwks-" + hashlib.sha256(public).hexdigest() + ".json")
    assets = state / "assetlinks.json"
    links = None
    if signer:
        compact = signer.replace(":", "").upper()
        if not re.fullmatch(r"[0-9A-F]{64}", compact):
            raise ValueError("invalid APK signing fingerprint")
        fingerprint = ":".join(compact[i:i + 2] for i in range(0, 64, 2))
        links = [{"relation": [RELATION], "target": {"namespace": "android_app", "package_name": "org.zapime.android",
                  "sha256_cert_fingerprints": [fingerprint]}, "relation_extensions": {RELATION: {
                      "dynamic_app_link_components": [{"/": urlsplit(config["callback"]).path},
                                                      {"/": "*", "exclude": True}]}}}]
    updates = {"ZAPIME_OIDC_ISSUER": config["issuer"], "ZAPIME_OIDC_AUDIENCE": config["resource"],
               "ZAPIME_OIDC_CLIENT_ID": config["client_id"], "ZAPIME_OIDC_AUTO_ENROLL": "1",
               "ZAPIME_OIDC_PUBLIC_JWKS": str(public_path), "ZAPIME_OIDC_JWKS_FILE": "/run/zapime-oidc.json"}
    if links is not None or assets.exists():
        asset_data = json_bytes(links) if links is not None else read_limited(assets, 8192)
        asset_path = state / ("assetlinks-" + hashlib.sha256(asset_data).hexdigest() + ".json")
        atomic_write(asset_path, asset_data, 0o444)
        updates["ZAPIME_ASSETLINKS"] = str(asset_path)
    if not selected.exists():
        atomic_write(state / "binding-mode", b"bind\n" if existing_account else b"bootstrap\n")
        atomic_write(selected, json_bytes(binding))
    atomic_write(public_path, public, 0o444)
    atomic_write(state / "android.json", json_bytes(config))
    if links is not None:
        atomic_write(assets, json_bytes(links), 0o444)
    new_lines = [line for line in lines if line.split("=", 1)[0] not in updates]
    new_lines.extend(f"{name}={value}" for name, value in updates.items())
    atomic_write(settings, ("\n".join(new_lines) + "\n").encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("config", type=Path)
    parser.add_argument("jwks", type=Path)
    parser.add_argument("subject", type=Path)
    parser.add_argument("--signer")
    parser.add_argument("--account")
    args = parser.parse_args()
    try:
        prepare(args.root, args.config, args.jwks, args.subject, args.signer, args.account)
    except (OSError, ValueError, TypeError, KeyError) as failure:
        print(f"OIDC configuration rejected ({type(failure).__name__}). Check inputs.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
