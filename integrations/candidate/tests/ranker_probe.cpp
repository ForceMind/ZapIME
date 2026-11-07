// SPDX-License-Identifier: GPL-3.0-only
// Test-only stdin protocol. Inputs must be synthetic; output contains scores
// and resource counters, not candidate or editor text.
#include "CandidateRanker.h"
#include <nlohmann/json.hpp>
#include <array>
#include <iostream>
#include <string>
#include <thread>

using zapime::candidate::CandidateRanker;
using json = nlohmann::json;

int main(int argc, char** argv) {
  if (argc != 3) return 2;
  try {
    CandidateRanker ranker(argv[1], argv[2]);
    std::cout << json{{"ready", true}}.dump() << std::endl;
    std::array<char, 32769> line{};
    while (std::cin.getline(line.data(), static_cast<std::streamsize>(line.size()))) {
      try {
        const auto request = json::parse(line.data());
        const auto op = request.value("op", std::string("score"));
        if (op == "clear") {
          ranker.clear();
          std::cout << json{{"ok", true}}.dump() << std::endl;
        } else if (op == "cancel") {
          std::thread other([&ranker] { ranker.cancel(); });
          other.join();
          std::cout << json{{"ok", true}}.dump() << std::endl;
        } else if (op == "foreign") {
          bool score_rejected = false, clear_rejected = false;
          std::thread other([&] {
            try { (void)ranker.score("", {"你"}); } catch (...) { score_rejected = true; }
            try { ranker.clear(); } catch (...) { clear_rejected = true; }
          });
          other.join();
          std::cout << json{{"ok", score_rejected && clear_rejected}}.dump() << std::endl;
        } else if (op == "score") {
          const auto scores = ranker.score(request.at("context").get<std::string>(),
                                           request.at("candidates").get<std::vector<std::string>>());
          const auto stats = ranker.stats();
          std::cout << json{{"ok", true}, {"scores", scores},
                           {"context_nodes", stats.context_nodes}, {"prefix_nodes", stats.prefix_nodes},
                           {"new_nodes", stats.new_nodes}, {"batches", stats.batches},
                           {"evictions", stats.evictions}}.dump() << std::endl;
        } else {
          std::cout << json{{"ok", false}}.dump() << std::endl;
        }
      } catch (...) { std::cout << json{{"ok", false}}.dump() << std::endl; }
    }
    return std::cin.eof() ? 0 : 3;
  } catch (const std::exception& error) {
    // CandidateRanker uses fixed errors only. Do not print ORT diagnostics.
    std::cerr << error.what() << '\n';
    return 1;
  }
}
