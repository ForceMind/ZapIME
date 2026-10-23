#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
sdk=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}
tools="$sdk/build-tools/35.0.0"
android_jar="$sdk/platforms/android-36/android.jar"
build="$root/target/android-voice-tests"
[[ -s target/ZapIME-XASR-model.zip && -s target/voice-probe/synthetic.wav ]] || { echo 'Verified model bundle and synthetic fixture must be prepared first' >&2; exit 1; }
rm -rf "$build"
mkdir -p "$build"/{classes,dex,assets/voice-fixtures,assets/licenses}
cp target/ZapIME-XASR-model.zip "$build/assets/voice-fixtures/model.zip"
cp LICENSE "$build/assets/licenses/GPL-3.0.txt"
cp third_party/licenses/X-ASR-Apache-2.0.txt "$build/assets/licenses/X-ASR-Apache-2.0.txt"
cat > "$build/assets/NOTICE.txt" <<'NOTICE'
ZapIME synthetic Android acceptance harness, GPL-3.0-only.
Corresponding source: https://github.com/ForceMind/ZapIME
Only this test-only APK embeds the pinned model ZIP and synthetic PCM.
X-ASR model and ONNX conversion attribution/LICENSE/NOTICE remain in model.zip.
Product ASR native libraries are in the separate target APK with their notices.
NOTICE
python3 - target/voice-probe/synthetic.wav "$build/assets/voice-fixtures/synthetic.pcm" <<'PY'
from pathlib import Path
import sys,wave
with wave.open(sys.argv[1]) as source:
    if source.getnchannels()!=1 or source.getsampwidth()!=2 or source.getframerate()!=16000 or not 16000<=source.getnframes()<=16000*60:
        raise SystemExit('Bounded synthetic PCM16 mono 16kHz fixture required')
    Path(sys.argv[2]).write_bytes(source.readframes(source.getnframes()))
PY
"$tools/aapt2" link -o "$build/base.apk" --manifest platforms/android/voice-tests/AndroidManifest.xml -I "$android_jar" -A "$build/assets" -0 zip -0 pcm
find platforms/android/voice-tests/java -name '*.java' > "$build/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -classpath "$android_jar" -d "$build/classes" @"$build/sources.txt"
find "$build/classes" -name '*.class' > "$build/classes.txt"
"$tools/d8" --lib "$android_jar" --min-api 28 --output "$build/dex" @"$build/classes.txt"
cp "$build/dex/classes.dex" "$build/classes.dex"
(cd "$build" && zip -q -u base.apk classes.dex)
"$tools/zipalign" -f 4 "$build/base.apk" "$build/aligned.apk"
"$tools/apksigner" sign --ks target/android-dev.keystore --ks-pass pass:android --key-pass pass:android --out "$build/ZapIME-voice-tests.apk" "$build/aligned.apk"
