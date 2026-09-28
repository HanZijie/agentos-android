#pragma once

#include <atomic>
#include <string>
#include <vector>
#include <json/json.h>

namespace agentos {

struct RuntimeTool {
  std::string name;
  std::string plugin_id;
  std::string capability;
  Json::Value schema;
  std::string description;
};

// The system broker owns discovery and binding; this worker consumes handles.
std::vector<RuntimeTool> RuntimeTools(int user_id);
Json::Value InvokeRuntimeTool(int user_id, const RuntimeTool& tool,
                             const Json::Value& args, const std::string& operation_id,
                             const std::atomic<bool>* cancelled);

}  // namespace agentos
