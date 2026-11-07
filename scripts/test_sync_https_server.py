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
import tempfile
import unittest
from urllib.parse import urlsplit

from fixtures.sync_https_server import Fixture, MAX_BOOTSTRAP, MAX_REQUEST, MAX_RESPONSE
from sync_service_smoke import manual_client_roundtrip


def request(origin, context, method, path, token=None, body=None, raw=None):
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
        return response.status, json.loads(contents)
    finally:
        connection.close()


@unittest.skipUnless(os.environ.get("ZAPIME_HTTPS_TEST_BINARY") and os.environ.get("ZAPIME_HTTPS_TEST_DATABASE_URL"),
                     "NOT RUN: explicit isolated Go binary/PG URL required")
class RealHttpsFixtureTests(unittest.TestCase):
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
