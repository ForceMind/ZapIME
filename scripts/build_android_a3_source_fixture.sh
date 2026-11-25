#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

tools="$ANDROID_HOME/build-tools/35.0.0"
platform="$ANDROID_HOME/platforms/android-36/android.jar"
output=$1
config=$2
mkdir -p "$output/classes" "$output/dex"
python3 scripts/android_account_login_config.py platforms/android/AndroidManifest.xml "$output/AndroidManifest.xml" \
  "$output/generated/org/zapime/android/AccountLoginDeployment.java" --config "$config"
python3 - "$output/AndroidManifest.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

namespace = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", namespace)
manifest = ET.parse(sys.argv[1])
manifest.find("application").set("{" + namespace + "}testOnly", "true")
ET.SubElement(manifest.getroot(), "instrumentation", {
    "{" + namespace + "}name": "org.zapime.android.EnrollmentVaultProbe",
    "{" + namespace + "}targetPackage": "org.zapime.android",
})
manifest.write(sys.argv[1], encoding="utf-8", xml_declaration=True)
PY
"$tools/aapt2" compile --dir platforms/android/res -o "$output/resources.zip"
"$tools/aapt2" link -o "$output/source-only.apk" --manifest "$output/AndroidManifest.xml" -I "$platform" \
  "$output/resources.zip" -A "$output/assets" --java "$output/generated"
find platforms/android/java "$output/generated" -name '*.java' > "$output/java-sources.txt"
printf '%s\n' platforms/android/oidc-probe/java/{EnrollmentVaultProbe,ConsentStoreProbe,ForegroundSyncProbe,DefaultNetworkProbe}.java >> "$output/java-sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -classpath "$platform" -d "$output/classes" @"$output/java-sources.txt"
jar cf "$output/classes.jar" -C "$output/classes" .
"$tools/d8" --lib "$platform" --min-api 28 --output "$output/dex" "$output/classes.jar"
zip -q -j "$output/source-only.apk" "$output/dex/classes.dex"
mkdir -p "$output/lib/x86_64"
cp target/x86_64-linux-android/release/libzapime_core.so "$output/lib/x86_64/"
(cd "$output" && zip -q -0 source-only.apk lib/x86_64/libzapime_core.so)
"$tools/zipalign" -P 16 -f 4 "$output/source-only.apk" "$output/aligned.apk"

if [[ $# -eq 3 ]]; then
  helper=$3
  mkdir -p "$helper/classes"
  cat > "$helper/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.zapime.android.a3picker">
  <uses-sdk android:minSdkVersion="35" android:targetSdkVersion="35" />
  <application android:testOnly="true" android:debuggable="true" android:allowBackup="false" />
  <instrumentation android:name=".RecoveryPickerProbe" android:targetPackage="org.zapime.android.a3picker" />
</manifest>
XML
  "$tools/aapt2" link --manifest "$helper/AndroidManifest.xml" -I "$platform" -o "$helper/unsigned.apk"
  javac -source 8 -target 8 -encoding UTF-8 -classpath "$platform" -d "$helper/classes" \
    platforms/android/oidc-probe/java/RecoveryPickerProbe.java
  jar cf "$helper/classes.jar" -C "$helper/classes" .
  "$tools/d8" --lib "$platform" --min-api 35 --output "$helper" "$helper/classes.jar"
  zip -q -j "$helper/unsigned.apk" "$helper/classes.dex"
fi
