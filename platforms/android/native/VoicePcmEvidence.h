// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

// Owned by one native voice session. Exact digital-zero evidence only: this is
// neither VAD nor proof of speech, microphone policy, or recording permission.
class VoicePcmEvidence {
 public:
  void accepted(const std::int16_t* pcm, std::size_t count) {
    for (std::size_t i = 0; !nonzero_ && i < count; ++i) nonzero_ = pcm[i] != 0;
  }
  bool has_nonzero_pcm() const { return nonzero_; }
  std::string visible_text(const std::string& text) const {
    return nonzero_ ? text : std::string{};
  }
 private:
  bool nonzero_ = false;
};
