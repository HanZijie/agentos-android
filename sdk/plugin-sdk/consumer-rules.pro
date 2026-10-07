# sdk:plugin-sdk 带给依赖方（插件 App、AgentOS）的 R8 规则。
# IMcpService 的 Stub / Proxy 按 AIDL 描述符跨进程匹配，名字和方法不能被改。
-keep class org.agentos.channel.IMcpService { *; }
-keep class org.agentos.channel.IMcpService$* { *; }
