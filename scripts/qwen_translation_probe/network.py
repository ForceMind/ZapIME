#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Specific process syscall restriction, not a malicious-code sandbox."""
import ctypes
import errno
import socket


def deny_network():
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(38, 1, 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), "cannot set no_new_privs")
    seccomp = ctypes.CDLL("libseccomp.so.2", use_errno=True)
    seccomp.seccomp_init.argtypes = [ctypes.c_uint32]
    seccomp.seccomp_init.restype = ctypes.c_void_p
    seccomp.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]
    seccomp.seccomp_syscall_resolve_name.restype = ctypes.c_int
    seccomp.seccomp_rule_add.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint]
    seccomp.seccomp_rule_add.restype = ctypes.c_int
    seccomp.seccomp_load.argtypes = [ctypes.c_void_p]
    seccomp.seccomp_load.restype = ctypes.c_int
    seccomp.seccomp_release.argtypes = [ctypes.c_void_p]
    context = seccomp.seccomp_init(0x7FFF0000)
    if not context:
        raise RuntimeError("cannot allocate syscall filter")
    blocked = ["socket", "socketpair", "connect", "accept", "accept4", "bind", "listen", "sendto", "sendmsg", "sendmmsg", "recvfrom", "recvmsg", "recvmmsg", "shutdown"]
    try:
        for name in blocked:
            number = seccomp.seccomp_syscall_resolve_name(name.encode("ascii"))
            if number < 0 or seccomp.seccomp_rule_add(context, 0x00050000 | errno.EPERM, number, 0) != 0:
                raise RuntimeError("cannot restrict syscall: " + name)
        if seccomp.seccomp_load(context) != 0:
            raise RuntimeError("cannot install filter")
    finally:
        seccomp.seccomp_release(context)
    rejected = []
    for family in (socket.AF_INET, socket.AF_INET6):
        try:
            connection = socket.socket(family, socket.SOCK_STREAM)
        except OSError as error:
            if error.errno != errno.EPERM:
                raise
            rejected.append(family.name)
        else:
            connection.close()
            raise RuntimeError("network restriction self-check failed")
    return {"method": "no_new_privs plus Linux libseccomp, before spawning the native runtime; inherited across exec", "blocked_syscalls": blocked, "socket_creation_rejected": rejected, "scope": "this worker and its native child only; specific socket syscalls, not all inherited-fd/io_uring paths, device airplane mode, or general sandbox"}
