#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -Eeuo pipefail
umask 077

usage() {
  cat <<'EOF'
ZapIME 服务器预检
  bash bootstrap.sh [--install] (--source HTTPS_URL | --bundle DIRECTORY) --version VERSION --key-sha256 SHA256 -- [参数]
  --source   包含 VERSION/amd64 或 VERSION/arm64 的发布目录
  --bundle   包含 zapime-preflight.zip、zapime-preflight.sig 和 release-public.pem 的本地目录
  --key-sha256 从独立可信渠道取得的发布公钥文件 SHA-256
  --install   由已核对启动脚本摘要的管理员执行，部署已签名的运行包
  预检参数包括 --domain、--proxy caddy|existing、--r2-endpoint 和 --offline。
EOF
}

fail() { printf 'ZapIME 预检失败 · %s\n' "$1" >&2; exit 1; }

source_url= bundle= version= key_hash=
kind=zapime-preflight
while (( $# )); do
  case "$1" in
    --source) source_url=${2:?缺少发布地址}; shift 2 ;;
    --bundle) bundle=${2:?缺少本地目录}; shift 2 ;;
    --version) version=${2:?缺少版本}; shift 2 ;;
    --key-sha256) key_hash=${2:?缺少公钥 SHA-256}; shift 2 ;;
    --install) kind=zapime-runtime; shift ;;
    --help|-h) usage; exit 0 ;;
    --) shift; break ;;
    *) usage >&2; fail '未知入口参数' ;;
  esac
done
if [[ $kind == zapime-runtime ]]; then
  [[ $EUID == 0 && $(uname -s) == Linux ]] || fail '安装需要在 Linux 上以管理员权限运行已核对摘要的入口'
  export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
  unset DOCKER_HOST DOCKER_CONTEXT DOCKER_CONFIG COMPOSE_FILE COMPOSE_ENV_FILES COMPOSE_PATH_SEPARATOR COMPOSE_PROFILES
  unset PYTHONPATH PYTHONHOME BASH_ENV ENV
fi

[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9]+([.-][a-z0-9]+)*)?$ ]] || fail '需要固定的版本号'
[[ $key_hash =~ ^[0-9a-f]{64}$ ]] || fail '需要通过可信渠道取得的 64 位小写 SHA-256'
if [[ -n $bundle && -n $source_url || -z $bundle && -z $source_url ]]; then
  fail '请选择一个 HTTPS 发布源或本地包目录'
fi
case $(uname -m) in
  x86_64|amd64) architecture=amd64 ;;
  aarch64|arm64) architecture=arm64 ;;
  *) fail '仅支持 amd64 和 arm64' ;;
esac
for tool in python3 openssl; do
  command -v "$tool" >/dev/null || fail "缺少 $tool"
done
if [[ -n $source_url ]]; then
  [[ $source_url =~ ^https://[[:alnum:].-]+(:[0-9]+)?(/[[:alnum:]_./~-]*)?$ ]] || fail '发布源必须为 HTTPS 目录'
  command -v curl >/dev/null || fail '缺少 curl'
fi

work=$(mktemp -d /tmp/zapime-install.XXXXXXXX)
cleanup() {
  [[ $work == /tmp/zapime-install.* ]] || return 1
  rm -rf -- "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

copy_artifact() {
  local name=$1 limit=$2
  if [[ -n $bundle ]]; then
    python3 -I - "$bundle/$name" "$work/$name" "$limit" <<'PY' || fail "读取 $name 失败"
import os
from pathlib import Path
import stat
import sys

try:
    fd = os.open(sys.argv[1], os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as source:
        if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
            raise ValueError()
        Path(sys.argv[2]).write_bytes(source.read(int(sys.argv[3]) + 1))
except (OSError, ValueError):
    raise SystemExit(1) from None
PY
  else
    curl --fail --silent --show-error --location --retry 2 --retry-max-time 45 --connect-timeout 8 \
      --max-time 25 --max-filesize "$limit" --proto '=https' --proto-redir '=https' \
      --output "$work/$name" "${source_url%/}/$version/$architecture/$name" || fail "下载 $name 失败"
  fi
  local size
  size=$(wc -c < "$work/$name")
  (( size > 0 && size <= limit )) || fail "$name 为空或超过大小限制"
}

copy_artifact release-public.pem 16384
python3 - "$work/release-public.pem" "$key_hash" <<'PY' || fail '发布公钥与可信指纹不符'
import hashlib
from pathlib import Path
import sys

raise SystemExit(hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() != sys.argv[2])
PY
archive_max=262144
[[ $kind != zapime-runtime ]] || archive_max=1048576
copy_artifact "$kind.sig" 16384
copy_artifact "$kind.zip" "$archive_max"
openssl dgst -sha256 -verify "$work/release-public.pem" -signature "$work/$kind.sig" \
  "$work/$kind.zip" >/dev/null 2>&1 || fail '发布包签名不符（预检包签名不符时也会停止）'

python3 - "$work" "$version" "$architecture" "$kind" <<'PY' || fail '已签名包的结构、版本或架构不符'
import json
from pathlib import Path
import re
import stat
import sys
from zipfile import ZipFile

directory = Path(sys.argv[1])
kind = sys.argv[4]
identity = {"format": 1, "purpose": kind, "version": sys.argv[2], "architecture": sys.argv[3]}
limits = {"manifest.json": 4096, "preflight.py": 65536}
if kind == "zapime-runtime":
    limits = {"manifest.json": 4096, **{name: 262144 for name in (
        "deploy/bootstrap.sh", "deploy/install.sh", "deploy/manage.sh", "deploy/oidc_setup.py", "deploy/preflight.py",
        "deploy/upgrade.sh",
        "scripts/android_account_login_config.py", "services/sync/compose.yml", "services/sync/Caddyfile",
        "services/sync/backup.sh", "services/sync/restore.sh", "services/sync/oidc-disabled.json",
        "services/sync/assetlinks-disabled.json", "LICENSE", "services/sync/LICENSE",
        "services/sync/deployment-state/.keep",
    )}}
try:
    with ZipFile(directory / (kind + ".zip")) as package:
        entries = package.infolist()
        if kind == "zapime-runtime" and any(item.filename == "deploy/console_setup.py" for item in entries):
            limits["deploy/console_setup.py"] = 262144
        if len(entries) != len(limits) or {item.filename for item in entries} != set(limits):
            raise ValueError()
        for item in entries:
            file_type = stat.S_IFMT(item.external_attr >> 16)
            if file_type not in (0, stat.S_IFREG) or not 0 < item.file_size <= limits[item.filename]:
                raise ValueError()
        raw = package.read("manifest.json")
        manifest = json.loads(raw)
        expected = set(identity)
        if kind == "zapime-runtime":
            expected |= {"source", "images", "archive_sha256"}
            if (not re.fullmatch(r"[a-f0-9]{40}", manifest["source"])
                    or not re.fullmatch(r"(?:[a-f0-9]{64})?", manifest["archive_sha256"])
                    or set(manifest["images"]) != {"sync", "db", "https"}):
                raise ValueError()
            for image in manifest["images"].values():
                if (set(image) != {"reference", "id"}
                        or not re.fullmatch(r"[a-z0-9][a-z0-9./:-]{1,200}(?:@sha256:[0-9a-f]{64})?", image["reference"])
                        or not re.fullmatch(r"sha256:[a-f0-9]{64}", image["id"])):
                    raise ValueError()
        if (set(manifest) != expected or any(manifest[key] != value for key, value in identity.items())
                or raw != (json.dumps(manifest, sort_keys=True) + "\n").encode()):
            raise ValueError()
        for name in limits:
            output = directory / name
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(package.read(name))
except Exception:
    raise SystemExit(1) from None
PY

if [[ $kind == zapime-runtime ]]; then
  printf 'ZapIME %s · %s · 运行包签名通过\n' "$version" "$architecture" >&2
  bash "$work/deploy/install.sh" --verified-runtime "$work" "$@"
  exit
fi
printf 'ZapIME %s · %s · 预检包签名通过\n' "$version" "$architecture" >&2
python3 "$work/preflight.py" --release-source "$source_url" "$@"
