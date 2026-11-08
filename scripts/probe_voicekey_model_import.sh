#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
build=target/android-voicekey-model-tests
mkdir -p "$build/classes"
python3 scripts/generate_voicekey_catalog.py --check
"${JAVAC:-javac}" -source 8 -target 8 -encoding UTF-8 -d "$build/classes" platforms/android/java/org/zapime/android/VoiceKeyModelStore.java platforms/android/java/org/zapime/android/VoiceKeyModelCatalog.java platforms/android/tests/jvm/VoiceKeyModelImportProbe.java
"${JAVA:-java}" -cp "$build/classes" org.zapime.android.VoiceKeyModelImportProbe "${1:?Official fixed package path required}" "$build/official-import-report.json" "$build/official"
