#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "https_client.h"

namespace agentos {

struct SessionCandidate {
  std::string id;
  std::string first_query;
  std::string first_answer;
  std::string latest_answer;
  int64_t last_activity_ms = 0;
  bool terminal = false;
};

struct SessionSelection {
  std::string session_id;
  std::string method;
  std::string fallback_reason;
};

// Jev is an advisory selector. It receives a bounded, user-local list and its
// exact returned id is checked against that list. Any missing credential,
// timeout, HTTP error or malformed choice safely selects new_session.
class NativeSessionSelector {
 public:
  NativeSessionSelector() = default;
  SessionSelection Select(const std::string& user_id, const std::string& query,
                          const std::vector<SessionCandidate>& sessions,
                          int64_t now_ms) const;

 private:
  HttpsClient http_;
};

}  // namespace agentos
