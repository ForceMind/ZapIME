# SPDX-License-Identifier: GPL-3.0-only
"""Synthetic retry decisions and clocks; no external network or real sleeps."""
import errno
from email.utils import formatdate
import http.client
import io
import socket
import ssl
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch

import build_download_retry as retry


class Clock:
    def __init__(self):
        self.now = 100.0
        self.waits = []

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.waits.append(seconds)
        self.now += seconds


def http_error(code, headers=None):
    return urllib.error.HTTPError("https://example.invalid/fixed", code,
                                  "synthetic status", headers or {}, None)


def network_attempt(operation):
    return lambda timeout, remaining: retry.network_call(operation, timeout, remaining)


class RetryTests(unittest.TestCase):
    def invoke(self, operation, **options):
        clock = Clock()
        with patch.object(retry.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(retry.time, "sleep", side_effect=clock.sleep):
            return retry.run_with_retry(network_attempt(operation), **options), clock

    def test_504_then_success_repeats_read_only_operation_with_remaining_budget(self):
        calls = []

        def operation(timeout, remaining):
            calls.append((timeout, remaining()))
            if len(calls) == 1:
                raise http_error(504)
            return "verified result"

        result, clock = self.invoke(operation, socket_timeout=5, total_timeout=10)
        self.assertEqual(result, "verified result")
        self.assertEqual(len(calls), 2)
        self.assertEqual(clock.waits, [1])
        self.assertEqual([remaining for _, remaining in calls], [10, 9])
        self.assertTrue(all(0 < timeout <= min(5, remaining) for timeout, remaining in calls))

    def test_explicit_transient_network_failures_can_recover(self):
        failures = [urllib.error.URLError(socket.gaierror(socket.EAI_AGAIN, "temporary")),
                    urllib.error.URLError(TimeoutError("temporary")),
                    ConnectionResetError(errno.ECONNRESET, "temporary"),
                    http.client.RemoteDisconnected("temporary"),
                    http.client.IncompleteRead(b"synthetic partial")]
        for failure in failures:
            calls = []

            def operation(timeout, remaining):
                calls.append(timeout)
                if len(calls) == 1:
                    raise failure
                return True

            with self.subTest(error=type(failure).__name__):
                result, _ = self.invoke(operation)
                self.assertTrue(result)
                self.assertEqual(len(calls), 2)

    def test_permanent_http_integrity_disk_tls_and_unknown_errors_are_not_retried(self):
        failures = [http_error(code) for code in (400, 401, 403, 404, 410, 501, 505)]
        failures += [ValueError("synthetic checksum mismatch"),
                     PermissionError(errno.EACCES, "synthetic disk error"),
                     OSError(errno.ENOSPC, "synthetic disk full"),
                     urllib.error.URLError(socket.gaierror(socket.EAI_NONAME, "permanent")),
                     urllib.error.URLError("unclassified transport failure"),
                     ssl.SSLCertVerificationError("synthetic certificate verification failed"),
                     urllib.error.URLError(ssl.SSLCertVerificationError("synthetic TLS"))]
        for failure in failures:
            calls = []

            def operation(timeout, remaining):
                calls.append(timeout)
                raise failure

            with self.subTest(error=type(failure).__name__, code=getattr(failure, "code", None)):
                with self.assertRaises(type(failure)):
                    self.invoke(operation)
                self.assertEqual(len(calls), 1)

    def test_final_http_failure_remains_failure_after_bounded_attempts(self):
        calls, clock = [], Clock()

        def operation(timeout, remaining):
            calls.append(timeout)
            raise http_error(504)

        with patch.object(retry.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(retry.time, "sleep", side_effect=clock.sleep):
            with self.assertRaises(urllib.error.HTTPError) as failure:
                retry.run_with_retry(network_attempt(operation), max_attempts=3)
        self.assertEqual(failure.exception.code, 504)
        self.assertEqual(len(calls), 3)
        self.assertEqual(clock.waits, [1, 2])

    def test_http_error_bodies_close_before_retry_and_final_status_is_preserved(self):
        bodies = []

        def operation(timeout, remaining):
            self.assertTrue(all(body.closed for body in bodies))
            body = io.BytesIO(b"synthetic gateway body must not be consumed")
            bodies.append(body)
            raise urllib.error.HTTPError("https://example.invalid/fixed", 504,
                                         "synthetic gateway", {}, body)

        with self.assertRaises(urllib.error.HTTPError) as failure:
            self.invoke(operation)
        self.assertEqual(failure.exception.code, 504)
        self.assertEqual(len(bodies), 3)
        self.assertTrue(all(body.closed for body in bodies))

    def test_unmarked_filesystem_timeout_does_not_retry_whole_attempt(self):
        calls = []

        def operation(timeout, remaining):
            calls.append(timeout)
            raise TimeoutError(errno.ETIMEDOUT, "synthetic file publication timeout")

        with self.assertRaises(TimeoutError):
            retry.run_with_retry(operation)
        self.assertEqual(len(calls), 1)

    def test_server_retry_after_is_honored_without_retrying_early(self):
        calls = []

        def operation(timeout, remaining):
            calls.append(remaining())
            if len(calls) == 1:
                raise http_error(429, {"Retry-After": "4"})
            return True

        result, clock = self.invoke(operation, socket_timeout=8, total_timeout=10)
        self.assertTrue(result)
        self.assertEqual(clock.waits, [4])
        self.assertEqual(calls, [10, 6])

    def test_retry_after_exceeding_budget_never_hammers_server(self):
        calls, clock = [], Clock()

        def operation(timeout, remaining):
            calls.append(timeout)
            raise http_error(503, {"Retry-After": "999"})

        with patch.object(retry.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(retry.time, "sleep", side_effect=clock.sleep):
            with self.assertRaises((TimeoutError, urllib.error.HTTPError)):
                retry.run_with_retry(network_attempt(operation), total_timeout=3)
        self.assertEqual(len(calls), 1)
        self.assertEqual(clock.waits, [])

    def test_http_date_retry_after_uses_wall_clock_without_changing_monotonic_budget(self):
        calls = []
        epoch = 1_700_000_000

        def operation(timeout, remaining):
            calls.append(remaining())
            if len(calls) == 1:
                raise http_error(503, {"Retry-After": formatdate(epoch + 4, usegmt=True)})
            return True

        with patch.object(retry.time, "time", return_value=epoch):
            result, clock = self.invoke(operation, total_timeout=10)
        self.assertTrue(result)
        self.assertEqual(clock.waits, [4])
        self.assertEqual(calls, [10, 6])

    def test_https_redirect_preserves_get_and_rejects_insecure_or_non_http_destinations(self):
        handler = retry.HttpsRedirect()
        request = urllib.request.Request("https://example.invalid/fixed", method="GET")
        redirected = handler.redirect_request(request, None, 302, "Found", {},
                                              "https://cdn.example.invalid/fixed")
        self.assertEqual(redirected.full_url, "https://cdn.example.invalid/fixed")
        self.assertEqual(redirected.get_method(), "GET")
        for destination in ("http://example.invalid/fixed", "ftp://example.invalid/fixed"):
            with self.subTest(destination=destination), self.assertRaises(ValueError):
                handler.redirect_request(request, None, 302, "Found", {}, destination)

    def test_time_spent_in_failed_attempt_reduces_next_socket_budget(self):
        clock, calls = Clock(), []

        def operation(timeout, remaining):
            calls.append(timeout)
            if len(calls) == 1:
                clock.now += 7
                raise http_error(502)
            return remaining()

        with patch.object(retry.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(retry.time, "sleep", side_effect=clock.sleep):
            self.assertEqual(retry.run_with_retry(network_attempt(operation), socket_timeout=8, total_timeout=10), 2)
        self.assertEqual(calls, [8, 2])

    def test_exhausted_deadline_does_not_start_another_request(self):
        clock, calls = Clock(), []

        def operation(timeout, remaining):
            calls.append(timeout)
            clock.now += 10
            raise http_error(504)

        with patch.object(retry.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(retry.time, "sleep", side_effect=clock.sleep):
            with self.assertRaises((TimeoutError, urllib.error.HTTPError)):
                retry.run_with_retry(network_attempt(operation), total_timeout=10)
        self.assertEqual(len(calls), 1)
        self.assertEqual(clock.waits, [])

    def test_remaining_callback_rejects_over_budget_stream_before_another_read(self):
        clock, calls = Clock(), []

        def operation(timeout, remaining):
            calls.append(timeout)
            clock.now += 4
            return remaining()

        with patch.object(retry.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(retry.time, "sleep", side_effect=clock.sleep):
            with self.assertRaises(TimeoutError):
                retry.run_with_retry(network_attempt(operation), total_timeout=3)
        self.assertEqual(len(calls), 1)


if __name__ == "__main__":
    unittest.main()
