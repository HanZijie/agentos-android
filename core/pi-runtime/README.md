# core/pi-runtime：打包进 APK 的 Pi Agent core

把上游 `@earendil-works/pi-agent-core` 和 `@earendil-works/pi-ai`（0.86.1，按 `package-lock.json` 锁定）打成一个文件，由 `:agent` 进程里的 QuickJS 执行。设计依据见 [docs/spikes/S8.md](../../docs/spikes/S8.md)，架构见 [docs/architecture.md](../../docs/architecture.md) 4.1 节。

- `pi-ai` 只含 `anthropic-messages`、`openai-completions` 两个协议族及其官方 SDK；
- 官方 SDK 和 `pi-ai` 里引用 Node 内置模块的三个文件由 `src/node-stubs/` 替换，出现其他 `node:*` 引用时构建失败；
- JS 里不做 I/O：网络、定时器、工具都经宿主原语回到 Kotlin；模型 key 只在 Kotlin 的 `HostFetch` 里注入，JS 只见到占位符 `agentos-host-injected-key`；`pi-ai` 以 `maxRetries: 0` 调用，重试只由宿主层做。

## 构建与测试

```bash
cd core/pi-runtime
npm ci
node build.mjs            # 默认 es2022 + 压缩；--no-minify 便于看调用栈
npm test                  # Node 裸 vm 上下文里的契约测试（只有 ECMAScript 内置对象，与 QuickJS 等价）
npm run test:quickjs      # 同一个 bundle 在 QuickJS（quickjs-kt-jvm）上的测试：./gradlew :core:runtime:test --tests 'org.agentos.runtime.pi.*'
```

| 产物 | 位置 | 说明 |
|---|---|---|
| `pi-agent.js` | `app/src/main/assets/` | 单文件 IIFE，文件头注明协议版本和依赖版本。生成物，不进仓库 |
| `model-catalog.json` | `app/src/main/assets/` | 厂商预设，格式见下文。生成物，不进仓库 |
| `build-report.json`、`meta.json` | `core/pi-runtime/build/` | 体积（按包）、SHA-256、被替换的模块、跳过的厂商 |

`:app` 的 `bundlePiAgent` 任务在构建 APK 前执行 `node build.mjs`。`core:runtime` 的 QuickJS 测试读取 `app/src/main/assets/` 里的产物，没有生成时跳过。

升级 `pi-agent-core` / `pi-ai` 要单独评估：改 `package.json` 与锁文件，重跑 `npm test`、`npm run test:quickjs` 和 S8 的设备用例（`spikes/S8/`）。

## 协议 v1

`src/host-bridge.js` 里的 `PROTOCOL_VERSION` 与 Kotlin 侧的 `PI_PROTOCOL_VERSION`（`core/runtime/.../pi/PiRuntime.kt`）必须相等，不相等时 `PiRuntime.start` 失败。不兼容的改动要同时加一。

### 执行模型（常驻泵）

宿主求值 bundle 后，只再求值一次 `__pi_main()`。它发出 `ready`，然后循环从 `__host_next()` 取命令，每条命令异步处理、互不等待，所以多个会话、以及对进行中会话的 `abort` 能同时推进。不要为每个操作单独求值：quickjs-kt 会把同一实例上的顶层求值串行化。

### 宿主原语

| 全局函数 | 同步 / 异步 | 作用 |
|---|---|---|
| `__host_emit(json)` | 同步 | JS → 宿主的所有消息：`ready`、`reply`、`event`、`tool_cancel`、`log` |
| `__host_next()` | 异步 | 下一条命令（JSON 字符串）；返回 null 表示关闭 |
| `__host_timer(id, ms)` / `__host_timer_cancel(id)` | 异步 / 同步 | 真实定时器，结果为 `"fired"` 或 `"cancelled"` |
| `__host_fetch(reqId, requestJson)` | 异步 | 发请求，返回响应头 JSON |
| `__host_fetch_read(reqId)` | 异步 | 下一段响应体（Uint8Array），结束时 null |
| `__host_fetch_abort(reqId)` | 同步 | 取消请求并关闭连接 |
| `__host_call(method, payloadJson)` | 异步 | 工具与钩子：`tool`、`beforeToolCall`、`afterToolCall` |

- 请求 JSON：`{url, method, headers: [[name, value]...], body: string|null, sid: string|null}`，`sid` 是发起请求的会话；
- 响应头 JSON：`{status, statusText, headers: [[name, value]...], url}`。

### 命令与回复

每条命令 `{id, op, ...}` 恰好得到一个回复 `{t: "reply", id, ok, value | error: {message, name, stack}}`。

| op | 参数 | 回复 |
|---|---|---|
| `ping` | — | `{protocol, apis}` |
| `create` | `sid, model, systemPrompt?, tools?, messages?, thinkingLevel?` | `{sid, messages}`。传入保存的 `messages` 即恢复会话 |
| `prompt` | `sid, text, images?` | Pi 的 `agent_end` 之后回复 `{stopReason, errorMessage?, text, usage?, messageCount, appended}`；`usage` 是最后一条 assistant 消息的 `usage`；`appended` 是本轮新增的消息，按顺序追加保存即可 |
| `abort` | `sid` | `{}`；进行中的 `prompt` 随后以 `stopReason: "aborted"`（或工具执行中被打断时的 `"toolUse"`，见下文）回复 |
| `setTools` | `sid, tools` | `{tools}`，下一次模型请求生效 |
| `setModel` | `sid, model, thinkingLevel?` | `{}` |
| `setSystemPrompt` | `sid, systemPrompt` | `{messageCount}`；追加一条 system 消息，替换 `agentos` 段。进行中的 `prompt` 期间调用会失败 |
| `history` | `sid` | 完整消息数组，第一条是 `role: "system"` |
| `dispose` | `sid` | `{disposed}` |
| `stats` | — | `{sessions, inflightFetches, activeTimers}` |

`stopReason` 取 Pi 的值：`stop`、`toolUse`、`length`、`aborted`、`error`。

`tools` 的每一项：`{name, description, parameters: <JSON Schema>, label?, executionMode?}`，默认顺序执行。

### system prompt

新会话的第一条消息是 `{role: "system", content: "", sections: {agentos: <systemPrompt>}, toolsAdded?}`：宿主的 prompt 放在名为 `agentos` 的段里，不放在 `content`。`setSystemPrompt` 追加一条只含 `agentos` 段的 system 消息；`pi-ai` 按段合并、后出现的值覆盖前面的，所以效果是替换而历史保持只追加。恢复会话时原样传回全部消息即可。

### abort

`abort` 触发 Pi 的 `AbortSignal`，并给正在执行的工具发 `tool_cancel`。会话用 `shouldStopAfterTurn: signal.aborted` 在下一个轮次边界结束，abort 之后不会再发模型请求：

- 模型流式输出中 abort：最后一条 assistant 消息 `stopReason: "aborted"`；
- 工具执行中 abort：最后一条 assistant 消息仍是 `stopReason: "toolUse"`，其后每个工具调用都有一条 `toolResult`（被打断的为 `isError: true`），不再有新的 assistant 消息。宿主据“abort 已请求”判定本轮为中止。

Pi 0.86.1 在 abort 后仍会对已完成的工具调用 `afterToolCall`；`beforeToolCall` 拦截的调用不会再 `afterToolCall`。

### 事件

`{t: "event", sid, e}`，`e` 是压缩过的 Pi 生命周期事件（按发生顺序）：

| `e.type` | 字段 |
|---|---|
| `agent_start`、`turn_start` | — |
| `message_start` | `role` |
| `message_update` | `role`，`update: {type, contentIndex, delta?, toolCall?, reason?}`；`type` 为 `text_delta`、`thinking_delta`、`toolcall_end`、`done`、`error` |
| `message_end` | `message`（完整消息） |
| `tool_execution_start` | `toolCallId, toolName, args` |
| `tool_execution_update` | `toolCallId, toolName, partialResult` |
| `tool_execution_end` | `toolCallId, toolName, result, isError` |
| `turn_end` | `stopReason, errorMessage?, toolResults`（条数） |
| `agent_end` | `messages`（条数） |

`message_update` 只带增量，不带整条 partial message。不过桥的增量类型（信息已被 `message_start` / `message_end` / `toolcall_end` 覆盖，见 `core/contracts/events.md` 第 3 节）：`start`、`text_start`、`text_end`、`thinking_start`、`thinking_end`、`toolcall_start`、`toolcall_delta`。Kotlin 侧由 `org.agentos.runtime.pi.PiEventMapper` 映射为 `AgentEvent`。

### 宿主调用

| method | 参数 | 返回 |
|---|---|---|
| `tool` | `sid, toolCallId, name, args` | `{content: [...], details?, isError?}`；`isError: true` 时 Pi 按工具失败处理 |
| `beforeToolCall` | `sid, toolCallId, name, args` | `{block: true, reason?, terminate?}` 拦截；`{}` 放行 |
| `afterToolCall` | `sid, toolCallId, name, args, result, isError` | 部分覆盖 `{content?, details?, isError?, terminate?}`；`{}` 不改 |

会话 `abort` 时正在执行的工具会收到 `{t: "tool_cancel", sid, toolCallId}`，宿主尽力取消（F6）。

## `model-catalog.json`（schemaVersion 1）

由 `build.mjs` 调用 `pi-ai` 各厂商的 provider 工厂导出：只保留两个协议族、支持普通 API key 的厂商；MiniMax 国际（`minimax`）、国内（`minimax-cn`）固定排在最前，其余按 id 排序。Kotlin 侧用 `org.agentos.runtime.pi.ModelCatalog` 解析。

```jsonc
{
  "schemaVersion": 1,
  "generatedFrom": { "@earendil-works/pi-ai": "0.86.1" },
  "apis": ["anthropic-messages", "openai-completions"],
  "customTemplates": {
    // 自定义兼容端点：宿主填 id、name、baseUrl，可覆盖 contextWindow / maxTokens / reasoning / input
    "anthropic-messages": { "api": "anthropic-messages", "provider": "custom", "reasoning": false, "input": ["text", "image"],
                            "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 }, "contextWindow": 128000, "maxTokens": 8192 },
    "openai-completions": { ... }
  },
  "providers": [
    {
      "id": "minimax",
      "name": "MiniMax",
      "apis": ["anthropic-messages"],
      "baseUrls": ["https://api.minimax.io/anthropic"],
      "auth": { "type": "api-key", "label": "MiniMax API key" },
      "models": [
        { "id": "MiniMax-M2.7", "name": "MiniMax-M2.7", "api": "anthropic-messages", "provider": "minimax",
          "baseUrl": "https://api.minimax.io/anthropic", "reasoning": true, "input": ["text"],
          "cost": { "input": 0.3, "output": 1.2, "cacheRead": 0.06, "cacheWrite": 0.375 },
          "contextWindow": 204800, "maxTokens": 131072 }
      ]
    },
    { "id": "minimax-cn", "baseUrls": ["https://api.minimaxi.com/anthropic"], ... }
  ]
}
```

- `models[]` 是 `pi-ai` 的 `Model` 对象，原样作为 `create` 的 `model` 传入。其中可能有 `compat`、`thinkingLevelMap`、`headers`、`promptCache` 等字段，界面不需要理解，原样保存；
- 设置页展示 `providers[].name`、`models[].name`、`contextWindow`、`maxTokens`、`reasoning`、`input`，用户只填 key；
- key 与模型的 `baseUrl` 绑定，由宿主注入（`org.agentos.runtime.net.BaseUrlCredentials`），不写进目录或模型对象；
- 自定义端点的 `baseUrl`：OpenAI Chat Completions 填到 `/v1` 这一级（SDK 追加 `/chat/completions`），Anthropic Messages 填 API 根（SDK 追加 `/v1/messages`），例如 `https://api.minimax.cn/anthropic`；
- 导出时跳过：`cloudflare-ai-gateway`、`cloudflare-workers-ai`（baseUrl 需要账号 ID），`github-copilot`（订阅账号登录，首版不做，F9）。`pi-ai` 0.86.1 的 `openai` 厂商全部走 `openai-responses`，不在目录里；要接 OpenAI 官方，用自定义兼容端点 + OpenAI Chat Completions；
- 模板的 `reasoning: false` 只决定请求里是否带 `thinking` 参数，不影响 thinking 块的接收和回传。用 MiniMax-M2.7 实测：模板默认值和 `reasoning: true` 下，对话、跨轮上下文、工具调用都正常，thinking 块照常流式到达并在下一轮原样回传。

`schemaVersion` 只在不兼容的改动时加一；新增字段不加。
