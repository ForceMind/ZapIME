#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Read-only checks for a fresh ZapIME server."""

import argparse
import errno
import json
from pathlib import Path
import platform
import re
import shutil
import socket
import subprocess
import urllib.error
import urllib.parse
import urllib.request


MIN_DISK = 2 * 1024**3


def hostname(value):
    if len(value) > 253 or "." not in value or not all(
        re.fullmatch(r"[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?", label)
        for label in value.split(".")
    ):
        raise argparse.ArgumentTypeError("请输入域名，不含协议、端口或路径")
    return value.lower()


def https_url(value):
    try:
        parsed = urllib.parse.urlsplit(value)
        if (parsed.scheme == "https" and parsed.hostname and parsed.username is None
                and not parsed.query and not parsed.fragment and parsed.port != 0):
            return value
    except ValueError:
        pass
    raise argparse.ArgumentTypeError("请输入 HTTPS 地址，不含登录信息、查询参数或片段")


def operating_system():
    if platform.system() != "Linux":
        return "FAIL", "仅在 Linux 服务器运行预检"
    try:
        info = platform.freedesktop_os_release()
    except OSError:
        info = {}
    family, codename = info.get("ID", ""), info.get("VERSION_CODENAME", "")
    return "PASS", " ".join(part for part in (family or "Linux", codename) if part)


def package_manager_status():
    for manager in ("apt-get", "dnf", "yum", "apk", "pacman", "zypper"):
        if shutil.which(manager):
            return "PASS", f"识别到 {manager}"
    return "PASS", "Linux 自备依赖模式"


def port_status(port, loopback=False):
    ipv4 = "127.0.0.1" if loopback else "0.0.0.0"
    addresses = [(socket.AF_INET, ipv4)]
    if not loopback:
        addresses.append((socket.AF_INET6, "::"))
    try:
        for family, address in addresses:
            try:
                with socket.socket(family, socket.SOCK_STREAM) as candidate:
                    # Match a normal Linux server restart after a closed connection.
                    candidate.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    if family == socket.AF_INET6:
                        candidate.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
                    candidate.bind((address, port))
                    candidate.listen()
            except OSError as error:
                if family != socket.AF_INET6 or error.errno not in {errno.EAFNOSUPPORT, errno.EADDRNOTAVAIL}:
                    raise
    except OSError as error:
        if error.errno == errno.EADDRINUSE:
            direction = "使用 --proxy existing 并确认现有反代归属" if port != 8787 else "确认端口归属及是否为已有 ZapIME 实例"
            return "FAIL", f"TCP {port} 已占用。{direction}"
        if error.errno in (errno.EACCES, errno.EPERM):
            return "PENDING", f"权限不足，无法确认 TCP {port}"
        return "FAIL", f"无法核实 TCP {port}"
    return "PASS", f"TCP {port} 可用"


def docker_status():
    if not shutil.which("docker"):
        return "PENDING", "安装前需要 Docker Engine 和 Compose v2"
    try:
        for command in (("docker", "compose", "version"), ("docker", "info", "--format", "{{.ServerVersion}}")):
            completed = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL, timeout=10)
            if completed.returncode:
                return "PENDING", "Compose v2 或 Docker daemon 当前不可用，检查安装及权限"
    except (OSError, subprocess.TimeoutExpired):
        return "PENDING", "Docker 无法在规定时间内确认"
    return "PASS", "Docker Engine 和 Compose v2 可用"


class HTTPSRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, message, headers, new_url):
        https_url(new_url)
        return super().redirect_request(req, fp, code, message, headers, new_url)


def endpoint_status(url, reachable_codes):
    try:
        opener = urllib.request.build_opener(HTTPSRedirects())
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "ZapIME-Preflight/1"})
        with opener.open(req, timeout=5) as response:
            code = response.status
    except urllib.error.HTTPError as error:
        code = error.code
        error.close()
    except (OSError, ValueError, argparse.ArgumentTypeError):
        return "FAIL", "连接、超时或 TLS 校验失败"
    if code in reachable_codes:
        return "PASS", f"HTTPS 可达，HTTP {code}"
    return "FAIL", f"收到 HTTP {code}，请核对地址及出口"


def dns_status(domain):
    if not domain:
        return "PENDING", "提供 --domain 后检查 DNS"
    try:
        addresses = socket.getaddrinfo(domain, None, type=socket.SOCK_STREAM)
    except OSError:
        return "FAIL", "域名在当前服务器无法解析"
    return ("PASS", "域名可解析，安装时再验证公网路由") if addresses else ("FAIL", "域名解析结果为空")


def main():
    parser = argparse.ArgumentParser(description="ZapIME 部署前检查")
    parser.add_argument("--domain", type=hostname)
    parser.add_argument("--proxy", choices=("caddy", "existing"), default="caddy")
    parser.add_argument("--r2-endpoint", type=https_url)
    parser.add_argument("--registry", type=https_url, default="https://registry-1.docker.io/v2/")
    parser.add_argument("--release-source", type=lambda value: https_url(value) if value else "", default="")
    parser.add_argument("--offline", action="store_true", help="仅进行本机预检，网络项记为待检查")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    results = {}
    results["系统"] = operating_system()
    if results["系统"][0] == "PASS":
        results["包管理器"] = package_manager_status()
        storage = "/opt" if Path("/opt").is_dir() else "/"
        results["磁盘"] = ("PASS", "可用空间至少 2 GiB") if shutil.disk_usage(storage).free >= MIN_DISK else (
            "FAIL", "/opt 可用空间低于 2 GiB"
        )
        results["Docker"] = docker_status()
        results["同步端口"] = port_status(8787, loopback=True)
        if args.proxy == "caddy":
            results["HTTP 端口"] = port_status(80)
            results["HTTPS 端口"] = port_status(443)
        else:
            results["现有反代"] = "PENDING", "安装阶段核对反代上游 127.0.0.1:8787 和公网 HTTPS"
        pending = "PENDING", "离线预检，等待在目标服务器核对"
        results["DNS"] = pending if args.offline else dns_status(args.domain)
        results["发布源"] = (("PASS", "已从 HTTPS 下载并验签") if args.release_source
                              else ("PENDING", "已验本地包，等待核对发布源"))
        results["镜像源"] = pending if args.offline else endpoint_status(args.registry, {200, 204, 401})
        results["R2"] = pending if args.offline else (
            endpoint_status(args.r2_endpoint, {200, 204, 400, 401, 403, 404, 405}) if args.r2_endpoint
            else ("PENDING", "提供 --r2-endpoint 后检查 HTTPS，桶读写另行验收")
        )
    if args.json:
        print(json.dumps({name: {"status": code, "detail": detail} for name, (code, detail) in results.items()},
                         ensure_ascii=False))
    else:
        for name, (code, detail) in results.items():
            print(f"[{code}] {name} · {detail}")
    states = {code for code, _ in results.values()}
    return 1 if "FAIL" in states else 2 if "PENDING" in states else 0


if __name__ == "__main__":
    raise SystemExit(main())
