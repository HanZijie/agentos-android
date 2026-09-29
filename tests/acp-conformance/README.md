# tests/acp-conformance：ACP 一致性测试

用**官方 TypeScript ACP 客户端**（`@agentclientprotocol/sdk` 1.4.0，与 `reference/pi-acp-adapter` 一致）连接 AgentOS 运行时，验证它对外是一个标准的 ACP v1 Agent，并验证 AgentOS Profile 的扩展（`core/protocol/acp-mapping.md`、`acp-extensions.schema.json`）和电脑端接入的配对握手（acp-mapping.md 第 10 节）。

| 阶段 | 被测对象 | 传输 | 状态 |
|---|---|---|---|
| W4 | 电脑上的运行时（`org.agentos.runtime.testing.AcpStdioAgent`：完整的 `RuntimeEngine` + 真实 SQLite + ACP Agent 端），Agent 循环是 FakeAgentCore | stdio，`LineTransport` | ✅ 14 例（`conformance.test.mjs`，目标 `stdio`） |
| W9 | 同上，以网关模式运行（与手机上同一份 `DesktopGatewayCore`：开关、配对码、令牌、握手；监听本机 TCP 代替抽象 socket） | `tools/acp-bridge --connect` | ✅ 14 例（目标 `gateway`）+ 配对与安全 9 例（`gateway.test.mjs`） |
| W9（C4 之后） | 手机上的 `:agent`（debug 包；真实 Pi），模型是电脑上的 FakeModelServer 经 `adb reverse` | `tools/acp-bridge`：`adb forward tcp:0 localabstract:agentos-acp` | ✅ 一致性 14 例中 10 例（4 例依赖工具、确认或 Jev，跳过）+ 设备专属 3 例 + 配对与安全 9 例（+1 例 todo：freezer），模拟器 API 36 |
| W4（B2 之后） | 电脑上的运行时，Agent 循环换成 B lane 的 PiAdapter（真实 Pi，跑在电脑上的 QuickJS 里）+ 假模型端点（FakeModelServer，Anthropic Messages / OpenAI Chat Completions 两个协议族） | stdio 和 gateway | ✅ 同样的 14 例 + 配对安全 9 例（`AGENTOS_ACP_CORE=pi`） |

## 运行

```bash
# 需要 JDK 21（JAVA_HOME）和 Node ≥ 22.19
cd tests/acp-conformance
npm ci
npm test
```

`npm test` 跑电脑上的全部用例（目标 `stdio` 和 `gateway`，以及本机网关的配对与安全用例），不需要设备。`AGENTOS_ACP_TARGETS=stdio` 只跑其中一种目标。

### 真实 Pi + 假模型端点

```bash
(cd ../../core/pi-runtime && npm ci && node build.mjs)   # 生成 app/src/main/assets/pi-agent.js、model-catalog.json
AGENTOS_ACP_CORE=pi npm test                             # 模型协议族：Anthropic Messages（MiniMax 预设）
AGENTOS_ACP_CORE=pi AGENTOS_ACP_PI_API=openai npm test   # OpenAI Chat Completions（自定义端点）
```

电脑上的运行时换成 B lane 的 PiAdapter（pi-agent.js 跑在 quickjs-kt-jvm 里，与 Android 同一个绑定），模型请求发往同一进程里的 FakeModelServer：它按同样的 `{"fake":…}` 指令回应（文字、thinking、工具调用、等待中止、max_tokens、各类失败），所以用例不用改。两种 core 的已知差别只有一处：模型调用一个从来没有声明过的工具时，FakeAgentCore 交给宿主层拒绝（`[agentos:tool_not_in_catalog]`），真实 Pi 不调 beforeToolCall、自己返回 `Tool <name> not found`；两者都没有执行它，状态都是 `failed`，用例按这个性质检查。

`npm test` 第一次运行时会调用 `../../gradlew :core:runtime:acpConformanceClasspath`，生成 `core/runtime/build/acp-conformance/classpath.txt`。改了 `core/runtime` 的代码之后，重新运行这个 Gradle 任务（或设置 `AGENTOS_ACP_REBUILD=1`）。也可以用 `AGENTOS_ACP_CLASSPATH` 直接给出类路径。

### 手机（W9）

```bash
# 设备上装 debug 包（开关和配对码经 debug 包的测试入口 DesktopGatewayDebugReceiver 操作）。
# 一定带 ANDROID_SERIAL：不带时 :app:installDebug 会装到所有连着的设备上
(cd ../../core/pi-runtime && npm ci && node build.mjs)
ANDROID_SERIAL=<serial> ../../gradlew -p ../.. :app:installDebug
AGENTOS_ACP_DEVICE=<serial> npm run test:device
```

设了 `AGENTOS_ACP_DEVICE` 时，一致性用例的默认目标是 `device`：手机上 `:agent` 里的真实 Pi 经 `tools/acp-bridge`（`adb forward` → `localabstract:agentos-acp`）对外。模型是电脑上的 B 的 FakeModelServer（`FakeModelServerMain`，按同样的 `{"fake":…}` 指令回应），经 `adb reverse tcp:18787 tcp:<端口>` 映射到手机的 `127.0.0.1:18787`；手机上的模型来源是 C 的测试端点（`tests/device/acp-channel/inapp` 的 `ensureTestModel`：`http://127.0.0.1:18787`、`fake-model`、测试 key），不是时借 C 的 `AgentScenarioActivity` 设置（key 在它的代码里，不经 adb 命令行）。

手机上 M1 没有这些，依赖它们的用例在 `device` 目标上跳过（原因写在 `agent.mjs` 的 `SKIP`，测试输出里也有）：

| 用例 | 依赖 | 归属 |
|---|---|---|
| a tool call shows up as tool_call, then tool_call_update in_progress and completed | 工具（电脑上是 AcpStdioAgent 注册的 `add`） | W14 插件工具 / W15 MCP |
| a write tool is confirmed by AgentOS (not by the client) and runs | 工具 + 确认（`send_note`，确认自动通过） | W14 / W15，W16 风险策略与确认 |
| the tool round limit maps to max_turn_requests | 工具（连续 13 轮工具调用） | W14 / W15 |
| creates a session, then selects it again, and reports the selection in _meta | Jev（电脑上是 `--jev=first`） | 手机上的 Jev 接线尚未分配工作包 |

“目录外的工具”一例在手机上照常跑：模型调用的工具从来没有声明过，真实 Pi 自己返回 `Tool <name> not found`。

`test:device` 还跑 `device.test.mjs`（设备专属：连接身份是 adbd 的 uid 2000、70,000 字符的模型输出经网关完整送达、手机发出的每一行格式与大小）和 `gateway.test.mjs`（本机网关和手机各一遍：开关关闭时没有应答、握手前的 ACP 消息得到 `auth_required` 并断开、错误配对码、5 次作废、过期、一次性、令牌重连与关开关作废、超长行、握手超时）。

**已知问题（todo 用例）**：电脑端接入打开、`:agent` 空闲时是 cached 进程，约 10 秒后被 cached-apps freezer 冻结；这时电脑端的新连接和已建立会话的请求都得不到服务，直到别的事件解冻进程。`gateway.test.mjs` 的 “the gateway still answers after the phone has been idle for 15 s” 在手机上复现它（标为 todo，不让整组失败）；握手超时用例在等待期间用 status 广播保持进程解冻，只测网关自己的计时。

debug 包的测试入口也可以手动用：

```bash
adb shell am broadcast -f 32 -n org.agentos.app/.agent.DesktopGatewayDebugReceiver --es op enable   # 或 disable、pair、status、revoke_all
```

### 单独启动电脑端 Agent 进程

电脑端 Agent 进程可以单独启动，用任何 ACP 客户端以子进程方式连接：

```bash
java -Dkotlin-logging-to-jul=true -cp "$(cat core/runtime/build/acp-conformance/classpath.txt)" \
  org.agentos.runtime.testing.AcpStdioAgent [--jev=first] [--db=<目录>] [--consent=allow|deny] \
  [--core=fake|pi] [--pi-assets=<目录>] [--pi-api=anthropic|openai]
```

加 `--listen=<端口>`（0 表示任选）以网关模式运行：stdin / stdout 变成控制通道（`{"op":"enable"}`、`{"op":"pair"}`、`{"op":"status"}`…，每行一条 JSON），ACP 客户端经 `tools/acp-bridge --connect 127.0.0.1:<端口>` 连进去。

stdout 上只有 JSON-RPC，日志走 stderr。ACP SDK 0.30.1 在 JVM 上依赖 kotlin-logging，必须设置 `-Dkotlin-logging-to-jul=true`，否则缺 slf4j 会在第一次记日志时崩溃（`AcpStdioAgent` 没设置时会自动补上）。

## 用例

Agent 循环是 FakeAgentCore 时，prompt 里的 JSON 指令决定它的行为（`FakeScripts.directives()`），例如 `{"fake":{"chunks":30,"chunkChars":4,"text":"abcd"}}`、`{"fake":{"tools":[{"name":"add","arguments":{"a":2,"b":3}}]}}`、`{"fake":{"awaitAbort":true}}`、`{"fake":{"fail":"model_rate_limited"}}`、`{"fake":{"toolLoop":13}}`；其他文字原样回显。

- `initialize`：ACP v1、能力声明、`_meta."org.agentos"`（Profile 版本、安全等级、扩展）；
- `session/new` → `session/prompt`：`agent_message_chunk` 流式输出、合并、`end_turn`、响应里的任务 ID；
- 工具：`tool_call`（pending）→ `tool_call_update`（in_progress → completed）；write 级工具由 AgentOS 确认；目录外的工具直接 `failed`；
- `session/cancel` → `cancelled`，会话随后照常可用；
- 失败 → JSON-RPC 错误 -32051，`data.agentosCode`、`retryable`；`max_tokens`；`max_turn_requests`（工具轮次上限 12）；
- 不支持的输入明确拒绝：`mcpServers`、图片、`session/load`；`resource_link` 和嵌入的文字资源可以用；
- 线上格式：每行一条 JSON-RPC 2.0；长输出切分后单行不超过 65,536 字符；
- 自动选会话扩展：没协商时拒绝；协商后第一次新建、第二次选中已有会话，结果在 `session_info_update` 的 `_meta` 里。

线上格式：电脑上的 stdio 和网关都用 `LineTransport`（手机上的网关用 C 的 `JsonRpcCodec` 编码，电脑上用同样实现的 `TestLineCodec`），不再经过 SDK 的 `StdioTransport`（它每条多一个 `"type"` 字段，S3 问题 3）；“每行一条消息”这条用例已收紧为不允许任何多余字段，手机上同样检查。
