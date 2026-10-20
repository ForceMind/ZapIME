#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
sdk=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}
tools="$sdk/build-tools/35.0.0"
android_jar="$sdk/platforms/android-36/android.jar"
build="$root/target/android-tests"
mkdir -p "$build"/{classes,dex}
"$tools/aapt2" link -o "$build/base.apk" --manifest platforms/android/tests/AndroidManifest.xml -I "$android_jar"
find platforms/android/tests/java -name '*.java' > "$build/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -classpath "$android_jar" -d "$build/classes" @"$build/sources.txt"
find "$build/classes" -name '*.class' > "$build/classes.txt"
"$tools/d8" --lib "$android_jar" --min-api 28 --output "$build/dex" @"$build/classes.txt"
cp "$build/dex/classes.dex" "$build/classes.dex"
(cd "$build" && zip -q -u base.apk classes.dex)
"$tools/zipalign" -f 4 "$build/base.apk" "$build/aligned.apk"
"$tools/apksigner" sign --ks target/android-dev.keystore --ks-pass pass:android --key-pass pass:android --out "$build/ZapIME-tests.apk" "$build/aligned.apk"
