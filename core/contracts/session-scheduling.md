# 会话调度契约（session-scheduling v1）

- **状态**：v1，W2 冻结。对调用方可见的语义以本文为准；实现可以替换存储和调度算法，但不能改变这些语义。
- **来源**：原型 agenriod 的 `session-scheduling-v1.md`（原文见 `reference/contracts/`）。保留调度、取消、恢复、sequence 的语义；方法名从 Agent Bus 改为 ACP 术语；删掉 capability lease、优先级与老化、ROM 相关内容（第 10 节）。
- **实现**：`core/runtime/.../scheduler/`（`Scheduler`、`Recovery`、`TaskRunner`、`CoreSessions`）、`store/`、`broker/`。测试：`SchedulerTest`、`RecoveryTest`、`StoreTest`、`CapabilityBrokerTest`。
- **相关**：[events.md](events.md)（事件与 sequence）、[errors.md](errors.md)（错误码）、[session-selection.md](session-selection.md)（自动选会话）。

## 1. 对象

| 对象 | 含义 |
|---|---|
| 会话（Session） | 持久化的对话和调度单元：Pi messages、任务队列、事件 sequence 都属于它。归属于创建它的调用方（`ownerKey`，architecture 5.3） |
| 任务（Task） | 一次 `session/prompt`。一个任务可以有多次执行（attempt），`taskId` 不变 |
| 执行（Attempt） | Agent core 对任务的一次实际运行。运行时崩溃时可能留下未知的外部副作用 |
| 调度器（Scheduler） | 只决定哪个会话获得下一个运行机会；不改变会话内的输入顺序 |

与 ACP 的对应：

| 调度语义 | ACP | 说明 |
|---|---|---|
| 提交输入 | `session/prompt` | ACP v1：本轮结束才返回 `stopReason`（第 5 节）。输入在开始运行前已经持久化 |
| 取消任务 | `session/cancel` | 取消会话里所有未结束的任务（第 6 节） |
| 新建会话 | `session/new` | 自动选会话扩展见 session-selection.md |
| 取回会话和历史 | `session/load`、`session/resume` | 已实现（acp-mapping.md 4a）；归属按 `ownerKey`，别人的会话一律 `session_not_found` |
| 断线续读（按事件游标补发） | 增量恢复扩展 | M2（W10） |
| 列出、分叉、删除、关闭会话 | `session/list`、`session/fork`、`session/delete`、`session/close` | 已实现（acp-mapping.md 4a） |

## 2. 会话状态

| 状态 | 含义 | 接受新的 `session/prompt` | 占运行名额 |
|---|---|---:|---:|
| `created` | 没有进行中或排队的任务 | 是 | 否 |
| `queued` | 有排队的任务，等调度 | 是，排到队尾 | 否 |
| `running` | 正在运行一个任务 | 是，排到队尾 | 是 |
| `cancelling` | 运行中的任务已请求取消，等 Agent core 确认停止 | 否，`invalid_state` | 是 |
| `paused` | 暂停：有结果未知、等用户决定的任务（`pause_reason = recovery_required`） | 否，`recovery_required` | 否 |
| `closed` | 已关闭（暂不启用） | 否，`session_terminal` | 否 |
| `failed` | 会话级不可恢复的故障 | 否，`session_terminal` | 否 |

一个任务结束不会让会话进入终态：结束后队列非空回到 `queued`（排到轮转队尾），否则回到 `created`。

```text
created    --提交-------------------------------> queued
queued     --调度器放行--------------------------> running
running    --任务结束，队列非空-------------------> queued
running    --任务结束，队列为空-------------------> created
running    --取消 / 执行超时----------------------> cancelling
cancelling --Agent core 确认停止------------------> queued | created
cancelling --宽限期内没停下-----------------------> paused（结果未知）
running    --Agent core 泵故障--------------------> paused（结果未知）
重启恢复   --有运行中 / 取消中的任务---------------> paused（结果未知）
paused     --用户放弃结果未知的任务----------------> queued | created
```

每次状态变化写一条 `session.state_changed { from, to, reason }`，与引起它的事件在同一个事务里提交。

## 3. 任务状态

| 状态 | 含义 | 终态 |
|---|---|---:|
| `queued` | 已持久化，等待运行 | |
| `running` | 正在运行 | |
| `cancelling` | 已请求取消，等 Agent core 停下 | |
| `completed` | 正常结束；`stopReason` 为 `end_turn` / `max_tokens` / `max_turn_requests` / `refusal` | 是 |
| `cancelled` | 已取消；`stopReason` 为 `cancelled` | 是 |
| `failed` | 失败，带错误码（errors.md） | 是 |
| `unknown` | 执行在没有终态记录的情况下丢失：运行时重启、Agent core 泵故障、取消宽限期超时。**不能当作成功、取消或可安全重试** | |

`unknown` 的任务只能由用户决定：放弃（任务以 `abandoned` 失败）；W10 起可以选择重试（新的执行，旧执行的 unknown 记录保留）。

## 4. 顺序与并发

- **会话内串行**：同一会话同一时间最多运行一个任务，按提交顺序执行。调度器不会为任何理由重排同一会话的输入。
- **会话间并行**，受两个上限约束（运行中和取消中都占名额）：

| 参数 | 默认 | 含义 |
|---|---|---|
| `maxRunningSessions` | 4 | 全局同时运行的会话数 |
| `maxRunningPerOwner` | 2 | 同一调用方同时运行的会话数，防止一个 App 占满名额 |
| `maxQueuedPerSession` | 16 | 一个会话排队的任务上限，超过返回 `busy` |
| `queueTimeoutMillis` | 0（不限） | 排队期限，超过以 `queue_timeout` 失败，不开始运行 |
| `executionTimeoutMillis` | 15 分钟 | 任务 deadline，超过按取消处理，以 `execution_timeout` 失败 |
| `cancelGraceMillis` | 10 秒 | 请求取消后等 Agent core 停下的时间 |
| `maxToolRounds` | 12 | 工具轮次上限（architecture 4.1），超过以 `max_turn_requests` 结束 |

- **公平**：就绪的会话按“进入 `queued` 的先后”轮转；一个会话跑完一个任务后如果还有排队的，重新排到队尾，所以长队列不能独占运行机会。
- v1 **没有优先级**。ACP 没有优先级的概念；需要时（例如 AgentOS 自带界面优先）再追加，并同时加上老化，避免饥饿。

## 5. 提交（`session/prompt`）

1. 校验调用方能访问这个会话（不属于调用方时按 `session_not_found` 处理，不泄露其存在）。
2. 在一个事务里写入任务（`queued`）和 `task.queued` 事件，必要时会话转为 `queued`；**提交后才开始运行**。
3. 拒绝的情况：会话已终态（`session_terminal`）、正在取消（`invalid_state`）、有等恢复决定的任务（`recovery_required`）、队列满（`busy`）、safe mode（`safe_mode`）；以及**第三方 App 的配额**（第 5.1 节）：文字超限（`invalid_params`）、已有一个 prompt 在进行或一小时内用完（`quota_exceeded`）。
4. 幂等：带提交标识（`clientRequestId`）的重复提交，内容相同返回原任务，不创建第二个；内容不同返回 `request_conflict`。ACP v1 本身没有提交标识，这个语义留给 W10 的持久化提交扩展使用。
5. ACP v1 的 `session/prompt` 在这一轮结束后才返回：`completed` → `stopReason`；`cancelled` → `cancelled`；`failed` → JSON-RPC 错误；`unknown` → JSON-RPC 错误（`agent_core_failed` 或 `tool_result_unknown`，errors.md）。不能把“已入队”当作 prompt 的完成响应。
6. 运行前如果没有配置模型，任务直接以 `model_not_configured` 失败，`attemptState = not_started`，不启动 Agent core。

### 5.1 第三方 App 的配额（`CallerQuota`，docs/third-party-acp.md 4.6）

只对 `CallerKind.APP`；AgentOS 自己、电脑端、运行时不计数、不受限。放行发生在写任务**之前**，顺序：文字长度 → 同时一个 → 每小时上限；被拒绝的 prompt 不计数、不占名额、不产生任务。

| 限制 | 默认 | 拒绝 | 配置 |
|---|---|---|---|
| 一次 prompt 的文字（text 块 + 嵌入的文本资源，字符） | 16,000 | `invalid_params`，`details.reason = too_large` | `CallerQuotaConfig.maxPromptChars` |
| 同时进行的 prompt | 1 | `quota_exceeded`，`details.reason = busy` | `maxConcurrentPrompts` |
| 滑动一小时内开始的 prompt | 30 | `quota_exceeded`，`details.reason = hourly`，`details.retryAfterSeconds` | `maxPromptsPerHour`、`windowMillis` |

- 按 UID（`ownerKey`）计：同一个 App 的所有连接、所有会话共用一份。
- “进行中”从放行算到**任务结束**（`completed` / `cancelled` / `failed` / 结果未知），不是到连接断开：断开不取消任务（F7），所以重连不能绕过“同时一个”。客户端看到上一轮结束就发下一轮时，名额一定已经释放（提交前会按 Store 里任务的状态核对一次）。
- 计数在内存里，:agent 进程重启后清零。
- **用量回调**：每个放行过的 prompt 结束时回调一次 `CallerUsageListener.onPromptFinished(PromptUsage)`（`RuntimeEngine.quota.addListener`）；被拒绝的、没能提交的不回调。`PromptUsage` 带调用方身份、开始/结束时间、文字长度、结局（`completed` / `cancelled` / `failed` / `unknown`）和一小时内的次数。当前数字用 `RuntimeEngine.quota.usage(caller)` 读。

## 6. 取消（`session/cancel`）

取消作用于会话里所有未结束的任务，按任务所处阶段处理：

| 任务状态 | 处理 |
|---|---|
| `queued` | 立即取消：依次写 `task.cancel_requested { phase: queued }`、`task.cancelled`，不会开始运行 |
| `running` | 任务转为 `cancelling`，会话转为 `cancelling`，调用 Agent core 的 `abort`；工具正在执行时 `abort` 同时取消工具调用（转给 Extension Host 尽力取消）。Agent core 停下后任务以 `cancelled` 结束 |
| `cancelling` | 已在取消中，不重复处理 |
| 终态 | 不改变 |

- 请求取消后 `cancelGraceMillis` 内 Agent core 没有停下，**不能假装已取消**：这次执行标记为 `unknown`，会话暂停，写 `task.recovery_required { reason: cancel_grace_exceeded }`，之后这次执行迟到的任何结果一律丢弃。
- 取消不等于撤销副作用。已经发出的工具调用，结果照实记录（`tool.settled`），发出后被取消的列在 `task.cancelled.unknownToolCalls` 里。

### 6.1 按调用方取消（`RuntimeEngine.cancelOwner`，撤销第三方 App 的授权）

关闭通道不取消任务（F7），所以撤销一个 App 时，它正在跑的任务会继续占着“同时一个 prompt”的名额、继续花用户的模型额度。`cancelOwner(caller, by, waitMillis)` 取消 `caller.ownerKey` 名下所有会话里没结束的任务（排队、运行、取消中），返回被请求取消的任务 ID：

- 对每个有未结束任务的会话走第 6 节同一条路径（排队的立即取消，运行中的请 Agent core abort，等确认的工具调用撤回确认），`task.cancel_requested.by` 记 `by`（撤销授权用 `revoked`）；
- **只动这个 `ownerKey` 的会话**：别的 App、AgentOS 自己、电脑端不受影响；按会话的归属算，包名和显示名不参与；
- 幂等：没有未结束的任务时返回空列表；已经在取消中的任务不重复取消，也不在返回值里；
- 返回前最多等 `waitMillis`（默认 `AcpConfig.cancelWaitMillis`，传 0 不等）让任务停下，然后释放它们占的配额名额（5.1）并回调用量（`cancelled`）；任务在等待期内没停下（例如一个不可打断的工具调用）时名额继续占着，直到任务真的结束。


## 7. 断开、超时与重启

**断开连接**（F7）：客户端断开不改变会话和任务，不取消任务；任务照常跑完，结果留在 Store 里，调用方回来后按 W10 的方式取回。

**超时**：排队超时和执行超时的 deadline 都持久化在任务上，按 `HostPort.clock` 计算。执行超时走取消流程；Agent core 确认停下后任务以 `execution_timeout` 失败。

**运行时重启**（F8）：启动时、调度器开始工作之前执行恢复流程（`Recovery`）：

- `running`、`cancelling` 的任务：没有终态记录，标记为 `unknown`；它已派发、没有结果的工具调用一并标记为 `unknown`；会话转为 `paused`；写 `task.recovery_required { reason: runtime_restarted, unknownToolCalls }`。**不自动重放。**
- `queued` 的任务：保持排队，由调度器重新调度。
- 系统流写 `runtime.recovered { requeued, recoveryRequired, interrupted }`。

**Agent core 泵故障**（S8）：进行中的轮次以 `CoreLost` 返回。系统流写 `agent_core.failed`，这些任务按上一条同样处理（`reason: agent_core_failed`）。下一次用到时新建 Agent core 实例（`agent_core.restarted`），会话按 Store 里保存的 Pi messages 重建。

**safe mode**（F13、W11）：不接受新任务（`safe_mode`），也不启动排队的任务（包括恢复出来的）；safe mode 结束后照常调度。

**用户主动停止**（S2 监督契约 a 第 6 条）：W6 在进程启动时根据 `ApplicationExitInfo` 判断上一个 `:agent` 是否被用户主动停止（`REASON_USER_REQUESTED` / `REASON_USER_STOPPED`），经 `HostPort.environment.previousExitStoppedByUser` 告诉运行时。为 true 时，恢复流程把排队的任务取消（`task.cancel_requested { by: user_stop, phase: queued }`、`task.cancelled`），不继续、不计入 runState，运行时不会因为它们进入前台；运行中、取消中的任务照常标记为结果未知（本来就不重放）。`runtime.recovered` 记录 `userStopped` 和取消的条数。

### 7.1 启动与恢复期间的请求

W6 在 `:agent` 进程启动时调用 `AgentRuntime.start()`（打开 Store、恢复流程、启动调度器），同时 ACP 连接可能已经到达：

- `serveAcp` 立即返回，不挂起、不做 I/O，可以在 `start()` 完成之前调用（acp-mapping.md 第 1 节）。`initialize` 不需要 Store，立即应答；`session/new`、`session/prompt` 等在运行时的协程里等 `start()` 完成，不会失败。
- **收到即计数**：`session/new`、自动选会话和 `session/prompt` 从收到的那一刻起就计入 `runState.queuedTasks`，直到调度器接手（任务持久化后由调度器自己的计数接替）。恢复期间收到的 prompt 同样在收到时计入，不等恢复结束。两段计数的交接只会短暂地多计，不会出现空档，所以 W6 按 `activeTasks + queuedTasks` 做的空闲判断不会在“收到 prompt、还没开始跑”之间让出前台。
- `start()` 失败时，这些等待中的请求以同一个异常结束。

## 8. 工具调用与“结果未知”

Broker（`broker/CapabilityBroker`）处理 Pi 回到宿主层的每一次工具调用：

1. **目录校验**：工具名不在当前目录里就拒绝，不调用提供方（`tool.settled { outcome: rejected }`，`tool_not_in_catalog`）；`authorize`（Pi 的 beforeToolCall）和 `execute` 各校验一次，目录在两步之间变化也拦得住。
2. **确认**：`read` 级直接执行；`write`、`high` 级请用户确认（`consent.*`），`write` 可以“本会话内不再询问”。Hook 的 allow 不能跳过确认。W16 用 RiskPolicy 细化。
3. **先落盘再调用**：`tool.dispatched` 提交之后才调用提供方。
4. **结果分类**：`completed`（拿到结果，可以是 isError）/ `not_dispatched`（确定没发出，没有副作用）/ `unknown`（已发出、没拿到结果：提供方死亡、超时）/ `cancelled`（发出后被取消）。
5. 工具失败、被拒绝都作为 isError 的结果交回模型，本轮继续（errors.md 第 6 节）。

恢复时，`tool.dispatched` 之后没有 `tool.settled` 的调用一律视为结果未知。**运行时永不自动重做结果未知的工具调用。**

## 9. Pi messages 的保存

- 每个会话的 Pi messages（pi-ai `Message[]`，开头是 system 消息）存在 Store 里，原样保存、原样用于重建。
- 保存时机：每个 `turn_end` 之后存一次检查点；任务结束（completed / cancelled / failed）时存最终版本，同时记为“稳定快照”。
- 用户放弃结果未知的任务时，messages 退回稳定快照，下一轮从最近一个结束的任务之后继续。
- Agent core 实例重建、运行时重启后，会话第一次用到时按 Store 里的 messages 重建 Pi `Agent`。

## 10. 与原型的差异

| session-scheduling-v1 | 本项目 |
|---|---|
| Agent Bus 方法（`submitInput`、`cancelTask`、`getSnapshot` …） | ACP 方法（第 1 节）；Snapshot 与订阅游标留给 W10 |
| 会话终态 `completed` | 改名 `closed`，避免与任务的 `completed` 混淆 |
| 优先级 `background < normal < interactive < urgent` 与老化 | 删除（ACP 没有优先级）；公平性靠就绪先后轮转 |
| Plugin capability lease | 删除：插件连接由 Extension Host 管理（extensions.md）；运行时只按工具目录放行 |
| `maxRunningSessionsPerUser` | 改为按调用方（`ownerKey`）限制 |
| 入队回执作为提交的返回值 | ACP v1 的 `session/prompt` 等本轮结束才返回；持久化回执由 W10 的扩展提供 |
| 关闭会话时撤销 lease | 不适用；`session/close` 暂不启用 |

## 11. 测试覆盖

| 要求 | 测试 |
|---|---|
| 同一会话的任务不并行、保持顺序 | `SchedulerTest.prompts in one session run one at a time in submission order` |
| 全局与每调用方上限 | `SchedulerTest.global and per-caller limits are respected` |
| 重复提交返回同一任务 | `SchedulerTest.resubmitting the same client request id is idempotent` |
| 排队取消、运行中取消 | `SchedulerTest.cancel stops a running prompt and removes queued ones` |
| 执行超时 | `SchedulerTest.execution deadline cancels the turn…` |
| 重启不重放结果未知的执行和工具调用 | `RecoveryTest`（两例） |
| 用户主动停止后恢复出来的任务不继续、不计入 runState | `RuntimeStartTest.after a user stop…` |
| 恢复期间收到的 prompt 在收到时计入 runState；收到到调度之间没有空档 | `RuntimeStartTest`（两例） |
| `serveAcp` 在 `start()` 之前立即返回，之后连接照常工作 | `RuntimeStartTest.serveAcp returns immediately…` |
| 泵故障后重建 | `SchedulerTest.a pump failure fences the task…` |
| sequence 追加写、不留空洞、重开后继续 | `StoreTest` |
| 目录外的工具被拒绝、先落盘再调用 | `CapabilityBrokerTest` |
| 会话隔离 | `SchedulerTest.callers cannot see or use each other's sessions…` |
| 第三方 App 的配额：同时一个、文字上限、每小时滑动窗口、各 App 互不影响、AgentOS 自己不受限、用量只回调一次 | `CallerQuotaTest`、`ThirdPartyAcpTest`（经 SDK 客户端） |
