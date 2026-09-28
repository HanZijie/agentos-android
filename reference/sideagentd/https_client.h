#pragma once

#include <atomic>
#include <map>
#include <string>

namespace agentos {

struct HttpResponse {
  int status = 0;
  std::string body;
  std::string error;
};

// Small HTTPS POST client used by the system daemon.  It deliberately exposes
// only status/body/error; request headers and credentials never enter logs or
// the durable Session state.
class HttpsClient {
 public:
  HttpResponse PostJson(const std::string& url,
                        const std::map<std::string, std::string>& headers,
                        const std::string& body, int timeout_ms,
                        const std::atomic<bool>* cancelled = nullptr) const;
};

}  // namespace agentos
