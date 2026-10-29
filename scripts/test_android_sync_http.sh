#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
build=target/android-sync-http-tests
mkdir -p "$build/classes"
fixture="$build/fixture-$$.p12"
trap 'rm -f "$fixture"' EXIT
keytool -genkeypair -alias fixture -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=localhost' -ext 'SAN=dns:localhost' -storetype PKCS12 -keystore "$fixture" -storepass changeit -keypass changeit -noprompt >/dev/null 2>&1
"${JAVAC:-javac}" -source 8 -target 8 -encoding UTF-8 -d "$build/classes" platforms/android/java/org/zapime/android/SyncHttp.java platforms/android/tests/jvm/SyncHttpSmoke.java
"${JAVA:-java}" --add-modules jdk.httpserver -cp "$build/classes" org.zapime.android.SyncHttpSmoke "$fixture" "$build/report.json"
