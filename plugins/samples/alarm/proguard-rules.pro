# 闹钟示例 App 的 R8 规则。
# Manifest 里声明的 Activity / Service / Receiver 由 AGP 自动保留；kotlinx-serialization-json 只用 JsonObject 构建器，不依赖反射。
# SDK 合入后：McpBinderService 的子类同样由 Manifest 规则保留，这里不需要额外规则。
