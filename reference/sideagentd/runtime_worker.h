#pragma once

#include <atomic>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <queue>
#include <string>
#include <thread>

#include "https_client.h"

namespace agentos {

struct RuntimeTask {
  std::string session_id;
  std::string task_id;
  std::string request_id;
  std::string content_json;
  std::string metadata_json;
  int32_t user_id = 0;
};

struct RuntimeEvent {
  std::string session_id;
  std::string task_id;
  std::string request_id;
  std::string type;
  std::string text;
  std::string error_code;
  std::string error_message;
};

using RuntimeEventCallback = std::function<void(const RuntimeEvent&)>;

// One native worker owns the model connection and has no Binder or UI state.
// The daemon remains the source of truth for task IDs, event sequence and
// recovery; a worker only reports an attempt's result.
class NativeRuntimeWorker {
 public:
  explicit NativeRuntimeWorker(RuntimeEventCallback callback,
      std::function<std::string(const std::string&)> history = {});
  ~NativeRuntimeWorker();

  NativeRuntimeWorker(const NativeRuntimeWorker&) = delete;
  NativeRuntimeWorker& operator=(const NativeRuntimeWorker&) = delete;

  void Start();
  bool Enqueue(RuntimeTask task);
  void Cancel(const std::string& session_id, const std::string& request_id);
  void Stop();

 private:
  void Loop();
  void Run(RuntimeTask task, const std::shared_ptr<std::atomic<bool>>& cancelled);
  void Emit(RuntimeEvent event);

  RuntimeEventCallback callback_;
  std::function<std::string(const std::string&)> history_;
  HttpsClient http_;
  std::mutex lock_;
  std::condition_variable condition_;
  std::queue<RuntimeTask> queue_;
  std::shared_ptr<std::atomic<bool>> active_cancelled_;
  std::string active_key_;
  bool started_ = false;
  bool stopping_ = false;
  std::thread thread_;
};

}  // namespace agentos
