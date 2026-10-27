// SPDX-License-Identifier: GPL-3.0-only
#include "VoicePcmEvidence.h"
#include <array>
#include <iostream>
#include <limits>
#include <stdexcept>

static void require(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}

int main() {
  const std::string synthetic_model_text = u8"synthetic model text 合成";
  VoicePcmEvidence empty;
  empty.accepted(nullptr, 0);
  require(!empty.has_nonzero_pcm() && empty.visible_text(synthetic_model_text).empty(), "no-sample text escaped");
  std::array<std::int16_t, 1600> zero{};
  for (int block = 0; block < 600; ++block) empty.accepted(zero.data(), zero.size());
  require(!empty.has_nonzero_pcm() && empty.visible_text(synthetic_model_text).empty(), "zero-only text escaped");
  const std::array<std::int16_t, 2> outside_prefix{0, 1};
  empty.accepted(outside_prefix.data(), 1);
  require(!empty.has_nonzero_pcm(), "ignored tail became evidence");
  for (const auto sample : std::array<std::int16_t, 4>{1, -1, std::numeric_limits<std::int16_t>::min(), std::numeric_limits<std::int16_t>::max()}) {
    VoicePcmEvidence session;
    session.accepted(&sample, 1);
    session.accepted(zero.data(), zero.size());
    require(session.has_nonzero_pcm(), "nonzero sample or trailing quiet was rejected");
    require(session.visible_text(synthetic_model_text) == synthetic_model_text, "model text changed after nonzero input");
  }
  VoicePcmEvidence next_session;
  require(!next_session.has_nonzero_pcm() && next_session.visible_text(synthetic_model_text).empty(), "evidence crossed sessions");
  require(outside_prefix[0] == 0 && outside_prefix[1] == 1 && zero[0] == 0 && zero.back() == 0, "PCM was modified");
  std::cout << "PASS exact-zero PCM policy: empty/zero simulated text suppressed, valid prefix only, +/-1 and int16 extremes retained, trailing quiet and session isolation\n";
}
