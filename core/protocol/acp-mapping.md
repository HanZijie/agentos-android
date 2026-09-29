# ACP 在 AgentOS 里的落地：方法范围与事件映射（acp-mapping v1）

- **状态**：v1，W4 冻结 M1 的方法范围、映射规则和“自动选会话”扩展；W9 追加电脑端接入的传输与配对握手（第 10 节）；M2（W10）、M3a（W16）按第 2 节的计划追加。
- **依据**：[acp-profile-v1.md](acp-profile-v1.md)（对外协议的权威文档）、architecture 5.3 / 5.6、[core/contracts/events.md](../contracts/events.md)、[errors.md](../contracts/errors.md)、[binder-channel-v1.md](binder-channel-v1.md)。
- **实现**：`core/runtime/.../acp/`（`AgentSide.kt`：Agent 端与会话；`UpdateMapper.kt`：事件 → `session/update`；`ProfileExtensions.kt`：`_meta` 约定；`LineTransport.kt`：按行传输），官方 ACP Kotlin SDK 0.30.1 的 Agent 端；电脑端接入：`core/runtime/.../desktop/`（开关、配对、握手、连接管理）+ app 的 `DesktopGateway.kt`（抽象 socket）+ `tools/acp-bridge/`。扩展字段的 schema：[acp-extensions.schema.json](acp-extensions.schema.json)。
- **测试**：`AcpAgentSideTest`（SDK 的 Kotlin Client，10 例）、`RuntimeStartTest`（启动与恢复期间，4 例）、`LineTransportTest`（8 例）、`DesktopPairingTest`（10 例）、`DesktopGatewayCoreTest`（11 例）；`tests/acp-conformance/`（官方 TypeScript 客户端 1.4.0）：14 例分别经 stdio 和电脑上的网关 + `tools/acp-bridge` 跑（Agent 循环 FakeAgentCore 或真实 Pi），网关配对与安全 9 例；手机上（设备可选）经 `adb forward` 跑同样的 14 例（依赖工具、确认、Jev 的 4 例跳过）、设备专属 3 例、配对与安全 9 例。

## 1. 连接

| 入口 | 传输 | 调用方身份（`CallerIdentity`） |
|---|---|---|
| AgentOS App 自带界面、第三方 App | Binder：`IAcpService.open` → `BinderAcpTransport`（W5、W6） | Binder 调用方 UID；自带界面为 `SELF`，第三方为 `APP`（M4 前一律“未开放”，由 W6 的 `AcpService` 拒绝） |
| 电脑端 | `adb forward` 到抽象 socket `agentos-acp`，按行收发 JSON-RPC（`LineTransport` + `JsonRpcCodec`，W9，第 10 节） | 开关 + 对端 UID + 配对握手；`DESKTOP`，所有电脑端连接共用一个会话空间 |
| 电脑上的测试 | stdio：`LineTransport`（`AcpStdioAgent`）；网关模式（`--listen`）：同一个 `DesktopGatewayCore` 监听本机 TCP 端口 | `DESKTOP` |

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
| `authenticate` | 不启用 | `authMethods` 为空；电脑端的配对在 ACP 之前、传输层完成（第 10 节） |
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

- 每发一条流式 `session/update` 之前调用 `OutboundGate.awaitWritable()`。Binder 上由 W6 接到 `BinderAcpTransport.awaitWritable(16_384)`（binder-channel-v1 第 5 节：生产者高水位 16,384 字符）；电脑端网关接到 `LineTransport.awaitWritable(16_384)`（本地出站队列的积压）；电脑上的 stdio 测试是空操作。
- 更新的条数由增量合并压低（每秒最多约 30 条文字更新），单条大小由第 6 节的切分保证。
- 按行的传输（电脑端网关、电脑上的 stdio）单行上限同样是 65,536 字符（第 10 节）。
- 不用 SDK 的 `StdioTransport`：它按接口类型编码，每条消息多一个 `"type"` 类鉴别字段（S3 问题 3）。`LineTransport` 按具体类型编码（Android 上是 `JsonRpcCodec`），一致性测试检查每行没有多余字段。

## 10. 电脑端接入（W9）：传输与配对握手

**传输**

- `:agent` 在抽象 socket `agentos-acp` 上监听（app 的 `DesktopGateway`；平台无关的部分是 core/runtime 的 `DesktopGatewayCore`）。电脑执行 `adb forward tcp:<端口> localabstract:agentos-acp` 后连本机端口；`tools/acp-bridge` 自动完成转发、握手，然后把自己的 stdin / stdout 接上去，以子进程方式启动 Agent 的客户端直接用它。
- 一行一条 JSON-RPC 2.0 消息，UTF-8，`\n` 结尾（也接受 `\r\n`）。编码是 `JsonRpcCodec`（与 Binder 通道同一份），没有多余字段；传输类是 `LineTransport`。
- **单行上限 65,536 字符**（`String.length`，不含换行符，与 binder-channel-v1 的单条上限相同）。入站超长：连接关闭。出站超长：与 Binder 通道相同，请求在本地合成错误响应、响应改发错误响应、通知丢弃并计数；错误的 `message` 以 `acp-line:` 开头。
- 背压：出站队列积压超过 16,384 字符时，`session/update` 的生产者等待。
- 调用方：`CallerKind.DESKTOP`，ownerKey `desktop`，uid 记为对端 UID（adbd 为 2000）。所有已配对的电脑、所有电脑端连接共用一个会话空间（architecture F11）。

**准入顺序**

1. **开关**（设置页“电脑端接入”，**默认关闭**）：关闭时 socket 不存在，`adb forward` 过来的连接立即被 adbd 关掉，一个字节也不回。
2. **对端 UID**（`SO_PEERCRED`）：只接受 shell（2000，即 adbd）和 root（0）；其他 UID（本机的 App）不读任何数据就关闭。
3. **配对握手**：连上后 10 秒内，第一行（上限 4,096 字符）必须是配对请求。**握手成功之前，不解析、不处理任何 ACP 消息。**
4. 同时最多 8 条已握手的连接、4 个进行中的握手；超出时回 `busy`（-32047）并关闭。

**配对请求**：JSON-RPC 请求，按 ACP 扩展方法的命名以 `_` 开头：

```json
{"jsonrpc":"2.0","id":0,"method":"_org.agentos/pair","params":{"version":1,"code":"482913","label":"acp-bridge@my-laptop"}}
{"jsonrpc":"2.0","id":0,"method":"_org.agentos/pair","params":{"version":1,"token":"<配对时拿到的令牌，43 个字符>"}}
```

| 字段 | 说明 |
|---|---|
| `version` | 1；缺省按 1，其他值按“不是配对请求”处理 |
| `code` | 手机上显示的一次性配对码（6 位数字；其中的空格和 `-` 忽略）。与 `token` 二选一 |
| `token` | 之前用配对码配对时拿到的令牌 |
| `label` | 可选。电脑端自报的名字（去掉控制字符，最多 64 字符），只在设置页用来区分配对，不作身份 |

**成功**：

```json
{"jsonrpc":"2.0","id":0,"result":{"version":1,"pairingId":"dp_3f2a9c01b7e4","token":"<43 个字符>","maxLineChars":65536}}
```

`token` 只在用配对码配对时出现，而且只出现这一次。之后同一条连接上是普通的 ACP（从 `initialize` 开始）。

**失败**：JSON-RPC 错误（errors.md 的 `auth_required`，-32000），然后关闭连接：

```json
{"jsonrpc":"2.0","id":0,"error":{"code":-32000,"message":"auth_required: wrong pairing code, or no pairing code is active; generate one on the phone","data":{"agentosCode":"auth_required","retryable":false,"details":{"reason":"invalid_code"}}}}
```

| `details.reason` | 含义 |
|---|---|
| `pairing_required` | 第一行不是配对请求，或 `code` / `token` 都没带（或都带了）。客户端直接发 ACP 的 `initialize` 时，沿用那条请求的 `id` 回这个错误，让它立即失败，但不处理它 |
| `invalid_code` | 配对码不对、已经用过，或当前没有有效的配对码。每次错误消耗一次机会 |
| `code_expired` | 配对码已过期 |
| `too_many_attempts` | 同一个配对码第 5 次输错，作废 |
| `invalid_token` | 令牌无效：配对被撤销，或开关关闭过 |
| `disabled` | 握手期间开关被关闭 |

第一行超过 4,096 字符、10 秒内没有发来：直接关闭，不回复。

**配对码与令牌**

- 设置页打开开关后生成配对码：6 位数字，**5 分钟有效**，**一次性**（配对成功即作废），输错 5 次作废；同一时刻最多一个，重新生成会替换旧的。配对码只在 `:agent` 的内存里，进程重启即失效，也不进诊断和日志。
- 令牌：32 字节随机数，base64url 无填充（43 字符）。手机上只存它的 SHA-256（DE 存储 `files/desktop/pairing.json`，连同开关状态）。开关关闭或被撤销之前一直有效；最多保留 16 个配对，超出时挤掉最久没用的。
- **关闭开关**：停止监听，断开所有电脑端连接，清掉配对码和全部配对（电脑要重新配对）。撤销单个配对：断开它的连接。
- `tools/acp-bridge` 把令牌存在 `~/.config/agentos/acp-bridge.json`（权限 0600），按 `adb:<serial>` 区分设备；收到 `invalid_token` 时删掉，提示重新配对。

**确认**：电脑端和其他调用方一样，风险策略要求的确认由手机上的 AgentOS 完成（`HostPort.consent`，`ConsentRequest.caller.kind = DESKTOP`）。ACP 里没有替用户同意的入口：M1 不发 `session/request_permission`；M3a 起客户端的回答也只能追加拒绝（第 2 节）。

**已知限制**

- 抽象 socket 的名字不归任何 App 所有，别的 App 可以抢先占用 `agentos-acp`。这时网关打不开监听（诊断里的 `listenError`，设置页应提示），电脑连到的是那个 App：它能看到电脑发出的配对码或令牌，但拿去也没用，因为真正的网关只接受 adbd / root 的连接。
- 能用 adb 的人本来就能在手机上做很多事（例如 `input tap`）。配对码防的是“开着 USB 调试的手机被电脑上任意程序直接使用”和本机的其他 App，不防 adb 持有者本人。
- **cached-apps freezer（待解决）**：`:agent` 空闲（没有任务、没有被绑定）时是 cached 进程，约 10 秒后会被冻结。冻结期间抽象 socket 上的新连接和已建立会话的请求都得不到服务，直到别的事件解冻进程（Binder 客户端绑定服务时，`:agent` 的优先级随绑定方变化，A6 没有验证那条路径）。在 API 36 模拟器上复现（A6）：空闲 15 秒后连接，25 秒没有响应。待定方案：电脑端接入打开期间 `:agent` 以前台服务运行并显示通知（C 的 RuntimeLifecycle、D 的通知）。

## 11. 从 pi-acp-adapter 借鉴与不采用的做法

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

## 12. 待定与已知限制

- `session/load`、持久化提交、增量恢复：W10。
- `session/request_permission`：W16。
- 图片输入：模型与真机验证后打开 `promptCapabilities.image`。
- SDK 的 Kotlin Client 会把 prompt 之前到达的会话通知并进下一轮的事件流；自带界面（W8）如果要读自动选会话的结果，应从那一轮的事件里取 `session_info_update`。
