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
python3 scripts/prepare_candidate_model.py --check-catalog
python3 scripts/prepare_candidate_model.py --destination "$build/assets/candidate-model" --headers "$root/target/candidate-runtime/include"
# 复用原语音构建已经校验的 ORT 动态库；这里只添加独立候选 JNI。
[[ -s "$output/libonnxruntime.so" && -s target/voice-json/single_include/nlohmann/json.hpp ]]
"$ndk/bin/${compiler_target}28-clang++" -std=c++17 -O2 -shared -fPIC -Wall -Wextra -Werror \
  -pthread -static-libstdc++ -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-soname,libzapime_candidate.so \
  platforms/android/native/candidate_jni.cpp integrations/candidate/CandidateRanker.cpp \
  -Iintegrations/candidate -Itarget/candidate-runtime/include -Itarget/voice-json/single_include \
  -ldl -o "$output/libzapime_candidate.so"
python3 - "$output/libzapime_candidate.so" "$abi" <<'PY'
from pathlib import Path
import sys
sys.path.insert(0, 'scripts')
from check_apk import check_candidate_native
check_candidate_native(Path(sys.argv[1]).read_bytes(), sys.argv[2])
print('PASS candidate JNI symbols, dependencies and 16 KiB LOAD alignment')
PY
