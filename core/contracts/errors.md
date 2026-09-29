# 错误码与可重试分类（errors v1）

- **状态**：v1。A1「接口先行」冻结错误码名字、可重试分类和 JSON-RPC 映射；新增错误码只追加。
- **来源**：新写。错误语义参考原型 agenriod 的 `agent-bus-v1.md` 第 5 节和 `session-scheduling-v1.md`（第 8 节对照表）。
- **代码**：`core/runtime/.../errors/ErrorCode.kt`（`ErrorCode`、`ErrorInfo`、`RpcCodes`、`ModelFailures`）。`ErrorCodeTest` 逐行比对第 3 节的表和代码，两处必须一起改。
- **使用者**：宿主层各模块；B lane 的网络出口 `net/HostFetch.kt` 和 Pi 适配层按第 4 节分类模型请求失败；ACP 层（W4）按第 5 节返回 JSON-RPC 错误。

## 1. 三个概念

- **错误码**（`code`）：稳定的小写蛇形字符串，写进事件日志、`task.failed`、JSON-RPC 错误的 `data.agentosCode`。一旦发布不改名、不改含义。
- **可重试**（`retryable`）：出错的条件是暂时的——同样的操作过一会儿再做可能成功，而且再做**不会重复外部副作用**。它描述错误本身，**不表示**宿主层会自动重试；谁在什么时候重试见第 4 节。
- **位置**（`kind`）：错误出现在哪里，决定它怎么呈现：

| kind | 含义 | 呈现 |
|---|---|---|
| `request` | 请求本身被拒绝，没有产生任务 | 立即作为 JSON-RPC 错误返回 |
| `model` | 一轮里模型调用失败 | 任务以 `task.failed` 结束；`session/prompt` 返回 JSON-RPC 错误 |
| `tool` | 一次工具调用失败 | **不让本轮失败**：作为 `isError` 的工具结果交回模型（第 6 节），写进 `tool.settled` |
| `task` | 调度、恢复、运行时本身的故障 | 任务失败，或进入恢复（`task.recovery_required`） |

## 2. 错误对象

事件里的 `error`、`ErrorInfo`：

```json
{ "code": "model_rate_limited", "message": "provider returned 429", "retryable": true, "details": { "status": 429, "retryAfterSeconds": 20 } }
```

| 字段 | 说明 |
|---|---|
| `code` | 第 3 节的错误码 |
| `message` | 给人看的简短说明，可以进诊断页；**不得**包含 key、请求头、完整 prompt、工具参数原文 |
| `retryable` | 通常等于错误码的默认值；个别情况可以更严格（例如带 `retry-after` 超过任务 deadline 时为 false） |
| `details` | 可选，只放可以公开的结构化信息：HTTP 状态码、`retryAfterSeconds`、工具名、`toolCallId` |

## 3. 错误码

表格格式由测试解析：第一列是错误码，第二列 `是` / `否`，第三列 JSON-RPC code。

### 3.1 请求错误（kind = request）

| 错误码 | 可重试 | JSON-RPC code | 含义 |
|---|---|---|---|
| `invalid_params` | 否 | -32602 | 参数不合法（缺字段、类型不对、内容为空） |
| `unsupported` | 否 | -32602 | 请求了不支持的能力，例如 `session/new` 的 `mcpServers` 非空（Profile：明确拒绝，不静默忽略） |
| `auth_required` | 否 | -32000 | 需要认证：电脑端还没有提交有效的配对码或令牌（W9；在 ACP 之前的配对握手里返回，`details.reason` 见 core/protocol/acp-mapping.md 第 10 节，随后关闭连接） |
| `not_open` | 否 | -32040 | 这类调用方还没有开放：M1–M3 期间第三方 App 的 ACP 通道（architecture 5.3） |
| `forbidden` | 否 | -32041 | 调用方无权操作（授权被撤销、访问别人的会话时一律按“不存在”处理，见下） |
| `session_not_found` | 否 | -32002 | 会话不存在，或不属于调用方（不泄露其存在） |
| `task_not_found` | 否 | -32002 | 任务不存在，或不属于调用方 |
| `invalid_state` | 否 | -32042 | 当前状态不允许这个操作（例如会话正在取消中时提交输入，session-scheduling.md） |
| `request_conflict` | 否 | -32043 | 同一个提交标识对应了不同的内容（持久化提交扩展，W10） |
| `session_terminal` | 否 | -32044 | 会话已关闭或已失败，不再接受输入 |
| `cursor_too_old` | 否 | -32045 | 增量恢复的游标早于保留范围，要重新取快照（W10） |
| `payload_too_large` | 否 | -32046 | 输入超过上限（例如单条 prompt 超过传输的单条消息上限） |
| `busy` | 是 | -32047 | 运行时暂时不能接受（排队已满、正在停止） |
| `quota_exceeded` | 是 | -32048 | 超过调用方的并发、频率或用量上限（W25） |
| `recovery_required` | 否 | -32049 | 会话里有等用户决定的恢复任务，决定之前不接受新输入 |
| `safe_mode` | 否 | -32050 | 运行时处于 safe mode，不执行新任务（F13） |

访问别人的会话返回 `session_not_found`，不返回 `forbidden`，避免泄露会话是否存在（session-scheduling-v1 第 5 节）。

### 3.2 模型错误（kind = model）

| 错误码 | 可重试 | JSON-RPC code | 含义 |
|---|---|---|---|
| `model_not_configured` | 否 | -32051 | 没有选择模型，或这个 endpoint 没有配置 key；网络出口不发请求。用户在设置里清除 key 时，正在传输的请求也被中止（网络出口 `NetErrorKind.KEY_REVOKED`，不重试），这一轮同样以此结束，`details.reason = key_revoked`，消息为 “The model key was removed in AgentOS settings; this turn was stopped.”；消息和 details 里不含 key（F9） |
| `model_auth_failed` | 否 | -32051 | 401 / 403：key 无效或没有权限 |
| `model_quota_exhausted` | 否 | -32051 | 402：账户余额或额度用完 |
| `model_bad_request` | 否 | -32051 | 其他 4xx：请求被模型服务拒绝（模型名不对、参数不对、上下文过长等） |
| `model_request_too_large` | 否 | -32051 | 413：请求体过大 |
| `model_rate_limited` | 是 | -32051 | 429、425：限流；`details.retryAfterSeconds` 来自 `retry-after` |
| `model_unavailable` | 是 | -32051 | 5xx（含 529 overloaded）：服务暂时不可用 |
| `model_network` | 是 | -32051 | 还没收到响应就失败：DNS、连接被拒、连接被重置、断网 |
| `model_timeout` | 是 | -32051 | 408，或连接、读写超时 |
| `model_stream_interrupted` | 是 | -32051 | 已经开始读响应体后连接断开 |
| `model_tls_failed` | 否 | -32051 | TLS 握手或证书校验失败（不自动重试，可能是中间人） |
| `model_protocol` | 否 | -32051 | 响应不是预期的格式（无法解析的流、意外的重定向） |

### 3.3 工具错误（kind = tool）

| 错误码 | 可重试 | JSON-RPC code | 含义 |
|---|---|---|---|
| `tool_not_in_catalog` | 否 | -32051 | 工具名不在当前目录里，Broker 拒绝派发（W2） |
| `tool_denied` | 否 | -32051 | 用户在确认界面拒绝，或确认超时（60 秒） |
| `tool_blocked` | 否 | -32051 | 被 Hook 或风险策略拦截 |
| `tool_failed` | 否 | -32051 | 工具提供方返回了错误 |
| `tool_timeout` | 否 | -32051 | 调用已发出，超时没拿到结果（副作用未知，按 `tool_result_unknown` 处理恢复） |
| `tool_unavailable` | 是 | -32051 | 请求确定没有到达提供方：未连接、bind 失败、提供方正在重启 |
| `tool_result_unknown` | 否 | -32051 | 请求已发出，但提供方进程死亡或运行时重启，副作用未知 |
| `tool_result_too_large` | 否 | -32051 | 结果超过上限，已截断或改为 `resource_link` |

工具错误的 JSON-RPC code 只在极少数情况下用到（工具错误通常不会让请求失败），统一写 -32051。

### 3.4 任务错误（kind = task）

| 错误码 | 可重试 | JSON-RPC code | 含义 |
|---|---|---|---|
| `queue_timeout` | 是 | -32051 | 排队超过期限，任务没有开始 |
| `execution_timeout` | 否 | -32051 | 执行超过任务 deadline，已取消 |
| `agent_core_failed` | 否 | -32051 | Agent core 的泵故障（S8），这一轮在 core 里的结局未知，任务进入恢复 |
| `abandoned` | 否 | -32051 | 需要恢复的任务被用户放弃（W10） |
| `recovery_expired` | 否 | -32051 | 需要恢复的任务没有人决定，运行时启动时按放弃结束（architecture F8 过渡期限）：满 24 小时（`details.rule = age`），或需要恢复的任务超过 50 条时最旧的那些（`details.rule = limit`）；期限和上限见 `SchedulerConfig.recoveryExpiryMillis` / `maxRecoveryPending` |
| `store_failed` | 否 | -32603 | Store 读写失败（磁盘满、数据库损坏） |
| `internal` | 否 | -32603 | 其他内部错误（程序缺陷） |

## 4. 谁在什么时候重试

`retryable` 只说明“可以重试”。实际的重试责任划分如下，任何一层都不能越过它：

| 对象 | 规则 |
|---|---|
| 官方 SDK 和 `pi-ai` | **永不自己重试**：调用时传 `maxRetries: 0`（S8 已验证 500、429 各只发 1 个请求） |
| 网络出口（HostFetch） | 只在**还没把响应头交给 JS 之前**重试同一个请求：`model_network`、`model_timeout`（连接阶段）、`model_rate_limited`、`model_unavailable`；按指数退避（1 s 起，最长 30 s），`retry-after` 优先，总时长不超过任务 deadline。响应头交给 JS 之后出错（`model_stream_interrupted` 等）不重试，这一轮以 `task.failed` 结束。M1 的 HostFetch 可以先不重试（W3），退避重试在 W12 加上 |
| 工具调用 | **永不自动重试**。`tool_unavailable`（确定没发出）作为错误结果交回模型，由模型决定是否再调；`tool_result_unknown`、`tool_timeout` 在恢复时标记为结果未知，不重放 |
| 任务 | 不自动重放。运行时重启、Agent core 故障时，已开始的任务进入 `task.recovery_required`，由用户选择重试或放弃（W10）；safe mode 下不自动继续。没人决定的，运行时启动时满 24 小时、或超过 50 条时从最旧的开始按放弃结束（`recovery_expired`，F8 过渡期限），同样不重放 |
| ACP 客户端 | 收到 `retryable: true` 的错误，可以稍后重发同样的 prompt；`false` 的错误重发没有意义，应提示用户（例如去设置页检查 key） |

## 5. JSON-RPC 映射

返回给 ACP 客户端的错误用 JSON-RPC 标准结构，`data` 里带 AgentOS 的错误码：

```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "error": {
    "code": -32051,
    "message": "model_rate_limited: provider returned 429",
    "data": { "agentosCode": "model_rate_limited", "retryable": true, "sessionId": "ses_01J9Z6Q7F3", "taskId": "tsk_01J9Z6Q9K2", "details": { "status": 429 } }
  }
}
```

- `code`：第 3 节表中的 JSON-RPC code。标准码（-32602、-32603）和 ACP 定义的码（-32000 需要认证、-32002 资源不存在、-32800 请求被取消）沿用 ACP Kotlin SDK 0.30.1 的 `JsonRpcErrorCode`；AgentOS 自己的码占用 **-32040 至 -32059**。
- `message`：`<错误码>: <说明>`。
- `data`：`agentosCode`、`retryable` 必有；`sessionId`、`taskId`、`details` 可选（`RpcErrorData`）。
- **取消不是错误**：`session/cancel` 之后这一轮正常返回 `stopReason: cancelled`。工具轮次超限返回 `stopReason: max_turn_requests`，也不是错误。
- 一轮 `session/prompt` 因模型或任务错误失败时返回 -32051（`task_failed`），客户端看 `data.agentosCode` 区分原因。
- SDK 自己产生的协议错误（-32700 解析错误、-32600 无效请求、-32601 方法不存在）保持原样。

## 6. 工具错误如何交回模型

工具错误不让本轮失败（architecture F5：拒绝后作为 `is_error` 的 `tool_result` 交回模型）。交回模型的文字格式：

```text
[agentos:tool_denied] The user declined this tool call.
```

- 方括号里是错误码，后面是给模型看的一句英文说明，不带内部细节（进程名、UID、堆栈）。
- 同一个错误写进 `tool.settled` 事件的 `error`（events.md 4.3）。

## 7. 与原型的对照

| agent-bus-v1 / session-scheduling-v1 | 本项目 |
|---|---|
| `-32001 unauthenticated` | `auth_required`（-32000，沿用 ACP 的码） |
| `-32002 forbidden` | `forbidden`（-32041；-32002 在 ACP 里是“资源不存在”） |
| `-32003 session_not_found` | `session_not_found`（-32002） |
| `-32004 invalid_state` | `invalid_state`（-32042） |
| `-32005 cursor_too_old` | `cursor_too_old`（-32045） |
| `-32006 payload_too_large` | `payload_too_large`（-32046） |
| `-32007 plugin_unavailable` | `tool_unavailable`（工具错误，不让请求失败） |
| `-32008 request_conflict` | `request_conflict`（-32043） |
| `-32009 task_not_found` | `task_not_found`（-32002） |
| `-32010 session_terminal` | `session_terminal`（-32044） |
| `-32011 scheduler_backpressure` | `busy`（-32047） |
| `-32012 recovery_required` | `recovery_required`（-32049） |
| `queue_timeout`、`execution_timeout` | 保留 |
| `lease_revoked` | 不迁移（没有 capability lease） |
