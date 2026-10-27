#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"
mkdir -p target/voice-pcm-evidence
"${CXX:-c++}" -std=c++17 -O2 -Wall -Wextra -Werror -pedantic \
  -Iplatforms/android/native platforms/android/tests/native/VoicePcmEvidenceSmoke.cpp \
  -o target/voice-pcm-evidence/smoke
target/voice-pcm-evidence/smoke
