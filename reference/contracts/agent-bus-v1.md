# Agent Bus v1

Agent Bus 是 Android 系统 Agent 与多个前端之间的逻辑协议。它定义连接、Session、输入、输出事件、重连和错误语义，不绑定某一种传输方式。

当前设计参考了：

- [Pi Agent Loop](https://github.com/earendil-works/pi/blob/main/packages/agent/src/agent-loop.ts)：Agent、turn、message、tool 的事件生命周期；
- [Pi RPC mode](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/rpc.md)：JSONL 请求、响应和异步事件；
- [DeepSeek Harness SDK protocol](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/sdk/protocol/README.md)：JSON-RPC 2.0、初始化握手、持久化入队回执和 Session 事件通知。

Agent Bus 不直接复制这些项目的私有协议。Pi 的 RPC 面向父进程驱动单个 Agent，DeepSeek Harness 的 SDK 协议面向 Harness runtime 客户端；Agent Bus 还必须处理 Android 的用户身份、权限、多前端订阅、事件游标和系统级恢复。

Session 的状态机、调度、公平性、并发上限、取消、超时、重启恢复和 Plugin capability lease 由配套的 [Session Scheduling Contract v1](session-scheduling-v1.md) 冻结。本文只定义消息名称和传输无关的调用形状；两份契约必须一起实现。

## 1. 传输和分层

Agent Bus 先定义与传输无关的逻辑消息，再由不同部署提供适配器：

```text
Agent Bus logical protocol
        ├── JSON-RPC 2.0 over JSONL   # CLI 和本地 reference 测试
        ├── AIDL/Binder adapter        # Android 系统部署
        └── Unix socket adapter        # sideagentd 内部测试
```

JSONL 是开发和协议测试的 reference transport；Android 生产实现使用稳定 AIDL/Binder，但必须保留相同的请求、响应、事件和重连语义。

协议名称：

```text
agent-bus/1
```

JSON-RPC 请求：

```json
{"jsonrpc":"2.0","id":"1","method":"initialize","params":{}}
```

服务端通知没有 `id`：

```json
{"jsonrpc":"2.0","method":"session/event","params":{}}
```

## 2. 身份和初始化

客户端发送：

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "method": "initialize",
  "params": {
    "protocolVersions": ["agent-bus/1"],
    "clientInfo": {
      "id": "agenriod.frontend",
      "version": "0.1.0"
    },
    "capabilities": {
      "streaming": true,
      "resume": true,
      "images": true
    }
  }
}
```

服务端响应：

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "result": {
    "protocolVersion": "agent-bus/1",
    "serverInfo": {
      "name": "sideagentd",
      "version": "0.1.0"
    },
    "connectionId": "connection-123",
    "capabilities": {
      "streaming": true,
      "resume": true,
      "coalescedDeltas": true
    }
  }
}
```

生产环境中的 `userId`、包名、UID、签名和权限不能由 JSON 自己声明。它们来自 Binder calling UID 和系统连接上下文。JSONL 测试客户端可以使用显式的 test identity。

## 3. Session 方法

### 3.0 自动选择已有 Session

前端没有可靠的当前 `sessionId` 时，可以提交 `session/prompt-auto`。生产
Binder 连接中的 `userId` 来自调用身份，不由 JSON 自己声明；reference
transport 才允许显式 test identity。

```json
{
  "jsonrpc": "2.0",
  "id": "4-auto",
  "method": "session/prompt-auto",
  "params": {
    "clientRequestId": "request-456",
    "content": [{"type": "text", "text": "继续处理刚才的构建问题"}]
  }
}
```

`sideagentd` 从本用户 30 分钟活跃池生成候选 Brief，调用 Jev Choice，并
严格校验返回的 `choiceId`。返回值仍然只是 durable enqueue receipt，并增加
选择证据：

```json
{
  "accepted": true,
  "sessionId": "session-123",
  "taskId": "task-001",
  "selection": {
    "choiceId": "session-123",
    "created": false,
    "selectionMethod": "jev"
  }
}
```

固定 `choiceId = "new_session"` 会先创建 Session，再把同一个请求入队。
Jev 未配置、超时、服务错误、输入预算不足或返回非法 id 时，服务端安全地
选择该固定入口，并在 `selection.fallbackReason` 中报告原因；不会把输入
写入其他用户的 Session。候选池和 Brief 的完整规则见 [Session Selection
Contract v1](session-selection-v1.md)。

### 3.1 创建 Session

```json
{
  "jsonrpc": "2.0",
  "id": "2",
  "method": "session/create",
  "params": {
    "frontendId": "agenriod.frontend",
    "metadata": {
      "title": "System Agent"
    }
  }
}
```

响应：

```json
{
  "jsonrpc": "2.0",
  "id": "2",
  "result": {
    "sessionId": "session-123",
    "createdAt": 1770000000000,
    "currentSequence": 0,
    "access": "controller"
  }
}
```

`controller` 可以提交输入；`observer` 只能订阅输出。

### 3.2 订阅输出

```json
{
  "jsonrpc": "2.0",
  "id": "3",
  "method": "session/subscribe",
  "params": {
    "sessionId": "session-123",
    "afterSequence": 0,
    "delivery": "all"
  }
}
```

响应：

```json
{
  "jsonrpc": "2.0",
  "id": "3",
  "result": {
    "subscriptionId": "sub-123",
    "replayFrom": 1,
    "currentSequence": 0
  }
}
```

订阅建立后，服务端按 Session sequence 递增发送事件。前端断线后，使用上一次收到的 `sequence` 重新订阅。

`delivery` 支持：

- `all`：发送全部可重放事件；
- `coalesced`：允许合并连续的 `message_update` 和工具进度事件，但必须保留最终的 `message_end`、`tool_execution_end` 和任务状态。

### 3.3 提交 Prompt

```json
{
  "jsonrpc": "2.0",
  "id": "4",
  "method": "session/prompt",
  "params": {
    "sessionId": "session-123",
    "clientRequestId": "request-456",
    "content": [
      {
        "type": "text",
        "text": "检查系统状态"
      }
    ]
  }
}
```

响应只表示输入已持久化并进入队列，不等待 Agent 完成：

```json
{
  "jsonrpc": "2.0",
  "id": "4",
  "result": {
    "accepted": true,
    "messageId": "message-789",
    "taskId": "task-001",
    "deduplicated": false
  }
}
```

`clientRequestId` 用于幂等。客户端超时后重发同一个请求，不应重复创建任务。

`content` 第一版允许：

```text
text
image
file_ref
```

`file_ref` 使用系统授权的 URI 或 capability reference，不能直接携带任意系统路径。

### 3.4 Steering 和 Follow-up

保留 Pi 的两种不同语义：

```text
session/steer       当前工具阶段结束后改变方向
session/follow_up  当前 Agent 完成后追加请求
```

二者都返回 `messageId` 和 `taskId`，不能简单当作普通的第二次 Prompt。

### 3.5 取消

```json
{
  "jsonrpc": "2.0",
  "id": "5",
  "method": "session/abort",
  "params": {
    "sessionId": "session-123",
    "taskId": "task-001"
  }
}
```

取消请求不代表任务已经停止。最终结果必须通过事件确认：

```text
task.cancel_requested
task.cancelled
```

### 3.6 其他方法

v1 保留：

```text
session/snapshot
session/unsubscribe
session/detach
session/close       有权 controller 或系统控制面显式关闭 Session
ping
```

前端只有 `detach`，没有 daemon `shutdown`。`session/close` 只关闭一个 Session，必须遵守 [Session Scheduling Contract v1](session-scheduling-v1.md)；前端断开不能关闭系统 Agent，也不能隐式关闭 Session。

## 4. 输出事件

事件统一包裹在 `session/event` 中：

```json
{
  "jsonrpc": "2.0",
  "method": "session/event",
  "params": {
    "sessionId": "session-123",
    "taskId": "task-001",
    "sequence": 12,
    "eventType": "message_update",
    "replayable": true,
    "timestamp": 1770000001000,
    "payload": {
      "assistantMessageEvent": {
        "type": "text_delta",
        "delta": "系统状态正常"
      }
    }
  }
}
```

`sequence` 在一个 Session 内单调递增。事件必须先写入 Event Store，再通知订阅者。`sessionId + sequence` 可以唯一定位一条事件。

第一版事件集合：

```text
agent_start
agent_end
agent_settled

turn_start
turn_end

message_start
message_update
message_end

tool_execution_start
tool_execution_update
tool_execution_end

queue_update
compaction_start
compaction_end
auto_retry_start
auto_retry_end

task.cancel_requested
task.cancelled
task.failed

调度契约另外要求持久化以下事件：

```text
session.state_changed
task.queued
task.started
task.completed
task.recovery_required
capability.lease_granted
capability.lease_revoked
```
```

需要区分：

- `agent_end`：一次底层 Agent run 结束；
- `agent_settled`：整个 Session 已经没有自动重试、压缩重试或排队 follow-up。

另外提供一个非持久化的状态提示：

```json
{
  "jsonrpc": "2.0",
  "method": "session/status",
  "params": {
    "sessionId": "session-123",
    "status": "running",
    "taskId": "task-001"
  }
}
```

`session/status` 只是当前状态提示；可恢复的事实必须存在于 `session/event` 中。

## 5. 错误

使用 JSON-RPC 标准错误结构，并增加 Bus 错误码：

```text
-32001 unauthenticated
-32002 forbidden
-32003 session_not_found
-32004 invalid_state
-32005 cursor_too_old
-32006 payload_too_large
-32007 plugin_unavailable
-32008 request_conflict
-32009 task_not_found
-32010 session_terminal
-32011 scheduler_backpressure
-32012 recovery_required
```

错误数据：

```json
{
  "code": -32004,
  "message": "Session is already settled",
  "data": {
    "retryable": false,
    "sessionId": "session-123"
  }
}
```

## 6. 与 Pi 和 DeepSeek Harness 的关系

### 从 Pi 保留

- Agent、turn、message、tool 的事件层次；
- `message_update` 中的文本、thinking 和 tool-call 增量；
- `steer` 与 `follow_up` 的区别；
- `agent_end` 与最终 settled 状态的区别。

### 从 DeepSeek Harness 保留

- JSON-RPC 2.0；
- JSONL reference transport；
- `initialize` 握手；
- Prompt 返回持久化入队回执；
- Session 事件异步通知；
- 协议类型与 Runtime 实现分离。

### Agent Bus 自己增加

- Android 系统身份和权限；
- 多前端订阅；
- Session 访问角色；
- `sequence` 和断线恢复；
- 前端 `detach` 而不是关闭系统 Agent；
- 事件先持久化再通知；
- 外部副作用的幂等和恢复语义。

## 7. v1 暂不包含

- Plugin 注册协议（已由 [Plugin Injection Contract v1](plugin-injection-v1.md) 单独冻结）；
- 模型 Provider 配置协议；
- 系统权限申请协议；
- 多 Agent 协作协议；
- 远程网络访问；
- AIDL 具体生成代码。

这些内容分别属于 Plugin、Capability 和平台管理协议，不应混入 Agent Bus 的核心 Session 流。

## 8. 第一批验证

协议实现前先完成 reference tests：

1. `initialize` 成功和版本协商；
2. `session/create` 返回 Session；
3. `session/prompt` 返回 durable enqueue receipt；
4. Fake Agent 发送完整事件序列；
5. 客户端断开后用 `afterSequence` 恢复；
6. 重复 `clientRequestId` 不重复创建任务；
7. 两个前端同时订阅同一个 Session；
8. `steer`、`follow_up` 和 `abort` 的状态差异；
9. 过旧游标返回 `cursor_too_old`；
10. 大量 delta 使用 `coalesced` 模式时仍保留最终消息和任务状态。
