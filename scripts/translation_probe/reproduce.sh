#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Explicit developer experiment, not a keyboard feature or server deployment.
set -euo pipefail
probe_scripts=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
probe_repo=$(CDPATH= cd -- "$probe_scripts/../.." && pwd)
probe_root="${ZAPIME_TRANSLATION_WORKDIR:-$probe_repo/target/translation-probe}"
python3 -c 'import platform,sys; assert sys.version_info[:2] == (3,12) and platform.system() == "Linux" and platform.machine() == "x86_64", "This hash-pinned reproduction is Linux x86-64 / Python 3.12 only"'
if [[ -e "$probe_root/ct2-int8" ]]; then
  echo 'Model output already exists; choose a fresh work directory or use probe.py infer.' >&2
  exit 1
fi
mkdir -p "$probe_root"
probe_root=$(CDPATH= cd -- "$probe_root" && pwd)
export ZAPIME_TRANSLATION_WORKDIR="$probe_root"
mkdir -p "$probe_root/tmp" "$probe_root/wheels" "$probe_root/pip-cache" "$probe_root/cache"
export TMPDIR="$probe_root/tmp"
export XDG_CACHE_HOME="$probe_root/cache"
python3 -m venv "$probe_root/venv"
probe_python="$probe_root/venv/bin/python"
"$probe_python" -m pip --isolated --cache-dir "$probe_root/pip-cache" download --index-url https://pypi.org/simple --only-binary=:all: --require-hashes --dest "$probe_root/wheels" -r "$probe_scripts/requirements-linux-py312.txt"
"$probe_python" -m pip --isolated --cache-dir "$probe_root/pip-cache" install --no-index --find-links "$probe_root/wheels" --require-hashes -r "$probe_scripts/requirements-linux-py312.txt"
if [[ ! -f "$probe_root/opus-2020-07-17.zip" ]]; then
  curl --fail --location --max-time 180 --max-filesize 600000000 --limit-rate 20M --proto '=https' --proto-redir '=https' --tlsv1.2 --output "$probe_root/opus-2020-07-17.zip" https://object.pouta.csc.fi/Tatoeba-MT-models/zho-eng/opus-2020-07-17.zip
fi
"$probe_python" "$probe_scripts/prepare_model.py"
"$probe_python" "$probe_scripts/probe.py" convert
"$probe_python" "$probe_scripts/probe.py" infer
"$probe_python" "$probe_scripts/probe.py" infer --offline
"$probe_python" "$probe_scripts/probe.py" infer --daily
"$probe_python" "$probe_scripts/probe.py" infer --daily --offline
