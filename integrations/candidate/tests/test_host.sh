#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
candidate_root=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$candidate_root"
candidate_model=${1:-"$candidate_root/target/candidate-model/msime-keystroke-8m-rerank-v2"}
candidate_build=${2:-"$candidate_root/target/candidate-host"}
candidate_python=${CANDIDATE_TEST_PYTHON:-python3}
candidate_jdk=${JAVA_HOME:?Set JAVA_HOME to a JDK with javac and JNI headers}
candidate_cxx=${CXX:-g++}
candidate_model=$(cd "$candidate_model" && pwd)
mkdir -p "$candidate_build/java"
candidate_build=$(cd "$candidate_build" && pwd)
candidate_runtime=$("$candidate_python" -c 'import pathlib,onnxruntime; print(next((pathlib.Path(onnxruntime.__file__).parent/"capi").glob("libonnxruntime.so*")).resolve())')
candidate_includes=(-Itarget/candidate-runtime/include -Itarget/voice-json/single_include -Iintegrations/candidate)
candidate_flags=(-std=c++17 -O2 -Wall -Wextra -Werror -pedantic)
"$candidate_cxx" "${candidate_flags[@]}" "${candidate_includes[@]}" \
  integrations/candidate/CandidateRanker.cpp integrations/candidate/tests/ranker_probe.cpp \
  -ldl -pthread -o "$candidate_build/ranker_probe"
"$candidate_cxx" "${candidate_flags[@]}" "${candidate_includes[@]}" -shared -fPIC \
  -I"$candidate_jdk/include" -I"$candidate_jdk/include/linux" \
  platforms/android/native/candidate_jni.cpp integrations/candidate/CandidateRanker.cpp \
  -Wl,--no-undefined -ldl -pthread -o "$candidate_build/libzapime_candidate.so"
"$candidate_jdk/bin/javac" --release 8 -d "$candidate_build/java" \
  platforms/android/java/org/zapime/android/NativeCandidateRanker.java \
  integrations/candidate/tests/java/org/zapime/android/NativeCandidateRankerSmoke.java
"$candidate_python" integrations/candidate/tests/real_model_test.py \
  --model-dir "$candidate_model" --probe "$candidate_build/ranker_probe" \
  --runtime "$candidate_runtime" --report "$candidate_build/native-report.json"
"$candidate_jdk/bin/java" -Djava.library.path="$candidate_build" -cp "$candidate_build/java" \
  org.zapime.android.NativeCandidateRankerSmoke "$candidate_model" "$candidate_runtime" \
  "$candidate_build/jni-report.json"
