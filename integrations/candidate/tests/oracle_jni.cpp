// SPDX-License-Identifier: GPL-3.0-only
// This source is packaged only in the synthetic candidate test APK.
#include "raw_ort_oracle.h"
#include <jni.h>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::string ascii(JNIEnv* env, jstring value, jsize maximum) {
  if (!value) throw std::runtime_error("candidate_oracle_argument");
  const auto length = env->GetStringLength(value);
  if (env->ExceptionCheck() || length < 1 || length > maximum)
    throw std::runtime_error("candidate_oracle_argument");
  // The fixture generator emits ASCII JSON escapes; these test APK paths are
  // also ASCII. Read UTF-16 explicitly so modified UTF-8 never changes hashes.
  std::vector<jchar> units(static_cast<std::size_t>(length));
  env->GetStringRegion(value, 0, length, units.data());
  if (env->ExceptionCheck()) throw std::runtime_error("candidate_oracle_argument");
  std::string result;
  result.reserve(units.size());
  for (auto unit : units) {
    if (unit < 1 || unit > 127) throw std::runtime_error("candidate_oracle_argument");
    result.push_back(static_cast<char>(unit));
  }
  return result;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_zapime_android_voicetest_NativeCandidateOracle_evaluate(
    JNIEnv* env, jclass, jstring model, jstring runtime, jstring fixture) {
  try {
    const auto output = zapime::candidate::oracle::evaluate(
        ascii(env, model, 4096), ascii(env, runtime, 4096), ascii(env, fixture, 65536));
    return env->NewStringUTF(output.c_str());
  } catch (...) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    return nullptr;
  }
}
