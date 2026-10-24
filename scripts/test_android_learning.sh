#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
build=target/android-learning-tests
mkdir -p "$build"
"${JAVAC:-javac}" --release 8 -encoding UTF-8 -d "$build" \
  platforms/android/java/org/zapime/android/EnglishLearningGate.java \
  platforms/android/java/org/zapime/android/EnglishLearningSession.java \
  platforms/android/tests/jvm/EnglishLearningGateSmoke.java \
  platforms/android/tests/jvm/EnglishLearningSessionSmoke.java
"${JAVA:-java}" -cp "$build" EnglishLearningGateSmoke
"${JAVA:-java}" -cp "$build" org.zapime.android.EnglishLearningSessionSmoke
