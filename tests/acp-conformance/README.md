# tests/acp-conformance：ACP 一致性测试

用**官方 TypeScript ACP 客户端**（`@agentclientprotocol/sdk` 1.4.0，与 `reference/pi-acp-adapter` 一致）连接 AgentOS 运行时，验证它对外是一个标准的 ACP v1 Agent，并验证 AgentOS Profile 的扩展（`core/protocol/acp-mapping.md`、`acp-extensions.schema.json`）。

| 阶段 | 被测对象 | 传输 | 状态 |
|---|---|---|---|
| W4 | 电脑上的运行时（`org.agentos.runtime.testing.AcpStdioAgent`：完整的 `RuntimeEngine` + 真实 SQLite + ACP Agent 端），Agent 循环是 FakeAgentCore | SDK 的 `StdioTransport` | ✅ 13 例 |
| W4（B2 之后） | 同上，Agent 循环换成 B lane 的 PiAdapter（真实 Pi + 假模型端点） | 同上 | 待 B2 进 main |
| W9 | 手机上的 `:agent` | `adb forward tcp:… localabstract:agentos-acp` | 待 W6 |

## 运行

```bash
# 需要 JDK 21（JAVA_HOME）和 Node ≥ 22.19
cd tests/acp-conformance
npm ci
npm test
```

`npm test` 第一次运行时会调用 `../../gradlew :core:runtime:acpConformanceClasspath`，生成 `core/runtime/build/acp-conformance/classpath.txt`。改了 `core/runtime` 的代码之后，重新运行这个 Gradle 任务（或设置 `AGENTOS_ACP_REBUILD=1`）。也可以用 `AGENTOS_ACP_CLASSPATH` 直接给出类路径。

电脑端 Agent 进程可以单独启动，用任何 ACP 客户端以子进程方式连接：

```bash
java -Dkotlin-logging-to-jul=true -cp "$(cat core/runtime/build/acp-conformance/classpath.txt)" \
  org.agentos.runtime.testing.AcpStdioAgent [--jev=first] [--db=<目录>]
```

stdout 上只有 JSON-RPC，日志走 stderr。ACP SDK 0.30.1 在 JVM 上依赖 kotlin-logging，必须设置 `-Dkotlin-logging-to-jul=true`，否则缺 slf4j 会在第一次记日志时崩溃（`AcpStdioAgent` 没设置时会自动补上）。

## 用例

Agent 循环是 FakeAgentCore 时，prompt 里的 JSON 指令决定它的行为（`FakeScripts.directives()`），例如 `{"fake":{"chunks":30,"chunkChars":4,"text":"abcd"}}`、`{"fake":{"tools":[{"name":"add","arguments":{"a":2,"b":3}}]}}`、`{"fake":{"awaitAbort":true}}`、`{"fake":{"fail":"model_rate_limited"}}`、`{"fake":{"toolLoop":13}}`；其他文字原样回显。

- `initialize`：ACP v1、能力声明、`_meta."org.agentos"`（Profile 版本、安全等级、扩展）；
- `session/new` → `session/prompt`：`agent_message_chunk` 流式输出、合并、`end_turn`、响应里的任务 ID；
- 工具：`tool_call`（pending）→ `tool_call_update`（in_progress → completed）；write 级工具由 AgentOS 确认；目录外的工具直接 `failed`；
- `session/cancel` → `cancelled`，会话随后照常可用；
- 失败 → JSON-RPC 错误 -32051，`data.agentosCode`、`retryable`；`max_tokens`、`max_turn_requests`（工具轮次上限 12）；
- 不支持的输入明确拒绝：`mcpServers`、图片、`session/load`；`resource_link` 和嵌入的文字资源可以用；
- 线上格式：每行一条 JSON-RPC 2.0；长输出切分后单行不超过 65,536 字符；
- 自动选会话扩展：没协商时拒绝；协商后第一次新建、第二次选中已有会话，结果在 `session_info_update` 的 `_meta` 里。

已知：SDK 的 `StdioTransport` 输出的每条消息多一个 `"type"` 字段（S3 问题 3），TypeScript 客户端忽略它；W9 的电脑端网关改用 `JsonRpcCodec` 之后，“每行一条消息”这条用例收紧为不允许多余字段。
