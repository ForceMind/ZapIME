#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Explicit developer experiment: not an installer, IME model pack, or service.
set -euo pipefail
qwen_scripts=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
python3 "$qwen_scripts/prepare.py"
python3 "$qwen_scripts/test_probe.py"
python3 "$qwen_scripts/run.py"
python3 "$qwen_scripts/run.py" --offline
python3 "$qwen_scripts/compare.py"
