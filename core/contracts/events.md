# 内部事件：信封与 sequence 语义（events v1）

- **状态**：v1。A1「接口先行」冻结信封、事件名和 sequence 规则；payload 字段在对应工作包落地时只做**追加**。
- **来源**：原型 agenriod 的 `system/agent/contracts/output-stream-v1.md`（只保留事件信封与 sequence 语义，管道与 Binder 细节不迁移）和 `session-scheduling-v1.md` 第 7、9 节。
- **代码**：`core/runtime/.../events/`（`EventEnvelope`、`EventTypes`、`AgentEvent`）；事件日志的实现是 `store/EventLog`（W2）。
- **相关**：错误码见 [errors.md](errors.md)；内部事件到 ACP `session/update` 的映射见 `core/protocol/acp-mapping.md`（W4）。

内部事件是 `:agent` 运行时里“发生过什么”的唯一记录：Pi Agent core 的生命周期事件，加上宿主层自己的任务、确认、Hook、监督事件，写进同一个事件日志。事件日志是事实（architecture 原则 3），ACP 更新、诊断页、增量恢复（W10）都从它读。

## 1. 基本规则

1. **先写日志，再通知**。一个事件在事件日志里提交之后，才交给 ACP 映射等订阅者。
2. **sequence 按会话独立**：每个会话从 1 开始，每条 +1，不留空洞，永不复用，64 位。
3. **只追加**：已写入的事件不修改、不删除（会话被用户删除时整段删除，见第 6 节）。
4. **事件名沿用 Pi**：Pi 的生命周期事件用 Pi 的名字和字段（第 3 节）；宿主层自己的事件用 `分类.动作`（第 4 节）。
5. **不含秘密**：payload 和 error 里不能出现 key、请求头、凭据（第 5 节）。

## 2. 信封

```json
{
  "v": 1,
  "sessionId": "ses_01J9Z6Q7F3",
  "taskId": "tsk_01J9Z6Q9K2",
  "sequence": 42,
  "eventType": "message_update",
  "timestamp": 1790620000123,
  "payload": { "role": "assistant", "update": { "type": "text_delta", "contentIndex": 0, "delta": "你好" } }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `v` | int | 信封版本，当前 1。只做追加式变更；不兼容的变更才升版本 |
| `sessionId` | string | 所属会话。运行时级别的事件写在系统流 `_system`（第 6 节） |
| `taskId` | string \| 省略 | 所属任务（一次 `session/prompt` 就是一个任务）。会话级事件（`session.*`）和系统流事件没有 |
| `sequence` | int64 | 见第 6 节 |
| `eventType` | string | 事件名，第 3、4 节 |
| `timestamp` | int64 | 写入时 `HostPort.clock` 的墙钟毫秒。只用于展示和统计；先后顺序**只看 sequence** |
| `payload` | object | 事件内容，按事件名解释；没有内容时是 `{}` |
| `error` | object \| 省略 | 只出现在 `task.failed`、`tool.settled`（失败时）、`hook.failed`、`agent_core.failed` 上，形状见 errors.md 第 2 节 |

```json
{
  "v": 1,
  "sessionId": "ses_01J9Z6Q7F3",
  "taskId": "tsk_01J9Z6Q9K2",
  "sequence": 57,
  "eventType": "task.failed",
  "timestamp": 1790620003456,
  "payload": { "attempt": 1, "attemptState": "failed" },
  "error": { "code": "model_rate_limited", "message": "provider returned 429", "retryable": true, "details": { "status": 429, "retryAfterSeconds": 20 } }
}
```

接收方忽略不认识的字段和事件名。

## 3. Pi 生命周期事件

由 Pi 适配层（`core/runtime/pi/`，W3）经 `AgentCore` 的 `TurnHost.onEvent` 交给宿主层，宿主层补上 `sessionId`、`taskId`、`sequence`、`timestamp` 写入日志。事件名和字段就是 `@earendil-works/pi-agent-core` 0.86.1 的 `AgentEvent`，采用 S8 在 JS 侧压缩后的形状（docs/spikes/S8.md）：`message_update` 只带增量，不带整条 partial message。payload 是事件 JSON 去掉 `type`。

| eventType | payload | 说明 |
|---|---|---|
| `agent_start` | `{}` | 一次 prompt 的 Agent 循环开始 |
| `turn_start` | `{}` | 一次模型往返开始 |
| `message_start` | `{ role }` | `role`：`user` / `assistant` / `toolResult` / `system` |
| `message_update` | `{ role, update: { type, contentIndex?, delta?, toolCall?, reason? } }` | `update.type` 见下表 |
| `message_end` | `{ message }` | `message` 是完整的 pi-ai `Message`（按第 5 节截断，图片数据省略） |
| `tool_execution_start` | `{ toolCallId, toolName, args }` | 工具调用开始（在 beforeToolCall 之前） |
| `tool_execution_update` | `{ toolCallId, toolName, partialResult }` | 工具的中间结果（目前没有工具产生） |
| `tool_execution_end` | `{ toolCallId, toolName, result, isError }` | `result` 是 Pi 的 `{ content, details }`，已经过 afterToolCall |
| `turn_end` | `{ stopReason, errorMessage?, toolResults }` | `stopReason` 是 Pi 的：`stop` / `length` / `toolUse` / `error` / `aborted` |
| `agent_end` | `{ messages }` | Agent 循环结束；`messages` 是结束时会话的消息条数。**一轮 `session/prompt` 在它之后才返回** |

`message_update.update.type`：

| `update.type` | 写入日志 | 字段 |
|---|---|---|
| `text_delta`、`thinking_delta` | 是，**合并后**写入（第 6.3 节） | `contentIndex`、`delta` |
| `toolcall_end` | 是 | `contentIndex`、`toolCall`：`{ type: "toolCall", id, name, arguments }` |
| `done`、`error` | 是 | `reason`：Pi 的 stopReason |
| `start`、`text_start`、`text_end`、`thinking_start`、`thinking_end`、`toolcall_start`、`toolcall_delta` | 否 | 这些信息已由 `message_start`、`message_end`、`toolcall_end` 覆盖；`toolcall_delta` 是半截的参数 JSON |

事件顺序与 Pi 的 agent-loop 一致。一次带工具调用的 prompt：

```text
agent_start
turn_start
  message_start(user) · message_end(user)
  message_start(assistant) · message_update* · message_end(assistant，含 toolCall)
  tool_execution_start → [beforeToolCall] → [executeTool] → [afterToolCall] → tool_execution_end
  message_start(toolResult) · message_end(toolResult)
turn_end(toolUse)
turn_start
  message_start(assistant) · message_update* · message_end(assistant)
turn_end(stop)
agent_end
```

Pi 以后新增的事件名，适配层原样交出（`AgentEvent.Other`），宿主层照写日志，不对外映射。

## 4. 宿主层事件

“产生者”一栏是工作包；列出的 payload 字段是最小集合，实现可以追加。

### 4.1 会话

| eventType | payload | 产生者 |
|---|---|---|
| `session.created` | `{ ownerKey, callerKind, callerUid, via }`；`via`：`session/new` / `auto_select` | W2、W4 |
| `session.selected` | `{ created, reason, score? }`：自动选会话（Jev）选中已有会话或新建 | W2 router |
| `session.state_changed` | `{ from, to, reason? }`：状态见 session-scheduling.md | W2 scheduler |
| `session.closed` | `{ reason }` | W2 |

### 4.2 任务

一次 `session/prompt` 产生一个任务。任务的事件以 `task.queued` 开始，以 `task.completed`、`task.cancelled`、`task.failed` 之一结束；中途进入 `task.recovery_required` 的任务，等 `task.recovery_resolved` 之后再以终态结束。

| eventType | payload | error | 产生者 |
|---|---|---|---|
| `task.queued` | `{ input, caller: { uid, kind }, position }`；`input` 是 ACP 的 ContentBlock 数组（按第 5 节截断） | — | W2 |
| `task.started` | `{ attempt }`：第几次执行，从 1 开始 | — | W2 |
| `task.completed` | `{ stopReason, usage? }`；`stopReason`：`end_turn` / `max_tokens` / `max_turn_requests` / `refusal` | — | W2 |
| `task.cancel_requested` | `{ by, phase }`；`by`：`client` / `timeout` / `system` / `user_stop`；`phase`：`queued` / `model` / `tool` | — | W2、W4 |
| `task.cancelled` | `{ phase, unknownToolCalls }`：取消时已发出、没拿到结果的工具调用 | — | W2 |
| `task.failed` | `{ attempt, attemptState }`；`attemptState`：`not_started` / `failed` / `unknown` | 有 | W2 |
| `task.recovery_required` | `{ attempt, reason, unknownToolCalls: [{ toolCallId, name }] }`；`reason`：`runtime_restarted` / `agent_core_failed` / `cancel_grace_exceeded` | — | W2 recovery |
| `task.recovery_resolved` | `{ decision, by }`；`decision`：`retry` / `abandon` | — | W10 |
| `tool_round_limit` | `{ rounds, limit }`：工具轮次超过上限，本轮随后以 `task.completed { stopReason: "max_turn_requests" }` 结束 | — | W2 |

### 4.3 工具派发、确认、Hook

| eventType | payload | error | 产生者 |
|---|---|---|---|
| `tool.dispatched` | `{ toolCallId, name, provider, risk }`：**在调用 `ToolPort.invoke` 之前提交**，用来判断“结果未知” | — | W2 broker |
| `tool.settled` | `{ toolCallId, outcome, isError }`；`outcome`：`completed` / `not_dispatched` / `unknown` / `cancelled` / `rejected` | `outcome` 不是 `completed` 时有 | W2 broker |
| `consent.requested` | `{ requestId, toolCallId, toolName, risk, callerUid }` | — | W16 |
| `consent.resolved` | `{ requestId, decision, reason, remember }`；`decision`：`allow` / `deny`；`reason`：`user` / `timeout` / `unavailable` / `remembered` / `policy` / `client` | — | W16 |
| `hook.dispatched` | `{ hookEvent, matched }` | — | W22 |
| `hook.decided` | `{ hookEvent, decision, reason?, inputUpdated, contextAdded }` | — | W22 |
| `hook.failed` | `{ hookEvent }` | 有 | W22 |

`rejected`：Broker 在派发前拒绝（例如工具名不在目录里），没有副作用。

### 4.4 系统流（`sessionId = "_system"`）

| eventType | payload | error | 产生者 |
|---|---|---|---|
| `runtime.started` | `{ version, schemaVersion }` | — | W2 |
| `runtime.recovered` | `{ requeued, recoveryRequired, interrupted, userStopped, cancelled }`：启动恢复流程（F8）的结果——重新排队的任务数、等恢复决定的任务数、这次启动围栏掉的执行数、上一个进程是否被用户主动停止、因此取消的排队任务数 | — | W2 recovery |
| `agent_core.failed` | `{ runningTasks }`：泵故障（S8），按运行时崩溃处理 | 有 | W2 |
| `agent_core.restarted` | `{}`：新的 Agent core 实例已启动；各会话在下次用到时按 Store 里的 messages 重建 | — | W2 |
| `supervisor.status` | `{ state, reason? }`：root 监督进程的状态广播（S2 契约） | — | W7、W11 |
| `extension.status` | `{ component, state, detail? }`：Extension Host、插件连接等 | — | W14 |

## 5. payload 的大小与隐私

- 单个事件的 payload 序列化后不超过 **65,536 字符**。超过时把最长的字符串字段截断到 16,384 字符，并在 payload 根上加 `"truncated": true`。完整内容仍在各会话保存的 Pi messages 里（store，W2），事件日志只作记录和重放。
- **文字和 thinking 增量不会被截断**：宿主层写日志之前把单条增量切到不超过 8,192 字符（6.3），模型一次发来再长的增量（非流式返回的厂商整段文字只有一条增量）也完整进日志、完整发给 ACP 客户端。会被截断的只有携带整段内容的事件（`message_end`、`turn_end`、`agent_end`、很长的工具参数和结果），客户端的文字来自增量，不受影响。
- 图片等二进制内容（base64）不进事件日志：`data` 替换为 `"<omitted: N chars>"`，保留 `mimeType`。
- 不能出现：模型 key、任何请求头、凭据、Keystore 数据、插件的私有数据。模型请求体和响应原文不写进日志。
- 可以出现：用户输入、模型输出、工具参数和结果（它们是会话内容，本来就在 Store 里）。诊断页和日志输出**不展示**这些内容（W11）。
- `error.message` 是给人看的简短说明，同样不能带上述秘密。

## 6. sequence 与写入

### 6.1 分配

- sequence 由事件日志在写入事件的**同一个数据库事务**里分配：`max(sequence) + 1`，按会话单独计数，从 1 开始。
- 一个会话的事件由一个写者串行写入，不存在两个事件拿到同一个 sequence。
- 系统流 `_system` 是一个保留的“会话”，有自己的 sequence，不对 ACP 客户端可见，也不能被客户端创建或加载。

### 6.2 提交与通知

- 事件提交后才交给订阅者（ACP 映射、诊断），订阅者按 sequence 顺序收到，不会看到未提交的事件。
- 状态转换与它对应的事件在同一个事务里提交（例如任务进入 running 与 `task.started`）。
- 进程崩溃时，已提交的事件都在；未提交的事件视为没有发生。恢复流程只根据已提交的事件和状态判断（session-scheduling.md）。

### 6.3 增量合并

Pi 的文字和 thinking 增量可能每秒几百条。宿主层在写入日志**之前**合并它们，写进日志和发给客户端的都是合并后的事件（S3 建议：按 16–50 ms 合并后再发）：

- 同一任务里连续的、`update.type` 和 `contentIndex` 都相同的 `text_delta`（或 `thinking_delta`）合并为一条，`delta` 按顺序拼接；
- 以下任一情况立即写出已合并的部分：距第一条未写出的增量已过 **32 ms**；合并后的 `delta` 达到 **8,192 字符**；来了其他任何事件；本轮结束；
- 写出时如果 `delta` 超过 8,192 字符（一条增量本身就很长），按 8,192 字符切成多条（不切开代理对），在同一个事务里依次写入。所以日志里每条 `text_delta` / `thinking_delta` 都不超过 8,192 字符，不会触发第 5 节的截断；
- 合并只改变切分方式，不改变拼接结果：把一条消息的所有 `text_delta` 按 sequence 拼起来，等于 `message_end` 里对应的文字。

### 6.4 读取与游标

- 读取方用 `afterSequence` 游标读 `sequence > afterSequence` 的事件，按 sequence 升序返回，没有空洞。
- 因为响应丢失而重读时，读取方可能收到重复的事件，按 `(sessionId, sequence)` 去重。
- 游标早于保留范围时返回 `cursor_too_old`（errors.md），读取方要重新取快照。W2 不做裁剪（保留全部事件）；裁剪与快照随增量恢复扩展在 W10 定义。
- 会话被删除时，它的全部事件一起删除；sequence 不会因为删除被别的会话复用（sequence 本来就按会话独立）。

## 7. 可见性

哪些事件以什么形式到达 ACP 客户端，以 `core/protocol/acp-mapping.md`（W4）为准。概要（architecture 5.6）：

| 事件 | 客户端看到 |
|---|---|
| `message_update`（文字、thinking） | `session/update`：`agent_message_chunk`、`agent_thought_chunk` |
| `tool_execution_start` / `_end` | `session/update`：`tool_call`、`tool_call_update` |
| `agent_end` 后的 `task.completed` | `session/prompt` 的 `stopReason` |
| `task.cancelled` | `stopReason: cancelled` |
| `tool_round_limit` | `stopReason: max_turn_requests` |
| `task.failed` | `session/prompt` 的 JSON-RPC 错误（errors.md 第 5 节） |
| `session.selected` | 只对协商了自动选会话扩展的客户端，在 `_meta` 里 |
| `consent.*`、`hook.*`、`tool.*`、`task.recovery_*`、系统流 | 不对外，只在 AgentOS App 的诊断页展示 |

## 8. 与原型的差异

| output-stream-v1 / session-scheduling-v1 | 本项目 |
|---|---|
| 信封字段 `protocolVersion`、`requestId` | 改为 `v`；`requestId` 去掉（ACP 请求与任务一一对应，用 `taskId`） |
| 事件经 `ParcelFileDescriptor` 管道推给前端 | 不迁移：对外只有 ACP，事件经映射变成 `session/update` |
| `agent_settled`、`queue_update`、`compaction_*` | 暂不产生：Pi Agent core 0.86.1 没有对应事件；需要时追加 |
| `capability.lease_granted` / `revoked` | 不迁移：租约由 Extension Host 的连接管理取代（extensions.md） |
| 前端订阅 `afterSequence` | 保留为读取语义，对外形式由 W10 的增量恢复扩展决定 |
