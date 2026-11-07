#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Private, disposable TLS transport around the actual Go/PostgreSQL service.

Only CI/host acceptance uses this fixture. No business response, ciphertext,
ACK or account is mocked. The caller owns the isolated database lifecycle and
Android system trust; this fixture never deletes a database or alters trust.
"""
from collections import Counter
from datetime import datetime, timezone
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import signal
import socket
import ssl
import stat
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlsplit
import uuid

MAX_REQUEST = 100_000
MAX_RESPONSE = 10 * 1024 * 1024
MAX_BOOTSTRAP = 4096
TIMEOUT = 10


def _private_file(path, contents=b""):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(contents)


def _free_port():
    with socket.socket() as stream:
        stream.bind(("127.0.0.1", 0))
        return stream.getsockname()[1]


def _expiry_valid(value):
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")) > datetime.now(timezone.utc)
    except (AttributeError, TypeError, ValueError):
        return False


def _credential(value):
    if not isinstance(value, dict) or set(value) != {"account_id", "device_id", "token", "expires_at"}:
        raise RuntimeError("fixture_credential_invalid")
    try:
        valid_ids = all(str(uuid.UUID(value[field])) == value[field]
                        and uuid.UUID(value[field]).int != 0 for field in ("account_id", "device_id"))
    except (ValueError, TypeError, AttributeError):
        valid_ids = False
    if (not valid_ids or not isinstance(value["token"], str)
            or re.fullmatch(r"zap1_[A-Za-z0-9_-]{43}", value["token"]) is None
            or not _expiry_valid(value["expires_at"])):
        raise RuntimeError("fixture_credential_invalid")
    return value


def _safe_path(path):
    # Never retain a random bootstrap capability, UUID or query in a report.
    try:
        parsed = urlsplit(path)
    except ValueError:
        return "/<other>"
    path = parsed.path
    if path.startswith("/ci/bootstrap/"):
        return "/ci/bootstrap/<redacted>"
    if path in {"/v1/session", "/v1/key-descriptor", "/v1/changes", "/v1/devices"}:
        return path
    if path.startswith("/v1/devices/"):
        return "/v1/devices/<redacted>"
    return "/<other>"


class _TLSServer(ThreadingHTTPServer):
    daemon_threads = False
    block_on_close = True
    allow_reuse_address = False

    def __init__(self, fixture, context, bad):
        self.fixture, self.context, self.bad = fixture, context, bad
        self._connections = set()
        self._connection_lock = threading.Lock()
        self._slots = threading.BoundedSemaphore(16)
        self.active_workers = 0
        super().__init__(("127.0.0.1", 0), _Handler)

    def get_request(self):
        raw, address = self.socket.accept()
        raw.settimeout(TIMEOUT)
        with self._connection_lock:
            self._connections.add(raw)
        try:
            wrapped = self.context.wrap_socket(raw, server_side=True)
        except (OSError, ssl.SSLError):
            with self._connection_lock:
                self._connections.discard(raw)
            raw.close()
            raise
        with self._connection_lock:
            self._connections.discard(raw)
            self._connections.add(wrapped)
        return wrapped, address

    def process_request(self, request, client_address):
        if not self._slots.acquire(blocking=False):
            self.shutdown_request(request)
            with self._connection_lock:
                self._connections.discard(request)
            return
        with self._connection_lock:
            self.active_workers += 1
        try:
            super().process_request(request, client_address)
        except BaseException:
            with self._connection_lock:
                self.active_workers -= 1
                self._connections.discard(request)
            self._slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            with self._connection_lock:
                self.active_workers -= 1
                self._connections.discard(request)
            self._slots.release()

    def disconnect_all(self):
        with self._connection_lock:
            connections = list(self._connections)
        for connection in connections:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()

    def handle_error(self, request, client_address):
        # In particular, never dump a handler/request object or body to stderr.
        pass


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        pass

    def _reply(self, status, body, headers=None):
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Type", "application/json")
        for name, value in headers or []:
            if name.lower() in {"www-authenticate", "x-content-type-options"}:
                self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def _error(self, status):
        self._reply(status, b'{"error":"fixture_transport_rejected"}')

    def _handle(self):
        fixture = self.server.fixture
        with fixture._count_lock:
            if self.server.bad:
                fixture._bad_requests += 1
            fixture._requests[(self.command, _safe_path(self.path))] += 1
        self.close_connection = True
        try:
            parsed = urlsplit(self.path)
        except ValueError:
            self._error(400)
            return
        if parsed.scheme or parsed.netloc or parsed.fragment or not self.path.startswith("/"):
            self._error(400)
            return
        lengths = self.headers.get_all("Content-Length", [])
        if (self.headers.get_all("Transfer-Encoding", []) or len(lengths) > 1
                or lengths and re.fullmatch(r"[0-9]+", lengths[0]) is None):
            self._error(400)
            return
        normalized_length = lengths[0].lstrip("0") or "0" if lengths else "0"
        if len(normalized_length) > 6:
            self._error(413)
            return
        length = int(normalized_length)
        if length > MAX_REQUEST:
            self._error(413)
            return
        body = self.rfile.read(length)
        if len(body) != length:
            self._error(400)
            return
        if (not self.server.bad and self.command == "GET" and self.path == fixture.bootstrap_path
                and not length):
            self._reply(200, fixture._bootstrap)
            return
        if not parsed.path.startswith("/v1/"):
            self._error(404)
            return
        # Forward exact bytes to the actual Go routes. Do not parse, amend or
        # synthesize a descriptor, encrypted operation, revision or ACK.
        headers = {}
        for name in ("Authorization", "Content-Type", "Accept"):
            values = self.headers.get_all(name, [])
            if len(values) > 1:
                self._error(400)
                return
            if values:
                headers[name] = values[0]
        try:
            status, response, response_headers = fixture._backend(self.command, self.path, body, headers)
            self._reply(status, response, response_headers)
        except (OSError, http.client.HTTPException, RuntimeError):
            self._error(502)

    do_GET = do_POST = do_PUT = do_DELETE = do_HEAD = do_OPTIONS = _handle


class Fixture:
    """Context manager; public metadata remains readable after close().

    origin/bad_origin target Android's standard host alias, while both actual
    listeners bind only host 127.0.0.1. bootstrap_path is a private capability:
    pass it to the acceptance process, never to logs or artifacts. close() is
    idempotent, removes only its own private child directory, and caches safe
    server observations before shutting down. It does not delete database rows.
    """
    def __init__(self, work_dir: Path, binary: Path, database_url: str):
        self.work_dir = Path(work_dir)
        self.binary = Path(binary)
        self._database_url = database_url
        self._temporary = None
        self._private_dir = None
        self._process = None
        self._servers = []
        self._threads = []
        self._count_lock = threading.Lock()
        self._lifecycle_lock = threading.RLock()
        self._requests = Counter()
        self._bad_requests = 0
        self._owner = self._member = None
        self._bootstrap = b""
        self._bootstrap_size = 0
        self._closed = False
        self._cleanup_completed = False
        self._observed = {"service_revision": None, "owner_session_valid": False,
                          "member_session_valid": False}

    def _openssl(self, *args):
        result = subprocess.run(["openssl", *map(str, args)], stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        if result.returncode:
            raise RuntimeError("fixture_certificate_command_failed")
        return result.stdout

    def _certificates(self):
        folder = self._private_dir
        for name in ("ca-key.pem", "ca.pem", "valid-key.pem", "valid.csr", "valid.pem",
                     "bad-key.pem", "bad.csr", "bad.pem"):
            _private_file(folder / name)
        self.ca_file = folder / "ca.pem"
        self._openssl("req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "2", "-nodes",
            "-keyout", folder / "ca-key.pem", "-out", self.ca_file, "-subj", "/CN=ZapIME-CI-CA-" + secrets.token_hex(8),
            "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign")
        for name, san in (("valid", "IP:10.0.2.2"), ("bad", "IP:10.0.2.3")):
            extension = folder / (name + ".ext")
            _private_file(extension, ("basicConstraints=critical,CA:FALSE\n"
                "keyUsage=critical,digitalSignature,keyEncipherment\n"
                "extendedKeyUsage=serverAuth\nsubjectAltName=" + san + "\n").encode())
            self._openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", folder / (name + "-key.pem"),
                "-out", folder / (name + ".csr"), "-subj", "/CN=ZapIME-CI-" + name)
            self._openssl("x509", "-req", "-in", folder / (name + ".csr"), "-CA", self.ca_file,
                "-CAkey", folder / "ca-key.pem", "-set_serial", str(secrets.randbits(159) + 1),
                "-days", "2", "-sha256", "-extfile", extension, "-out", folder / (name + ".pem"))
        for path in folder.iterdir():
            if stat.S_IMODE(path.stat().st_mode) != 0o600:
                raise RuntimeError("fixture_private_file_permissions_invalid")
        pem = self.ca_file.read_bytes()
        der = ssl.PEM_cert_to_DER_cert(pem.decode("ascii"))
        self.ca_sha256_pem = hashlib.sha256(pem).hexdigest()
        self.ca_sha256_der = hashlib.sha256(der).hexdigest()
        self.ca_subject_hash_old = self._openssl("x509", "-in", self.ca_file, "-subject_hash_old", "-noout").decode().strip()
        if re.fullmatch(r"[0-9a-f]{8}", self.ca_subject_hash_old) is None:
            raise RuntimeError("fixture_ca_subject_hash_invalid")

    def _backend(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self._backend_port, timeout=TIMEOUT)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            data = response.read(MAX_RESPONSE + 1)
            if len(data) > MAX_RESPONSE:
                raise RuntimeError("fixture_upstream_response_too_large")
            return response.status, data, response.getheaders()
        finally:
            connection.close()

    def _json_backend(self, method, path, credential, body=None):
        headers = {"Authorization": "Bearer " + credential["token"], "Content-Type": "application/json"}
        status, data, _ = self._backend(method, path, json.dumps(body).encode() if body is not None else None, headers)
        return status, json.loads(data)

    def __enter__(self):
        with self._lifecycle_lock:
            if self._temporary is not None or self._closed:
                raise RuntimeError("fixture_already_started")
            if (not self.work_dir.is_absolute() or not self.work_dir.is_dir() or self.work_dir.is_symlink()
                    or not self.binary.is_absolute() or not self.binary.is_file()
                    or urlsplit(self._database_url).scheme not in {"postgres", "postgresql"}):
                raise RuntimeError("fixture_configuration_invalid")
            try:
                self._temporary = tempfile.TemporaryDirectory(prefix="sync-https-private-", dir=self.work_dir)
                self._private_dir = Path(self._temporary.name)
                os.chmod(self._private_dir, 0o700)
                self._certificates()
                self._backend_port = _free_port()
                env = {key: value for key, value in os.environ.items() if not key.startswith("ZAPIME_S3_")}
                env.update(DATABASE_URL=self._database_url, ZAPIME_LISTEN=f"127.0.0.1:{self._backend_port}",
                           ZAPIME_OBJECT_BACKEND="postgres")
                provision = subprocess.run([str(self.binary), "provision", "CI HTTPS owner"], env=env,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL, timeout=30)
                if provision.returncode or len(provision.stdout) > MAX_BOOTSTRAP:
                    raise RuntimeError("fixture_owner_provision_failed")
                self._owner = _credential(json.loads(provision.stdout))
                self._process = subprocess.Popen([str(self.binary)], env=env, stdin=subprocess.DEVNULL,
                                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                for _ in range(100):
                    if self._process.poll() is not None:
                        raise RuntimeError("fixture_go_start_failed")
                    try:
                        if self._backend("GET", "/healthz")[0] == 200:
                            break
                    except (OSError, http.client.HTTPException):
                        pass
                    time.sleep(0.05)
                else:
                    raise RuntimeError("fixture_go_unavailable")
                status, member = self._json_backend("POST", "/v1/devices", self._owner, {"name": "CI HTTPS member"})
                if status != 201:
                    raise RuntimeError("fixture_member_provision_failed")
                self._member = _credential(member)
                if self._owner["account_id"] != self._member["account_id"]:
                    raise RuntimeError("fixture_account_mismatch")
                self._bootstrap = json.dumps({"owner": self._owner, "member": self._member}, separators=(",", ":")).encode()
                if len(self._bootstrap) > MAX_BOOTSTRAP:
                    raise RuntimeError("fixture_bootstrap_too_large")
                self._bootstrap_size = len(self._bootstrap)
                self.bootstrap_path = "/ci/bootstrap/" + secrets.token_hex(32)
                for name, bad in (("valid", False), ("bad", True)):
                    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                    context.minimum_version = ssl.TLSVersion.TLSv1_2
                    context.load_cert_chain(self._private_dir / (name + ".pem"), self._private_dir / (name + "-key.pem"))
                    server = _TLSServer(self, context, bad)
                    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05},
                                              name="zapime-ci-tls-" + name, daemon=False)
                    self._servers.append(server)
                    self._threads.append(thread)
                    thread.start()
                self.origin = "https://10.0.2.2:" + str(self._servers[0].server_port)
                self.bad_origin = "https://10.0.2.2:" + str(self._servers[1].server_port)
                return self
            except BaseException:
                self.close()
                raise

    def _observe(self):
        if self._process is None or self._process.poll() is not None or self._owner is None:
            return
        try:
            for name, credential in (("owner", self._owner), ("member", self._member)):
                if credential is None:
                    continue
                status, session = self._json_backend("GET", "/v1/session", credential)
                self._observed[name + "_session_valid"] = bool(status == 200 and session.get("protocol") == 1
                    and session.get("account_id") == credential["account_id"]
                    and session.get("device_id") == credential["device_id"]
                    and session.get("role") == name and _expiry_valid(session.get("expires_at")))
            status, page = self._json_backend("GET", "/v1/changes?after=0&limit=1", self._owner)
            head = page.get("head")
            if status == 200 and type(head) is int and head >= 0:
                self._observed["service_revision"] = head
        except (OSError, ValueError, AttributeError, http.client.HTTPException, RuntimeError):
            self._observed["observation_failed"] = True

    def safe_summary(self):
        with self._lifecycle_lock:
            if not self._closed:
                self._observe()
            with self._count_lock:
                requests = [{"method": method, "path": path, "count": count}
                    for (method, path), count in sorted(self._requests.items())]
                bad = self._bad_requests
            return {"schema_version": 1, "scope": "Real isolated Go/PostgreSQL via temporary host TLS; no Android claim",
                "requests": requests, "bad_hostname_http_requests": bad, **self._observed,
                "bootstrap_response_bytes": self._bootstrap_size, "request_limit_bytes": MAX_REQUEST,
                "response_limit_bytes": MAX_RESPONSE, "cleanup_completed": self._cleanup_completed,
                "go_process_stopped": self._process is not None and self._process.poll() is not None,
                "tls_threads_stopped": all(not thread.is_alive() for thread in self._threads)
                    and all(server.active_workers == 0 for server in self._servers),
                "private_temp_removed": self._private_dir is not None and not self._private_dir.exists()}

    def close(self):
        with self._lifecycle_lock:
            if self._closed:
                return
            self._observe()
            for server, thread in zip(self._servers, self._threads):
                server.disconnect_all()
                if thread.is_alive():
                    server.shutdown()
                server.server_close()
            for thread in self._threads:
                thread.join(timeout=TIMEOUT + 2)
            if self._process is not None and self._process.poll() is None:
                try:
                    self._process.send_signal(signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    self._process.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    self._process.kill()
                    self._process.wait(timeout=5)
            if self._temporary is not None:
                self._temporary.cleanup()
            self._closed = True
            self._cleanup_completed = (all(not thread.is_alive() for thread in self._threads)
                and all(server.active_workers == 0 for server in self._servers)
                and (self._process is None or self._process.poll() is not None)
                and (self._private_dir is None or not self._private_dir.exists()))
            self._owner = self._member = None
            self._bootstrap = b""
            self._database_url = ""

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()
        return False
