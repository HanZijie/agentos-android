# ACP 在 AgentOS 里的落地：方法范围与事件映射（acp-mapping v1）

- **状态**：v1，W4 冻结 M1 的方法范围、映射规则和“自动选会话”扩展；W9 追加电脑端接入的传输与配对握手（第 10 节）；**会话生命周期、会话级模式与模型、调用方自带的 MCP 服务器、会话建立收尾标记（第 4a–4d 节）已实现**；持久化提交与增量恢复（W10）、`session/request_permission`（W16）按第 2 节的计划追加。
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
| `session/load` | 已启用 | 第 4a 节：重放历史后返回；`loadSession: true` |
| `session/resume` | 已启用 | 第 4a 节：不重放历史 |
| `session/fork` | 已启用 | 第 4a 节：带着已结束的几轮，范围只能收窄 |
| `session/list` | 已启用 | 第 4a 节：只列调用方自己的会话（AgentOS 自己的界面看全部） |
| `session/delete` | 已启用 | 第 4a 节：先停下进行中的任务，再删会话、任务、事件、Pi messages |
| `session/close` | 已启用 | 第 4a 节：释放内存，保留会话和历史 |
| `session/set_mode`、配置项 `mode` | 已启用 | 第 4b 节：只能在 toolScope 之上再收一层 |
| `session/set_model`、配置项 `model` | 已启用（有可选模型时） | 第 4b 节：只能选同一个 key 下的模型 |
| `mcpServers`（`session/new` 等） | 已启用，只收 `http`（Streamable HTTP） | 第 4c 节；`stdio`、`sse` 返回 `unsupported` |
| 持久化提交、增量恢复扩展 | M2（W10） | 名字已保留（acp-extensions.schema.json `reservedExtensions`） |
| `session/request_permission`（Agent → Client） | M3a（W16） | 只能追加拒绝，不能替用户同意（architecture 5.3）。M1 不发 |
| `authenticate` | 不启用 | `authMethods` 为空；电脑端的配对在 ACP 之前、传输层完成（第 10 节） |
| 客户端的文件系统、终端能力 | 不使用 | Android 上没有对应的工作目录语义；客户端声明了也不调用 |

## 3. `initialize`

| 字段 | 值 |
|---|---|
| `protocolVersion` | 1（ACP v1；SDK 按 v1 协商，客户端请求别的版本也答 1） |
| `agentCapabilities.loadSession` | `true`（第 4a 节） |
| `agentCapabilities.promptCapabilities` | `image: false`、`audio: false`、`embeddedContext: true`。Profile：图片等能力验证后再声明 |
| `agentCapabilities.mcpCapabilities` | `http: true`（构建接了会话级工具时）、`sse: false`：第 4c 节 |
| `agentCapabilities.sessionCapabilities` | `fork`、`list`、`resume`、`delete`、`close` 都声明（第 4a 节） |
| `agentInfo` | `{ name: "agentos", title: "AgentOS", version: <App 版本> }` |
| `authMethods` | `[]` |
| `_meta."org.agentos"` | `{ profile: 1, runtime, securityLevel: "best_effort", extensions: { sessionAutoSelect: { version: 1 }, toolScope: { version: 1 }, sessionSetup: { version: 1 } } }` |

客户端在请求的 `_meta."org.agentos".extensions` 里声明要用的扩展（字符串数组）。`toolScope` 只在这里**声明**让客户端探测，不要求协商（见第 4 节）。

## 4. `session/new`

- `cwd` 只作标签（记在会话上），不作为文件系统根；可以是任意字符串。
- `mcpServers`：第 4c 节。`stdio`、`sse` 返回 -32602（`data.agentosCode = unsupported`，Profile：明确拒绝，不静默忽略）；`http` 挂到这个会话上。
- `additionalDirectories`：忽略。
- 返回的 `sessionId` 形如 `ses_<26 位 ULID>`，归属调用方（`ownerKey`）。
- **自动选会话扩展**：请求带 `_meta."org.agentos".autoSelect.query` 时，运行时在调用方自己的会话里选一个或新建（session-selection.md），返回的 `sessionId` 可能是已有会话；之后发一条 `session/update`（`session_info_update`，普通字段为空），在 `_meta."org.agentos".selection` 里告知 `{ sessionId, created, method, fallbackReason? }`。没有在 `initialize` 里声明 `sessionAutoSelect` 就带上 `autoSelect`：返回 -32602（`invalid_params`）。`query` 为空同样拒绝。

- **toolScope 扩展**（docs/third-party-acp.md 4.5）：请求带 `_meta."org.agentos".toolScope = [{"plugin": "alarm", "tool": "alarm_create"}, …]`，指定**这个会话**能用哪几个工具。`plugin` 是 `plugin.json` 的 `name`，`tool` 是插件自己报告的原始工具名（不是最终的 `mcp__…` 名字）。
  - **只能缩小**：实际可用 = toolScope ∩ 当前目录（用户策略禁用、插件未启用的照样没有）。写了不存在的项**静默忽略**，不报错（调用方不能借此探测用户装了什么）。
  - **toolScope 对所有调用方都是可选的**（用户 2026-10-08 的决定）：没带 = 目录里全部已启用插件的全部工具，AgentOS 自己、电脑端、第三方 App 都一样；带了就缩小到它（只能缩小）；空数组 = 没有任何工具（调用方自己要求的）。受限的 scope 里没有 `read_skill`，系统提示里也没有 Skill 目录；没带时两者都有。
  - 这条规则由 `CallerPolicy`（`core/runtime`，`RuntimeConfig.callerPolicy`）决定：默认 `OpenCallerPolicy` 就是上面这样。可选的 `StrictCallerPolicy`（**默认关**）让第三方 App 没带 toolScope（或为空）= 没有任何工具。策略只能加严，Broker 强制范围取交集。
  - 范围外的工具：不在交给模型的工具列表里，模型按名字硬调也在 `authorize` / `execute` 被拒绝，错误码 `tool_not_in_catalog`，文字与工具真不存在时一字不差。
  - **随会话持久化**（`sessions.tool_scope`，store schema v2），重启后不丢；会话创建后**没有任何办法改它**。`session/load` 现在没有启用（`loadSession: false`），将来启用时也只能恢复，不能改；自动选会话只会选到 toolScope 与这次请求相同的会话（session-selection.md 第 1 节）。
  - 形状非法 → -32602（`invalid_params`），不创建会话：不是数组、元素不是对象、缺 `plugin` / `tool` 或不是非空字符串、超过 **32** 项（按客户端发来的数，重复的也算）、字符串超过 **128** 字符。元素里多出来的键忽略；重复的项合并。
  - 不要求在 `initialize` 里协商：它只会让会话能用的工具**更少**，不改变任何标准方法的含义。（与 `sessionAutoSelect` 不同，那个会改变 `session/new` 返回的会话。）

选择这种形式而不是单独的 `_agentos/…` 方法，是因为 SDK 的 Agent 只在 `session/new`（和 load / resume / fork）里把会话登记到连接上；单独的方法返回的会话 ID 无法接着发 `session/prompt`。选择结果放在随后的通知里，是因为 SDK 0.30.1 不在 `session/new` 的响应里带 `_meta`。

## 4a. 会话生命周期：`list`、`load`、`resume`、`fork`、`delete`、`close`

**归属**（先于一切）：调用方只能碰自己创建的会话（`ownerKey` = Binder 调用方 UID；电脑端共用一个会话空间；AgentOS 自己看全部）。别人的、已删除的、从没有过的会话，对 `load`、`resume`、`fork`、`delete`、`set_*` 的回答**完全一样**：`session_not_found`（消息 `session_not_found: no such session`），看不出是哪一种；`list` 只列自己的。

| 方法 | 行为 |
|---|---|
| `session/list` | 调用方自己的会话，最近活动在前，最多 200 个；`cwd` 过滤。每项：`sessionId`、`cwd`、`title`（第一条 prompt 的第一行，最多 80 字符，还没有为空）、`updatedAt`（ISO 8601）。 |
| `session/load` | 取回会话。**先**按事件日志把历史重放成 `session/update`（`user_message_chunk`、`agent_message_chunk`、`agent_thought_chunk`、`tool_call`、`tool_call_update`，与实时的一轮用同一个映射），重放完才返回响应。会话很长时只重放最近 `AcpConfig.maxReplayTurns`（200）轮；没结束就停下的一轮里还停在“进行中”的工具调用补一条 `failed`。每条之前过出站背压（`OutboundGate`）。请求里的 `_meta.toolScope` **忽略**：范围属于会话，创建时定下，加载不能改。`mcpServers` 是这次连接要用的那批，**替换**会话原来挂的。 |
| `session/resume` | 同 `load`，但不重放。 |
| `session/fork` | 新会话属于调用方，带着原会话**已结束的**任务的事件（`consent.*`、`hook.*` 不拷：那是当时的一次授权，不属于对话内容）和最近一次稳定的 Pi messages，模型、模式、选择元数据沿用；进行中的一轮不带过去。请求的 `toolScope` 只能在原会话的范围之上**收窄**（取交集；空交集 = 没有工具，不是不限制）。**不继承** MCP 服务器（它们的 URL 和头是原会话的），要用在请求的 `mcpServers` 里重新给。审计：`session.created` 带 `forkedFrom`。 |
| `session/delete` | 先取消没结束的任务并等它们停下（最多 `cancelWaitMillis`，没停下返回 `busy`，会话原样保留），再释放 Pi 会话、会话级工具，删会话、任务、工具调用、Pi messages 和**事件日志**（`events` 没有外键，自己删）。系统流不能删。 |
| `session/close` | 取消没结束的任务并等它们停下，释放内存（Pi 会话、会话级工具的连接、“本会话内不再询问”的记忆）。**会话和历史保留**，以后可以 `load` 或 `resume` 回来。 |

`load` 和 `resume` 还会在响应之前发 `session_info_update`，`_meta."org.agentos"` 里有 `mcpServers`（各服务器的连接状态）和 `activeTask`（会话里还有一轮没结束 `{taskId, state}`），见第 4d 节。客户端在 `activeTask` 结束前再发 prompt 会排在它后面（第 5 节）；想重来就 `session/cancel`。

**与 ACP SDK 0.30.1 客户端的一个已知行为**：同一条连接上对**同一个会话 ID** 再 `load` 或 `resume`，SDK 的客户端把通知和流式输出继续发给第一次注册的回调和会话对象，新返回的会话对象 prompt 时收不到文字（JVM 复现：第二次 load 之后 `text=0`）。这是锁定版本的行为，不是服务端问题；`sdk:acp-android` 在每条连接上按会话 ID 只保留一份并复用（设备用例 `sessions` 的 `repeatLoadStreams` 覆盖）。直接用官方 SDK 的第三方要自己注意。

## 4b. 会话级模式与模型：`set_mode`、`set_model`、配置项

存在会话上（schema v4：`sessions.mode`、`sessions.model_id`），**下一个任务起生效**，进行中的任务不受影响；随会话保存，`load`、`resume`、`fork` 带回来。`session/new`、`load`、`resume`、`fork` 的响应带 `modes`、`models`、`configOptions`。

**模式只能收窄**：在会话创建时定下的 toolScope（和调用方策略）之上再收一层，任何模式都不会放宽。

| 模式 | 交给模型的工具 |
|---|---|
| `default` | toolScope 范围内的全部（写级每次确认，高风险每次确认，用户策略照常生效） |
| `read_only` | 只有读级工具；写级、高风险、调用方自带的 MCP 工具（写级起步）都不交给模型 |
| `chat` | 一个都没有，系统提示里也没有 Skill 目录 |

模式隐藏的工具，模型按名字硬调也会在 `authorize` / `execute` 被拒（`tool_not_in_catalog`，文字与工具真不存在时一字不差，也不会弹确认）。存储里读不懂的模式值按 `chat`（最窄）处理，不会放宽。未知的模式、空串 → -32602 `invalid_params`，不改任何东西。

**模型只能在“同一个 key 下的模型”里选**（`ModelConfigPort.choices`）：用户选的是厂商预设时，该厂商目录里 baseUrl 也被同一把 key 覆盖的模型；自定义端点没有可选的（不声明 `models`，`set_model` 返回 `unsupported`）。选不在列表里的 id → -32602，消息不回显传进来的值；不能指定别的端点，也不能带自己的 key。会话选的模型之后不再可选（用户换了厂商）时，回落到用户在设置里选的模型；没有可用的 key 时任务照常以 `model_not_configured` 失败，会话级模型绕不过它。思考档位：所选模型支持推理时沿用用户的设置，否则关闭。

配置项：`mode`（`category: mode`）、`model`（`category: model`，有可选模型时）；值必须是字符串，别的类型、未知的配置项 → -32602。

## 4c. 调用方自带的 MCP 服务器：`mcpServers`

`session/new`、`load`、`resume`、`fork` 的 `mcpServers` 里，调用方可以带自己的 MCP 服务器，**只对这个会话可见**。只收 `http`（Streamable HTTP，MCP 2025-06-18）：`stdio` 要在手机上起任意进程，`sse` 是 MCP 已经弃用的传输，都返回 `unsupported`，错误消息只说第几项和类型，不回显名字、URL、头。构建没接会话级工具时 `mcpCapabilities.http = false`，非空的 `mcpServers` 一律 `unsupported`。

**信任边界**（与插件工具不同，因为来源是调用方、不是用户）：

- 用户没在插件页里审阅过它，所以**不进用户策略**（`ApprovalPolicy`），**永远没有“始终允许”**（那张表的键是插件/服务器/工具，调用方可以随便取名撞上用户给别的工具设的键）；`alwaysAllowOffered = false`。
- 风险**至少写级**（每次确认）；服务端注解只能调高（`destructiveHint` → 高风险），`readOnlyHint` 不降级。所以 `read_only` 和 `chat` 模式下看不到它们。
- 工具名 `ses__<服务器>__<工具>`（≤ 64 字符，超长截断加哈希，会话内唯一），**永远不以 `mcp__` 开头**，不会和插件工具或 `read_skill` 重名；`CatalogTool.source = null`，`provider = session:<服务器>`。toolScope 只缩小插件工具，不涉及它们。
- **URL 和头只在 `:agent` 进程的内存里**：不进 Store、事件、日志、确认框、`toString`、`SessionMcpResult`。进程重启后要调用方在 `load` 或 `resume` 里重新带上来。
- 会话 `close`、`delete` 时断开并丢弃；`load`、`resume`、`fork` 的 `mcpServers` 是替换，不是追加。

**校验**（整批，失败 → -32602 `invalid_params`，不创建会话，消息写哪条规则、不回显值；数量或总量超限 → `quota_exceeded`，`details.reason = mcp_servers`）：最多 4 个/会话、每个 App 8 个、总共 64 个；名字 `[A-Za-z0-9_.-]{1,48}`；URL 必须 `https`，≤ 2048 字符，无 userinfo，不指向本机、内网、链路本地（含 169.254.169.254）、CGNAT、多播、IPv6 ULA（IPv4 映射按内嵌的 IPv4 判断），不是 `localhost`、`*.local`、`*.internal`、`*.lan`、`*.home.arpa`、无点主机名，数字写法的 IP 只收规范点分十进制；头最多 16 个，禁用 `Host`、`Content-Type`、`Mcp-Session-Id`、`Origin` 等，值不含控制字符；DNS 解析结果只要有一个非公网地址就整个拒绝（防 DNS rebinding）；**不跟随重定向**；响应体有大小上限。

**连不上不让会话失败**：这个服务器的结局是 `connected: false` + 短代码 `reason`（`connect_failed`、`timeout`、`list_failed`、`too_many_tools`…，不含 URL 和头），经第 4d 节的通知告诉调用方；它的工具这次没有，每个任务开始前 AgentOS 再试着连一次（失败后 30 秒内不重试）。

## 4d. 会话建立的通知：`sessionSetup`

`session/new`、`load`、`resume`、`fork` 在响应**之前**可能发 `session/update` 和 `session_info_update`。官方 SDK 的客户端对通知和响应是并发处理的，**响应回来不等于通知都到了**（设备上实测出过：偶尔拿不到 MCP 服务器状态）。所以有一个**要协商**的扩展：

- 客户端在 `initialize` 的 `_meta."org.agentos".extensions` 里带 `"sessionSetup"`；AgentOS 在响应里声明 `extensions.sessionSetup`（旧版本没有，客户端就不等）。
- 协商了的客户端，每次会话建立的**最后一条通知**、响应之前，是一条 `session_info_update`，`_meta."org.agentos"` 里有 `setup: { replayed: N }`（前面一共重放了 N 条 `session/update`，不含 `session_info_update`；新建、分叉、`resume` 是 0），和这次的 `mcpServers`（`[{name, connected, toolCount, reason?}]`）、`activeTask`（`{taskId, state}`）、`selection`（自动选会话的结果）放在同一个对象里。
- 客户端等到这条标记**和** N 条 `session/update` 都到齐，才算历史和状态收齐；等不到（SDK 默认 5 秒）就用手上有的。
- 没协商的客户端不会收到 `setup`；`mcpServers`、`activeTask`、`selection` 仍然在有内容时发（与以前一样）。标准 ACP 客户端看到的只是普通字段为空的 `session_info_update`。

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

**第三方 App 的配额**（docs/third-party-acp.md 4.6，session-scheduling.md 第 5.1 节）只对 `CallerKind.APP`：一次 prompt 的文字超过 16,000 字符 → -32602 `invalid_params`（`data.details.reason = too_large`，先于上面 200,000 字符的 `payload_too_large` 判断）；已有一个 prompt 在进行 → -32048 `quota_exceeded`（`details.reason = busy`）；一小时内超过 30 次 → -32048 `quota_exceeded`（`details.reason = hourly`，`details.retryAfterSeconds`）。数值在 `CallerQuotaConfig`。AgentOS 自己、电脑端不受影响。

**确认语义**（docs/third-party-acp.md 4.4）：确认由 AgentOS 在手机上完成，客户端看到的只是 `tool_call` / `tool_call_update` 因用户拒绝而 `failed`。**默认（`OpenCallerPolicy`）所有调用方用同一套规则**：读级直接执行；写级默认每次确认，用户为这个工具设了“始终允许”、或本会话里选过“不再询问”就不再确认；高风险每次确认，只有“允许一次 / 拒绝”；确认框上写明“由「X」发起”。可选的 `StrictCallerPolicy`（**默认关**，`RuntimeConfig.callerPolicy` 选择）对第三方 App 更严：每次调用都确认（读级也确认），“始终允许”和“本会话内不再询问”都不生效，确认框只给“允许一次”和“拒绝”。AgentOS 自己和电脑端在两种策略下都不受影响。

## 8. `session/cancel`

1. 只针对**本连接上进行中的那一轮**（ACP）：本连接上没有进行中的 prompt 时什么都不做（也不挂起）。有的话，取消这个会话里所有未结束的任务：排队的立即取消，运行中的请 Agent core abort（F6、session-scheduling.md 第 6 节）；
2. **等运行中的任务停下再返回**（最多 12 秒）：随后立即发的 prompt 不会撞上“取消中”（`invalid_state`）；
3. 被取消的这一轮照常读到 `task.cancelled`、把已提交的更新发完，但 `cancelled` 响应**压到 SDK 完成取消之后才交给客户端**（A8）。原因：SDK 0.30.1 在 `AgentSession.cancel()` 返回之后才取消它眼里的“当前 prompt”（`_activePrompt.getAndSet(null)?.promptJob.cancel()`）；如果这一轮提前返回、客户端马上发了下一轮，被取消的就是下一轮（“点停止后马上发的消息也被取消”）。所以客户端收到 `cancelled` 时，会话已经可以接受下一轮；响应仍带 `_meta."org.agentos".taskId`；
4. 12 秒内没停下，SDK 取消这一轮的处理协程，`session/prompt` 同样以 `cancelled` 返回；运行时这一侧按取消宽限期把这次执行标记为结果未知；
5. cancel 在这一轮的任务提交完成之前到达时，提交一完成就把它取消，不留下没人等的任务。

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
- **cached-apps freezer**：`:agent` 空闲（没有任务、没有被绑定）时是 cached 进程，约 10 秒后会被冻结，冻结期间抽象 socket 上的连接和请求都得不到服务（A6 在 API 36 模拟器上复现）。所以**电脑端接入打开期间 `:agent` 以前台服务运行并显示常驻通知**（C6，architecture F11 第 4 点），关闭开关即退出前台（没有任务时）。设备用例“空闲 15 秒后新连接能配对、已建立会话照常应答”覆盖这一点。

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

- 持久化提交、增量恢复（`clientRequestId` 幂等提交、事件游标补发）：W10。`session/load` 已实现（第 4a 节），它重放的是已提交的历史，不是按游标补发。
- `session/request_permission`：W16。
- 图片输入：模型与真机验证后打开 `promptCapabilities.image`。
- `session/load`、`resume` 忽略请求里的 `toolScope`，用会话创建时存下的那个（4.5：scope 属于会话，加载不能改）；有测试覆盖。
- 会话级 MCP 服务器只实现了 POST 响应流：没有服务端推送的 GET 流、没有 SSE 断线续传，也不回复服务端发来的请求（ping、sampling）。
- 重放历史是整段发的，不分页；会话很长时只发最近 200 轮。
- 图片输入仍然关闭，`promptCapabilities.image = false`。
- 第三方 App 的配额计数在内存里，:agent 进程重启后清零（见 session-scheduling.md 第 5.1 节）。
- SDK 的 Kotlin Client 会把 prompt 之前到达的会话通知并进下一轮的事件流；自带界面（W8）如果要读自动选会话的结果，应从那一轮的事件里取 `session_info_update`。
