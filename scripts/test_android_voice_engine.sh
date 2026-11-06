#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
build=target/android-voice-engine-tests
mkdir -p "$build"
"${JAVAC:-javac}" -source 8 -target 8 -encoding UTF-8 -d "$build" platforms/android/java/org/zapime/android/VoicePreviewState.java platforms/android/java/org/zapime/android/VoiceEngineSession.java platforms/android/tests/jvm/VoiceEngineSessionSmoke.java
"${JAVA:-java}" -cp "$build" org.zapime.android.VoiceEngineSessionSmoke
