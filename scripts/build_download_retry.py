# SPDX-License-Identifier: GPL-3.0-only
"""Bounded retries for fixed build-time HTTPS GETs, never product networking.

Only network_call-marked failures retry the callers' complete read/verify/
atomic-publish attempt. A caller must discard its partial file before raising.
Disk and integrity failures never enter this retry path. The deadline is checked at
connection, read and publication boundaries; it cannot forcibly kill Python's
DNS resolution or an OS/socket call already blocked beneath urllib.
"""
import email.utils
import errno
import http.client
import math
from pathlib import Path
import socket
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

TRANSIENT_HTTP = frozenset({429, 500, 502, 503, 504})
TRANSIENT_ERRNO = frozenset({errno.ECONNRESET, errno.ECONNABORTED,
                            errno.ETIMEDOUT, errno.ECONNREFUSED,
                            errno.ENETUNREACH, errno.EHOSTUNREACH, errno.EPIPE})


class DownloadBudgetExceeded(TimeoutError):
    pass


class _RetryableNetworkError(Exception):
    def __init__(self, error):
        self.error = error


class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        if urllib.parse.urlparse(newurl).scheme != "https":
            raise ValueError("build dependency redirect must remain HTTPS")
        return super().redirect_request(request, response, code, message, headers, newurl)


def safe_directory(path):
    path = Path(path).absolute()
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink() or (ancestor.exists() and not ancestor.is_dir()):
            raise ValueError("build output ancestor is not a real directory")
    path.mkdir(parents=True, exist_ok=True)
    return path


def transient(error):
    if isinstance(error, DownloadBudgetExceeded):
        return False
    if isinstance(error, urllib.error.HTTPError):
        return error.code in TRANSIENT_HTTP
    # A bad certificate/protocol is not made trustworthy by another attempt.
    if isinstance(error, ssl.SSLError):
        return False
    if isinstance(error, urllib.error.URLError):
        return transient(error.reason) if isinstance(error.reason, BaseException) else False
    if isinstance(error, socket.gaierror):
        return error.errno == socket.EAI_AGAIN
    if isinstance(error, (TimeoutError, ConnectionError, http.client.RemoteDisconnected,
                          http.client.IncompleteRead)):
        return True
    return isinstance(error, OSError) and error.errno in TRANSIENT_ERRNO


def retry_after(error):
    if not isinstance(error, urllib.error.HTTPError) or error.headers is None:
        return 0.0
    value = error.headers.get("Retry-After", "")
    if not isinstance(value, str) or len(value) > 128:
        return 0.0
    value = value.strip()
    if value.isascii() and value.isdigit():
        return float(value)
    try:
        date = email.utils.parsedate_to_datetime(value)
        if date.tzinfo is None:
            return 0.0
        return max(0.0, date.timestamp() - time.time())
    except (TypeError, ValueError, OverflowError):
        return 0.0


def network_call(operation, *args, **kwargs):
    """Mark errors from open/read only; a disk timeout must not restart a GET."""
    try:
        return operation(*args, **kwargs)
    except Exception as error:
        if transient(error):
            raise _RetryableNetworkError(error) from error
        raise


def run_with_retry(operation, *, socket_timeout=60, total_timeout=180, max_attempts=3):
    if (type(max_attempts) is not int or not 1 <= max_attempts <= 3
            or type(socket_timeout) not in (int, float) or not math.isfinite(socket_timeout)
            or type(total_timeout) not in (int, float) or not math.isfinite(total_timeout)
            or socket_timeout <= 0 or total_timeout <= 0):
        raise ValueError("invalid build download retry bounds")
    deadline = time.monotonic() + total_timeout

    def remaining():
        left = deadline - time.monotonic()
        if left <= 0:
            raise DownloadBudgetExceeded("build dependency download budget exhausted")
        return left

    for attempt in range(1, max_attempts + 1):
        timeout = min(socket_timeout, remaining())
        try:
            return operation(timeout, remaining)
        except _RetryableNetworkError as failure:
            error = failure.error
            # urlopen raises before entering the response context manager.
            # Release an HTTP error's connection before waiting or propagating
            # its original status. Do not consume or log a response body.
            if isinstance(error, urllib.error.HTTPError):
                try:
                    error.close()
                except OSError:
                    pass
            if attempt == max_attempts:
                raise error from None
            delay = max(float(attempt), retry_after(error))
            # A Retry-After cannot be clipped into an early request. Preserve
            # the original status if no allowed retry fits this budget.
            if delay >= remaining():
                raise error from None
            reason = str(error.code) if isinstance(error, urllib.error.HTTPError) else "network"
            print(f"build_download_retry reason={reason} attempt={attempt} next={attempt + 1} wait_seconds={delay:g}", file=sys.stderr)
            time.sleep(delay)


class BudgetedReader:
    """Check monotonic budget around each public response read1/read call."""
    def __init__(self, response, remaining):
        self.response, self.remaining = response, remaining
        if hasattr(response, "geturl") and urllib.parse.urlparse(response.geturl()).scheme != "https":
            raise ValueError("build dependency response must remain HTTPS")

    def read(self, size):
        self.remaining()
        # HTTPResponse.read1 avoids waiting to fill a whole MiB across many
        # successful socket reads before the next deadline check.
        read = getattr(self.response, "read1", self.response.read)
        value = network_call(read, size)
        self.remaining()
        return value
