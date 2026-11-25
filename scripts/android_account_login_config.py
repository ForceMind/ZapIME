#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Generate public deployment configuration and the exact App Link for an APK."""

import argparse
import json
from pathlib import Path
import re
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET


ANDROID = "{http://schemas.android.com/apk/res/android}"
FIELDS = ("issuer", "authorization", "token", "callback", "resource", "sync_server", "client_id")


def origin(address):
    url = urlsplit(address)
    if (
        url.scheme != "https"
        or not address.startswith("https://")
        or not url.hostname
        or not re.fullmatch(r"[a-z0-9.-]+(?::[1-9][0-9]{0,4})?", url.netloc)
        or url.port == 0
        or not re.fullmatch(r"[a-zA-Z0-9._~/-]*", url.path)
        or any(segment in {".", ".."} for segment in url.path.split("/"))
        or "//" in url.path
        or "?" in address
        or "#" in address
    ):
        raise ValueError("Expected an exact HTTPS endpoint")
    return url.hostname, url.port or 443


def load(path):
    with path.open("rb") as source:
        raw = source.read(8193)
    if len(raw) > 8192:
        raise ValueError("Android account login configuration exceeds limit")
    config = json.loads(raw)
    if (
        not isinstance(config, dict)
        or set(config) != set(FIELDS)
        or any(not isinstance(value, str) or not re.fullmatch(r"[!-~]{1,1024}", value) for value in config.values())
    ):
        raise ValueError("Invalid Android account login configuration")
    endpoint_origin = {key: origin(config[key]) for key in FIELDS if key != "client_id"}
    callback = urlsplit(config["callback"])
    server = urlsplit(config["sync_server"])
    if (
        endpoint_origin["issuer"] != endpoint_origin["authorization"]
        or endpoint_origin["issuer"] != endpoint_origin["token"]
        or endpoint_origin["resource"] != endpoint_origin["sync_server"]
        or server.path not in {"", "/"}
        or callback.path in {"", "/"}
        or callback.port is not None
        or "." not in callback.hostname
        or len(config["client_id"]) > 255
    ):
        raise ValueError("Invalid Android account login deployment")
    return config


def generate(manifest_path, output_manifest, generated_java, config):
    if config is None:
        output_manifest.write_bytes(manifest_path.read_bytes())
        expression = "return null;"
    else:
        ET.register_namespace("android", ANDROID[1:-1])
        document = ET.parse(manifest_path)
        document.getroot().insert(1, ET.Element(
            "uses-permission", {ANDROID + "name": "android.permission.ACCESS_NETWORK_STATE"}
        ))
        app = document.find("application")
        ET.SubElement(app, "activity", {
            ANDROID + "name": ".AccountSyncActivity",
            ANDROID + "exported": "false",
            ANDROID + "launchMode": "singleTop",
        })
        activity = ET.SubElement(app, "activity", {
            ANDROID + "name": ".AccountLoginCallbackActivity",
            ANDROID + "exported": "true",
            ANDROID + "noHistory": "true",
        })
        view = ET.SubElement(activity, "intent-filter", {ANDROID + "autoVerify": "true"})
        ET.SubElement(view, "action", {ANDROID + "name": "android.intent.action.VIEW"})
        for category in ("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"):
            ET.SubElement(view, "category", {ANDROID + "name": category})
        callback = urlsplit(config["callback"])
        ET.SubElement(view, "data", {
            ANDROID + "scheme": "https", ANDROID + "host": callback.hostname, ANDROID + "path": callback.path,
        })
        document.write(output_manifest, encoding="utf-8", xml_declaration=True)
        arguments = ", ".join(json.dumps(config[key], ensure_ascii=True) for key in FIELDS)
        expression = (
            f"try {{ return new AccountLoginProtocol.Config({arguments}); }} "
            "catch (SyncHttp.Failure impossible) { throw new IllegalStateException(\"invalid_account_login_config\"); }"
        )
    generated_java.parent.mkdir(parents=True, exist_ok=True)
    generated_java.write_text(
        "// SPDX-License-Identifier: GPL-3.0-only\n"
        "package org.zapime.android;\n"
        "final class AccountLoginDeployment {\n"
        f"    static AccountLoginProtocol.Config config() {{ {expression} }}\n"
        "    private AccountLoginDeployment() {}\n"
        "}\n",
        encoding="utf-8",
    )


def main():
    args = argparse.ArgumentParser()
    args.add_argument("manifest", type=Path)
    args.add_argument("output_manifest", type=Path)
    args.add_argument("java", type=Path)
    args.add_argument("--config", type=Path)
    options = args.parse_args()
    try:
        config = load(options.config) if options.config is not None else None
        generate(options.manifest, options.output_manifest, options.java, config)
    except (ValueError, OSError, ET.ParseError):
        args.error("Cannot generate the Android account login configuration")


if __name__ == "__main__":
    main()
