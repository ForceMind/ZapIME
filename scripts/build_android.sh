#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
sdk=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the Android SDK}
abi=${1:-arm64-v8a}
case "$abi" in
  arm64-v8a) rust_target=aarch64-linux-android; compiler=aarch64-linux-android28-clang ;;
  x86_64) rust_target=x86_64-linux-android; compiler=x86_64-linux-android28-clang ;;
  *) echo 'Supported ABIs: arm64-v8a, x86_64' >&2; exit 1 ;;
esac
ndk="$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
tools="$sdk/build-tools/35.0.0"
android_jar="$sdk/platforms/android-36/android.jar"
[[ -x "$ndk/$compiler" && -x "$tools/aapt2" && -f "$android_jar" ]] || { echo 'Android API 36, build-tools 35 and NDK 28.2.13676358 required' >&2; exit 1; }
python3 scripts/verify_upstream.py
python3 scripts/fetch_resources.py
# 构建密钥只用于开发包；正式签名在发布阶段另行配置。
export PATH="$ndk:$PATH"
linker_var="CARGO_TARGET_$(echo "$rust_target" | tr '[:lower:]-' '[:upper:]_')_LINKER"
cc_var="CC_${rust_target//-/_}"
export "$linker_var=$ndk/$compiler" "$cc_var=$ndk/$compiler"
cargo build --locked -p zapime-core --release --target "$rust_target"
build="$root/target/android-$abi"
rm -rf "$build"
mkdir -p "$build"/{classes,dex,assets/dictionary,assets/licenses,lib/"$abi"}
cp target/resources/* "$build/assets/dictionary/"
cp LICENSE "$build/assets/licenses/GPL-3.0.txt"
cp third_party/licenses/* "$build/assets/licenses/"
cp third_party/NOTICE.txt "$build/assets/NOTICE.txt"
python3 scripts/collect_rust_notices.py "$rust_target" "$build/assets/rust-licenses"
cp "target/$rust_target/release/libzapime_core.so" "$build/lib/$abi/"
"$tools/aapt2" compile --dir platforms/android/res -o "$build/resources.zip"
"$tools/aapt2" link -o "$build/base.apk" --manifest platforms/android/AndroidManifest.xml -I "$android_jar" -A "$build/assets" "$build/resources.zip" --java "$build/generated"
find platforms/android/java "$build/generated" -name '*.java' > "$build/java-sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -classpath "$android_jar" -d "$build/classes" @"$build/java-sources.txt"
find "$build/classes" -name '*.class' > "$build/classes.txt"
"$tools/d8" --lib "$android_jar" --min-api 28 --output "$build/dex" @"$build/classes.txt"
cp "$build/dex/classes.dex" "$build/classes.dex"
(cd "$build" && zip -q -u base.apk classes.dex lib/"$abi"/libzapime_core.so)
"$tools/zipalign" -P 16 -f 4 "$build/base.apk" "$build/aligned.apk"
key="$root/target/android-dev.keystore"
if [[ ! -f "$key" ]]; then
  keytool -genkeypair -keystore "$key" -storepass android -keypass android -alias zapime-dev -dname 'CN=ZapIME Development' -keyalg RSA -keysize 2048 -validity 3650 >/dev/null 2>&1
fi
"$tools/apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android --out "$build/ZapIME-0.1.0-dev-$abi.apk" "$build/aligned.apk"
"$tools/apksigner" verify --verbose "$build/ZapIME-0.1.0-dev-$abi.apk"
"$tools/zipalign" -c -P 16 4 "$build/ZapIME-0.1.0-dev-$abi.apk"
python3 scripts/check_apk.py "$build/ZapIME-0.1.0-dev-$abi.apk" "$tools/aapt2"
