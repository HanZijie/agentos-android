# 待办示例 App 的 R8 规则。
# TodoMcpService 由 AgentOS 通过 Binder 绑定，类名写在 plugin.json 里（按名字反射），不能被改名或移除。
-keep class org.agentos.sample.todo.agent.TodoMcpService { *; }
