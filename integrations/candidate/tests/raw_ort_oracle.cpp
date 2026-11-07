// SPDX-License-Identifier: GPL-3.0-only
// Test-only executor for independently generated, pinned upstream tensors.
#include "raw_ort_oracle.h"
#include <nlohmann/json.hpp>
#include <onnxruntime_c_api.h>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <stdexcept>
#include <utility>
#include <vector>

namespace zapime::candidate::oracle {
namespace {
using json = nlohmann::json;
constexpr std::array<const char*, 10> inputs = {"cache_k", "cache_v", "cache_h", "cache_z",
    "nodes", "positions", "visible", "candidates", "steps", "lengths"};
constexpr std::array<const char*, 5> outputs = {"scores", "next_k", "next_v", "next_h", "next_z"};
void require(bool value) { if (!value) throw std::runtime_error("candidate_oracle_contract"); }
void ORT_API_CALL log_nothing(void*, OrtLoggingLevel, const char*, const char*, const char*, const char*) {}

// All ORT objects are per-invocation and released before evaluate returns.
struct Runtime {
  void* library = nullptr;
  const OrtApi* api = nullptr;
  OrtEnv* env = nullptr;
  OrtSessionOptions* options = nullptr;
  OrtSession* session = nullptr;
  OrtAllocator* allocator = nullptr;
  std::string version;
  ~Runtime() {
    if (api) {
      if (session) api->ReleaseSession(session);
      if (options) api->ReleaseSessionOptions(options);
      if (env) api->ReleaseEnv(env);
    }
    if (library) dlclose(library);
  }
  void check(OrtStatus* status) const {
    if (status) { api->ReleaseStatus(status); throw std::runtime_error("candidate_oracle_runtime"); }
  }
  void open(const std::string& model, const std::string& runtime) {
    require(!model.empty() && model.front() == '/' && model.size() <= 4096 && model.find('\0') == std::string::npos);
    require(!runtime.empty() && runtime.front() == '/' && runtime.size() <= 4096 && runtime.find('\0') == std::string::npos);
    library = dlopen(runtime.c_str(), RTLD_NOW | RTLD_LOCAL);
    require(library != nullptr);
    using GetBase = const OrtApiBase* (ORT_API_CALL*)();
    auto get_base = reinterpret_cast<GetBase>(dlsym(library, "OrtGetApiBase"));
    require(get_base != nullptr);
    const auto* base = get_base();
    require(base != nullptr);
    api = base->GetApi(24);
    require(api != nullptr);
    version = base->GetVersionString();
    check(api->CreateEnvWithCustomLogger(log_nothing, nullptr, ORT_LOGGING_LEVEL_FATAL,
                                         "candidate-raw-oracle", &env));
    check(api->GetAllocatorWithDefaultOptions(&allocator));
    check(api->CreateSessionOptions(&options));
    check(api->SetIntraOpNumThreads(options, 2));
    check(api->SetInterOpNumThreads(options, 1));
    check(api->SetSessionExecutionMode(options, ORT_SEQUENTIAL));
    check(api->DisableCpuMemArena(options));
    check(api->DisableMemPattern(options));
    check(api->AddSessionConfigEntry(options, "session.intra_op.allow_spinning", "0"));
    check(api->AddSessionConfigEntry(options, "session.inter_op.allow_spinning", "0"));
    check(api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL));
    check(api->CreateSession(env, model.c_str(), options, &session));
  }
};

struct Values {
  const OrtApi* api;
  std::vector<OrtValue*> values;
  explicit Values(const OrtApi* a, std::size_t n) : api(a), values(n, nullptr) {}
  ~Values() { for (auto* v : values) if (v) api->ReleaseValue(v); }
  Values(const Values&) = delete;
  Values& operator=(const Values&) = delete;
};

std::vector<std::int64_t> dimensions(const json& value) {
  require(value.is_array() && !value.empty() && value.size() <= 4);
  auto shape = value.get<std::vector<std::int64_t>>();
  for (const auto size : shape) require(size >= 0 && size <= 256);
  return shape;
}

std::size_t count(const std::vector<std::int64_t>& shape) {
  std::size_t size = 1;
  for (auto dim : shape) { size *= static_cast<std::size_t>(dim); require(size <= 1048576); }
  return size;
}

void fill_tensor(const Runtime& r, const json& spec, OrtValue** output) {
  const auto shape = dimensions(spec.at("shape"));
  const auto size = count(shape);
  require(spec.at("values").is_array() && spec.at("values").size() == size);
  const bool boolean = spec.at("type") == "bool";
  require(boolean || spec.at("type") == "int64");
  r.check(r.api->CreateTensorAsOrtValue(r.allocator, shape.data(), shape.size(),
      boolean ? ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL : ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, output));
  void* data = nullptr;
  r.check(r.api->GetTensorMutableData(*output, &data));
  require(data != nullptr || size == 0);
  for (std::size_t i = 0; i < size; ++i) {
    if (boolean) {
      require(spec["values"][i].is_boolean());
      static_cast<std::uint8_t*>(data)[i] = spec["values"][i].get<bool>() ? 1 : 0;
    } else {
      require(spec["values"][i].is_number_integer());
      static_cast<std::int64_t*>(data)[i] = spec["values"][i].get<std::int64_t>();
    }
  }
}

std::vector<std::int64_t> shape_of(const Runtime& r, OrtValue* value) {
  require(value != nullptr);
  OrtTensorTypeAndShapeInfo* info = nullptr;
  r.check(r.api->GetTensorTypeAndShape(value, &info));
  struct Release { const OrtApi* api; OrtTensorTypeAndShapeInfo* p;
    ~Release() { if (p) api->ReleaseTensorTypeAndShapeInfo(p); } } release{r.api, info};
  ONNXTensorElementDataType type;
  std::size_t rank = 0;
  r.check(r.api->GetTensorElementType(info, &type));
  r.check(r.api->GetDimensionsCount(info, &rank));
  require(type == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT && rank <= 4);
  std::vector<std::int64_t> shape(rank);
  r.check(r.api->GetDimensions(info, shape.data(), rank));
  return shape;
}

constexpr std::array<std::uint32_t, 64> round_constants = {
  0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
  0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
  0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
  0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
  0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
  0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
  0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
  0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
std::uint32_t rotate(std::uint32_t x, unsigned n) { return (x >> n) | (x << (32 - n)); }
}  // namespace

// SHA-256's standard compression function (FIPS 180-4); input is bounded by
// evaluate. This hashes the actual fixture bytes, never an embedded hash field.
std::string sha256(const std::string& bytes) {
  require(bytes.size() <= 65536);
  std::vector<std::uint8_t> data(bytes.begin(), bytes.end());
  const auto bits = static_cast<std::uint64_t>(data.size()) * 8;
  data.push_back(0x80);
  while (data.size() % 64 != 56) data.push_back(0);
  for (int shift = 56; shift >= 0; shift -= 8) data.push_back(static_cast<std::uint8_t>(bits >> shift));
  std::array<std::uint32_t, 8> state = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
                                     0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
  for (std::size_t offset = 0; offset < data.size(); offset += 64) {
    std::array<std::uint32_t, 64> words{};
    for (std::size_t i = 0; i < 16; ++i)
      for (std::size_t j = 0; j < 4; ++j) words[i] = (words[i] << 8) | data[offset + 4*i + j];
    for (std::size_t i = 16; i < 64; ++i) {
      const auto x = words[i-15], y = words[i-2];
      words[i] = words[i-16] + (rotate(x,7)^rotate(x,18)^(x>>3)) + words[i-7]
                 + (rotate(y,17)^rotate(y,19)^(y>>10));
    }
    auto a=state[0],b=state[1],c=state[2],d=state[3],e=state[4],f=state[5],g=state[6],h=state[7];
    for (std::size_t i = 0; i < 64; ++i) {
      const auto t1 = h + (rotate(e,6)^rotate(e,11)^rotate(e,25)) + ((e&f)^(~e&g))
                      + round_constants[i] + words[i];
      const auto t2 = (rotate(a,2)^rotate(a,13)^rotate(a,22)) + ((a&b)^(a&c)^(b&c));
      h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
    }
    state[0]+=a;state[1]+=b;state[2]+=c;state[3]+=d;state[4]+=e;state[5]+=f;state[6]+=g;state[7]+=h;
  }
  std::string result;
  constexpr char hex[] = "0123456789abcdef";
  for (auto word : state) for (int shift = 28; shift >= 0; shift -= 4) result += hex[(word >> shift)&15];
  return result;
}

std::string evaluate(const std::string& model, const std::string& library, const std::string& encoded) {
  require(!encoded.empty() && encoded.size() <= 65536);
  const auto fixture = json::parse(encoded);
  require(fixture.at("schema_version") == 1 && fixture.at("fixture_id") == "candidate-upstream-8m-two-stage-v1");
  require(fixture.at("calls").is_array() && fixture["calls"].size() == 2);
  require(fixture.at("empty_cache_shapes").size() == 4);
  json result;
  {
    Runtime runtime;
    runtime.open(model, library);
    Values cache(runtime.api, 4);
    for (std::size_t i = 0; i < 4; ++i) {
      const auto shape = dimensions(fixture["empty_cache_shapes"][i]);
      require(count(shape) == 0);
      runtime.check(runtime.api->CreateTensorAsOrtValue(runtime.allocator, shape.data(), shape.size(),
                         ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &cache.values[i]));
    }
    std::size_t context_nodes = 0, final_nodes = 0;
    std::vector<float> scores;
    for (std::size_t call = 0; call < 2; ++call) {
      const auto& tensors = fixture["calls"][call];
      require(tensors.is_object() && tensors.size() == 6);
      Values feed(runtime.api, 6), output(runtime.api, 5);
      std::array<const OrtValue*, 10> values{};
      for (std::size_t i = 0; i < 4; ++i) values[i] = cache.values[i];
      for (std::size_t i = 0; i < 6; ++i) {
        fill_tensor(runtime, tensors.at(inputs[i + 4]), &feed.values[i]);
        values[i + 4] = feed.values[i];
      }
      runtime.check(runtime.api->Run(runtime.session, nullptr, inputs.data(), values.data(), values.size(),
                                     outputs.data(), outputs.size(), output.values.data()));
      const auto score_shape = shape_of(runtime, output.values[0]);
      require(score_shape == std::vector<std::int64_t>{call == 0 ? 1 : 3});
      const std::int64_t expected_nodes = call == 0 ? 7 : 10;
      const auto& dims = fixture.at("dimensions");
      const std::array<std::vector<std::int64_t>, 4> expected = {{
          {dims["layers"], dims["heads"], expected_nodes, dims["head_dim"]},
          {dims["layers"], dims["heads"], expected_nodes, dims["head_dim"]},
          {expected_nodes, dims["width"]}, {expected_nodes}}};
      for (std::size_t i = 0; i < 4; ++i) require(shape_of(runtime, output.values[i+1]) == expected[i]);
      if (call == 0) context_nodes = static_cast<std::size_t>(expected_nodes);
      else {
        final_nodes = static_cast<std::size_t>(expected_nodes);
        void* data = nullptr;
        runtime.check(runtime.api->GetTensorMutableData(output.values[0], &data));
        require(data != nullptr);
        const auto* f = static_cast<const float*>(data);
        scores.assign(f, f + 3);
        for (auto score : scores) require(std::isfinite(score));
      }
      // These are the raw previous-graph outputs, without production cache
      // selection, pruning, prefix indices, copied hidden states or rebuilding.
      for (std::size_t i = 0; i < 4; ++i) {
        runtime.api->ReleaseValue(cache.values[i]);
        cache.values[i] = std::exchange(output.values[i+1], nullptr);
      }
    }
    result = {{"executed", true}, {"runtime_version", runtime.version}, {"graph_calls", 2},
              {"scores", scores}, {"context_cache_nodes", context_nodes},
              {"candidate_cache_nodes", final_nodes}, {"fixture_sha256", sha256(encoded)}};
  }
  // The cache, session, environment and dlopen reference have all left scope.
  result["session_closed"] = true;
  return result.dump();
}
}  // namespace zapime::candidate::oracle

#ifdef ZAPIME_CANDIDATE_ORACLE_MAIN
#include <fstream>
#include <iostream>
#include <iterator>
int main(int argc, char** argv) {
  if (argc != 4) return 2;
  try {
    std::ifstream file(argv[3], std::ios::binary);
    if (!file) return 2;
    std::string fixture((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    std::cout << zapime::candidate::oracle::evaluate(argv[1], argv[2], fixture) << '\n';
    return 0;
  } catch (...) { std::cerr << "candidate_oracle_failed\n"; return 1; }
}
#endif
