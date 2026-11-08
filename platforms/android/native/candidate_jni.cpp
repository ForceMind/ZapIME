// SPDX-License-Identifier: GPL-3.0-only
// ZapIME ownership boundary for the offline candidate model. No JNI call here
// contacts an editor, selects a candidate, performs network I/O, or logs text.
#include <jni.h>
#include "CandidateRanker.h"

#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {
using zapime::candidate::CandidateRanker;
struct State {
  const std::thread::id owner = std::this_thread::get_id();
  // cancel() briefly holds this lock, never score(). close() uses it to ensure
  // destruction stays on the owning worker while a concurrent cancel drains.
  std::mutex lifecycle;
  std::unique_ptr<CandidateRanker> ranker;
};
std::mutex handles_mutex;
std::unordered_map<jlong, std::shared_ptr<State>> handles;
std::size_t opening = 0;
jlong next_handle = 1;
constexpr std::size_t kHandles = 2;

std::shared_ptr<State> state(jlong handle) {
  std::lock_guard<std::mutex> lock(handles_mutex);
  const auto found = handles.find(handle);
  return found == handles.end() ? nullptr : found->second;
}
bool owner(const std::shared_ptr<State>& current) {
  return current && current->owner == std::this_thread::get_id() && current->ranker;
}

enum class TextKind { Path, Context, Candidate };

// JNI's GetStringUTFChars returns modified UTF-8 (including surrogate-pair
// encodings), while the model vocabulary uses real Unicode scalar values.
// Convert UTF-16 explicitly and reject isolated surrogates instead of silently
// giving supplementary characters two UNK tokens.
bool text(JNIEnv* env, jstring input, TextKind kind, std::string& output) {
  if (!input) return false;
  const auto length = env->GetStringLength(input);
  if (env->ExceptionCheck()) return false;
  jsize start = 0;
  if (kind == TextKind::Context) {
    // At most 128 UTF-16 units contain the last 64 complete scalar values.
    if (length > 128) start = length - 128;
  } else if (length < 1 || length > (kind == TextKind::Path ? 4096 : 128)) {
    return false;
  }
  std::vector<jchar> chars(static_cast<std::size_t>(length - start));
  if (!chars.empty()) env->GetStringRegion(input, start, length - start, chars.data());
  if (env->ExceptionCheck()) return false;
  std::size_t begin = 0;
  if (start && !chars.empty() && chars[0] >= 0xdc00 && chars[0] <= 0xdfff) {
    jchar previous = 0;
    env->GetStringRegion(input, start - 1, 1, &previous);
    if (env->ExceptionCheck() || previous < 0xd800 || previous > 0xdbff) return false;
    begin = 1;
  }
  std::size_t scalar_count = 0;
  for (std::size_t i = begin; i < chars.size(); ++i) {
    std::uint32_t cp = chars[i];
    if (cp >= 0xd800 && cp <= 0xdbff) {
      if (i + 1 == chars.size() || chars[i + 1] < 0xdc00 || chars[i + 1] > 0xdfff) return false;
      cp = 0x10000 + ((cp - 0xd800) << 10) + (chars[++i] - 0xdc00);
    } else if (cp >= 0xdc00 && cp <= 0xdfff) return false;
    ++scalar_count;
    if (kind == TextKind::Candidate && scalar_count > CandidateRanker::kCandidateChars) return false;
    if (kind == TextKind::Path && cp == 0) return false;
    if (cp < 0x80) output.push_back(static_cast<char>(cp));
    else if (cp < 0x800) {
      output.push_back(static_cast<char>(0xc0 | (cp >> 6)));
      output.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else if (cp < 0x10000) {
      output.push_back(static_cast<char>(0xe0 | (cp >> 12)));
      output.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
      output.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else {
      output.push_back(static_cast<char>(0xf0 | (cp >> 18)));
      output.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
      output.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
      output.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    }
  }
  return kind != TextKind::Path || (!output.empty() && output.front() == '/' && output.size() <= 4096);
}

// Reserve before loading: simultaneous opens must not allocate an unbounded
// number of sessions while waiting to enter the public handle registry.
class OpenSlot final {
 public:
  bool reserved = false;
  OpenSlot() {
    std::lock_guard<std::mutex> lock(handles_mutex);
    if (handles.size() + opening < kHandles && next_handle < std::numeric_limits<jlong>::max()) {
      ++opening;
      reserved = true;
    }
  }
  ~OpenSlot() {
    if (reserved) { std::lock_guard<std::mutex> lock(handles_mutex); --opening; }
  }
  jlong publish(std::shared_ptr<State> value) {
    std::lock_guard<std::mutex> lock(handles_mutex);
    if (!reserved || next_handle == std::numeric_limits<jlong>::max()) return 0;
    const auto handle = next_handle;
    handles.emplace(handle, std::move(value));
    ++next_handle;
    --opening;
    reserved = false;
    return handle;
  }
};
}  // namespace

extern "C" {
JNIEXPORT jlong JNICALL Java_org_zapime_android_NativeCandidateRanker_open(
    JNIEnv* env, jclass, jstring model, jstring runtime) {
  try {
    std::string model_path, runtime_path;
    if (!text(env, model, TextKind::Path, model_path) || !text(env, runtime, TextKind::Path, runtime_path)) return 0;
    OpenSlot slot;
    if (!slot.reserved) return 0;
    auto current = std::make_shared<State>();
    current->ranker = std::make_unique<CandidateRanker>(model_path, runtime_path);
    return slot.publish(std::move(current));
  } catch (...) { return 0; }
}

JNIEXPORT jfloatArray JNICALL Java_org_zapime_android_NativeCandidateRanker_score(
    JNIEnv* env, jclass, jlong handle, jstring context, jobjectArray candidates) {
  try {
    const auto current = state(handle);
    if (!owner(current) || !candidates) return nullptr;
    const auto count = env->GetArrayLength(candidates);
    if (env->ExceptionCheck() || count < 1 || count > static_cast<jsize>(CandidateRanker::kCandidates)) return nullptr;
    std::string context_text;
    if (!text(env, context, TextKind::Context, context_text)) return nullptr;
    std::vector<std::string> rows;
    rows.reserve(static_cast<std::size_t>(count));
    for (jsize i = 0; i < count; ++i) {
      auto item = static_cast<jstring>(env->GetObjectArrayElement(candidates, i));
      if (env->ExceptionCheck()) return nullptr;
      std::string value;
      const bool valid = text(env, item, TextKind::Candidate, value);
      if (item) env->DeleteLocalRef(item);
      if (!valid) return nullptr;
      rows.push_back(std::move(value));
    }
    const auto scores = current->ranker->score(context_text, rows);
    if (scores.size() != rows.size()) return nullptr;
    auto result = env->NewFloatArray(count);
    if (result) env->SetFloatArrayRegion(result, 0, count, scores.data());
    return env->ExceptionCheck() ? nullptr : result;
  } catch (...) { return nullptr; }
}

JNIEXPORT jboolean JNICALL Java_org_zapime_android_NativeCandidateRanker_clear(JNIEnv*, jclass, jlong handle) {
  try {
    const auto current = state(handle);
    if (!owner(current)) return JNI_FALSE;
    current->ranker->clear();
    return JNI_TRUE;
  } catch (...) { return JNI_FALSE; }
}

JNIEXPORT void JNICALL Java_org_zapime_android_NativeCandidateRanker_cancel(JNIEnv*, jclass, jlong handle) {
  try {
    const auto current = state(handle);
    if (!current) return;
    std::lock_guard<std::mutex> lock(current->lifecycle);
    if (current->ranker) current->ranker->cancel();
  } catch (...) { /* A cancellation request never propagates to the UI thread. */ }
}

JNIEXPORT jboolean JNICALL Java_org_zapime_android_NativeCandidateRanker_close(JNIEnv*, jclass, jlong handle) {
  try {
    const auto current = state(handle);
    if (!owner(current)) return JNI_FALSE;
    std::unique_ptr<CandidateRanker> released;
    {
      std::lock_guard<std::mutex> lock(current->lifecycle);
      released = std::move(current->ranker);
    }
    // Joining ORT's workers may take time. Do not hold the short cancellation
    // lock during destruction, otherwise a UI-thread cancel could wait for it.
    // The registry continues to count this slot until destruction completes.
    released->cancel();
    released.reset();
    { std::lock_guard<std::mutex> lock(handles_mutex); handles.erase(handle); }
    return JNI_TRUE;
  } catch (...) { return JNI_FALSE; }
}
}
