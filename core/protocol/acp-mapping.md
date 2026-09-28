# ACP 在 AgentOS 里的落地：方法范围与事件映射（acp-mapping v1）

- **状态**：v1，W4 冻结 M1 的方法范围、映射规则和“自动选会话”扩展；M2（W10）、M3a（W16）按第 2 节的计划追加。
- **依据**：[acp-profile-v1.md](acp-profile-v1.md)（对外协议的权威文档）、architecture 5.3 / 5.6、[core/contracts/events.md](../contracts/events.md)、[errors.md](../contracts/errors.md)、[binder-channel-v1.md](binder-channel-v1.md)。
- **实现**：`core/runtime/.../acp/`（`AgentSide.kt`：Agent 端与会话；`UpdateMapper.kt`：事件 → `session/update`；`ProfileExtensions.kt`：`_meta` 约定），官方 ACP Kotlin SDK 0.30.1 的 Agent 端。扩展字段的 schema：[acp-extensions.schema.json](acp-extensions.schema.json)。
- **测试**：`AcpAgentSideTest`（SDK 的 Kotlin Client，9 例）、`RuntimeStartTest`（启动与恢复期间，4 例）、`tests/acp-conformance/`（官方 TypeScript 客户端 1.4.0 经 stdio，12 例）。

## 1. 连接

| 入口 | 传输 | 调用方身份（`CallerIdentity`） |
|---|---|---|
| AgentOS App 自带界面、第三方 App | Binder：`IAcpService.open` → `BinderAcpTransport`（W5、W6） | Binder 调用方 UID；自带界面为 `SELF`，第三方为 `APP`（M4 前一律“未开放”，由 W6 的 `AcpService` 拒绝） |
| 电脑端 | `adb forward` 到抽象 socket `agentos-acp`，按行收发 JSON（W9） | 一次性配对码；`DESKTOP`，所有电脑端连接共用一个会话空间 |
| 电脑上的测试 | stdio：SDK 的 `StdioTransport`（`AcpStdioAgent`） | `DESKTOP` |

- 一条传输对应一个 SDK `Agent`：`RuntimeEngine.serveAcp(transport, caller, gate)`。它**立即返回，不挂起、不做 I/O**（W6 在 Binder 线程上、`IAcpService.open` 里同步调用），可以在 `start()` 完成之前调用；需要 Store 的请求在运行时的协程里等恢复结束（session-scheduling.md 第 7 节）。
- 身份只取自连接上下文，消息里自报的 UID、包名一概不看（Profile、architecture 原则 4）。
- **Protocol 随 Transport 一起关闭**（S3 问题 4）：挂起的请求随之结束。**连接断开不取消任务**（F7）：任务照常跑完，结果留在 Store 里。
- SDK 在一个单线程调度器上处理一条连接的所有请求和通知（S3 问题 7）。prompt 的更新从事件日志按挂起方式读取，不在处理协程里紧循环，所以同一连接上的 `session/cancel` 不会排队。
- 日志：SDK 依赖 kotlin-logging。Android 上在 SDK 第一次记日志前设置 `kotlin-logging-to-android-native=true`（`AcpAndroid.ensureInitialized()`，W5）；电脑上设置 `-Dkotlin-logging-to-jul=true`（`core/runtime` 的测试任务、`AcpStdioAgent` 都已设置），否则缺 slf4j 会在第一次记日志时崩溃。

## 2. 方法启用范围

| ACP 方法 / 能力 | 状态 | 说明 |
|---|---|---|
| `initialize` | M1 | 第 3 节 |
| `session/new` | M1 | 第 4 节；含自动选会话扩展 |
| `session/prompt` | M1 | 第 5 节；本轮结束才返回 `stopReason` |
| `session/update`（Agent → Client） | M1 | 第 6 节 |
| `session/cancel` | M1 | 第 8 节 |
| `session/load` | M2（W10） | 目前 `loadSession: false`，调用返回 -32601 |
| 持久化提交、增量恢复扩展 | M2（W10） | 名字已保留（acp-extensions.schema.json `reservedExtensions`） |
| `session/request_permission`（Agent → Client） | M3a（W16） | 只能追加拒绝，不能替用户同意（architecture 5.3）。M1 不发 |
| `session/resume`、`session/fork`、`session/list`、`session/delete` | 不启用 | 返回 -32601 |
| `session/close`、`session/set_mode`、`session/set_model`、配置项 | 不启用 | 不声明对应能力 |
| `authenticate` | 不启用 | `authMethods` 为空；电脑端的配对在传输层完成（W9） |
| 客户端的文件系统、终端能力 | 不使用 | Android 上没有对应的工作目录语义；客户端声明了也不调用 |

## 3. `initialize`

| 字段 | 值 |
|---|---|
| `protocolVersion` | 1（ACP v1；SDK 按 v1 协商，客户端请求别的版本也答 1） |
| `agentCapabilities.loadSession` | `false`（W10 起为 true） |
| `agentCapabilities.promptCapabilities` | `image: false`、`audio: false`、`embeddedContext: true`。Profile：图片等能力验证后再声明 |
| `agentCapabilities.mcpCapabilities` | `http: false`、`sse: false`：工具来自 AgentOS 的插件，客户端不能传 MCP 服务器 |
| `agentInfo` | `{ name: "agentos", title: "AgentOS", version: <App 版本> }` |
| `authMethods` | `[]` |
| `_meta."org.agentos"` | `{ profile: 1, runtime, securityLevel: "best_effort", extensions: { sessionAutoSelect: { version: 1 } } }` |

客户端在请求的 `_meta."org.agentos".extensions` 里声明要用的扩展（字符串数组）。

## 4. `session/new`

- `cwd` 只作标签（记在会话上），不作为文件系统根；可以是任意字符串。
- `mcpServers` 非空：返回 -32602，`data.agentosCode = unsupported`（Profile：明确拒绝，不静默忽略）。
- `additionalDirectories`：忽略。
- 返回的 `sessionId` 形如 `ses_<26 位 ULID>`，归属调用方（`ownerKey`）。
- **自动选会话扩展**：请求带 `_meta."org.agentos".autoSelect.query` 时，运行时在调用方自己的会话里选一个或新建（session-selection.md），返回的 `sessionId` 可能是已有会话；之后发一条 `session/update`（`session_info_update`，普通字段为空），在 `_meta."org.agentos".selection` 里告知 `{ sessionId, created, method, fallbackReason? }`。没有在 `initialize` 里声明 `sessionAutoSelect` 就带上 `autoSelect`：返回 -32602（`invalid_params`）。`query` 为空同样拒绝。

选择这种形式而不是单独的 `_agentos/…` 方法，是因为 SDK 的 Agent 只在 `session/new`（和 load / resume / fork）里把会话登记到连接上；单独的方法返回的会话 ID 无法接着发 `session/prompt`。选择结果放在随后的通知里，是因为 SDK 0.30.1 不在 `session/new` 的响应里带 `_meta`。

## 5. `session/prompt`

**接受的内容**（其余返回 -32602）：

| ContentBlock | 处理 |
|---|---|
| `text` | 原样 |
| `resource_link` | 转成一行文字 `[resource] <name> <uri>` 交给 Pi（Android 上没有工作目录，Agent 不能自己去读） |
| `resource`（文字） | 转成 `[resource <uri>]` + 正文 |
| `resource`（二进制） | `unsupported` |
| `image` | M1 `unsupported`；声明 `image: true` 之后交给 Pi 的 ImageContent |
| `audio` | `unsupported` |

- 空 prompt：`invalid_params`；文字总长超过 200,000 字符：`payload_too_large`（单条 JSON-RPC 消息的上限另由传输保证，binder-channel-v1 第 3 节）。
- ContentBlock 数组原样存在任务上（`task.queued.input`）。

**生命周期**（ACP v1 与 Profile：一轮结束才返回，不能拿入队回执当完成响应）：

1. 收到即计入 `runState.queuedTasks`；持久化提交（`task.queued`），提交前会话的最新 sequence 作为读取起点；
2. 从事件日志按 sequence 读这一轮已提交的事件（先写日志再发送，events.md 6.2），逐条映射成 `session/update`（第 6 节）；
3. 读到这一轮的终态事件时返回（第 7 节），响应的 `_meta."org.agentos".taskId` 是任务 ID。

同一会话同一连接上同时只能有一轮 prompt（SDK 限制，第二个请求返回错误）；不同连接对同一会话的 prompt 在运行时里按提交顺序排队（session-scheduling.md 第 4 节）。

## 6. 内部事件 → `session/update`

| 内部事件（events.md） | `session/update` |
|---|---|
| `message_update`（assistant，`text_delta`） | `agent_message_chunk`，`content: { type: "text", text }` |
| `message_update`（assistant，`thinking_delta`） | `agent_thought_chunk` |
| `tool_execution_start` | `tool_call`：`toolCallId`、`title`（工具名）、`kind: other`、`status: pending`、`rawInput`（参数） |
| `tool.dispatched` | `tool_call_update`：`status: in_progress`（确认已通过、开始调用工具提供方） |
| `tool_execution_update` | `tool_call_update`：`status: in_progress`，有文字时带 `content` |
| `tool_execution_end` | `tool_call_update`：`status: completed`（isError 时 `failed`），`content` 是结果文字 |
| 其他事件 | 不发送（`message_start` / `message_end`、`turn_*`、`agent_*`、`task.*`、`consent.*`、`hook.*`、`tool.settled`、`tool_round_limit`、系统流） |

- 文字增量在写日志前已按 32 ms / 8,192 字符合并（events.md 6.3），这里再按 **8,192 字符**切分（不切开代理对）。即使每个字符都要转义成 6 个字符，一行 JSON-RPC 也低于 binder-channel-v1 的单条上限 65,536 字符。
- 工具结果的文字在 `tool_call_update.content` 里最多 8,192 字符，超出截断并注明原长；完整结果（Broker 上限 32,768 字符）只交给模型。图片记为 `[image]`。
- 被拒绝、被 Hook 拦截、确认超时的工具调用同样以 `tool_call` → `tool_call_update { status: failed }` 出现，`content` 是 `[agentos:<错误码>] …`（errors.md 第 6 节）；没有派发，所以没有 `in_progress`。
- 用户消息不回显（客户端自己有）；`plan`、`available_commands_update` 等不发送。

## 7. 结束：`stopReason` 与错误

| 这一轮的终态事件 | `session/prompt` 的结果 |
|---|---|
| `task.completed { stopReason: end_turn }` | `end_turn` |
| `task.completed { stopReason: max_tokens }` | `max_tokens` |
| `tool_round_limit` 之后的 `task.completed { stopReason: max_turn_requests }` | `max_turn_requests` |
| `task.completed { stopReason: refusal }` | `refusal` |
| `task.cancelled` | `cancelled` |
| `task.failed` | JSON-RPC 错误：`code` 为错误码的 rpcCode（模型与任务错误都是 -32051），`message` 为 `<错误码>: <说明>`，`data = { agentosCode, retryable, taskId }` |
| `task.recovery_required`（结果未知：泵故障、取消宽限期超时） | JSON-RPC 错误 -32051，`agentosCode` 为 `agent_core_failed` 或 `tool_result_unknown`；会话进入等恢复决定，之后的 prompt 返回 `recovery_required`（-32049） |

请求本身被拒绝（会话不存在或不属于调用方、正在取消、safe mode、队列满）时，`session/prompt` 立即返回对应的 JSON-RPC 错误（errors.md 3.1），不产生任务。

## 8. `session/cancel`

1. 取消这个会话里所有未结束的任务：排队的立即取消，运行中的请 Agent core abort（F6、session-scheduling.md 第 6 节）；
2. **等运行中的任务停下再返回**（最多 12 秒）：这样这一轮的 `session/prompt` 先按正常路径读到 `task.cancelled`、把已提交的更新发完、以 `cancelled` 返回；随后立即发的 prompt 也不会撞上“取消中”（`invalid_state`）；
3. 12 秒内没停下，SDK 取消这一轮的处理协程，`session/prompt` 同样以 `cancelled` 返回；运行时这一侧按取消宽限期把这次执行标记为结果未知。

## 9. 背压与消息大小

- 每发一条流式 `session/update` 之前调用 `OutboundGate.awaitWritable()`。Binder 上由 W6 接到 `BinderAcpTransport.awaitWritable(16_384)`（binder-channel-v1 第 5 节：生产者高水位 16,384 字符）；stdio 和 socket 上是空操作。
- 更新的条数由增量合并压低（每秒最多约 30 条文字更新），单条大小由第 6 节的切分保证。
- SDK 的 `StdioTransport` 输出的每条消息多一个 `"type"` 类鉴别字段（S3 问题 3），官方 TypeScript 客户端 1.4.0 会忽略它（一致性测试已验证）。电脑端网关（W9）改用 `JsonRpcCodec` 编码，不直接用 `StdioTransport`。

## 10. 从 pi-acp-adapter 借鉴与不采用的做法

`reference/pi-acp-adapter/` 是 Profile 阶段的 ACP ↔ Pi 适配器（接 `pi-coding-agent`）。

| pi-acp-adapter | 本项目 |
|---|---|
| 文字 / thinking 增量直接转 `agent_message_chunk` / `agent_thought_chunk` | 沿用，但先合并、再从事件日志发出 |
| `resource_link` 转成一行文字，嵌入的文字资源展开 | 沿用 |
| 在 Pi 的事件回调里不等待地发送 `session/update` | 不采用：先写日志再发，按顺序、带背压，断线后可以从日志接回（W10） |
| 从错误消息字符串猜 `stopReason`（`max_tokens` 等） | 不采用：Pi 适配层返回结构化的 `TurnOutcome`，宿主层写 `task.*` 事件，映射只看事件 |
| `tool_call` 一开始就是 `in_progress`，按编码工具名推断 `kind` | `tool_call` 先 `pending`，确认通过、真正派发时 `in_progress`；`kind` 统一为 `other`（工具来自插件，名字没有固定语义） |
| 工具确认交给客户端的 `session/request_permission` | 不采用：确认由 AgentOS 自己的界面完成，客户端的回答只能追加拒绝（M3a） |
| 声明 `image: true` | M1 不声明，验证后再开 |
| `cwd` 必须是绝对路径 | 不要求：`cwd` 只作标签 |

## 11. 待定与已知限制

- `session/load`、持久化提交、增量恢复：W10。
- `session/request_permission`：W16。
- 图片输入：模型与真机验证后打开 `promptCapabilities.image`。
- SDK 的 Kotlin Client 会把 prompt 之前到达的会话通知并进下一轮的事件流；自带界面（W8）如果要读自动选会话的结果，应从那一轮的事件里取 `session_info_update`。
