#include "plugin_runtime.h"

#include <android/binder_manager.h>
#include <android/binder_ibinder.h>
#include <android/log.h>
#include <aidl/com/example/agentos/IAgentManager.h>
#include <aidl/com/example/agentos/BnAgentPluginResultSink.h>
#include <aidl/com/example/agentos/AgentPluginSession.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <sstream>

namespace agentos {
namespace {
using namespace aidl::com::example::agentos;

Json::Value Error(const char* code) {
  Json::Value value(Json::objectValue);
  value["error"] = code;
  value["ok"] = false;
  return value;
}

std::shared_ptr<IAgentManager> Manager() {
  ndk::SpAIBinder binder(AServiceManager_checkService("agentos"));
  return binder.get() ? IAgentManager::fromBinder(binder) : nullptr;
}

bool Parse(const std::string& text, Json::Value* value) {
  Json::CharReaderBuilder builder;
  builder["collectComments"] = false;
  std::unique_ptr<Json::CharReader> reader(builder.newCharReader());
  std::string errors;
  return reader->parse(text.data(), text.data() + text.size(), value, &errors);
}

class ResultSink final : public BnAgentPluginResultSink {
 public:
  ResultSink(int uid, std::string request) : uid_(uid), request_(std::move(request)) {}
  ndk::ScopedAStatus onResult(const AgentPluginInvokeResult& result) override {
    return HandleResult(result);
  }
  ndk::ScopedAStatus onResultSync(const AgentPluginInvokeResult& result) override {
    return HandleResult(result);
  }

  Json::Value Wait(const std::atomic<bool>* cancelled, bool* finished) {
    std::unique_lock<std::mutex> guard(lock_);
    auto until = std::chrono::steady_clock::now() + std::chrono::seconds(15);
    while (!done_ && !(cancelled && cancelled->load()) && std::chrono::steady_clock::now() < until)
      ready_.wait_for(guard, std::chrono::milliseconds(100));
    *finished = done_;
    if (done_) return value_;
    done_ = true;
    return Error(cancelled && cancelled->load() ? "cancelled" : "operation_unknown");
  }

 private:
  ndk::ScopedAStatus HandleResult(const AgentPluginInvokeResult& result) {
    const uid_t caller = AIBinder_getCallingUid();
    __android_log_print(ANDROID_LOG_ERROR, "sideagentd",
                        "plugin result uid=%u expected=%d request_match=%d status=%s bytes=%zu",
                        caller, uid_, result.requestId == request_, result.status.c_str(),
                        result.resultJson.size());
    if (caller != static_cast<uid_t>(uid_) || result.requestId != request_)
      return ndk::ScopedAStatus::fromExceptionCode(EX_SECURITY);
    std::lock_guard<std::mutex> guard(lock_);
    if (done_) return ndk::ScopedAStatus::ok();
    if (result.resultJson.size() > 128 * 1024) value_ = Error("result_too_large");
    else if (result.status != "ok") value_ = Error(result.error.code.c_str());
    else if (!Parse(result.resultJson, &value_)) {
      __android_log_print(ANDROID_LOG_ERROR, "sideagentd", "plugin result JSON parse failed");
      value_ = Error("invalid_plugin_result");
    }
    done_ = true;
    ready_.notify_all();
    return ndk::ScopedAStatus::ok();
  }
  int uid_;
  std::string request_;
  std::mutex lock_;
  std::condition_variable ready_;
  bool done_ = false;
  Json::Value value_;
};
}  // namespace

std::vector<RuntimeTool> RuntimeTools(int user_id) {
  std::vector<RuntimeTool> result;
  const auto manager = Manager();
  std::string catalog;
  if (!manager || !manager->getRuntimePluginCatalog(user_id, &catalog).isOk() || catalog.size() > 512 * 1024)
    return result;
  Json::Value plugins;
  if (!Parse(catalog, &plugins) || !plugins.isArray()) return result;
  for (const auto& plugin : plugins) {
    if (!plugin["pluginId"].isString() || !plugin["tools"].isArray()) continue;
    for (const auto& tool : plugin["tools"]) {
      if (!tool["name"].isString() || !tool["inputSchema"].isObject() || result.size() >= 64) continue;
      result.push_back(RuntimeTool{"plugin_tool_" + std::to_string(result.size()),
          plugin["pluginId"].asString(), tool["name"].asString(), tool["inputSchema"],
          tool.get("description", tool["name"]).asString()});
    }
  }
  return result;
}

Json::Value InvokeRuntimeTool(int user_id, const RuntimeTool& tool, const Json::Value& args,
                             const std::string& operation_id, const std::atomic<bool>* cancelled) {
  auto manager = Manager();
  if (!manager) return Error("broker_unavailable");
  AgentPluginSession session;
  if (!manager->acquireRuntimePlugin(user_id, tool.plugin_id, operation_id, &session).isOk())
    return Error("plugin_acquisition_failed");
  struct Lease {
    std::shared_ptr<IAgentManager> manager;
    std::string session;
    std::string operation;
    ~Lease() { manager->releaseRuntimePlugin(session, operation); }
  } lease{manager, session.pluginSessionId, operation_id};
  if (session.userId != user_id || session.pluginId != tool.plugin_id || !session.endpoint ||
      std::find(session.grantedTools.begin(), session.grantedTools.end(), tool.capability) == session.grantedTools.end())
    return Error("capability_denied");
  if (cancelled && cancelled->load()) return Error("cancelled");
  AgentPluginInvokeRequest request;
  request.pluginSessionId = session.pluginSessionId;
  request.leaseId = operation_id;
  request.requestId = operation_id;
  request.idempotencyKey = operation_id;
  request.tool = tool.capability;
  Json::StreamWriterBuilder writer;
  writer["indentation"] = "";
  request.argsJson = Json::writeString(writer, args);
  if (request.argsJson.size() > 65536) return Error("arguments_too_large");
  request.deadlineEpochMs = std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::system_clock::now().time_since_epoch()).count() + 15000;
  std::string direct_json;
  const auto direct_json_status = session.endpoint->invokeSyncJson(request, &direct_json);
  if (direct_json_status.isOk()) {
    Json::Value envelope;
    if (!Parse(direct_json, &envelope) || !envelope.isObject()) return Error("invalid_plugin_result");
    if (envelope["status"].asString() != "ok") {
      __android_log_print(ANDROID_LOG_ERROR, "sideagentd", "plugin invokeSyncJson result error code=%s message=%s",
                          envelope["errorCode"].asCString(), envelope["errorMessage"].asCString());
      return Error(envelope["errorCode"].asCString());
    }
    Json::Value value;
    if (!Parse(envelope["resultJson"].asString(), &value)) return Error("invalid_plugin_result");
    return value;
  }
  __android_log_print(ANDROID_LOG_ERROR, "sideagentd", "plugin invokeSyncJson failed: %s",
                      direct_json_status.getDescription().c_str());
  AgentPluginInvokeResult direct;
  const auto direct_status = session.endpoint->invokeSync(request, &direct);
  if (direct_status.isOk()) {
    if (direct.status != "ok") return Error(direct.error.code.c_str());
    Json::Value value;
    if (!Parse(direct.resultJson, &value)) return Error("invalid_plugin_result");
    return value;
  }
  __android_log_print(ANDROID_LOG_ERROR, "sideagentd", "plugin invokeSync failed: %s",
                      direct_status.getDescription().c_str());
  auto sink = ndk::SharedRefBase::make<ResultSink>(session.pluginUid, operation_id);
  __android_log_print(ANDROID_LOG_ERROR, "sideagentd", "calling plugin beginInvoke request=%s tool=%s",
                      operation_id.c_str(), tool.capability.c_str());
  if (!session.endpoint->beginInvoke(request, sink).isOk()) return Error("plugin_unavailable");
  bool finished = false;
  Json::Value result = sink->Wait(cancelled, &finished);
  __android_log_print(ANDROID_LOG_ERROR, "sideagentd", "plugin wait finished=%d request=%s",
                      finished, operation_id.c_str());
  if (!finished) session.endpoint->cancelInvoke(session.pluginSessionId, operation_id);
  return result;
}
}  // namespace agentos
