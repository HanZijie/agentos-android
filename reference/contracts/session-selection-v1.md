# Session 自动选择契约 v1

本文补充 [Session Scheduling Contract v1](session-scheduling-v1.md)，冻结
“用户提交一个新 query 时，如何选择已有 Session”的语义。它只描述
`sideagentd` 的参考数据面；前端不能自己复制一份选择事实。

## 1. 范围和不变量

- 活跃池按 `userId` 隔离。一个用户不能看到另一个用户的 Session brief，
  也不能让 Jev 为另一个用户返回的 id 被接受。
- Session 的持久化记录是事实来源。活跃池只是从 Store 重建的有界视图，
  daemon 重启后不会依赖进程内缓存恢复。
- 活跃 Session 必须满足：状态不是 `completed`/`failed`、已经有首个 query，
  且 `lastActivityAt >= now - 30 分钟`。超过这个窗口后，Session 会立即从
  **active pool** 移除，不再计入活跃数量；它的持久化记录不会被删除。
- 默认最多保留 254 个活跃 Session。选择列表最后始终附带固定的
  `choiceId = "new_session"`，所以 Jev Choice 的最大列表长度是
  `254 + 1 = 255`。这正好处于 Jev 支持的 255 个 Choice 上限内。
- 活跃池按 `lastActivityAt` 降序排列；超过上限时保留最近的 Session。
  超出的旧 Session 仍在 Store 中，之后重新活跃即可再次进入池。
- 为避免用户只有少量活跃会话时选择上下文过窄，选择请求默认补足最近
  20 个非终态 Session。补足项是 `stale` 冷候选，不属于 active pool，
  不会延长 30 分钟 TTL，也不会计入活跃数量；总候选仍不超过 254 个。

30 分钟和 254 是产品/系统配置，不是 Jev 自动提供的限制。实现允许把
`maxActiveSessions` 调小，但不能配置到 254 以上，以免破坏 255 Choice
上限。

## 2. Brief

每个候选 Session 的 `brief` 只用于本次选择请求，不写入公开事件。它由
Session 侧已经持久化的选择元数据生成，包含以下证据（字段为空时省略）：

1. 首轮用户 query；
2. 首轮完成时的 AI final answer（如果已完成）；
3. 最近一次 AI final answer；
4. 最近两轮已完成的 query 和 answer。

`brief` 和新 query 会按配置的 Jev 输入 token 预算截断。Brief 是不可信的
用户/模型历史文本，Jev instructions 明确要求把它当证据而不是指令；它不能
改变 Choice schema 或权限。参考实现使用
UTF-8 字节估算作本地上界；真正的 Jev adapter 仍必须以其 tokenizer 和服务端
限制再次校验。截断不会改变 `choiceId`，预算不足时宁可去掉 brief 文本，
也不能删除固定的 `new_session` 入口。如果所有候选的原始 Brief 加起来仍
超过预算，选择器会先按能容纳的候选批次调用 Jev，再把每批赢家和
`new_session` 做最终一轮选择；中间批次不会包含新建入口。

Session 的 `lastActivityAt` 在输入入队、Agent 输出事件和完成时更新。输出
文本只收集 `agent_message*` 事件，工具参数、凭据和 Plugin 私有数据不进入
brief。

## 3. Jev Choice 请求

`SessionSelector` 将候选列表映射为 Jev System One 的 Choice question：

```json
{
  "state": "本次用户 query",
  "model": "jev-1.13.0",
  "questions": {
    "session": {
      "type": "choice",
      "instructions": "Choose the existing Session ... Choose new_session when none matches.",
      "criteria": {
        "<session-id>": "First query: ...",
        "new_session": "Start a new Session"
      }
    }
  }
}
```

Jev 返回 `answers.session.choice` 后，服务端只接受本次请求中出现的精确
`choiceId`。模型不能创造 Session id，也不能扩大候选范围。`new_session`
被选中时，服务端先持久化创建 Session，再把输入写入新 Session 的队列。

参考客户端默认读取以下未跟踪的运行时配置：

```text
AGENTOS_JEV_API_KEY       # 必填；也兼容 TYPESAFE_API_KEY
AGENTOS_JEV_ENDPOINT      # 默认 https://omnilabs.vibeadmin.cn/v1/systemone
AGENTOS_JEV_MODEL         # 默认 jev-1.13.0
AGENTOS_JEV_TIMEOUT_MS    # 默认 1500
AGENTOS_JEV_INPUT_TOKENS  # 默认 16384；本地输入上界
AGENTOS_JEV_OUTPUT_TOKENS # 默认 64；选择器适配器输出上界
AGENTOS_JEV_MIN_RECENT_SESSIONS # 默认 20；冷候选补足目标
```

密钥不能写入 Git、AOSP checkout、事件日志、Session body 或通用系统镜像。
设备部署应通过受权限保护的 secret 注入给 `sideagentd`（例如设备上的受控
环境文件或等价的 SecretStore）；镜像只携带读取入口和默认非敏感配置。

参考实现设置了 `maxInputTokens = 16384`、`maxOutputTokens = 64`。它们是
AgentOS 的本地预算，不会伪造 Jev API 不接受的顶层字段；调整预算必须同时
重新评估 254 个候选 brief 是否能保留足够信息。

如果产品要求“30 分钟后完全不再交给 Jev”，将
`AGENTOS_JEV_MIN_RECENT_SESSIONS=0`；默认值 20 只影响冷候选补足，不改变
active pool 的 30 分钟过期规则。

## 4. 回退、错误和恢复

以下情况不应阻塞用户提交：未配置密钥、HTTP/网络错误、超时、非法 JSON、
非法 Choice 或输入预算不足。`SessionSelector` 返回
`selectionMethod = "fallback_new_session"` 和稳定的 `fallbackReason`，然后
使用固定 `new_session` 创建新 Session。回执仍标记为已持久化；模型失败不会
重试一个可能产生副作用的 Agent Task。

如果 Jev 已选择已有 Session，提交前服务端再次检查该 Session 的 `userId`
和非终态状态。竞态或权限失败必须拒绝提交，不能把输入写入其他用户的
Session。

前端断线、daemon 重启和 Jev 回退都不会关闭或删除已有 Session。活跃池在
下一次选择时从 Store 重建；旧 Session 过期后不再属于 active pool，但在未
达到最近 20 个候选时可以作为标记为 `stale` 的冷候选参与选择。

## 5. 验证要求

Reference tests 至少覆盖：

1. 30 分钟边界、每用户隔离和 254 + `new_session` 的 255 Choice 上限；
2. Brief 的首轮 query、AI final answer、最近两轮 query/answer 和 token 截断；
3. Jev Choice 的精确 id 校验、new-session 分支和已有 Session 分支；
4. 无密钥、HTTP 错误、超时、非法响应的安全回退；
5. 请求体不包含 API key，且密钥不进入 Session Store、事件或日志。
