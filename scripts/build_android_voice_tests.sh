#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
voicekey_only=false
candidate_only=false
case "$#" in
  0) ;;
  1) case "$1" in
       --voicekey-only) voicekey_only=true ;;
       --candidate-only) candidate_only=true ;;
       *) echo 'Usage: build_android_voice_tests.sh [--voicekey-only|--candidate-only]' >&2; exit 2 ;;
     esac ;;
  *) echo 'Usage: build_android_voice_tests.sh [--voicekey-only|--candidate-only]' >&2; exit 2 ;;
esac
sdk=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}
tools="$sdk/build-tools/35.0.0"
android_jar="$sdk/platforms/android-36/android.jar"
build="$root/target/android-voice-tests"
if ! "$candidate_only"; then
  [[ -s target/voice-probe/synthetic.wav ]] || { echo 'Synthetic fixture must be prepared first' >&2; exit 1; }
fi
if ! "$voicekey_only" && ! "$candidate_only"; then
  [[ -s target/ZapIME-XASR-model.zip ]] || { echo 'Verified model bundle must be prepared first' >&2; exit 1; }
fi
rm -rf "$build"
mkdir -p "$build"/{classes,dex,assets/voice-fixtures,assets/licenses}
cp LICENSE "$build/assets/licenses/GPL-3.0.txt"
if "$candidate_only"; then
  cat > "$build/assets/NOTICE.txt" <<'NOTICE'
ZapIME synthetic Android candidate ranking acceptance harness, GPL-3.0-only.
Corresponding source: https://github.com/ForceMind/ZapIME
This harness embeds no model weights, PCM, personal dictionaries or user input.
Fixed synthetic dictionary rows are constructed by the instrumentation probe.
The actual pinned candidate model is in the separate target APK with its notices.
NOTICE
elif "$voicekey_only"; then
  cat > "$build/assets/NOTICE.txt" <<'NOTICE'
ZapIME synthetic Android VoiceKey-only acceptance harness, GPL-3.0-only.
Corresponding source: https://github.com/ForceMind/ZapIME
Only fixed public synthetic PCM is embedded; no X-ASR or VoiceKey model weights.
The official VoiceKey model is explicitly imported through SAF on dedicated CI.
Product ASR native libraries are in the separate target APK with their notices.
NOTICE
else
  cp target/ZapIME-XASR-model.zip "$build/assets/voice-fixtures/model.zip"
  cp third_party/licenses/X-ASR-Apache-2.0.txt "$build/assets/licenses/X-ASR-Apache-2.0.txt"
  cat > "$build/assets/NOTICE.txt" <<'NOTICE'
ZapIME synthetic Android acceptance harness, GPL-3.0-only.
Corresponding source: https://github.com/ForceMind/ZapIME
Only this test-only APK embeds the pinned model ZIP and synthetic PCM.
X-ASR model and ONNX conversion attribution/LICENSE/NOTICE remain in model.zip.
Product ASR native libraries are in the separate target APK with their notices.
NOTICE
fi
if ! "$candidate_only"; then
  python3 - target/voice-probe/synthetic.wav "$build/assets/voice-fixtures/synthetic.pcm" <<'PY'
from pathlib import Path
import sys,wave
with wave.open(sys.argv[1]) as source:
    if source.getnchannels()!=1 or source.getsampwidth()!=2 or source.getframerate()!=16000 or not 16000<=source.getnframes()<=16000*60:
        raise SystemExit('Bounded synthetic PCM16 mono 16kHz fixture required')
    Path(sys.argv[2]).write_bytes(source.readframes(source.getnframes()))
PY
fi
"$tools/aapt2" link -o "$build/base.apk" --manifest platforms/android/voice-tests/AndroidManifest.xml -I "$android_jar" -A "$build/assets" -0 zip -0 pcm
find platforms/android/voice-tests/java -name '*.java' > "$build/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -classpath "$android_jar" -d "$build/classes" @"$build/sources.txt"
find "$build/classes" -name '*.class' > "$build/classes.txt"
"$tools/d8" --lib "$android_jar" --min-api 28 --output "$build/dex" @"$build/classes.txt"
cp "$build/dex/classes.dex" "$build/classes.dex"
(cd "$build" && zip -q -u base.apk classes.dex)
"$tools/zipalign" -f 4 "$build/base.apk" "$build/aligned.apk"
"$tools/apksigner" sign --ks target/android-dev.keystore --ks-pass pass:android --key-pass pass:android --out "$build/ZapIME-voice-tests.apk" "$build/aligned.apk"
