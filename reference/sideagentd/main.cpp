#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/binder_stability.h>
#include <android-base/logging.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstdlib>
#include <fstream>
#include <memory>
#include <mutex>
#include <random>
#include <sstream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include <sys/stat.h>
#include <unistd.h>

#include "aidl/com/example/agentos/AgentEnqueueResult.h"
#include "aidl/com/example/agentos/AgentHealth.h"
#include "aidl/com/example/agentos/AgentPluginInvokeRequest.h"
#include "aidl/com/example/agentos/AgentPluginInvokeResult.h"
#include "aidl/com/example/agentos/AgentPluginResourceRequest.h"
#include "aidl/com/example/agentos/AgentPluginSession.h"
#include "aidl/com/example/agentos/AgentSessionSnapshot.h"
#include "aidl/com/example/agentos/BnSideagentd.h"
#include "aidl/com/example/agentos/IAgentEventCallback.h"
#include "aidl/com/example/agentos/IAgentPluginEndpoint.h"
#include "aidl/com/example/agentos/IAgentPluginResultSink.h"

#include "runtime_worker.h"
#include <json/json.h>
#include "session_selector.h"

using aidl::com::example::agentos::AgentEnqueueResult;
using aidl::com::example::agentos::AgentHealth;
using aidl::com::example::agentos::AgentPluginInvokeRequest;
using aidl::com::example::agentos::AgentPluginInvokeResult;
using aidl::com::example::agentos::AgentPluginResourceRequest;
using aidl::com::example::agentos::AgentPluginSession;
using aidl::com::example::agentos::AgentSessionSnapshot;
using aidl::com::example::agentos::BnSideagentd;
using aidl::com::example::agentos::IAgentEventCallback;
using aidl::com::example::agentos::IAgentPluginEndpoint;
using aidl::com::example::agentos::IAgentPluginResultSink;
using ndk::ScopedAStatus;

namespace {

constexpr char kStatePath[] = "/data/agent/state/sideagentd.state";

bool AuthorizedCaller() {
  const uid_t caller = AIBinder_getCallingUid();
  return caller == 0 || caller == 1000;
}

bool Contains(const std::vector<std::string>& values, const std::string& value) {
  return std::find(values.begin(), values.end(), value) != values.end();
}

std::string JsonString(const std::string& value) {
  std::string result = "\"";
  for (unsigned char ch : value) {
    switch (ch) {
      case '\\': result += "\\\\"; break;
      case '"': result += "\\\""; break;
      case '\n': result += "\\n"; break;
      case '\r': result += "\\r"; break;
      case '\t': result += "\\t"; break;
      default:
        if (ch < 0x20) {
          const char* hex = "0123456789abcdef";
          result += "\\u00";
          result += hex[ch >> 4];
          result += hex[ch & 15];
        } else {
          result.push_back(static_cast<char>(ch));
        }
    }
  }
  result.push_back('"');
  return result;
}

std::string HexEncode(const std::string& value) {
  static constexpr char kHex[] = "0123456789abcdef";
  std::string result;
  result.reserve(value.size() * 2);
  for (unsigned char byte : value) {
    result.push_back(kHex[byte >> 4]);
    result.push_back(kHex[byte & 0x0f]);
  }
  return result;
}

bool HexDecode(const std::string& input, std::string* output) {
  if (input.size() % 2 != 0) return false;
  output->clear();
  output->reserve(input.size() / 2);
  auto digit = [](char c) -> int {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
  };
  for (size_t i = 0; i < input.size(); i += 2) {
    const int high = digit(input[i]);
    const int low = digit(input[i + 1]);
    if (high < 0 || low < 0) return false;
    output->push_back(static_cast<char>((high << 4) | low));
  }
  return true;
}

std::vector<std::string> SplitTabs(const std::string& line) {
  std::vector<std::string> result;
  size_t start = 0;
  while (start <= line.size()) {
    const size_t tab = line.find('\t', start);
    result.push_back(line.substr(start, tab == std::string::npos ? std::string::npos : tab - start));
    if (tab == std::string::npos) break;
    start = tab + 1;
  }
  return result;
}

bool ReadJsonStringAt(const std::string& source, size_t quote, std::string* out,
                      size_t* end) {
  if (quote >= source.size() || source[quote] != '"') return false;
  std::string value;
  for (size_t index = quote + 1; index < source.size(); ++index) {
    const char c = source[index];
    if (c == '"') {
      if (out) *out = value;
      if (end) *end = index + 1;
      return true;
    }
    if (c != '\\') { value.push_back(c); continue; }
    if (++index >= source.size()) return false;
    switch (source[index]) {
      case '"': value.push_back('"'); break;
      case '\\': value.push_back('\\'); break;
      case 'n': value.push_back('\n'); break;
      case 'r': value.push_back('\r'); break;
      case 't': value.push_back('\t'); break;
      default: value.push_back(source[index]); break;
    }
  }
  return false;
}

std::string PromptText(const std::string& content_json) {
  const std::string needle = "\"text\"";
  std::string result;
  size_t cursor = 0;
  while ((cursor = content_json.find(needle, cursor)) != std::string::npos) {
    size_t colon = content_json.find(':', cursor + needle.size());
    if (colon == std::string::npos) break;
    size_t quote = colon + 1;
    while (quote < content_json.size() &&
           std::isspace(static_cast<unsigned char>(content_json[quote]))) ++quote;
    std::string value;
    size_t end = 0;
    if (ReadJsonStringAt(content_json, quote, &value, &end)) {
      if (!result.empty()) result.push_back('\n');
      result += value;
      cursor = end;
    } else {
      cursor += needle.size();
    }
  }
  return result;
}

int64_t NowMs() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::system_clock::now().time_since_epoch()).count();
}

void EnsureDirectory(const std::string& path) {
  size_t start = path.front() == '/' ? 1 : 0;
  while (start < path.size()) {
    const size_t slash = path.find('/', start);
    const std::string part = path.substr(0, slash == std::string::npos ? path.size() : slash);
    if (!part.empty()) mkdir(part.c_str(), 0700);
    if (slash == std::string::npos) break;
    start = slash + 1;
  }
}

ScopedAStatus DeliverError(const std::shared_ptr<IAgentPluginResultSink>& sink,
                           const std::string& request_id, const std::string& code,
                           const std::string& message, bool retryable = false) {
  if (sink) {
    AgentPluginInvokeResult result;
    result.requestId = request_id;
    result.status = "error";
    result.error.code = code;
    result.error.message = message;
    result.error.retryable = retryable;
    result.error.dataJson = "";
    sink->onResult(result);
  }
  return ScopedAStatus::ok();
}

class Sideagentd final : public BnSideagentd {
 public:
  Sideagentd()
      : started_at_ms_(NowMs()), random_(std::random_device{}()),
        worker_([this](const agentos::RuntimeEvent& event) { OnRuntimeEvent(event); },
                [this](const std::string& id) { return History(id); }) {
    LoadState();
    worker_.Start();
    ResumeQueued();
  }

  ~Sideagentd() override { worker_.Stop(); }

  ScopedAStatus getHealth(AgentHealth* out) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    if (out == nullptr) return ScopedAStatus::fromExceptionCode(EX_NULL_POINTER);
    std::lock_guard<std::mutex> guard(lock_);
    out->protocolVersion = 1;
    out->state = "ready";
    out->startedAtMs = started_at_ms_;
    out->activeSessions = static_cast<int32_t>(agent_sessions_.size());
    out->queuedTasks = static_cast<int32_t>(queued_tasks_);
    return ScopedAStatus::ok();
  }

  ScopedAStatus registerPluginSession(const AgentPluginSession& session) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    if (session.pluginSessionId.empty() || session.pluginId.empty() ||
        session.packageName.empty() || session.endpoint == nullptr) {
      return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    std::lock_guard<std::mutex> guard(lock_);
    plugin_sessions_[session.pluginSessionId] = PluginSession{
        session.userId, session.pluginId, session.packageName, session.grantedTools,
        session.grantedResources, session.endpoint};
    return ScopedAStatus::ok();
  }

  ScopedAStatus unregisterPluginSession(const std::string& plugin_session_id,
                                        const std::string& /*reason*/) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    std::lock_guard<std::mutex> guard(lock_);
    plugin_sessions_.erase(plugin_session_id);
    const std::string prefix = plugin_session_id + "\n";
    for (auto it = requests_.begin(); it != requests_.end();) {
      if (it->rfind(prefix, 0) == 0) it = requests_.erase(it);
      else ++it;
    }
    return ScopedAStatus::ok();
  }

  ScopedAStatus beginInvoke(const AgentPluginInvokeRequest& request,
                            const std::shared_ptr<IAgentPluginResultSink>& sink) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    if (request.requestId.empty() || request.tool.empty() || request.pluginSessionId.empty())
      return DeliverError(sink, request.requestId, "invalid_request", "request fields are required");
    if (request.deadlineEpochMs <= NowMs())
      return DeliverError(sink, request.requestId, "timeout", "deadline has elapsed");
    std::shared_ptr<IAgentPluginEndpoint> endpoint;
    const std::string request_key = request.pluginSessionId + "\n" + request.requestId;
    {
      std::lock_guard<std::mutex> guard(lock_);
      auto session = plugin_sessions_.find(request.pluginSessionId);
      if (session == plugin_sessions_.end())
        return DeliverError(sink, request.requestId, "unavailable", "Plugin session is not registered", true);
      if (!Contains(session->second.tools, request.tool))
        return DeliverError(sink, request.requestId, "unknown_tool", "tool is not granted");
      if (!requests_.emplace(request_key).second)
        return DeliverError(sink, request.requestId, "invalid_request", "duplicate request id");
      endpoint = session->second.endpoint;
    }
    const ScopedAStatus status = endpoint->beginInvoke(request, sink);
    if (!status.isOk()) {
      std::lock_guard<std::mutex> guard(lock_);
      requests_.erase(request_key);
      return DeliverError(sink, request.requestId, "unavailable", "Plugin endpoint rejected invoke", true);
    }
    return ScopedAStatus::ok();
  }

  ScopedAStatus beginReadResource(const AgentPluginResourceRequest& request,
                                  const std::shared_ptr<IAgentPluginResultSink>& sink) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    if (request.requestId.empty() || request.resource.empty() || request.pluginSessionId.empty())
      return DeliverError(sink, request.requestId, "invalid_request", "request fields are required");
    if (request.deadlineEpochMs <= NowMs() || request.maxBytes <= 0)
      return DeliverError(sink, request.requestId, "invalid_request", "invalid resource deadline or size");
    std::shared_ptr<IAgentPluginEndpoint> endpoint;
    const std::string request_key = request.pluginSessionId + "\n" + request.requestId;
    {
      std::lock_guard<std::mutex> guard(lock_);
      auto session = plugin_sessions_.find(request.pluginSessionId);
      if (session == plugin_sessions_.end())
        return DeliverError(sink, request.requestId, "unavailable", "Plugin session is not registered", true);
      if (!Contains(session->second.resources, request.resource))
        return DeliverError(sink, request.requestId, "unknown_resource", "resource is not granted");
      if (!requests_.emplace(request_key).second)
        return DeliverError(sink, request.requestId, "invalid_request", "duplicate request id");
      endpoint = session->second.endpoint;
    }
    const ScopedAStatus status = endpoint->beginReadResource(request, sink);
    if (!status.isOk()) {
      std::lock_guard<std::mutex> guard(lock_);
      requests_.erase(request_key);
      return DeliverError(sink, request.requestId, "unavailable", "Plugin endpoint rejected resource read", true);
    }
    return ScopedAStatus::ok();
  }

  ScopedAStatus cancelInvoke(const std::string& plugin_session_id,
                             const std::string& request_id) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    std::shared_ptr<IAgentPluginEndpoint> endpoint;
    {
      std::lock_guard<std::mutex> guard(lock_);
      auto session = plugin_sessions_.find(plugin_session_id);
      if (session == plugin_sessions_.end()) return ScopedAStatus::ok();
      endpoint = session->second.endpoint;
      requests_.erase(plugin_session_id + "\n" + request_id);
    }
    return endpoint->cancelInvoke(plugin_session_id, request_id);
  }

  ScopedAStatus createSession(int32_t user_id, int32_t frontend_uid,
                              const std::string& frontend_id,
                              const std::string& metadata_json,
                              std::string* out_session_id) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    if (out_session_id == nullptr || frontend_id.empty() || frontend_id.size() > 128)
      return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    std::string id;
    {
      std::lock_guard<std::mutex> guard(lock_);
      id = "session-" + std::to_string(random_()) + "-" + std::to_string(random_());
      AgentSession session;
      session.user_id = user_id;
      session.owner_uid = frontend_uid;
      session.frontend_id = frontend_id;
      session.metadata_json = metadata_json.empty() ? "{}" : metadata_json;
      agent_sessions_.emplace(id, std::move(session));
      PersistLocked();
    }
    *out_session_id = id;
    return ScopedAStatus::ok();
  }

  ScopedAStatus submitInput(int32_t user_id, int32_t frontend_uid,
                            const std::string& session_id, const std::string& request_id,
                            const std::string& content_json,
                            AgentEnqueueResult* out_result) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    if (out_result == nullptr || session_id.empty() || request_id.empty() || content_json.empty())
      return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    *out_result = Submit(user_id, frontend_uid, session_id, request_id, content_json);
    return ScopedAStatus::ok();
  }

  ScopedAStatus submitAutoInput(int32_t user_id, int32_t frontend_uid,
                                const std::string& frontend_id, const std::string& metadata_json,
                                const std::string& request_id, const std::string& content_json,
                                AgentEnqueueResult* out_result) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    if (out_result == nullptr || frontend_id.empty() || request_id.empty() || content_json.empty())
      return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    const std::string query = PromptText(content_json);
    std::vector<agentos::SessionCandidate> candidates;
    {
      std::lock_guard<std::mutex> guard(lock_);
      for (const auto& [id, session] : agent_sessions_) {
        if (session.user_id != user_id || session.owner_uid != frontend_uid) continue;
        candidates.push_back(agentos::SessionCandidate{
            id, session.first_query, session.first_answer, session.latest_answer,
            session.last_activity_ms, session.state == "completed" || session.state == "failed"});
      }
    }
    const agentos::SessionSelection selection = selector_.Select(
        std::to_string(user_id), query, candidates, NowMs());
    std::string session_id = selection.session_id;
    bool created = false;
    if (session_id.empty()) {
      std::string new_id;
      ScopedAStatus status = createSession(user_id, frontend_uid, frontend_id, metadata_json, &new_id);
      if (!status.isOk()) return std::move(status);
      session_id = new_id;
      created = true;
    }
    *out_result = Submit(user_id, frontend_uid, session_id, request_id, content_json);
    if (out_result->accepted) {
      Emit(session_id, "{\"eventType\":\"session.selected\",\"method\":" +
                       JsonString(selection.method) + ",\"created\":" +
                       std::string(created ? "true" : "false") +
                       (selection.fallback_reason.empty() ? "}" : ",\"fallbackReason\":" +
                        JsonString(selection.fallback_reason) + "}"));
    }
    return ScopedAStatus::ok();
  }

  ScopedAStatus subscribeOutput(int32_t user_id, int32_t frontend_uid,
                                const std::string& session_id, int64_t after_sequence,
                                const std::shared_ptr<IAgentEventCallback>& callback) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    if (callback == nullptr) return ScopedAStatus::fromExceptionCode(EX_NULL_POINTER);
    std::vector<Event> replay;
    {
      std::lock_guard<std::mutex> guard(lock_);
      auto session = agent_sessions_.find(session_id);
      if (session == agent_sessions_.end() || session->second.user_id != user_id ||
          session->second.owner_uid != frontend_uid) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
      session->second.subscribers.push_back(callback);
      for (const auto& event : session->second.events) if (event.sequence > after_sequence) replay.push_back(event);
    }
    for (const auto& event : replay) if (callback) callback->onEvent(session_id, event.sequence, event.json);
    return ScopedAStatus::ok();
  }

  ScopedAStatus unsubscribeOutput(int32_t user_id, int32_t frontend_uid,
                                  const std::string& session_id,
                                  const std::shared_ptr<IAgentEventCallback>& callback) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    if (callback == nullptr) return ScopedAStatus::ok();
    std::lock_guard<std::mutex> guard(lock_);
    auto session = agent_sessions_.find(session_id);
    if (session == agent_sessions_.end() || session->second.user_id != user_id ||
        session->second.owner_uid != frontend_uid) return ScopedAStatus::ok();
    session->second.subscribers.erase(
        std::remove_if(session->second.subscribers.begin(), session->second.subscribers.end(),
                       [&](const auto& item) { return item.get() == callback.get(); }),
        session->second.subscribers.end());
    return ScopedAStatus::ok();
  }

  ScopedAStatus cancelTask(int32_t user_id, int32_t frontend_uid,
                           const std::string& session_id, const std::string& request_id) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    bool cancel = false;
    {
      std::lock_guard<std::mutex> guard(lock_);
      auto session = agent_sessions_.find(session_id);
      if (session == agent_sessions_.end() || session->second.user_id != user_id ||
          session->second.owner_uid != frontend_uid) return ScopedAStatus::ok();
      auto request = session->second.requests.find(request_id);
      if (request == session->second.requests.end() || request->second.state == "completed" ||
          request->second.state == "failed" || request->second.state == "cancelled" ||
          request->second.state == "unknown") return ScopedAStatus::ok();
      request->second.state = "cancelling";
      PersistLocked();
      cancel = true;
    }
    if (cancel) worker_.Cancel(session_id, request_id);
    return ScopedAStatus::ok();
  }

  ScopedAStatus resolveRecovery(int32_t user_id, int32_t frontend_uid,
                                const std::string& session_id,
                                const std::string& request_id) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    bool resolved = false;
    {
      std::lock_guard<std::mutex> guard(lock_);
      auto session = agent_sessions_.find(session_id);
      if (session == agent_sessions_.end() || session->second.user_id != user_id ||
          session->second.owner_uid != frontend_uid) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
      auto request = session->second.requests.find(request_id);
      if (request == session->second.requests.end() || request->second.state != "unknown")
        return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
      request->second.state = "failed";
      request->second.error_code = "recovery_failed";
      request->second.error_message = "Unknown runtime attempt was explicitly fenced";
      session->second.recovery_required = false;
      for (const auto& [id, item] : session->second.requests)
        if (item.state == "unknown") session->second.recovery_required = true;
      PersistLocked();
      resolved = true;
    }
    if (resolved) Emit(session_id, "{\"eventType\":\"task.failed\",\"requestId\":" +
                              JsonString(request_id) + ",\"error\":{\"code\":\"recovery_failed\",\"retryable\":false,\"message\":\"Unknown runtime attempt was explicitly fenced\"},\"attemptState\":\"unknown\"}");
    return ScopedAStatus::ok();
  }

  ScopedAStatus getSnapshot(int32_t user_id, int32_t frontend_uid,
                            const std::string& session_id,
                            AgentSessionSnapshot* out_snapshot) override {
    if (!AuthorizedCaller()) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    if (out_snapshot == nullptr) return ScopedAStatus::fromExceptionCode(EX_NULL_POINTER);
    std::lock_guard<std::mutex> guard(lock_);
    auto session = agent_sessions_.find(session_id);
    if (session == agent_sessions_.end() || session->second.user_id != user_id ||
        session->second.owner_uid != frontend_uid) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
    const AgentSession& value = session->second;
    std::ostringstream json;
    json << "{\"sessionId\":" << JsonString(session_id)
         << ",\"state\":" << JsonString(value.state)
         << ",\"runtime\":\"native_minimax\",\"currentSequence\":" << value.sequence
         << ",\"latestAnswer\":" << JsonString(value.latest_answer)
         << ",\"recoveryRequired\":" << (value.recovery_required ? "true" : "false") << ",\"tasks\":[";
    bool first = true;
    for (const auto& [request_id, request] : value.requests) {
      if (!first) json << ',';
      first = false;
      json << "{\"requestId\":" << JsonString(request_id)
           << ",\"taskId\":" << JsonString(request.task_id)
           << ",\"messageId\":" << JsonString(request.message_id)
           << ",\"state\":" << JsonString(request.state);
      if (!request.error_code.empty()) json << ",\"error\":{\"code\":" << JsonString(request.error_code)
                                               << ",\"message\":" << JsonString(request.error_message) << "}";
      json << '}';
    }
    json << "],\"selection\":";
    auto selected = std::find_if(value.events.rbegin(), value.events.rend(), [](const Event& event) {
      return event.json.find("\"eventType\":\"session.selected\"") != std::string::npos;
    });
    json << (selected == value.events.rend() ? "null" : selected->json) << '}';
    out_snapshot->sessionId = session_id;
    out_snapshot->currentSequence = value.sequence;
    out_snapshot->json = json.str();
    return ScopedAStatus::ok();
  }

 private:
  struct PluginSession {
    int32_t user_id;
    std::string plugin_id;
    std::string package_name;
    std::vector<std::string> tools;
    std::vector<std::string> resources;
    std::shared_ptr<IAgentPluginEndpoint> endpoint;
  };

  struct Request {
    std::string task_id;
    std::string message_id;
    std::string content_json;
    std::string state = "running";
    std::string error_code;
    std::string error_message;
  };

  struct Event { int64_t sequence; std::string json; };

  struct AgentSession {
    int32_t user_id = 0;
    int32_t owner_uid = 0;
    std::string frontend_id;
    std::string metadata_json;
    std::string state = "created";
    std::string first_query;
    std::string first_answer;
    std::string latest_answer;
    int64_t last_activity_ms = 0;
    int64_t sequence = 0;
    bool recovery_required = false;
    std::vector<Event> events;
    std::unordered_map<std::string, Request> requests;
    std::vector<std::shared_ptr<IAgentEventCallback>> subscribers;
  };

  AgentEnqueueResult Submit(int32_t user_id, int32_t frontend_uid,
                            const std::string& session_id, const std::string& request_id,
                            const std::string& content_json) {
    AgentEnqueueResult result;
    result.accepted = false; result.sessionId = session_id; result.taskId = "";
    result.messageId = ""; result.deduplicated = false; result.errorCode = ""; result.errorMessage = "";
    agentos::RuntimeTask task;
    {
      std::lock_guard<std::mutex> guard(lock_);
      auto session = agent_sessions_.find(session_id);
      if (session == agent_sessions_.end() || session->second.user_id != user_id ||
          session->second.owner_uid != frontend_uid) {
        result.errorCode = "session_not_found"; result.errorMessage = "Agent session is not available";
        return result;
      }
      auto existing = session->second.requests.find(request_id);
      if (existing != session->second.requests.end()) {
        if (existing->second.content_json != content_json) {
          result.errorCode = "request_conflict";
          result.errorMessage = "request id was already used with different content";
          return result;
        }
        result.accepted = true; result.deduplicated = true;
        result.taskId = existing->second.task_id; result.messageId = existing->second.message_id;
        return result;
      }
      if (session->second.state == "completed" || session->second.state == "failed") {
        result.errorCode = "session_terminal"; result.errorMessage = "Agent session is terminal";
        return result;
      }
      for (const auto& [id, request] : session->second.requests) {
        if (request.state == "unknown") {
          result.errorCode = "recovery_required";
          result.errorMessage = "A previous runtime attempt is unknown and must be reconciled";
          return result;
        }
      }
      Request request;
      request.task_id = "task-" + std::to_string(++next_task_id_);
      request.message_id = "message-" + std::to_string(++next_message_id_);
      request.content_json = content_json; request.state = "queued";
      session->second.requests.emplace(request_id, request);
      if (session->second.first_query.empty()) session->second.first_query = PromptText(content_json);
      session->second.last_activity_ms = NowMs(); ++queued_tasks_;
      result.accepted = true; result.taskId = request.task_id; result.messageId = request.message_id;
      task = agentos::RuntimeTask{session_id, request.task_id, request_id, content_json,
                                  session->second.metadata_json, user_id};
      PersistLocked();
    }
    Emit(session_id, "{\"eventType\":\"task.queued\",\"requestId\":" +
                     JsonString(request_id) + ",\"taskId\":" + JsonString(result.taskId) + "}");
    if (!worker_.Enqueue(std::move(task))) {
      OnRuntimeEvent(agentos::RuntimeEvent{session_id, result.taskId, request_id, "failed", "",
                                           "runtime_unavailable", "Native runtime worker is unavailable"});
    }
    return result;
  }

  std::string History(const std::string& session_id) {
    std::lock_guard<std::mutex> guard(lock_);
    Json::Value messages(Json::arrayValue);
    auto session = agent_sessions_.find(session_id);
    if (session == agent_sessions_.end()) return "[]";
    Json::CharReaderBuilder reader;
    for (const Event& event : session->second.events) {
      Json::Value value;
      std::string errors;
      std::istringstream stream(event.json);
      if (!Json::parseFromStream(reader, stream, &value, &errors) || value["eventType"] != "task.output") continue;
      auto request = session->second.requests.find(value["requestId"].asString());
      if (request == session->second.requests.end() || request->second.state != "completed") continue;
      Json::Value user(Json::objectValue), answer(Json::objectValue);
      user["role"] = "user"; user["content"] = PromptText(request->second.content_json);
      answer["role"] = "assistant"; answer["content"] = value["content"]["text"];
      messages.append(user); messages.append(answer);
    }
    Json::Value recent(Json::arrayValue);
    for (Json::ArrayIndex i = messages.size() > 12 ? messages.size() - 12 : 0; i < messages.size(); ++i)
      recent.append(messages[i]);
    Json::StreamWriterBuilder writer; writer["indentation"] = "";
    return Json::writeString(writer, recent);
  }

  void OnRuntimeEvent(const agentos::RuntimeEvent& event) {
    std::string event_json;
    {
      std::lock_guard<std::mutex> guard(lock_);
      auto session = agent_sessions_.find(event.session_id);
      if (session == agent_sessions_.end()) return;
      auto request = session->second.requests.find(event.request_id);
      if (request == session->second.requests.end() || request->second.task_id != event.task_id) return;
      if (event.type == "tool") {
        event_json = event.text;
      } else if (event.type == "output") {
        session->second.latest_answer = event.text;
        if (session->second.first_answer.empty()) session->second.first_answer = event.text;
        event_json = "{\"eventType\":\"task.output\",\"requestId\":" + JsonString(event.request_id) +
            ",\"taskId\":" + JsonString(event.task_id) + ",\"content\":{\"type\":\"text\",\"text\":" +
            JsonString(event.text) + "}}";
      } else if (event.type == "started") {
        request->second.state = "running";
      } else if (event.type == "completed") {
        request->second.state = "completed"; if (queued_tasks_ > 0) --queued_tasks_;
        session->second.last_activity_ms = NowMs();
        event_json = "{\"eventType\":\"task.completed\",\"requestId\":" + JsonString(event.request_id) +
            ",\"taskId\":" + JsonString(event.task_id) + "}";
      } else if (event.type == "cancelled") {
        request->second.state = "cancelled"; if (queued_tasks_ > 0) --queued_tasks_;
        event_json = "{\"eventType\":\"task.cancelled\",\"requestId\":" + JsonString(event.request_id) +
            ",\"taskId\":" + JsonString(event.task_id) + "}";
      } else if (event.type == "failed") {
        request->second.state = "failed";
        request->second.error_code = event.error_code.empty() ? "runtime_failed" : event.error_code;
        request->second.error_message = event.error_message.empty() ? "Agent runtime failed" : event.error_message;
        if (queued_tasks_ > 0) --queued_tasks_;
        event_json = "{\"eventType\":\"task.failed\",\"requestId\":" + JsonString(event.request_id) +
            ",\"taskId\":" + JsonString(event.task_id) + ",\"error\":{\"code\":" +
            JsonString(request->second.error_code) + ",\"retryable\":false,\"message\":" +
            JsonString(request->second.error_message) + "}}";
      }
      PersistLocked();
    }
    if (!event_json.empty()) Emit(event.session_id, event_json);
  }

  void Emit(const std::string& session_id, const std::string& event_json) {
    std::vector<std::shared_ptr<IAgentEventCallback>> subscribers;
    int64_t sequence = 0;
    {
      std::lock_guard<std::mutex> guard(lock_);
      auto session = agent_sessions_.find(session_id);
      if (session == agent_sessions_.end()) return;
      sequence = ++session->second.sequence;
      session->second.events.push_back(Event{sequence, event_json});
      if (session->second.events.size() > 512) session->second.events.erase(session->second.events.begin());
      PersistLocked(); subscribers = session->second.subscribers;
    }
    for (const auto& subscriber : subscribers) if (subscriber) subscriber->onEvent(session_id, sequence, event_json);
  }

  void AppendRecoveryEventLocked(const std::string& session_id, const std::string& request_id,
                                 const Request& request) {
    auto session = agent_sessions_.find(session_id);
    if (session == agent_sessions_.end()) return;
    session->second.events.push_back(Event{++session->second.sequence,
        "{\"eventType\":\"task.recovery_required\",\"requestId\":" + JsonString(request_id) +
        ",\"taskId\":" + JsonString(request.task_id) +
        ",\"error\":{\"code\":\"worker_lost\",\"retryable\":false,\"message\":\"Daemon restarted before runtime completion\"}}"});
  }

  void LoadState() {
    const char* override_path = std::getenv("AGENTOS_STATE_FILE");
    state_path_ = override_path != nullptr && *override_path != '\0' ? override_path : kStatePath;
    const size_t slash = state_path_.rfind('/');
    if (slash != std::string::npos) EnsureDirectory(state_path_.substr(0, slash));
    std::ifstream stream(state_path_);
    if (!stream.good()) return;
    std::string line;
    std::vector<std::pair<std::string, std::string>> recoveries;
    std::lock_guard<std::mutex> guard(lock_);
    while (std::getline(stream, line)) {
      const auto fields = SplitTabs(line);
      if (fields.empty() || fields[0] == "AGENTOS_STATE_V2") continue;
      if (fields[0] == "S" && fields.size() == 11) {
        std::string id;
        AgentSession session;
        if (!HexDecode(fields[1], &id) || !HexDecode(fields[4], &session.frontend_id) ||
            !HexDecode(fields[5], &session.metadata_json) || !HexDecode(fields[6], &session.state) ||
            !HexDecode(fields[7], &session.first_query) || !HexDecode(fields[8], &session.first_answer) ||
            !HexDecode(fields[9], &session.latest_answer)) continue;
        session.user_id = std::atoi(fields[2].c_str()); session.owner_uid = std::atoi(fields[3].c_str());
        session.last_activity_ms = std::strtoll(fields[10].c_str(), nullptr, 10);
        agent_sessions_[id] = std::move(session);
      } else if (fields[0] == "R" && fields.size() == 8) {
        std::string session_id, request_id, task_id, message_id, content, state, error_code;
        if (!HexDecode(fields[1], &session_id) || !HexDecode(fields[2], &request_id) ||
            !HexDecode(fields[3], &task_id) || !HexDecode(fields[4], &message_id) ||
            !HexDecode(fields[5], &content) || !HexDecode(fields[6], &state) || !HexDecode(fields[7], &error_code)) continue;
        auto session = agent_sessions_.find(session_id);
        if (session == agent_sessions_.end()) continue;
        Request request{task_id, message_id, content, state, error_code, ""};
        if (state == "running" || state == "cancelling") {
          request.state = "unknown"; session->second.recovery_required = true;
          recoveries.emplace_back(session_id, request_id);
        }
        session->second.requests[request_id] = std::move(request);
      } else if (fields[0] == "E" && fields.size() == 4) {
        std::string session_id, json;
        if (!HexDecode(fields[1], &session_id) || !HexDecode(fields[3], &json)) continue;
        auto session = agent_sessions_.find(session_id);
        if (session != agent_sessions_.end()) session->second.events.push_back(
            Event{std::strtoll(fields[2].c_str(), nullptr, 10), json});
      }
    }
    for (const auto& [id, session] : agent_sessions_) {
      auto current = agent_sessions_.find(id);
      for (const auto& event : current->second.events)
        current->second.sequence = std::max(current->second.sequence, event.sequence);
      for (const auto& [request_id, request] : session.requests) {
        if (request.state == "unknown") current->second.recovery_required = true;
        if (request.state == "running" || request.state == "cancelling" || request.state == "queued") ++queued_tasks_;
      }
    }
    for (const auto& [session_id, request_id] : recoveries) {
      auto session = agent_sessions_.find(session_id);
      if (session == agent_sessions_.end()) continue;
      auto request = session->second.requests.find(request_id);
      if (request != session->second.requests.end()) AppendRecoveryEventLocked(session_id, request_id, request->second);
    }
    PersistLocked();
  }

  void ResumeQueued() {
    std::vector<agentos::RuntimeTask> tasks;
    {
      std::lock_guard<std::mutex> guard(lock_);
      for (auto& [session_id, session] : agent_sessions_) {
        for (auto& [request_id, request] : session.requests) {
          if (request.state != "queued") continue;
          tasks.push_back(agentos::RuntimeTask{session_id, request.task_id, request_id,
                                              request.content_json, session.metadata_json, session.user_id});
        }
      }
      PersistLocked();
    }
    for (auto& task : tasks) {
      const agentos::RuntimeTask failed_task = task;
      if (worker_.Enqueue(std::move(task))) continue;
      OnRuntimeEvent(agentos::RuntimeEvent{failed_task.session_id, failed_task.task_id, failed_task.request_id, "failed", "",
                                           "runtime_unavailable", "Native runtime worker is unavailable"});
    }
  }

  void PersistLocked() {
    const std::string temporary = state_path_ + ".tmp";
    std::ofstream stream(temporary, std::ios::trunc);
    if (!stream.good()) return;
    stream << "AGENTOS_STATE_V2\n";
    for (const auto& [id, session] : agent_sessions_) {
      stream << "S\t" << HexEncode(id) << '\t' << session.user_id << '\t' << session.owner_uid << '\t'
             << HexEncode(session.frontend_id) << '\t' << HexEncode(session.metadata_json) << '\t'
             << HexEncode(session.state) << '\t' << HexEncode(session.first_query) << '\t'
             << HexEncode(session.first_answer) << '\t' << HexEncode(session.latest_answer) << '\t'
             << session.last_activity_ms << '\n';
      for (const auto& [request_id, request] : session.requests) {
        stream << "R\t" << HexEncode(id) << '\t' << HexEncode(request_id) << '\t'
               << HexEncode(request.task_id) << '\t' << HexEncode(request.message_id) << '\t'
               << HexEncode(request.content_json) << '\t' << HexEncode(request.state) << '\t'
               << HexEncode(request.error_code) << '\n';
      }
      for (const auto& event : session.events)
        stream << "E\t" << HexEncode(id) << '\t' << event.sequence << '\t' << HexEncode(event.json) << '\n';
    }
    stream.flush();
    if (stream.good()) { chmod(temporary.c_str(), 0600); rename(temporary.c_str(), state_path_.c_str()); chmod(state_path_.c_str(), 0600); }
  }

  const int64_t started_at_ms_;
  std::mutex lock_;
  std::unordered_map<std::string, PluginSession> plugin_sessions_;
  std::unordered_set<std::string> requests_;
  std::unordered_map<std::string, AgentSession> agent_sessions_;
  std::mt19937_64 random_;
  uint64_t next_task_id_ = 0;
  uint64_t next_message_id_ = 0;
  uint64_t queued_tasks_ = 0;
  std::string state_path_ = kStatePath;
  agentos::NativeRuntimeWorker worker_;
  agentos::NativeSessionSelector selector_;
};

}  // namespace

int main() {
  android::base::InitLogging(nullptr);
  ABinderProcess_setThreadPoolMaxThreadCount(4);
  auto service = ndk::SharedRefBase::make<Sideagentd>();
  auto binder = service->asBinder();
  AIBinder_forceDowngradeToSystemStability(binder.get());
  const binder_status_t status = AServiceManager_addService(binder.get(), "agentos.sideagentd");
  if (status != STATUS_OK) {
    LOG(ERROR) << "Unable to publish agentos.sideagentd: " << status;
    return 1;
  }
  LOG(INFO) << "sideagentd native runtime worker ready";
  ABinderProcess_startThreadPool();
  ABinderProcess_joinThreadPool();
  return 0;
}
