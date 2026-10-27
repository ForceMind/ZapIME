// SPDX-License-Identifier: GPL-3.0-only
// ZapIME JNI ownership boundary over the pinned MSIME LocalAsr implementation.
#include <jni.h>
#include "LocalAsr.h"
#include "VoicePcmEvidence.h"
#include <nlohmann/json.hpp>
#include <algorithm>
#include <atomic>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {
using json = nlohmann::json;
struct State {
  const std::thread::id owner = std::this_thread::get_id();
  const std::shared_ptr<std::atomic_bool> cancelled = std::make_shared<std::atomic_bool>(false);
  std::unique_ptr<msime::voice::LocalAsrSession> session;
  std::mutex partial_mutex;
  std::string partial;
  std::size_t samples = 0;
  VoicePcmEvidence pcm_evidence;
  bool finished = false;
};
std::mutex handles_mutex;
std::unordered_map<jlong, std::shared_ptr<State>> handles;
jlong next_handle = 1;
std::shared_ptr<State> state(jlong handle) {
  std::lock_guard<std::mutex> lock(handles_mutex);
  const auto found = handles.find(handle);
  return found == handles.end() ? nullptr : found->second;
}
bool owner(const std::shared_ptr<State>& value) { return value && value->owner == std::this_thread::get_id(); }
jbyteArray output(JNIEnv* env, const json& value) {
  try {
    const std::string text = value.dump();
    if (text.size() > 512 * 1024) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(text.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
    return result;
  } catch (...) { return nullptr; }
}
jbyteArray error(JNIEnv* env, const char* name) {
  try { return output(env, json{{"error", name}}); } catch (...) { return nullptr; }
}
bool path(JNIEnv* env, jbyteArray input, std::string& result) {
  if (!input) return false;
  const auto count = env->GetArrayLength(input);
  if (count < 1 || count > 4096) return false;
  result.resize(static_cast<std::size_t>(count));
  env->GetByteArrayRegion(input, 0, count, reinterpret_cast<jbyte*>(result.data()));
  return !env->ExceptionCheck() && result.front() == '/' && result.find('\0') == std::string::npos;
}
}

extern "C" {
JNIEXPORT jlong JNICALL Java_org_zapime_android_NativeVoice_allocate(JNIEnv*, jclass) {
  try {
    auto value = std::make_shared<State>();
    std::lock_guard<std::mutex> lock(handles_mutex);
    if (handles.size() >= 4 || next_handle == std::numeric_limits<jlong>::max()) return 0;
    const auto handle = next_handle++;
    handles.emplace(handle, std::move(value));
    return handle;
  } catch (...) { return 0; }
}
JNIEXPORT jbyteArray JNICALL Java_org_zapime_android_NativeVoice_start(JNIEnv* env, jclass, jlong handle, jbyteArray model, jbyteArray runtime) {
  const auto value = state(handle);
  if (!owner(value)) return error(env, "invalid_owner");
  if (value->session || value->finished) return error(env, "invalid_session");
  try {
    if (value->cancelled->load()) return error(env, "cancelled");
    std::string model_path, runtime_path;
    if (!path(env, model, model_path) || !path(env, runtime, runtime_path))
      return env->ExceptionCheck() ? nullptr : error(env, "invalid_path");
    msime::voice::set_sherpa_library_path(runtime_path);
    if (!msime::voice::is_local_model_dir(model_path)) return error(env, "model_path_rejected");
    if (!msime::voice::sherpa_runtime_available()) return error(env, "runtime_unavailable");
    msime::voice::LocalAsrOptions options;
    options.model_dir = model_path; options.language = "auto"; options.threads = 2;
    const std::weak_ptr<State> weak = value;
    value->session = std::make_unique<msime::voice::LocalAsrSession>(options, [weak](const std::string& text) {
      if (text.size() > 65536) throw msime::voice::VoiceError("transcript limit");
      if (auto current = weak.lock()) { std::lock_guard<std::mutex> lock(current->partial_mutex); current->partial = text; }
    }, value->cancelled);
    if (value->cancelled->load()) { value->session.reset(); return error(env, "cancelled"); }
    return output(env, json{{"ready", true}});
  } catch (...) { return error(env, value->cancelled->load() ? "cancelled" : "model_failure"); }
}
JNIEXPORT jbyteArray JNICALL Java_org_zapime_android_NativeVoice_accept(JNIEnv* env, jclass, jlong handle, jshortArray pcm, jint count) {
  const auto value = state(handle);
  if (!owner(value)) return error(env, "invalid_owner");
  if (!value->session || value->finished) return error(env, "invalid_session");
  if (!pcm || count < 1 || count > 1600 || count > env->GetArrayLength(pcm)) return error(env, "invalid_audio");
  if (value->samples + static_cast<std::size_t>(count) > 16000 * 60) return error(env, "audio_limit");
  try {
    if (value->cancelled->load()) return error(env, "cancelled");
    std::vector<jshort> samples(static_cast<std::size_t>(count));
    env->GetShortArrayRegion(pcm, 0, count, samples.data());
    if (env->ExceptionCheck()) return nullptr;
    std::vector<float> floats(samples.size());
    for (std::size_t i = 0; i < samples.size(); ++i) floats[i] = static_cast<float>(samples[i]) / 32768.0f;
    value->session->accept(floats.data(), floats.size()); value->samples += samples.size();
    // Commit evidence only after successful acceptance of the validated prefix.
    value->pcm_evidence.accepted(samples.data(), samples.size());
    std::lock_guard<std::mutex> lock(value->partial_mutex);
    return output(env, json{{"text", value->pcm_evidence.visible_text(value->partial)},
                            {"has_nonzero_pcm", value->pcm_evidence.has_nonzero_pcm()}});
  } catch (...) { return error(env, value->cancelled->load() ? "cancelled" : "decode_failure"); }
}
JNIEXPORT jbyteArray JNICALL Java_org_zapime_android_NativeVoice_finish(JNIEnv* env, jclass, jlong handle) {
  const auto value = state(handle);
  if (!owner(value)) return error(env, "invalid_owner");
  if (!value->session || value->finished) return error(env, "invalid_session");
  try {
    if (value->cancelled->load()) return error(env, "cancelled");
    // With no nonzero input there is no acoustic evidence to justify model
    // text. Do not decode an artificial zero tail in this terminal path.
    const auto text = value->pcm_evidence.has_nonzero_pcm() ? value->session->finish() : std::string{};
    value->finished = true;
    if (text.size() > 65536) return error(env, "text_limit");
    return output(env, json{{"text", text}, {"has_nonzero_pcm", value->pcm_evidence.has_nonzero_pcm()}});
  } catch (...) { return error(env, value->cancelled->load() ? "cancelled" : "decode_failure"); }
}
JNIEXPORT void JNICALL Java_org_zapime_android_NativeVoice_cancel(JNIEnv*, jclass, jlong handle) {
  if (const auto value = state(handle)) value->cancelled->store(true);
}
JNIEXPORT jboolean JNICALL Java_org_zapime_android_NativeVoice_close(JNIEnv*, jclass, jlong handle) {
  auto value = state(handle);
  if (!owner(value)) return JNI_FALSE;
  value->cancelled->store(true); value->session.reset();
  { std::lock_guard<std::mutex> lock(value->partial_mutex); value->partial.clear(); }
  { std::lock_guard<std::mutex> lock(handles_mutex); handles.erase(handle); }
  return JNI_TRUE;
}
JNIEXPORT jint JNICALL Java_org_zapime_android_NativeVoice_release(JNIEnv*, jclass, jlong idle_ms) {
  try {
    const auto bounded = std::clamp<jlong>(idle_ms, 0, 86400000);
    return static_cast<jint>(msime::voice::release_idle_local_models(std::chrono::milliseconds(bounded)));
  } catch (...) { return 0; }
}
}
