// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <string>

// Test-only raw tensor executor. It has no dependency on CandidateRanker,
// production tokenization, prefix construction, cache policy or JNI handles.
namespace zapime::candidate::oracle {
std::string sha256(const std::string& bytes);
std::string evaluate(const std::string& model_path, const std::string& runtime_path,
                     const std::string& fixture_json);
}
