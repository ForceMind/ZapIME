#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
build=target/android-selection-tests
mkdir -p "$build"
javac -source 8 -target 8 -encoding UTF-8 -d "$build" \
  platforms/android/java/org/zapime/android/SelectionEchoTracker.java \
  platforms/android/tests/jvm/SelectionEchoTrackerSmoke.java
java -cp "$build" SelectionEchoTrackerSmoke
