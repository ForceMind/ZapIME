#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -Eeuo pipefail
umask 077

usage() {
  cat <<'EOF'
ZapIME 服务器预检
  bash bootstrap.sh (--source HTTPS_URL | --bundle DIRECTORY) --version VERSION --key-sha256 SHA256 -- [预检参数]
  --source   包含 VERSION/amd64 或 VERSION/arm64 的发布目录
  --bundle   包含 zapime-preflight.zip、zapime-preflight.sig 和 release-public.pem 的本地目录
  --key-sha256 从独立可信渠道取得的发布公钥文件 SHA-256
  预检参数包括 --domain、--proxy caddy|existing、--r2-endpoint 和 --offline。
EOF
}

fail() { printf 'ZapIME 预检失败 · %s\n' "$1" >&2; exit 1; }

source_url= bundle= version= key_hash=
while (( $# )); do
  case "$1" in
    --source) source_url=${2:?缺少发布地址}; shift 2 ;;
    --bundle) bundle=${2:?缺少本地目录}; shift 2 ;;
    --version) version=${2:?缺少版本}; shift 2 ;;
    --key-sha256) key_hash=${2:?缺少公钥 SHA-256}; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    --) shift; break ;;
    *) usage >&2; fail '未知入口参数' ;;
  esac
done

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

work=$(mktemp -d)
cleanup() {
  # Only the three admitted downloads and the two output files belong here.
  rm -f -- "$work"/{release-public.pem,zapime-preflight.zip,zapime-preflight.sig,preflight.py,manifest.json}
  rmdir -- "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

copy_artifact() {
  local name=$1 limit=$2
  if [[ -n $bundle ]]; then
    [[ -f $bundle/$name && ! -L $bundle/$name ]] || fail "本地包缺少安全的 $name"
    head -c "$((limit + 1))" -- "$bundle/$name" > "$work/$name" || fail "读取 $name 失败"
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
copy_artifact zapime-preflight.sig 16384
copy_artifact zapime-preflight.zip 262144
openssl dgst -sha256 -verify "$work/release-public.pem" -signature "$work/zapime-preflight.sig" \
  "$work/zapime-preflight.zip" >/dev/null 2>&1 || fail '预检包签名不符'

python3 - "$work" "$version" "$architecture" <<'PY' || fail '已签名包的结构、版本或架构不符'
import json
from pathlib import Path
import stat
import sys
from zipfile import ZipFile

directory = Path(sys.argv[1])
identity = {"format": 1, "purpose": "zapime-preflight", "version": sys.argv[2], "architecture": sys.argv[3]}
limits = {"manifest.json": 4096, "preflight.py": 65536}
try:
    with ZipFile(directory / "zapime-preflight.zip") as package:
        entries = package.infolist()
        if len(entries) != len(limits) or {item.filename for item in entries} != set(limits):
            raise ValueError()
        for item in entries:
            file_type = stat.S_IFMT(item.external_attr >> 16)
            if file_type not in (0, stat.S_IFREG) or not 0 < item.file_size <= limits[item.filename]:
                raise ValueError()
        if package.read("manifest.json") != (json.dumps(identity, sort_keys=True) + "\n").encode("utf-8"):
            raise ValueError()
        for name in limits:
            (directory / name).write_bytes(package.read(name))
except Exception:
    raise SystemExit(1) from None
PY

printf 'ZapIME %s · %s · 预检包签名通过\n' "$version" "$architecture" >&2
python3 "$work/preflight.py" --release-source "$source_url" "$@"
