#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
# Explicit preparation uses the existing version/hash-locked Linux Py3.12
# environment from scripts/translation_probe/reproduce.sh; no new runtime.
probe_python="$root/target/translation-probe/venv/bin/python"
test -x "$probe_python"
"$probe_python" scripts/translation_probe_2021/prepare.py
"$probe_python" scripts/translation_probe_2021/test_preprocessing.py
"$probe_python" scripts/translation_probe_2021/compare.py
