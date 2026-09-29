# tests/device/acp-channel/inapp 的 R8 规则：只在 AgentOS 的 releaseTest 包（R8 下的设备验证，不发布）里生效，
# debug 包不做 R8，release 包里没有本库。
# ControlClient 按名字反射调用 IAgentControl（本库不能依赖 :app）
-keep class org.agentos.internal.IAgentControl { *; }
-keep class org.agentos.internal.IAgentControl$** { *; }
