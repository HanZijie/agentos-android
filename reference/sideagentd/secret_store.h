#pragma once

#include <string>

namespace agentos {

// Runtime credentials are deliberately kept out of the Binder protocol and
// the durable session store.  The daemon reads this file once per request so
// an operator can rotate a credential without rebuilding the system image.
struct RuntimeSecrets {
  std::string minimax_api_key;
  std::string minimax_base_url;
  std::string minimax_model;
  std::string jev_api_key;
  std::string jev_endpoint;
  std::string jev_model;
  int minimax_timeout_ms = 120000;
  int jev_timeout_ms = 1500;
};

class SecretStore {
 public:
  static constexpr const char* kDefaultPath = "/data/agent/secrets/agent.env";

  // Loads a protected env-style file.  Values from the process environment
  // are accepted only as a local-development fallback; the production init
  // service supplies AGENTOS_SECRET_FILE.
  static bool Load(RuntimeSecrets* out, std::string* error = nullptr,
                   const std::string& path = "");
};

}  // namespace agentos
