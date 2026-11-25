# SPDX-License-Identifier: GPL-3.0-only
"""Real host TLS/Go/PG acceptance; never substitutes for Android system trust.

Set ZAPIME_HTTPS_TEST_BINARY and ZAPIME_HTTPS_TEST_DATABASE_URL explicitly to
run against an isolated fixture database. No database is cleared or deleted by
these tests. The owner/member/CA/root material is private and temporary.
"""
import http.client
import json
import os
from pathlib import Path
import socket
import ssl
import stat
import subprocess
import tempfile
import unittest
from urllib.parse import urlsplit

from fixtures.sync_https_server import Fixture, MAX_BOOTSTRAP, MAX_REQUEST, MAX_RESPONSE
from sync_service_smoke import manual_client_roundtrip


def request(origin, context, method, path, token=None, body=None, raw=None, decode=True):
    origin = urlsplit(origin)
    connection = http.client.HTTPConnection(origin.hostname, origin.port, timeout=10)
    try:
        # Only TCP routing is host-specific. Standard SSLContext verifies this
        # actual server certificate against IP SAN10.0.2.2 and the trusted CA.
        connection.sock = context.wrap_socket(socket.create_connection(("127.0.0.1", origin.port), 10),
                                              server_hostname=origin.hostname)
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        connection.request(method, path, body=raw if raw is not None else
                           (json.dumps(body).encode() if body is not None else None), headers=headers)
        response = connection.getresponse()
        contents = response.read(MAX_RESPONSE + 1)
        if len(contents) > MAX_RESPONSE:
            raise RuntimeError("host_fixture_response_too_large")
        return response.status, json.loads(contents) if decode else contents
    finally:
        connection.close()


@unittest.skipUnless(os.environ.get("ZAPIME_HTTPS_TEST_BINARY") and os.environ.get("ZAPIME_HTTPS_TEST_DATABASE_URL"),
                     "NOT RUN: explicit isolated Go binary/PG URL required")
class RealHttpsFixtureTests(unittest.TestCase):
    def test_automatic_shortcut_real_https_and_no_echo(self):
        root = Path(__file__).resolve().parents[1]
        probe = root / "target/debug/examples/manual_sync_fixture"
        with tempfile.TemporaryDirectory(prefix="automatic-https-", dir=root / "target") as temporary:
            work = Path(temporary)
            fixture = Fixture(work, Path(os.environ["ZAPIME_HTTPS_TEST_BINARY"]).resolve(),
                              os.environ["ZAPIME_HTTPS_TEST_DATABASE_URL"])

            def run(command, *args):
                result = subprocess.run([str(probe), command, str(work), *map(str, args)],
                                        check=True, capture_output=True, text=True, timeout=20)
                reports = [json.loads(line) for line in result.stdout.splitlines()]
                self.assertTrue(reports and all(report["status"] == "PASS" for report in reports), command)
                return reports

            def save(name, raw):
                path = work / name
                path.write_bytes(raw)
                return path

            with fixture:
                trusted = ssl.create_default_context(cafile=str(fixture.ca_file))

                def api(method, path, token=None, body=None, raw=None, decode=True):
                    return request(fixture.origin, trusted, method, path, token, body, raw, decode)

                status, credentials = api("GET", fixture.bootstrap_path)
                self.assertTrue(status == 200 and set(credentials) == {"owner", "member"}, "actual bootstrap")
                owner, member = credentials["owner"], credentials["member"]
                self.assertTrue(owner["device_id"] != member["device_id"]
                                and owner["account_id"] == member["account_id"], "independent devices")
                owner_token, member_token = owner["token"], member["token"]
                self.assertEqual(run("init-automatic", owner["account_id"])[-1]["pending"], 1)
                descriptor = json.loads((work / "descriptor.json").read_bytes())
                status, registered = api("PUT", "/v1/key-descriptor", owner_token, body=descriptor, decode=False)
                self.assertTrue(status == 200 and json.loads(registered)["descriptor"] == descriptor,
                                "server registered the exact key")
                status, member_key = api("GET", "/v1/key-descriptor", member_token, decode=False)
                self.assertTrue(status == 200 and json.loads(member_key) == json.loads(registered), "same member key")
                key_file = save("registered.json", registered)

                status, empty_page = api("GET", "/v1/changes?after=0", owner_token, decode=False)
                empty = json.loads(empty_page)
                self.assertTrue(status == 200 and empty["head"] == empty["next_cursor"] == 0
                                and empty["changes"] == [], "server starts empty")
                self.assertEqual(run("apply", "a", key_file, save("empty.json", empty_page))[-1]["cursor"], 0)
                packet, retry = work / "upload.json", work / "retry.json"
                self.assertEqual(run("stage-automatic", "a", key_file, packet)[-1]["pending"], 1)
                status, first = api("POST", "/v1/changes", owner_token, raw=packet.read_bytes(), decode=False)
                self.assertTrue(status == 200 and json.loads(first)["revision"] == 1, "first server receipt")
                # Discard the first response at the client. Reopen SQLite before retrying.
                run("stage-automatic", "a", key_file, retry)
                self.assertTrue(packet.read_bytes() == retry.read_bytes(), "retry reuses exact ciphertext")
                status, receipt = api("POST", "/v1/changes", owner_token, raw=retry.read_bytes(), decode=False)
                self.assertTrue(status == 200 and json.loads(receipt) == json.loads(first), "no duplicate revision")
                report = run("ack-automatic", "a", key_file, owner["device_id"], retry, save("receipt.json", receipt))
                self.assertTrue(report[0]["revision"] == 1 and report[-1]["pending"] == report[-1]["cursor"] == 0,
                                "persisted receipt leaves the pull cursor unchanged")

                for device, token, counts in (("b", member_token, (1, 0)), ("a", owner_token, (0, 1))):
                    status, page = api("GET", "/v1/changes?after=0", token, decode=False)
                    decoded = json.loads(page)
                    self.assertTrue(status == 200 and decoded["head"] == decoded["next_cursor"] == 1
                                    and len(decoded["changes"]) == 1, "one terminal server change")
                    report = run("apply", device, key_file, save(f"page-{device}.json", page))
                    self.assertEqual((report[0]["applied"], report[0]["echoed"]), counts)
                    self.assertTrue(report[-1]["pending"] == 0 and report[-1]["cursor"] == 1, "no echo upload")
                report = run("verify-automatic")[-1]
                self.assertTrue(report["records_each"] == 1 and report["pending_each"] == 0
                                and report["cursor_each"] == 1, "both reopened stores converge")
            report = fixture.safe_summary()
            self.assertTrue(report["service_revision"] == 1 and report["owner_session_valid"]
                            and report["member_session_valid"] and report["cleanup_completed"], "final server and TLS state")
            self.assertEqual(sum(entry["count"] for entry in report["requests"]
                                 if entry["method"] == "POST" and entry["path"] == "/v1/changes"), 2)
        print(json.dumps({"status": "PASS", "scope": "host_real_https_automatic_shortcut", "stores": 2,
                          "records_each": 1, "pending_each": 0, "server_revision": 1, "upload_attempts": 2,
                          "fixture_cleaned": not work.exists()}))

    def test_real_certificate_proxy_manual_client_and_resource_cleanup(self):
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix="https-host-acceptance-", dir=root / "target") as temporary:
            work = Path(temporary)
            fixture = Fixture(work, Path(os.environ["ZAPIME_HTTPS_TEST_BINARY"]).resolve(),
                              os.environ["ZAPIME_HTTPS_TEST_DATABASE_URL"])
            with fixture:
                self.assertTrue(fixture.ca_file.exists(), "temporary CA exists")
                self.assertTrue(all(stat.S_IMODE(path.stat().st_mode) == 0o600
                                    for path in fixture.ca_file.parent.iterdir()), "private file permissions")
                with self.assertRaises(ssl.SSLCertVerificationError):
                    request(fixture.origin, ssl.create_default_context(), "GET", "/v1/session")
                trusted = ssl.create_default_context(cafile=str(fixture.ca_file))
                self.assertTrue(trusted.check_hostname and trusted.verify_mode == ssl.CERT_REQUIRED,
                                "standard hostname and certificate checks")
                with self.assertRaises(ssl.SSLCertVerificationError):
                    request(fixture.bad_origin, trusted, "GET", fixture.bootstrap_path)
                status, credentials = request(fixture.origin, trusted, "GET", fixture.bootstrap_path)
                self.assertTrue(status == 200 and set(credentials) == {"owner", "member"}, "real bootstrap")
                self.assertTrue(len(json.dumps(credentials).encode()) < MAX_BOOTSTRAP, "bounded bootstrap")
                secrets = [fixture.bootstrap_path]
                for role in ("owner", "member"):
                    credential = credentials[role]
                    self.assertTrue(set(credential) == {"account_id", "device_id", "token", "expires_at"},
                                    "original credential shape")
                    secrets.extend(credential.values())
                    status, session = request(fixture.origin, trusted, "GET", "/v1/session", credential["token"])
                    self.assertTrue(status == 200 and session["role"] == role
                                    and session["account_id"] == credential["account_id"], "actual authenticated session")
                def api(method, path, token, body=None, raw=None):
                    return request(fixture.origin, trusted, method, path, token, body, raw)
                manual_client_roundtrip(api, credentials["owner"], credentials["member"])
                status, _ = request(fixture.origin, trusted, "POST", "/v1/changes", credentials["owner"]["token"],
                                    raw=b"x" * (MAX_REQUEST + 1))
                self.assertTrue(status == 413, "request bound enforced before Go")
                status, _ = request(fixture.origin, trusted, "GET", fixture.bootstrap_path + "?extra=1")
                self.assertTrue(status == 404, "bootstrap requires exact private path")
                # http.client rejects this URI locally, so send the HTTP
                # request line only after a normally verified TLS handshake.
                with socket.create_connection(("127.0.0.1", urlsplit(fixture.origin).port), 10) as raw:
                    with trusted.wrap_socket(raw, server_hostname="10.0.2.2") as stream:
                        stream.sendall(b"GET http://[bad HTTP/1.1\r\nHost: 10.0.2.2\r\nConnection: close\r\n\r\n")
                        response = http.client.HTTPResponse(stream)
                        response.begin()
                        self.assertTrue(response.status == 400, "malformed absolute URI counted and rejected")
                        response.read(MAX_RESPONSE + 1)
                summary = fixture.safe_summary()
                self.assertTrue(summary["service_revision"] == 2 and summary["bad_hostname_http_requests"] == 0,
                                "actual server head and zero bad-hostname HTTP")
                self.assertTrue(summary["owner_session_valid"] and summary["member_session_valid"],
                                "actual session recheck")
                self.assertTrue(all(secret not in json.dumps(summary) for secret in secrets), "safe summary redacts secrets")
            fixture.close()  # Idempotence after normal context exit.
            summary = fixture.safe_summary()
            self.assertTrue(summary["cleanup_completed"] and summary["go_process_stopped"]
                            and summary["tls_threads_stopped"] and summary["private_temp_removed"], "actual cleanup")
            self.assertTrue(summary["service_revision"] == 2 and summary["bad_hostname_http_requests"] == 0,
                            "cached observations survive close")
            self.assertTrue(not fixture.ca_file.exists() and work.exists(), "remove only own private child")
            self.assertTrue(fixture._bootstrap == b"", "bootstrap material released")
            print(json.dumps({"status": "PASS", "scope": "host TLS/real Go/PG/shared client; not Android",
                              "fixture": summary}, sort_keys=True))


if __name__ == "__main__":
    unittest.main()
