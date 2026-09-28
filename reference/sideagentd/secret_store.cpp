#include "secret_store.h"

#include <cerrno>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <unordered_map>

namespace agentos {
namespace {

std::string Trim(std::string value) {
  auto is_space = [](unsigned char c) { return std::isspace(c) != 0; };
  value.erase(value.begin(), std::find_if(value.begin(), value.end(),
                                           [&](unsigned char c) { return !is_space(c); }));
  value.erase(std::find_if(value.rbegin(), value.rend(),
                           [&](unsigned char c) { return !is_space(c); }).base(), value.end());
  return value;
}

std::string Unquote(std::string value) {
  if (value.size() >= 2 && ((value.front() == '"' && value.back() == '"') ||
                            (value.front() == '\'' && value.back() == '\''))) {
    value = value.substr(1, value.size() - 2);
  }
  std::string result;
  result.reserve(value.size());
  bool escaped = false;
  for (char c : value) {
    if (escaped) {
      switch (c) {
        case 'n': result.push_back('\n'); break;
        case 'r': result.push_back('\r'); break;
        case 't': result.push_back('\t'); break;
        default: result.push_back(c); break;
      }
      escaped = false;
    } else if (c == '\\') {
      escaped = true;
    } else {
      result.push_back(c);
    }
  }
  if (escaped) result.push_back('\\');
  return result;
}

void AddEnvironment(std::unordered_map<std::string, std::string>* values,
                    const char* key) {
  const char* value = std::getenv(key);
  if (value != nullptr && *value != '\0' && values->find(key) == values->end()) {
    values->emplace(key, value);
  }
}

int ParseTimeout(const std::unordered_map<std::string, std::string>& values,
                 const char* key, int fallback) {
  auto it = values.find(key);
  if (it == values.end()) return fallback;
  char* end = nullptr;
  const long parsed = std::strtol(it->second.c_str(), &end, 10);
  if (end == it->second.c_str() || *end != '\0' || parsed < 1 || parsed > 600000) {
    return fallback;
  }
  return static_cast<int>(parsed);
}

}  // namespace

bool SecretStore::Load(RuntimeSecrets* out, std::string* error, const std::string& path_arg) {
  if (out == nullptr) {
    if (error) *error = "secret output is null";
    return false;
  }
  std::unordered_map<std::string, std::string> values;
  const std::string path = path_arg.empty()
      ? (std::getenv("AGENTOS_SECRET_FILE") != nullptr
             ? std::getenv("AGENTOS_SECRET_FILE") : kDefaultPath)
      : path_arg;

  std::ifstream stream(path);
  if (stream.good()) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) {
      if (error) *error = "secret file stat failed";
      return false;
    }
    // A root-owned file is useful while provisioning a userdebug image, but a
    // world/group-readable file would make the key visible to app UIDs.
    if ((st.st_mode & 0077) != 0) {
      if (error) *error = "secret file permissions are too broad";
      return false;
    }
    std::string line;
    while (std::getline(stream, line)) {
      line = Trim(line);
      if (line.empty() || line.front() == '#') continue;
      const size_t separator = line.find('=');
      if (separator == std::string::npos) continue;
      std::string key = Trim(line.substr(0, separator));
      std::string value = Unquote(Trim(line.substr(separator + 1)));
      if (key.empty() || key.size() > 96 || value.size() > 8192) continue;
      values[key] = value;
    }
  } else if (errno != ENOENT) {
    if (error) *error = "secret file could not be opened";
    return false;
  }

  // The environment fallback keeps host-side tests useful.  init does not
  // populate these variables in production, and no value is ever logged.
  for (const char* key : {"MINIMAX_API_KEY", "MINIMAX_BASE_URL", "MINIMAX_MODEL",
                          "JEV_API_KEY", "JEV_ENDPOINT", "JEV_MODEL",
                          "MINIMAX_TIMEOUT_MS", "JEV_TIMEOUT_MS"}) {
    AddEnvironment(&values, key);
  }

  out->minimax_api_key = values["MINIMAX_API_KEY"];
  out->minimax_base_url = values.count("MINIMAX_BASE_URL")
      ? values["MINIMAX_BASE_URL"] : "https://api.minimax.cn/anthropic/v1/messages";
  out->minimax_model = values.count("MINIMAX_MODEL") ? values["MINIMAX_MODEL"] : "MiniMax-M3";
  out->jev_api_key = values["JEV_API_KEY"];
  out->jev_endpoint = values.count("JEV_ENDPOINT")
      ? values["JEV_ENDPOINT"] : "https://omnilabs.vibeadmin.cn/v1/systemone";
  out->jev_model = values.count("JEV_MODEL") ? values["JEV_MODEL"] : "jev-1.13.0";
  out->minimax_timeout_ms = ParseTimeout(values, "MINIMAX_TIMEOUT_MS", 120000);
  out->jev_timeout_ms = ParseTimeout(values, "JEV_TIMEOUT_MS", 1500);
  return true;
}

}  // namespace agentos
