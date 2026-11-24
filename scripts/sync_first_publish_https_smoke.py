# SPDX-License-Identifier: GPL-3.0-only
"""Synthetic A2 acceptance with a strict TLS host, real Go and isolated PostgreSQL."""
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import tempfile
import traceback
from urllib.parse import urlsplit
from uuid import UUID

from fixtures.sync_https_server import Fixture
from test_sync_https_server import request, request_raw

ROOT = Path(__file__).resolve().parents[1]


class Scenario:
    def __init__(self, name, fixture, directory, probe):
        self.name = name
        self.fixture = fixture
        self.directory = directory
        self.probe = probe
        self.trust = ssl.create_default_context(cafile=str(fixture.ca_file))
        assert self.trust.check_hostname and self.trust.verify_mode == ssl.CERT_REQUIRED
        status, self.devices = request(fixture.origin, self.trust, "GET", fixture.bootstrap_path)
        assert status == 200 and self.devices.keys() == {"owner", "member"}
        for role in self.devices:
            self.check_session(role)
        self.account = self.devices["owner"]["account_id"]
        assert self.devices["member"]["account_id"] == self.account
        assert self.devices["member"]["device_id"] != self.devices["owner"]["device_id"]
        result = self.run("init-restored", self.account, name)
        assert result[-1]["records"] == 1 and result[-1]["pending"] == 0 and result[-1]["cursor"] == 0

        self.descriptor = json.loads((directory / "descriptor.json").read_bytes())
        status, empty = self.api("owner", "GET", "/v1/key-descriptor")
        assert status == 200 and empty["state"] == "empty" and empty["account_id"] == self.account
        status, self.registered = self.api("owner", "PUT", "/v1/key-descriptor", body=self.descriptor)
        assert status == 200 and self.registered["registered_by"] == self.devices["owner"]["device_id"]
        self.registered_file = self.save("registered.json", self.check_binding("owner"))
        self.check_binding("member")

    def run(self, command, *args):
        proc = subprocess.run([str(self.probe), command, str(self.directory), *map(str, args)],
                              stdin=subprocess.DEVNULL, capture_output=True, timeout=20)
        if proc.returncode != 0:
            location = re.fullmatch(rb"FAIL: manual client fixture assertion ((?:manual_sync_fixture|first_publish)\.rs:\d+)\s*",
                                    proc.stderr)
            print("A2 probe failure", self.name, command, proc.returncode,
                  location[1].decode("ascii") if location else "location unavailable")
            raise RuntimeError(f"synthetic fixture command failed: {self.name}/{command} ({proc.returncode})")
        reports = [json.loads(line) for line in proc.stdout.splitlines()]
        assert reports and all(report["status"] == "PASS" for report in reports)
        return reports

    def save(self, name, body):
        path = self.directory / name
        with path.open("xb") as file:
            file.write(body)
        return path

    def raw(self, role, method, path, **kwargs):
        return request_raw(self.fixture.origin, self.trust, method, path,
                           self.devices[role]["token"], **kwargs)

    def api(self, role, method, path, **kwargs):
        status, body = self.raw(role, method, path, **kwargs)
        return status, json.loads(body)

    def check_session(self, role):
        identity = self.devices[role]
        assert identity.keys() == {"account_id", "device_id", "token", "expires_at"}
        assert all(UUID(identity[field]).int != 0 for field in ("account_id", "device_id"))
        status, state = self.api(role, "GET", "/v1/session")
        assert status == 200 and state.keys() == {"protocol", "account_id", "device_id", "role", "expires_at"}
        assert state["protocol"] == 1 and state["role"] == role
        assert all(state[field] == identity[field] for field in ("account_id", "device_id"))
        stored_expiry = datetime.fromisoformat(state["expires_at"])
        issued_expiry = datetime.fromisoformat(identity["expires_at"])
        # PostgreSQL stores timestamps at microsecond precision.
        assert abs(stored_expiry - issued_expiry) <= timedelta(microseconds=1)
        assert stored_expiry > datetime.now(timezone.utc)

    def check_binding(self, role):
        self.check_session(role)
        status, wire = self.raw(role, "GET", "/v1/key-descriptor")
        state = json.loads(wire)
        assert status == 200 and state == self.registered
        assert state["protocol"] == 1 and state["account_id"] == self.account and state["state"] == "registered"
        assert state["descriptor"] == self.descriptor
        return wire

    def page(self, role, after, limit=1):
        self.check_binding(role)
        status, wire = self.raw(role, "GET", f"/v1/changes?after={after}&limit={limit}")
        assert status == 200
        page = json.loads(wire)
        assert all(change["account_id"] == self.account for change in page["changes"])
        return page, self.save(f"page-{role}-{after}.json", wire)

    def stage(self, device, name):
        role = "owner" if device == "a" else "member"
        self.check_binding(role)
        packet = self.directory / name
        self.run("stage", device, self.registered_file, packet)
        return packet

    def post(self, role, packet):
        self.check_binding(role)
        return self.raw(role, "POST", "/v1/changes", raw=packet.read_bytes())

    def acknowledge(self, device, packet, result):
        role = "owner" if device == "a" else "member"
        self.check_binding(role)
        return self.run("ack", device, self.registered_file, self.devices[role]["device_id"], packet, result)[-1]

    def seed(self, revision):
        packet = self.stage("b", f"seed-{revision}.json")
        status, wire = self.post("member", packet)
        assert status == 200 and json.loads(wire)["revision"] == revision
        ack = self.save(f"seed-ack-{revision}.json", wire)
        self.acknowledge("b", packet, ack)
        return packet

    def head(self):
        self.check_binding("owner")
        status, state = self.api("owner", "GET", "/v1/changes?after=0&limit=1")
        assert status == 200
        return state["head"]


def positive(case):
    page, path = case.page("owner", 0)
    assert page == {"changes": [], "next_cursor": 0, "head": 0}
    confirmed = case.run("first-publish", case.registered_file, "confirm", path)[-1]
    assert confirmed["pending"] == 1 and confirmed["cursor"] == 0
    original = case.stage("a", "original-upload.json")
    status, lost_ack = case.post("owner", original)
    assert status == 200 and json.loads(lost_ack)["revision"] == 1
    retry = case.stage("a", "retry-upload.json")
    assert retry.read_bytes() == original.read_bytes()
    status, ack = case.post("owner", retry)
    assert status == 200 and json.loads(ack) == json.loads(lost_ack)
    recorded = case.acknowledge("a", original, case.save("restored-ack.json", ack))
    assert recorded["pending"] == 0 and recorded["cursor"] == 0

    downloaded, page_b = case.page("member", 0)
    assert downloaded["head"] == 1 and downloaded["next_cursor"] == 1 and len(downloaded["changes"]) == 1
    counts, receiver = case.run("apply", "b", case.registered_file, page_b)
    assert counts["applied"] == 1 and counts["echoed"] == 0
    assert receiver["cursor"] == 1 and receiver["pending"] == 0
    case.run("verify-restored")

    member = case.devices["member"]["device_id"]
    assert case.api("owner", "DELETE", "/v1/devices/" + member) == (200, {"revoked": True})
    for method, path, options in (
        ("GET", "/v1/session", {}), ("GET", "/v1/changes?after=1", {}),
        ("POST", "/v1/changes", {"raw": retry.read_bytes()}),
    ):
        assert case.raw("member", method, path, **options)[0] == 401
    assert case.head() == 1


def nonterminal(case):
    for revision in (1, 2):
        case.seed(revision)
    page, first = case.page("owner", 0)
    assert page["next_cursor"] == 1 and page["head"] == 2
    stopped = case.run("first-publish", case.registered_file, "nonterminal", first)[-1]
    assert stopped["cursor"] == 1 and stopped["pending"] == 0
    page, last = case.page("owner", 1)
    assert page["next_cursor"] == page["head"] == 2
    resumed = case.run("first-publish", case.registered_file, "confirm", last)[-1]
    assert resumed["cursor"] == 2 and resumed["pending"] == 1 and case.head() == 2


def same_id(case):
    _, before = case.page("owner", 0)
    case.run("first-publish", case.registered_file, "confirm", before)
    staged = case.stage("a", "planned-upload.json")
    intended = json.loads(staged.read_bytes())
    winner = case.seed(1)
    assert json.loads(winner.read_bytes())["record_id"] == intended["record_id"]
    status, raw = case.post("owner", staged)
    assert status == 409 and json.loads(raw)["error"] == "revision_conflict" and case.head() == 1
    assert case.stage("a", "still-frozen.json").read_bytes() == staged.read_bytes()


def conflicting_pull(case):
    uploaded = case.seed(1)
    packet = json.loads(uploaded.read_bytes())
    restored = json.loads((case.directory / "record.json").read_bytes())
    assert (packet["record_id"] == restored) == (case.name == "tombstone")
    assert packet["deleted"] == (case.name == "tombstone")
    page, path = case.page("owner", 0)
    assert page["head"] == 1 and page["changes"][0]["operation"] == packet
    report = case.run("first-publish", case.registered_file, case.name, path)[-1]
    assert report["records"] == 1 and report["pending"] == 0 and report["cursor"] == 0
    assert case.head() == 1


def main():
    assert os.name == "posix", "strict POSIX fixture required"
    binary = Path(os.environ["ZAPIME_HTTPS_TEST_BINARY"]).resolve(strict=True)
    database = os.environ["ZAPIME_HTTPS_TEST_DATABASE_URL"]
    probe = ROOT / "target/debug/examples/manual_sync_fixture"
    uri = urlsplit(database)
    assert uri.scheme in {"postgres", "postgresql"} and uri.hostname in {"localhost", "127.0.0.1"}
    assert binary.is_file() and probe.is_file()
    reports = []
    for name, test in (("positive", positive), ("nonterminal", nonterminal), ("same-id", same_id),
                       ("duplicate", conflicting_pull), ("tombstone", conflicting_pull)):
        with tempfile.TemporaryDirectory(prefix="first-publish-https-", dir=ROOT / "target") as temporary:
            directory = Path(temporary)
            fixture = Fixture(directory, binary, database)
            with fixture:
                if name == "positive":
                    for origin, context in ((fixture.origin, ssl.create_default_context()),
                                            (fixture.bad_origin, ssl.create_default_context(cafile=str(fixture.ca_file)))):
                        try:
                            request(origin, context, "GET", "/v1/session")
                        except ssl.SSLCertVerificationError:
                            pass
                        else:
                            raise RuntimeError("synthetic TLS rejection did not occur")
                test(Scenario(name, fixture, directory, probe))
            summary = fixture.safe_summary()
            assert summary["cleanup_completed"] and summary["bad_hostname_http_requests"] == 0
            reports.append({"case": name, "status": "PASS", "server_head": summary["service_revision"]})
    print(json.dumps({"status": "PASS", "scope": "A2 host HTTPS, Go, PostgreSQL and two SQLite clients",
                      "source_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                      "server_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(), "cases": reports}, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except BaseException as error:
        # No credential, URL, fixture plaintext, subprocess output or exception value in logs.
        frames = traceback.extract_tb(error.__traceback__)
        print("A2 HTTPS FAIL", type(error).__name__, [(Path(f.filename).name, f.lineno) for f in frames[-4:]])
        raise SystemExit(1) from None
