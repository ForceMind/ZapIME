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
  # This independent raw-tensor oracle is confined to the x86_64 CI harness.
  # It loads the target APK's pinned ORT; it embeds neither weights nor another runtime.
  ndk="$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64"
  python3 integrations/candidate/tests/generate_oracle_fixture.py --check
  mkdir -p "$build/lib/x86_64"
  cp integrations/candidate/tests/oracle-fixture.json "$build/assets/candidate-oracle-fixture.json"
  "$ndk/bin/x86_64-linux-android28-clang++" -std=c++17 -O2 -shared -fPIC -Wall -Wextra -Werror \
    -pthread -static-libstdc++ -Wl,--no-undefined -Wl,-z,max-page-size=16384 \
    -Wl,-soname,libzapime_candidate_oracle.so \
    integrations/candidate/tests/raw_ort_oracle.cpp integrations/candidate/tests/oracle_jni.cpp \
    -Iintegrations/candidate/tests -Itarget/candidate-runtime/include -Itarget/voice-json/single_include \
    -ldl -o "$build/lib/x86_64/libzapime_candidate_oracle.so"
  python3 - "$build/lib/x86_64/libzapime_candidate_oracle.so" <<'PY'
from pathlib import Path
import sys
sys.path.insert(0, 'scripts')
from prepare_android_voice import verify_elf
from check_apk import elf_metadata
library = Path(sys.argv[1])
verify_elf(library, 'x86_64')
needed, exports = elf_metadata(library.read_bytes())
if 'libc.so' not in needed or not needed <= {'libc.so', 'libm.so', 'libdl.so'}:
    raise SystemExit('Unexpected raw-oracle runtime dependency')
if 'Java_org_zapime_android_voicetest_NativeCandidateOracle_evaluate' not in exports:
    raise SystemExit('Missing raw-oracle JNI entry point')
print('PASS test-only raw ORT oracle JNI, dependency and 16 KiB ELF checks')
PY
  cp target/voice-json/LICENSE.MIT "$build/assets/licenses/nlohmann-json-MIT.txt"
  cp vendor/msime/platforms/linux/data/licenses/onnxruntime-MIT.txt "$build/assets/licenses/onnxruntime-MIT.txt"
  cp "$ndk/NOTICE" "$build/assets/licenses/ndk-toolchain.txt"
  cp "$ndk/sysroot/NOTICE" "$build/assets/licenses/ndk-sysroot.txt"
  cat > "$build/assets/NOTICE.txt" <<'NOTICE'
ZapIME synthetic Android candidate ranking acceptance harness, GPL-3.0-only.
Corresponding source: https://github.com/ForceMind/ZapIME
This harness embeds no model weights, PCM, personal dictionaries or user input.
Fixed synthetic dictionary rows are constructed by the instrumentation probe.
The actual pinned candidate model is in the separate target APK with its notices.
The independent raw tensor oracle and its fixed upstream feed fixture are test-only.
JSON and native toolchain notices accompany the oracle; ORT is loaded from the target APK.
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
if "$candidate_only"; then
  (cd "$build" && zip -q -u base.apk lib/x86_64/libzapime_candidate_oracle.so)
fi
"$tools/zipalign" -P 16 -f 4 "$build/base.apk" "$build/aligned.apk"
"$tools/apksigner" sign --ks target/android-dev.keystore --ks-pass pass:android --key-pass pass:android --out "$build/ZapIME-voice-tests.apk" "$build/aligned.apk"
