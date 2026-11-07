#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
build=target/android-sync-round-tests
mkdir -p "$build/classes"
"${JAVAC:-javac}" -source 8 -target 8 -encoding UTF-8 -d "$build/classes" platforms/android/java/org/zapime/android/SyncRound.java platforms/android/tests/jvm/SyncRoundSmoke.java
"${JAVA:-java}" -cp "$build/classes" org.zapime.android.SyncRoundSmoke
