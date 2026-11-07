// SPDX-License-Identifier: GPL-3.0-only
// Copyright 2026 ZapIME contributors
// Prefix topology and lm_step feeds adapted from chinese-ime-lm contributors:
// https://github.com/metasequoiaime/chinese-ime-lm/tree/5b82758eaf3419dff9555f41bbda2744a56e45c4
// neural/choice/model.py (context_inputs, step_inputs) and export_step.py.
// Those training/export sources are GPL-3.0; see SOURCE.md and upstream NOTICE.

#include "CandidateRanker.h"

#include <nlohmann/json.hpp>
#include <onnxruntime_c_api.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <map>
#include <mutex>
#include <stdexcept>
#include <string_view>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <unordered_map>
#include <utility>

namespace zapime::candidate {
namespace {

using json = nlohmann::json;
using Tokens = std::vector<std::int64_t>;
using Prefixes = std::vector<Tokens>;
constexpr std::uint32_t kOrtApi = 24;
constexpr std::size_t kModelBytes = 40 * 1024 * 1024;
constexpr std::size_t kMetadataBytes = 16 * 1024;
constexpr std::size_t kVocabBytes = 512 * 1024;
constexpr std::size_t kContextInputBytes = 4096;
constexpr std::array<const char*, 10> kInputs = {
    "cache_k", "cache_v", "cache_h", "cache_z", "nodes", "positions",
    "visible", "candidates", "steps", "lengths"};
constexpr std::array<const char*, 5> kOutputs = {
    "scores", "next_k", "next_v", "next_h", "next_z"};

[[noreturn]] void fail(const char* code) { throw std::runtime_error(code); }

bool valid_path(const std::string& path) {
  return !path.empty() && path.size() <= 4096 && path.front() == '/' &&
         path.find('\0') == std::string::npos;
}

class Fd final {
 public:
  explicit Fd(int fd) : fd_(fd) {}
  ~Fd() { if (fd_ >= 0) ::close(fd_); }
  Fd(const Fd&) = delete;
  Fd& operator=(const Fd&) = delete;
  int get() const { return fd_; }
 private:
  int fd_;
};

// The platform verifies release hashes before this boundary. Here, additionally
// reject links, nonregular/oversized files and files changed during reading.
std::vector<char> read_file(int directory, const char* name, std::size_t limit) {
  Fd fd(::openat(directory, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK));
  struct stat before {};
  if (fd.get() < 0 || ::fstat(fd.get(), &before) != 0 || !S_ISREG(before.st_mode) ||
      before.st_size <= 0 || static_cast<std::uint64_t>(before.st_size) > limit)
    fail("candidate_model_file_invalid");
  std::vector<char> data(static_cast<std::size_t>(before.st_size));
  std::size_t cursor = 0;
  while (cursor < data.size()) {
    const auto count = ::read(fd.get(), data.data() + cursor, data.size() - cursor);
    if (count < 0 && errno == EINTR) continue;
    if (count <= 0) fail("candidate_model_file_unreadable");
    cursor += static_cast<std::size_t>(count);
  }
  char extra = 0;
  struct stat after {};
  if (::read(fd.get(), &extra, 1) != 0 || ::fstat(fd.get(), &after) != 0 ||
      before.st_dev != after.st_dev || before.st_ino != after.st_ino ||
      before.st_size != after.st_size || before.st_mtime != after.st_mtime ||
      before.st_ctime != after.st_ctime)
    fail("candidate_model_file_changed");
  return data;
}

std::vector<std::uint32_t> scalars(std::string_view text, std::size_t limit) {
  std::vector<std::uint32_t> out;
  out.reserve(std::min(text.size(), limit));
  for (std::size_t i = 0; i < text.size();) {
    const auto first = static_cast<unsigned char>(text[i++]);
    std::uint32_t cp = first;
    std::uint32_t minimum = 0;
    std::size_t more = 0;
    if (first < 0x80) { /* ASCII, including the vocabulary's NUL token. */ }
    else if ((first & 0xe0) == 0xc0) { cp = first & 0x1f; minimum = 0x80; more = 1; }
    else if ((first & 0xf0) == 0xe0) { cp = first & 0x0f; minimum = 0x800; more = 2; }
    else if ((first & 0xf8) == 0xf0) { cp = first & 0x07; minimum = 0x10000; more = 3; }
    else fail("candidate_invalid_utf8");
    if (more > text.size() - i) fail("candidate_invalid_utf8");
    for (std::size_t j = 0; j < more; ++j) {
      const auto next = static_cast<unsigned char>(text[i++]);
      if ((next & 0xc0) != 0x80) fail("candidate_invalid_utf8");
      cp = (cp << 6) | (next & 0x3f);
    }
    if (cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff))
      fail("candidate_invalid_utf8");
    if (out.size() == limit) fail("candidate_text_limit");
    out.push_back(cp);
  }
  return out;
}

std::int64_t integer(const json& value, const char* key, std::int64_t lo, std::int64_t hi) {
  const auto found = value.find(key);
  if (found == value.end() || !found->is_number_integer()) fail("candidate_metadata_invalid");
  const auto number = found->get<std::int64_t>();
  if (number < lo || number > hi) fail("candidate_metadata_invalid");
  return number;
}

struct Shape {
  std::int64_t layers = 0, heads = 0, head_dim = 0, width = 0, positions = 0;
  std::int64_t bos = 2, unk = 1, vocab = 0;
};

Shape shape(const json& meta) {
  if (!meta.is_object() || !meta.contains("config") || !meta["config"].is_object())
    fail("candidate_metadata_invalid");
  Shape s;
  s.layers = integer(meta, "layers", 1, 8);
  s.heads = integer(meta, "heads", 1, 8);
  s.head_dim = integer(meta, "head_dim", 1, 64);
  s.width = integer(meta, "width", 1, 512);
  s.positions = integer(meta, "max_positions", 128, 256);
  s.bos = integer(meta, "bos", 2, 2);
  s.unk = integer(meta, "unk", 1, 1);
  s.vocab = integer(meta["config"], "vocab", 3, 16384);
  if (s.width != s.heads * s.head_dim || integer(meta, "context_chars", 64, 64) != 64 ||
      integer(meta["config"], "n_layer", 1, 8) != s.layers ||
      integer(meta["config"], "n_head", 1, 8) != s.heads ||
      integer(meta["config"], "n_embd", 1, 512) != s.width ||
      integer(meta["config"], "max_positions", 128, 256) != s.positions ||
      integer(meta["config"], "ngram_buckets", 0, 0) != 0 ||
      !meta.contains("self_normalized") || !meta["self_normalized"].is_boolean() ||
      !meta["config"].contains("self_normalized") ||
      meta["self_normalized"] != meta["config"]["self_normalized"])
    fail("candidate_metadata_invalid");
  return s;
}

template <class T> struct OrtRelease {
  void (ORT_API_CALL *release)(T*) = nullptr;
  void operator()(T* pointer) const noexcept { if (pointer && release) release(pointer); }
};
template <class T> using OrtPtr = std::unique_ptr<T, OrtRelease<T>>;
template <class T> OrtPtr<T> own(T* pointer, void (ORT_API_CALL *release)(T*)) {
  return OrtPtr<T>(pointer, OrtRelease<T>{release});
}

struct Library {
  void* handle = nullptr;
  explicit Library(const std::string& path) {
    if (!valid_path(path)) fail("candidate_runtime_path_invalid");
    handle = ::dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) fail("candidate_runtime_unavailable");
  }
  ~Library() { if (handle) ::dlclose(handle); }
  Library(const Library&) = delete;
  Library& operator=(const Library&) = delete;
};

struct Cache {
  std::array<OrtPtr<OrtValue>, 4> values;
  std::size_t nodes = 0;
};

struct Feed {
  Tokens nodes, positions, candidates, steps, lengths;
  std::vector<std::uint8_t> visible;
  std::size_t columns = 0, rows = 0, width = 0;
};

struct Plan {
  Prefixes every;
  std::map<Tokens, std::size_t> index;
  std::size_t rows = 0;
};

// Greedy bounded batches do not change the causal dependency graph. Dynamic
// activation quantization can still cause small batch-dependent rounding; the
// real-model probe measures it against upstream's unconstrained page feed.
Plan plan(const std::vector<Tokens>& bodies, std::size_t offset,
          const Prefixes& cached, const std::map<Tokens, std::size_t>& index) {
  Plan result{cached, index, 0};
  for (std::size_t row = offset; row < bodies.size(); ++row) {
    Prefixes extra;
    const auto& body = bodies[row];
    for (std::size_t length = 1; length < body.size(); ++length) {
      Tokens prefix(body.begin(), body.begin() + static_cast<std::ptrdiff_t>(length));
      if (result.index.find(prefix) == result.index.end()) extra.push_back(std::move(prefix));
    }
    if (result.rows && result.every.size() - cached.size() + extra.size() >
                           CandidateRanker::kBatchNodes) break;
    for (auto& prefix : extra) {
      result.index.emplace(prefix, result.every.size());
      result.every.push_back(std::move(prefix));
    }
    ++result.rows;
  }
  if (!result.rows || result.every.size() - cached.size() > CandidateRanker::kBatchNodes)
    fail("candidate_batch_limit");
  return result;
}

Feed prefix_feed(std::size_t context_nodes, const Prefixes& old, const Plan& p,
                 const std::vector<Tokens>& bodies, std::size_t offset) {
  Feed f;
  f.columns = context_nodes + p.every.size();
  f.rows = p.rows;
  const auto fresh = p.every.size() - old.size();
  f.visible.assign(fresh * f.columns, 0);
  for (std::size_t i = 0; i < fresh; ++i) {
    const auto& prefix = p.every[old.size() + i];
    f.nodes.push_back(prefix.back());
    f.positions.push_back(static_cast<std::int64_t>(context_nodes + prefix.size() - 1));
    auto row = f.visible.begin() + static_cast<std::ptrdiff_t>(i * f.columns);
    std::fill_n(row, context_nodes, std::uint8_t{1});
    for (std::size_t j = 0; j < p.every.size(); ++j) {
      const auto& possible_parent = p.every[j];
      if (possible_parent.size() <= prefix.size() &&
          std::equal(possible_parent.begin(), possible_parent.end(), prefix.begin()))
        row[static_cast<std::ptrdiff_t>(context_nodes + j)] = 1;
    }
  }
  for (std::size_t i = 0; i < p.rows; ++i) f.width = std::max(f.width, bodies[offset + i].size());
  f.candidates.assign(p.rows * f.width, 0);
  f.steps.assign(p.rows * f.width, 0);
  for (std::size_t i = 0; i < p.rows; ++i) {
    const auto& body = bodies[offset + i];
    f.lengths.push_back(static_cast<std::int64_t>(body.size()));
    std::copy(body.begin(), body.end(), f.candidates.begin() + static_cast<std::ptrdiff_t>(i * f.width));
    // The final context node predicts the first candidate character. Omitting
    // this term makes single-character scores meaningless and biases rankings.
    f.steps[i * f.width] = static_cast<std::int64_t>(context_nodes - 1);
    for (std::size_t j = 1; j < body.size(); ++j) {
      const Tokens prefix(body.begin(), body.begin() + static_cast<std::ptrdiff_t>(j));
      f.steps[i * f.width + j] = static_cast<std::int64_t>(context_nodes + p.index.at(prefix));
    }
  }
  return f;
}

void ORT_API_CALL silent_log(void*, OrtLoggingLevel, const char*, const char*, const char*, const char*) {}

}  // namespace

struct CandidateRanker::Impl {
  const std::thread::id owner = std::this_thread::get_id();
  Library library;
  const OrtApi* api = nullptr;
  Shape dimensions;
  std::unordered_map<std::uint32_t, std::int64_t> vocabulary;
  OrtPtr<OrtEnv> environment;
  OrtPtr<OrtSession> session;
  OrtPtr<OrtMemoryInfo> memory;
  OrtPtr<OrtRunOptions> run_options;
  OrtAllocator* allocator = nullptr;
  std::mutex cancellation_mutex;
  std::atomic_bool cancelled{false};
  Tokens context_tokens;
  Prefixes prefixes;
  std::map<Tokens, std::size_t> prefix_index;
  std::shared_ptr<Cache> context_cache;
  std::shared_ptr<Cache> active_cache;
  CacheStats last_stats;

  Impl(const std::string& directory, const std::string& runtime) : library(runtime) {
    if (!valid_path(directory)) fail("candidate_model_path_invalid");
    Fd dir(::open(directory.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (dir.get() < 0) fail("candidate_model_path_invalid");
    const auto metadata_bytes = read_file(dir.get(), "model.json", kMetadataBytes);
    const auto meta = json::parse(metadata_bytes.begin(), metadata_bytes.end(), nullptr, false);
    if (meta.is_discarded()) fail("candidate_metadata_invalid");
    dimensions = shape(meta);
    const auto vocab_bytes = read_file(dir.get(), "vocab.json", kVocabBytes);
    const auto vocab = json::parse(vocab_bytes.begin(), vocab_bytes.end(), nullptr, false);
    if (!vocab.is_object() || !vocab.contains("tokens") || !vocab["tokens"].is_array() ||
        vocab["tokens"].size() != static_cast<std::size_t>(dimensions.vocab))
      fail("candidate_vocabulary_invalid");
    const auto& tokens = vocab["tokens"];
    if (tokens[0] != "<pad>" || tokens[1] != "<unk>" ||
        (tokens[2] != "<bos>" && tokens[2] != "<end>")) fail("candidate_vocabulary_invalid");
    for (std::size_t i = 3; i < tokens.size(); ++i) {
      if (!tokens[i].is_string()) fail("candidate_vocabulary_invalid");
      const auto text = tokens[i].get<std::string>();
      const auto codepoints = scalars(text, 1);
      if (codepoints.size() != 1 || !vocabulary.emplace(codepoints[0], static_cast<std::int64_t>(i)).second)
        fail("candidate_vocabulary_invalid");
    }

    using GetBase = const OrtApiBase* (ORT_API_CALL *)();
    const auto get_base = reinterpret_cast<GetBase>(::dlsym(library.handle, "OrtGetApiBase"));
    const auto* base = get_base ? get_base() : nullptr;
    api = base ? base->GetApi(kOrtApi) : nullptr;
    if (!api) fail("candidate_runtime_api_unavailable");
    OrtEnv* env = nullptr;
    check(api->CreateEnvWithCustomLogger(silent_log, nullptr, ORT_LOGGING_LEVEL_FATAL,
                                        "zapime-candidate", &env));
    environment = own(env, api->ReleaseEnv);
    check(api->GetAllocatorWithDefaultOptions(&allocator));
    OrtMemoryInfo* info = nullptr;
    check(api->CreateCpuMemoryInfo(OrtDeviceAllocator, OrtMemTypeDefault, &info));
    memory = own(info, api->ReleaseMemoryInfo);
    OrtRunOptions* run = nullptr;
    check(api->CreateRunOptions(&run));
    run_options = own(run, api->ReleaseRunOptions);
    OrtSessionOptions* raw_options = nullptr;
    check(api->CreateSessionOptions(&raw_options));
    auto options = own(raw_options, api->ReleaseSessionOptions);
    check(api->SetIntraOpNumThreads(options.get(), 2));
    check(api->SetInterOpNumThreads(options.get(), 1));
    check(api->SetSessionExecutionMode(options.get(), ORT_SEQUENTIAL));
    check(api->DisableCpuMemArena(options.get()));
    check(api->DisableMemPattern(options.get()));
    check(api->AddSessionConfigEntry(options.get(), "session.intra_op.allow_spinning", "0"));
    check(api->AddSessionConfigEntry(options.get(), "session.inter_op.allow_spinning", "0"));
    check(api->SetSessionGraphOptimizationLevel(options.get(), ORT_ENABLE_ALL));
    // Loading is always done on the dedicated worker. Session creation copies
    // the bounded ORT buffer; it is never retained as a second weight cache.
    const auto model = read_file(dir.get(), "lm_step.ort", kModelBytes);
    OrtSession* raw_session = nullptr;
    check(api->CreateSessionFromArray(environment.get(), model.data(), model.size(), options.get(), &raw_session));
    session = own(raw_session, api->ReleaseSession);
    validate_graph();
  }

  void check(OrtStatus* status) const {
    if (!status) return;
    // Do not expose ORT error messages: they can include application paths or
    // operator/debug data. The caller receives only this fixed failure code.
    api->ReleaseStatus(status);
    fail("candidate_runtime_failure");
  }

  void require_owner() const {
    if (owner != std::this_thread::get_id()) fail("candidate_invalid_owner");
  }

  void require_active() const {
    if (cancelled.load(std::memory_order_acquire)) fail("candidate_cancelled");
  }

  void validate_shape(const OrtTensorTypeAndShapeInfo* info, ONNXTensorElementDataType type,
                      const std::vector<std::int64_t>& expected, bool symbolic) const {
    ONNXTensorElementDataType actual_type = ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;
    std::size_t rank = 0;
    check(api->GetTensorElementType(info, &actual_type));
    check(api->GetDimensionsCount(info, &rank));
    if (actual_type != type || rank != expected.size()) fail("candidate_tensor_shape_invalid");
    Tokens actual(rank);
    check(api->GetDimensions(info, actual.data(), rank));
    for (std::size_t i = 0; i < rank; ++i) {
      if (expected[i] >= 0 && actual[i] != expected[i]) fail("candidate_tensor_shape_invalid");
      if (expected[i] < 0 && (!symbolic || actual[i] >= 0)) fail("candidate_tensor_shape_invalid");
    }
  }

  void validate_graph() const {
    const std::vector<Tokens> shapes = {
      {dimensions.layers, dimensions.heads, -1, dimensions.head_dim},
      {dimensions.layers, dimensions.heads, -1, dimensions.head_dim},
      {-1, dimensions.width}, {-1}, {-1}, {-1}, {-1, -1}, {-1, -1}, {-1, -1}, {-1}};
    std::size_t count = 0;
    check(api->SessionGetInputCount(session.get(), &count));
    if (count != kInputs.size()) fail("candidate_graph_invalid");
    check(api->SessionGetOutputCount(session.get(), &count));
    if (count != kOutputs.size()) fail("candidate_graph_invalid");
    struct FreeName {
      OrtAllocator* allocator;
      void operator()(char* text) const { if (text) allocator->Free(allocator, text); }
    };
    for (std::size_t i = 0; i < kInputs.size() + kOutputs.size(); ++i) {
      const bool input = i < kInputs.size();
      const auto index = input ? i : i - kInputs.size();
      char* raw_name = nullptr;
      OrtTypeInfo* raw_type = nullptr;
      check(input ? api->SessionGetInputName(session.get(), index, allocator, &raw_name)
                  : api->SessionGetOutputName(session.get(), index, allocator, &raw_name));
      std::unique_ptr<char, FreeName> name(raw_name, FreeName{allocator});
      if (!name || std::strcmp(name.get(), input ? kInputs[index] : kOutputs[index]) != 0)
        fail("candidate_graph_invalid");
      check(input ? api->SessionGetInputTypeInfo(session.get(), index, &raw_type)
                  : api->SessionGetOutputTypeInfo(session.get(), index, &raw_type));
      auto type = own(raw_type, api->ReleaseTypeInfo);
      const OrtTensorTypeAndShapeInfo* tensor = nullptr;
      check(api->CastTypeInfoToTensorInfo(type.get(), &tensor));
      if (!tensor) fail("candidate_graph_invalid");
      const auto element = !input || index < 4 ? ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT
          : index == 6 ? ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL : ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64;
      const Tokens expected = input ? shapes[index] : index == 0 ? Tokens{-1} : shapes[index - 1];
      validate_shape(tensor, element, expected, true);
    }
  }

  OrtPtr<OrtValue> tensor(void* data, std::size_t bytes, const Tokens& dims,
                          ONNXTensorElementDataType type) const {
    // ORT accepts zero dimensions. Give it a valid aligned address even when a
    // fully cached page has no new nodes, without allowing an actual read.
    static std::uint64_t empty = 0;
    OrtValue* value = nullptr;
    check(api->CreateTensorWithDataAsOrtValue(memory.get(), bytes ? data : &empty, bytes,
                                             dims.data(), dims.size(), type, &value));
    return own(value, api->ReleaseValue);
  }

  std::shared_ptr<Cache> empty_cache() const {
    const std::array<Tokens, 4> shapes = {{
      {dimensions.layers, dimensions.heads, 0, dimensions.head_dim},
      {dimensions.layers, dimensions.heads, 0, dimensions.head_dim},
      {0, dimensions.width}, {0}}};
    auto cache = std::make_shared<Cache>();
    for (std::size_t i = 0; i < shapes.size(); ++i) {
      OrtValue* value = nullptr;
      check(api->CreateTensorAsOrtValue(allocator, shapes[i].data(), shapes[i].size(),
                                       ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &value));
      cache->values[i] = own(value, api->ReleaseValue);
    }
    return cache;
  }

  std::pair<std::vector<float>, std::shared_ptr<Cache>> run(const Cache& previous, Feed& f) {
    require_active();
    const auto n = static_cast<std::int64_t>(f.nodes.size());
    const auto b = static_cast<std::int64_t>(f.rows);
    const auto width = static_cast<std::int64_t>(f.width);
    const auto total = previous.nodes + f.nodes.size();
    if (f.nodes.size() > kBatchNodes || total != f.columns || total > kPrefixCapacity + kContextChars + 1 ||
        f.rows == 0 || f.rows > kCandidates || f.width == 0 || f.width > kCandidateChars ||
        f.visible.size() != f.nodes.size() * f.columns || f.positions.size() != f.nodes.size() ||
        f.candidates.size() != f.rows * f.width || f.steps.size() != f.candidates.size() ||
        f.lengths.size() != f.rows) fail("candidate_feed_invalid");
    std::array<OrtPtr<OrtValue>, 6> feeds;
    feeds[0] = tensor(f.nodes.data(), f.nodes.size() * sizeof(std::int64_t), {n}, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);
    feeds[1] = tensor(f.positions.data(), f.positions.size() * sizeof(std::int64_t), {n}, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);
    feeds[2] = tensor(f.visible.data(), f.visible.size(), {n, static_cast<std::int64_t>(f.columns)}, ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL);
    feeds[3] = tensor(f.candidates.data(), f.candidates.size() * sizeof(std::int64_t), {b, width}, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);
    feeds[4] = tensor(f.steps.data(), f.steps.size() * sizeof(std::int64_t), {b, width}, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);
    feeds[5] = tensor(f.lengths.data(), f.lengths.size() * sizeof(std::int64_t), {b}, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);
    std::array<const OrtValue*, 10> inputs{};
    for (std::size_t i = 0; i < 4; ++i) inputs[i] = previous.values[i].get();
    for (std::size_t i = 0; i < feeds.size(); ++i) inputs[i + 4] = feeds[i].get();
    std::array<OrtValue*, 5> raw{};
    OrtStatus* status = api->Run(session.get(), run_options.get(), kInputs.data(), inputs.data(), inputs.size(),
                               kOutputs.data(), kOutputs.size(), raw.data());
    std::array<OrtPtr<OrtValue>, 5> outputs;
    for (std::size_t i = 0; i < raw.size(); ++i) outputs[i] = own(raw[i], api->ReleaseValue);
    check(status);
    require_active();
    const auto m = static_cast<std::int64_t>(total);
    const std::array<Tokens, 5> expected = {{
      {b}, {dimensions.layers, dimensions.heads, m, dimensions.head_dim},
      {dimensions.layers, dimensions.heads, m, dimensions.head_dim}, {m, dimensions.width}, {m}}};
    for (std::size_t i = 0; i < outputs.size(); ++i) {
      if (!outputs[i]) fail("candidate_output_invalid");
      OrtTensorTypeAndShapeInfo* info = nullptr;
      check(api->GetTensorTypeAndShape(outputs[i].get(), &info));
      auto owned = own(info, api->ReleaseTensorTypeAndShapeInfo);
      validate_shape(owned.get(), ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, expected[i], false);
    }
    void* pointer = nullptr;
    check(api->GetTensorMutableData(outputs[0].get(), &pointer));
    const auto* values = static_cast<const float*>(pointer);
    if (!values) fail("candidate_output_invalid");
    std::vector<float> scores(values, values + f.rows);
    if (!std::all_of(scores.begin(), scores.end(), [](float x) { return std::isfinite(x); }))
      fail("candidate_nonfinite_score");
    auto cache = std::make_shared<Cache>();
    cache->nodes = total;
    for (std::size_t i = 0; i < cache->values.size(); ++i) cache->values[i] = std::move(outputs[i + 1]);
    last_stats.new_nodes += f.nodes.size();
    ++last_stats.batches;
    return {std::move(scores), std::move(cache)};
  }

  Tokens encode(std::string_view text, std::size_t limit) const {
    const auto chars = scalars(text, limit);
    Tokens out;
    out.reserve(chars.size());
    for (const auto c : chars) {
      const auto found = vocabulary.find(c);
      out.push_back(found == vocabulary.end() ? dimensions.unk : found->second);
    }
    return out;
  }

  void drop_caches() {
    context_cache.reset();
    active_cache.reset();
    context_tokens.clear();
    prefixes.clear();
    prefix_index.clear();
    last_stats = {};
  }

  void set_context(Tokens tokens) {
    if (context_cache && tokens == context_tokens) return;
    drop_caches();
    Feed f;
    f.nodes = tokens;
    f.columns = tokens.size();
    f.rows = f.width = 1;
    for (std::size_t i = 0; i < tokens.size(); ++i) f.positions.push_back(static_cast<std::int64_t>(i));
    f.visible.assign(tokens.size() * tokens.size(), 0);
    for (std::size_t i = 0; i < tokens.size(); ++i)
      std::fill_n(f.visible.begin() + static_cast<std::ptrdiff_t>(i * tokens.size()), i + 1, std::uint8_t{1});
    f.candidates = {dimensions.bos};
    f.steps = {0};
    f.lengths = {1};
    auto result = run(*empty_cache(), f);
    context_cache = active_cache = std::move(result.second);
    context_tokens = std::move(tokens);
  }

  std::vector<float> score(const std::string& context, const std::vector<std::string>& candidates) {
    require_owner();
    require_active();
    if (candidates.empty() || candidates.size() > kCandidates || context.size() > kContextInputBytes)
      fail("candidate_input_limit");
    std::vector<Tokens> bodies;
    bodies.reserve(candidates.size());
    for (const auto& candidate : candidates) {
      if (candidate.empty() || candidate.size() > 4 * kCandidateChars) fail("candidate_input_limit");
      bodies.push_back(encode(candidate, kCandidateChars));
    }
    auto context_ids = encode(context, kContextInputBytes);
    if (context_ids.size() > kContextChars)
      context_ids.erase(context_ids.begin(), context_ids.end() - static_cast<std::ptrdiff_t>(kContextChars));
    context_ids.insert(context_ids.begin(), dimensions.bos);
    last_stats = {};
    set_context(std::move(context_ids));
    std::vector<float> scores;
    scores.reserve(bodies.size());
    for (std::size_t offset = 0; offset < bodies.size();) {
      require_active();
      auto next = plan(bodies, offset, prefixes, prefix_index);
      if (next.every.size() > kPrefixCapacity) {
        active_cache = context_cache;
        prefixes.clear();
        prefix_index.clear();
        ++last_stats.evictions;
        next = plan(bodies, offset, prefixes, prefix_index);
      }
      auto feed = prefix_feed(context_cache->nodes, prefixes, next, bodies, offset);
      auto result = run(*active_cache, feed);
      active_cache = std::move(result.second);
      prefixes = std::move(next.every);
      prefix_index = std::move(next.index);
      scores.insert(scores.end(), result.first.begin(), result.first.end());
      offset += next.rows;
    }
    last_stats.context_nodes = context_cache->nodes;
    last_stats.prefix_nodes = prefixes.size();
    require_active();
    return scores;
  }
};

CandidateRanker::CandidateRanker(const std::string& directory, const std::string& runtime)
    : impl_(std::make_unique<Impl>(directory, runtime)) {}
CandidateRanker::~CandidateRanker() = default;

std::vector<float> CandidateRanker::score(const std::string& context,
                                         const std::vector<std::string>& candidates) {
  // A foreign caller must not mutate or discard the owner's cached tensors.
  impl_->require_owner();
  try { return impl_->score(context, candidates); }
  catch (...) { impl_->drop_caches(); throw; }
}

void CandidateRanker::clear() {
  impl_->require_owner();
  std::lock_guard<std::mutex> lock(impl_->cancellation_mutex);
  impl_->drop_caches();
  impl_->check(impl_->api->RunOptionsUnsetTerminate(impl_->run_options.get()));
  impl_->cancelled.store(false, std::memory_order_release);
}

void CandidateRanker::cancel() noexcept {
  std::lock_guard<std::mutex> lock(impl_->cancellation_mutex);
  impl_->cancelled.store(true, std::memory_order_release);
  if (auto* status = impl_->api->RunOptionsSetTerminate(impl_->run_options.get()))
    impl_->api->ReleaseStatus(status);
}

CacheStats CandidateRanker::stats() const {
  impl_->require_owner();
  return impl_->last_stats;
}

}  // namespace zapime::candidate
