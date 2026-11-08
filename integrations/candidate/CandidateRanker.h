// SPDX-License-Identifier: GPL-3.0-only
// ZapIME's bounded host for chinese-ime-lm's published lm_step ORT graph.
#pragma once

#include <cstddef>
#include <memory>
#include <string>
#include <vector>

namespace zapime::candidate {

struct CacheStats {
  std::size_t context_nodes = 0;
  std::size_t prefix_nodes = 0;
  std::size_t new_nodes = 0;
  std::size_t batches = 0;
  std::size_t evictions = 0;
};

// Construction, score, clear, stats and destruction belong to one worker.
// cancel() is the sole cross-thread operation. Its caller must keep this object
// alive, and cancellation stays set until the owning worker calls clear().
// This class never opens a network connection, logs text, or chooses a row.
class CandidateRanker final {
 public:
  static constexpr std::size_t kContextChars = 64;
  static constexpr std::size_t kCandidates = 32;
  static constexpr std::size_t kCandidateChars = 64;
  static constexpr std::size_t kPrefixCapacity = 512;
  static constexpr std::size_t kBatchNodes = 128;

  CandidateRanker(const std::string& model_directory, const std::string& runtime_path);
  ~CandidateRanker();
  CandidateRanker(const CandidateRanker&) = delete;
  CandidateRanker& operator=(const CandidateRanker&) = delete;

  // Context is clipped to its last 64 Unicode scalar values. Candidates are
  // not truncated: empty, malformed UTF-8, >64-scalar or >32-row input fails.
  // Returns finite mean character log-probabilities in the caller's row order.
  // Unknown scalar values use the release's UNK token, as upstream does.
  // Errors throw a fixed diagnostic code, never text or filesystem paths.
  std::vector<float> score(const std::string& context,
                           const std::vector<std::string>& candidates);
  void clear();
  void cancel() noexcept;
  CacheStats stats() const;

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace zapime::candidate
