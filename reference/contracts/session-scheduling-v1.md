# Session Scheduling Contract v1

本文冻结 AgentOS Session 调度的逻辑语义。它是 `agent-bus/1` 的调度补充，独立于 JSON-RPC、Binder、ACP 或其他传输绑定。

本文中的“必须”“不能”和“可以”是契约要求。实现可以替换存储、worker 和调度器，但不能改变这些对调用方可见的语义。

## 1. 对象和职责

- **Session**：持久化的会话和调度单元，拥有上下文、输入顺序、任务队列、事件 sequence 和 capability lease 关联。
- **Task**：一次 `submitInput` 产生的逻辑执行。一个 Task 可以有多次 runtime attempt，但 `taskId` 不变。
- **Attempt**：某个 Agent worker 对 Task 的一次实际执行。Attempt 可能在 daemon 崩溃时留下未知的外部副作用状态。
- **Scheduler**：只决定哪个可运行 Session 获得下一个执行机会；不改变 Session 内输入顺序。
- **Plugin capability lease**：将一个已校验的 Plugin capability 临时绑定到一个 Session 和 worker generation 的租约。

Task 还拥有自己的执行状态 `queued`、`running`、`cancelling`、`cancelled`、`completed`、`failed` 和 `unknown`。`unknown` 只表示 daemon 或 worker 在没有终态记录时丢失了 Attempt 的事实，不能被当作成功、取消或可安全重试。

现有 Agent Bus 方法与本文术语对应如下：

| 调度术语 | Agent Bus v1 方法 |
|---|---|
| `submitInput` | `session/prompt` |
| `cancelTask` | `session/abort` |
| `getSnapshot` | `session/snapshot` |
| `closeSession` | `session/close` |
| 订阅事件 | `session/subscribe` |
| 断开订阅 | `session/detach` / transport disconnect |

ACP 适配器可以把这些语义映射到 ACP 的 `session/prompt`、`session/cancel` 和标准更新，但不能把入队回执改成 ACP v1 的 Prompt 完成响应。

## 2. Session 状态

Session 的持久化状态只有以下七种：

| 状态 | 含义 | 是否接受 `submitInput` | 是否占用运行槽位 |
|---|---|---:|---:|
| `created` | Session 已持久化，没有活动 Task 或待执行 Task，可以开始新的输入 | 是 | 否 |
| `queued` | 至少有一个待执行 Task，正在等待 Scheduler | 是 | 否 |
| `running` | Session 当前有且只有一个运行中的 Task | 是，追加到本 Session 队列 | 是 |
| `cancelling` | 当前运行 Task 已请求取消或超时，正在等待 runtime 确认 | 否，返回 `request_conflict` | 是 |
| `paused` | Session 被调度器、恢复流程或 capability 条件暂时挂起；队列保留 | 是，持久化到队列 | 否 |
| `completed` | Session 已显式关闭并完成资源释放 | 否，返回 `session_terminal` | 否 |
| `failed` | Session 发生不可恢复的系统级故障 | 否，返回 `session_terminal` | 否 |

普通 Task 完成不会把 Session 变成 `completed`。Task 完成后，若队列为空，Session 回到 `created`；若仍有任务，Session 保持或进入 `queued`。`completed` 只表示显式关闭成功，`failed` 只表示 Session 级不可恢复故障。

### 2.1 合法转换

```text
created  --submitInput----------------------> queued
queued   --scheduler admits-----------------> running
running  --task terminal, queue non-empty--> queued
running  --task terminal, queue empty------> created
running  --cancelTask/timeout--------------> cancelling
cancelling --runtime confirms stop----------> queued | created
running  --close----------------------------> cancelling
cancelling --close + unknown Attempt--------> paused (recovery required)
created | queued --pause--------------------> paused
paused  --scheduler/runtime resumes--------> queued | created
created | queued | paused --close-----------> completed
any non-terminal --unrecoverable failure---> failed
```

`running` 不能被优先级抢占；高优先级 Session 必须等待当前 Attempt 到达可停止的边界。`cancelling` 占用运行槽位，直到 Attempt 有明确的终态或进入恢复所需的未知状态。

`closeSession` 只能由有权的 controller 或系统恢复控制面调用。它会先取消活动 Task，等待 runtime 确认，再撤销 lease，最后把 Session 置为 `completed`。如果 Attempt 进入 `unknown`，关闭不能报告成功；Session 保持 `paused` 并返回 `recovery_required`，直到恢复控制面明确处理该 Attempt。普通前端断线和 `session/detach` 都不能触发该转换。

## 3. Task 顺序、优先级和并发

### 3.1 Session 内串行

同一 Session 同时最多运行一个 Task。输入按 `submitInput` 的持久化顺序进入该 Session 队列，Scheduler 不能为了优先级重排同一 Session 的输入；这保证上下文顺序和 Agent 对话顺序一致。

不同 Session 可以并行。系统配置提供：

```text
maxRunningSessions       >= 1，系统全局运行槽位上限，必填
maxRunningSessionsPerUser  可选的单 User 上限
queueTimeoutMs            可选的排队期限
executionTimeoutMs       可选的单 Attempt 执行期限
cancelGraceMs            取消或超时后的 runtime 确认期限
agingIntervalMs          优先级老化间隔
```

`maxRunningSessions` 改变时只影响新的调度准入，不终止已经运行的 Session。`running` 和 `cancelling` 都计入运行槽位；`created`、`queued`、`paused`、`completed` 和 `failed` 不计入。

### 3.2 优先级和公平性

Session 创建时设置优先级，默认 `normal`。v1 的优先级顺序为：

```text
background < normal < interactive < urgent
```

优先级只影响不同 Session 之间的下一次准入，不改变 Session 内 FIFO 顺序。为避免低优先级永久饥饿，等待中的 Session 按 `agingIntervalMs` 老化，每经过一个间隔提升一个有效优先级，最高不超过 `urgent`。

Scheduler 每次只从一个 Session 取一个 Task。相同有效优先级内使用轮转；同一轮中已获得机会的 Session 排到队尾。若多个 Session 的有效优先级和轮转位置相同，以 `sessionId` 稳定排序。

因此，调度保证：

1. 不超过全局和 User 并发上限；
2. 同一 Session 不并行；
3. `urgent` 在资源可用时优先；
4. 其他优先级会通过老化最终获得机会；
5. 一个 Session 的长队列不能独占同优先级的所有运行机会。

## 4. `submitInput`

请求必须包含 `sessionId`、客户端生成的 `clientRequestId` 和输入内容。服务端按 `(sessionId, clientRequestId)` 做幂等：

- 第一次请求先写入 Session、Task 和事件，再返回入队回执；
- 相同 key 且内容哈希相同，返回同一个 `taskId`，`deduplicated: true`，不能创建第二个 Task；
- 相同 key 但内容不同，返回 `request_conflict`，不能覆盖原请求；
- 客户端没有收到响应时可以安全重发同一个 `clientRequestId`；
- 回执只确认输入已经持久化并进入队列，不确认 Agent 已运行或完成。

回执至少包含：

```json
{
  "sessionId": "session-1",
  "taskId": "task-1",
  "messageId": "message-1",
  "accepted": true,
  "deduplicated": false,
  "sessionState": "queued"
}
```

`running` Session 可以追加输入；新 Task 排在当前 Task 后面。`cancelling`、`completed` 和 `failed` 不接受新输入。`paused` 可以接受输入并持久化，但在恢复前不能启动。

## 5. `cancelTask`

取消是异步操作，返回只表示取消请求已经持久化：

```json
{
  "sessionId": "session-1",
  "taskId": "task-1",
  "accepted": true,
  "alreadyRequested": false
}
```

- `queued` Task：可以立即标记为取消，按顺序写入 `task.cancel_requested` 和 `task.cancelled`，不会启动 Attempt。
- `running` Task：Session 进入 `cancelling`，向 runtime 发送取消，并等待最多 `cancelGraceMs`。
- `cancelling` Task：重复请求幂等返回，不重复发送 runtime 取消。
- 已经 `completed`、`cancelled` 或 `failed` 的 Task：返回当前终态，不改变事件历史。
- Task 不属于请求方可访问的 Session 时，返回 `task_not_found` 或 `forbidden`，不能泄露其存在。

runtime 确认停止后，Task 进入 `cancelled`，Session 按队列回到 `queued` 或 `created`。取消不代表已经撤销外部副作用；工具的副作用必须由工具自身的幂等和恢复策略处理。

## 6. 断线、超时和重启

### 6.1 前端断线

前端连接断开只撤销该连接的订阅，不改变 Session、Task 或 Agent worker：

- `created`、`queued`、`running`、`cancelling` 和 `paused` 保持原状态；
- 不因前端断线自动取消 Task；
- 已经写入的 `submitInput` 可以用原 `clientRequestId` 重试；
- 重连后先取 Snapshot，再从 `afterSequence` 订阅事件；
- `detach` 也不等价于 `session/close`。

### 6.2 超时

排队超时和执行超时都必须持久化 deadline：

- 排队超时的 Task 尚未启动，写入 `task.failed`，错误码为 `queue_timeout`，Session 继续调度下一个 Task；
- 执行超时先执行与 `cancelTask` 相同的取消流程，Session 进入 `cancelling`；runtime 确认停止后，Task 以 `execution_timeout` 失败；
- `cancelGraceMs` 内没有确认停止，不能假装 Task 已取消。该 Attempt 标记为 `unknown`，Session 进入 `paused`，并写入 `task.recovery_required`；不自动重放可能产生外部副作用的操作。

### 6.3 sideagentd 重启

所有状态转换和事件必须在返回回执前写入持久化 Store。重启恢复规则：

- `created`、`queued`：恢复后重新进入 Scheduler；
- `paused`：保持暂停，除非恢复流程明确允许继续；
- `completed`、`failed`：保持终态；
- `running`、`cancelling`：如果没有持久化的 Task 终态，转为 `paused`，对应 Attempt 标记为 `unknown`，写入 `task.recovery_required`；
- 未知 Attempt 不得被自动重新执行。恢复操作必须明确选择 `fail` 或以新的 Attempt、幂等上下文继续，并保留旧 Attempt 的未知状态记录。

Plugin 或 runtime worker 重启采用相同规则。重启不会清空 Session 队列，也不会根据“进程曾经退出”重放所有 Task。

## 7. Event sequence、Snapshot 和恢复

每个 Session 拥有独立的、从 1 开始的 64 位单调递增 `sequence`：

- 每个可恢复事件占用一个 sequence；sequence 永不复用；
- Event Store 先提交事件，再通知订阅者；
- 事件按 sequence 顺序发送；
- 重连请求 `afterSequence` 时只补发 `sequence > afterSequence` 的事件；
- 因响应丢失而重试时允许重复收到最后一批事件，客户端必须按 `(sessionId, sequence)` 去重；
- 服务不能发送 sequence 间隙；如果游标早于保留范围，返回 `cursor_too_old`，要求客户端重新获取 Snapshot。

Snapshot 是某个 sequence 的一致视图，至少包含：

```json
{
  "sessionId": "session-1",
  "state": "queued",
  "priority": "normal",
  "activeTask": null,
  "queuedTasks": [{"taskId": "task-2", "state": "queued"}],
  "recoveryRequired": false,
  "snapshotSequence": 42,
  "oldestRetainedSequence": 1
}
```

客户端恢复顺序固定为：

1. 获取 Snapshot，记录 `snapshotSequence`；
2. 订阅 `afterSequence = snapshotSequence`；
3. 按事件 sequence 应用更新并去重；
4. 遇到 `cursor_too_old` 时重新获取 Snapshot，不能自行拼接旧缓存。

`session/status` 可以作为非持久化的即时提示，但不能替代 Snapshot 或事件日志。

## 8. Plugin capability lease

每次 Session 使用 Plugin 前，系统必须为该使用关系创建 lease。Plugin session 的发现、绑定、握手和调用契约见 [Plugin Injection Contract v1](plugin-injection-v1.md)；本节冻结 lease 的使用与撤销语义。Lease 至少绑定：

```text
leaseId
userId
pluginId
pluginSessionId
sessionId
workerGeneration
capabilities
issuedAt / expiresAt
```

约束如下：

- lease 只能被绑定的 Session 和 worker 使用，不能转交给前端、其他 Session 或新的 worker generation；
- lease 的 capability 集合取自系统校验后的 Plugin descriptor 和当前 policy，不能由 Agent prompt 或前端自行扩大；
- Session 进入 `running` 前必须拥有当前 Attempt 所需的有效 lease；
- Session 进入 `completed`、`failed` 或被系统撤销时，相关 lease 必须撤销；`paused` 可以保留队列，但不应继续调用已失效 lease；
- Plugin 进程死亡、Binder death、签名/权限变化、用户停止、显式 unregister 或过期都会立即撤销 lease；
- 撤销后禁止新调用；已经发出的调用可以返回，但不能自动重放；
- 依赖被撤销 lease 的 Task 以 `plugin_unavailable` 或 `lease_revoked` 失败。若队首 Task 仍需要该 capability，Session 进入 `paused`，等待新的 Plugin session 和新的 lease；
- Plugin 重新连接必须获得新的 `pluginSessionId` 和 `leaseId`，不能复用旧 lease。

Lease 的授予和撤销写入 Session 事件，事件中只记录 capability 名称和状态，不记录 credential、认证 header 或 Plugin 私有数据。

## 9. 持久化事件

调度实现至少产生以下可恢复事件：

```text
session.state_changed
task.queued
task.started
task.completed
task.failed
task.cancel_requested
task.cancelled
task.recovery_required
capability.lease_granted
capability.lease_revoked
```

`task.failed` 必须包含稳定错误码、是否可重试和 Attempt 状态；`task.recovery_required` 必须包含未知副作用提示。事件 payload 不能包含 API key、OAuth token 或未授权的 Plugin 私有数据。

## 10. 验证要求

在实现 Scheduler 前，reference tests 必须覆盖：

1. 同一 Session 的两个 Task 不并行且保持输入顺序；
2. 多 Session 并行不超过 `maxRunningSessions` 和 User 上限；
3. 相同优先级轮转，低优先级经过 aging 后不会饥饿；
4. 重复 `clientRequestId` 返回同一个 Task，不重复执行；
5. queued、running、cancelling、paused、completed、failed 的转换和非法转换；
6. queued cancel、running cancel、cancel grace timeout；
7. 前端断线不改变 Task，重连可用 Snapshot + `afterSequence` 恢复；
8. 过旧 cursor 返回 `cursor_too_old`；
9. daemon 在 running/cancelling 时重启不会自动重放未知 Attempt；
10. Plugin lease 绑定 Session，Plugin death 撤销 lease 并阻止新调用；
11. lease 重新建立使用新的 `pluginSessionId` 和 `leaseId`；
12. Snapshot 与事件 sequence 一致，重复事件可安全去重。
