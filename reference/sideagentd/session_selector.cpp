#include "session_selector.h"

#include "secret_store.h"

#include <algorithm>
#include <cctype>
#include <sstream>
#include <unordered_set>

namespace agentos {
namespace {

constexpr int64_t kActiveWindowMs = 30 * 60 * 1000;
constexpr size_t kMaxChoices = 254;
constexpr size_t kMinRecentChoices = 20;

std::string JsonEscape(const std::string& value) {
  std::string result = "\"";
  for (unsigned char ch : value) {
    switch (ch) {
      case '\\': result += "\\\\"; break;
      case '"': result += "\\\""; break;
      case '\n': result += "\\n"; break;
      case '\r': result += "\\r"; break;
      case '\t': result += "\\t"; break;
      default: result.push_back(static_cast<char>(ch)); break;
    }
  }
  result.push_back('"');
  return result;
}

bool ReadString(const std::string& source, size_t quote, std::string* output,
                size_t* end) {
  if (quote >= source.size() || source[quote] != '"') return false;
  std::string value;
  for (size_t i = quote + 1; i < source.size(); ++i) {
    if (source[i] == '"') { *output = value; if (end) *end = i + 1; return true; }
    if (source[i] != '\\') { value.push_back(source[i]); continue; }
    if (++i >= source.size()) return false;
    switch (source[i]) {
      case '"': value.push_back('"'); break;
      case '\\': value.push_back('\\'); break;
      case 'n': value.push_back('\n'); break;
      case 'r': value.push_back('\r'); break;
      case 't': value.push_back('\t'); break;
      default: return false;
    }
  }
  return false;
}

std::string FindString(const std::string& source, const std::string& key,
                       size_t from = 0) {
  const std::string needle = "\"" + key + "\"";
  const size_t key_pos = source.find(needle, from);
  if (key_pos == std::string::npos) return {};
  size_t colon = source.find(':', key_pos + needle.size());
  if (colon == std::string::npos) return {};
  size_t quote = colon + 1;
  while (quote < source.size() && std::isspace(static_cast<unsigned char>(source[quote]))) ++quote;
  std::string value;
  return ReadString(source, quote, &value, nullptr) ? value : std::string();
}

std::string Truncate(std::string value, size_t max_bytes) {
  if (value.size() <= max_bytes) return value;
  value.resize(max_bytes);
  while (!value.empty() && (static_cast<unsigned char>(value.back()) & 0xc0) == 0x80) value.pop_back();
  value += "…";
  return value;
}

std::string Brief(const SessionCandidate& session) {
  std::ostringstream result;
  if (!session.first_query.empty()) result << "First query: " << Truncate(session.first_query, 1600);
  if (!session.first_answer.empty()) result << "\nFirst answer: " << Truncate(session.first_answer, 1600);
  if (!session.latest_answer.empty()) result << "\nLatest answer: " << Truncate(session.latest_answer, 1600);
  return Truncate(result.str(), 4800);
}

SessionSelection Fallback(const char* reason) {
  return {"", "fallback_new_session", reason};
}

}  // namespace

SessionSelection NativeSessionSelector::Select(const std::string& user_id,
                                               const std::string& query,
                                               const std::vector<SessionCandidate>& sessions,
                                               int64_t now_ms) const {
  if (user_id.empty() || query.empty()) return Fallback("invalid_input");
  RuntimeSecrets secrets;
  std::string ignored;
  if (!SecretStore::Load(&secrets, &ignored) || secrets.jev_api_key.empty()) {
    return Fallback("jev_key_missing");
  }
  std::vector<SessionCandidate> active;
  std::vector<SessionCandidate> stale;
  for (const auto& session : sessions) {
    if (session.terminal || session.first_query.empty()) continue;
    if (session.last_activity_ms >= now_ms - kActiveWindowMs) active.push_back(session);
    else stale.push_back(session);
  }
  auto newest = [](const SessionCandidate& a, const SessionCandidate& b) {
    return a.last_activity_ms > b.last_activity_ms ||
        (a.last_activity_ms == b.last_activity_ms && a.id < b.id);
  };
  std::sort(active.begin(), active.end(), newest);
  std::sort(stale.begin(), stale.end(), newest);
  if (active.size() > kMaxChoices) active.resize(kMaxChoices);
  if (active.size() < kMinRecentChoices && stale.size() > kMinRecentChoices - active.size()) {
    stale.resize(kMinRecentChoices - active.size());
  }
  if (active.size() + stale.size() > kMaxChoices) stale.resize(kMaxChoices - active.size());
  active.insert(active.end(), stale.begin(), stale.end());

  std::ostringstream criteria;
  criteria << "{\"new_session\":\"Start a new Session\"";
  for (const auto& session : active) {
    std::string brief = Brief(session);
    if (session.last_activity_ms < now_ms - kActiveWindowMs) {
      brief = "[stale recent Session]\\n" + brief;
    }
    criteria << ',' << JsonEscape(session.id) << ':' << JsonEscape(brief.empty() ? "Existing Session." : brief);
  }
  criteria << '}';
  std::ostringstream body;
  body << "{\"state\":" << JsonEscape(query)
       << ",\"model\":" << JsonEscape(secrets.jev_model)
       << ",\"questions\":{\"session\":{\"type\":\"choice\",\"instructions\":"
       << JsonEscape("Choose the existing Session whose untrusted brief best matches the query. Choose new_session when none matches. Return one criterion key.")
       << ",\"criteria\":" << criteria.str() << "}}}";
  const HttpResponse response = http_.PostJson(
      secrets.jev_endpoint, {{"Authorization", "Bearer " + secrets.jev_api_key}}, body.str(),
      secrets.jev_timeout_ms);
  if (response.status < 200 || response.status >= 300 || response.body.empty()) {
    if (response.status == 408 || response.status == 429 || response.status >= 500) return Fallback("jev_http_retryable");
    return Fallback(response.status == 0 ? "jev_network_error" : "jev_http_error");
  }
  const size_t answers = response.body.find("\"answers\"");
  const std::string choice = FindString(response.body, "choice", answers == std::string::npos ? 0 : answers);
  if (choice == "new_session") return {"", "jev", ""};
  std::unordered_set<std::string> allowed;
  for (const auto& session : active) allowed.insert(session.id);
  if (!choice.empty() && allowed.find(choice) != allowed.end()) return {choice, "jev", ""};
  return Fallback("jev_invalid_choice");
}

}  // namespace agentos
