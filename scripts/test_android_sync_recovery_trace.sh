#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
build=target/android-sync-recovery-trace-tests
mkdir -p "$build"
javac -source 8 -target 8 -encoding UTF-8 -d "$build" \
  platforms/android/voice-tests/java/org/zapime/android/voicetest/SyncRecoveryTrace.java \
  platforms/android/voice-tests/java/org/zapime/android/voicetest/SyncRecoveryNavigation.java \
  platforms/android/tests/jvm/SyncRecoveryTraceSmoke.java \
  platforms/android/tests/jvm/SyncRecoveryNavigationSmoke.java
java -cp "$build" org.zapime.android.voicetest.SyncRecoveryTraceSmoke
java -cp "$build" org.zapime.android.voicetest.SyncRecoveryNavigationSmoke
