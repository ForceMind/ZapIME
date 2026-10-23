#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
sdk=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}
abi=${1:?ABI required}
build=${2:?Android staging directory required}
case "$abi" in
  arm64-v8a) compiler_target=aarch64-linux-android ;;
  x86_64) compiler_target=x86_64-linux-android ;;
  *) echo 'Unsupported ABI' >&2; exit 1 ;;
esac
ndk="$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64"
output="$build/lib/$abi"
python3 scripts/prepare_android_voice.py "$abi" "$output"
"$ndk/bin/${compiler_target}28-clang++" -std=c++17 -O2 -shared -fPIC -Wall -Wextra -Werror \
  -static-libstdc++ -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-soname,libzapime_voice.so \
  platforms/android/native/voice_jni.cpp vendor/msime/shared/voice/LocalAsr.cpp \
  -Ivendor/msime/shared/voice -Ivendor/msime/shared/voice/third_party \
  -Itarget/voice-json/single_include -ldl -o "$output/libzapime_voice.so"
python3 - "$output" "$abi" "$ndk/bin/llvm-readelf" <<'PY'
from pathlib import Path
import re,subprocess,sys
sys.path.insert(0, 'scripts')
from prepare_android_voice import verify_elf
directory=Path(sys.argv[1]);verify_elf(directory/'libzapime_voice.so',sys.argv[2])
allowed={'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libonnxruntime.so'}
for name in ('libonnxruntime.so','libsherpa-onnx-c-api.so','libzapime_voice.so'):
    dynamic=subprocess.check_output([sys.argv[3],'-d',str(directory/name)],text=True)
    needed=set(re.findall(r'Shared library: \[([^\]]+)\]',dynamic))
    if not needed or not needed<=allowed:raise SystemExit('Unexpected native dependencies: '+name)
    if name=='libsherpa-onnx-c-api.so' and 'libonnxruntime.so' not in needed:raise SystemExit('Missing ONNX dependency')
    print('PASS voice dynamic dependency allowlist:',name)
PY
notices="$build/assets/voice-licenses"
mkdir -p "$notices"
cp vendor/msime/shared/voice/third_party/sherpa-onnx/LICENSE "$notices/sherpa-onnx-Apache-2.0.txt"
cp vendor/msime/platforms/linux/data/licenses/onnxruntime-MIT.txt "$notices/onnxruntime-MIT.txt"
cp vendor/msime/platforms/linux/data/licenses/onnxruntime-ThirdPartyNotices.txt "$notices/onnxruntime-ThirdPartyNotices.txt"
cp target/voice-json/LICENSE.MIT "$notices/nlohmann-json-MIT.txt"
cp "$ndk/NOTICE" "$notices/ndk-toolchain.txt"
cp "$ndk/sysroot/NOTICE" "$notices/ndk-sysroot.txt"
python3 - "$build/assets/voice-model.json" <<'PY'
import json,sys
from pathlib import Path
sys.path.insert(0, 'scripts')
from prepare_android_voice import model_config
Path(sys.argv[1]).write_text(json.dumps(model_config(),ensure_ascii=False,indent=2)+'\n')
PY
