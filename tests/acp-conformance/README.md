# tests/acp-conformance：ACP 一致性测试

用**官方 TypeScript ACP 客户端**（`@agentclientprotocol/sdk` 1.4.0，与 `reference/pi-acp-adapter` 一致）连接 AgentOS 运行时，验证它对外是一个标准的 ACP v1 Agent，并验证 AgentOS Profile 的扩展（`core/protocol/acp-mapping.md`、`acp-extensions.schema.json`）和电脑端接入的配对握手（acp-mapping.md 第 10 节）。

| 阶段 | 被测对象 | 传输 | 状态 |
|---|---|---|---|
| W4 | 电脑上的运行时（`org.agentos.runtime.testing.AcpStdioAgent`：完整的 `RuntimeEngine` + 真实 SQLite + ACP Agent 端），Agent 循环是 FakeAgentCore | stdio，`LineTransport` | ✅ 13 例（`conformance.test.mjs`，目标 `stdio`） |
| W9 | 同上，以网关模式运行（与手机上同一份 `DesktopGatewayCore`：开关、配对码、令牌、握手；监听本机 TCP 代替抽象 socket） | `tools/acp-bridge --connect` | ✅ 13 例（目标 `gateway`）+ 配对与安全 9 例（`gateway.test.mjs`） |
| W9 | 手机上的 `:agent`（debug 包；真 RuntimeEngine，Agent 循环暂时是 C 的 ScriptedAgentCore） | `tools/acp-bridge`：`adb forward tcp:0 localabstract:agentos-acp` | ✅ 握手类 6 例（`device.test.mjs`）+ 配对与安全 9 例，模拟器 API 36 |
| W4（B2 之后） | 电脑上的运行时，Agent 循环换成 B lane 的 PiAdapter（真实 Pi，跑在电脑上的 QuickJS 里）+ 假模型端点（FakeModelServer，Anthropic Messages / OpenAI Chat Completions 两个协议族） | stdio 和 gateway | ✅ 同样的 13 例 + 配对安全 9 例（`AGENTOS_ACP_CORE=pi`） |

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
# 设备上装 debug 包（开关和配对码经 debug 包的测试入口 DesktopGatewayDebugReceiver 操作）
ANDROID_SERIAL=<serial> ./gradlew :app:installDebug
cd tests/acp-conformance
AGENTOS_ACP_DEVICE=<serial> npm run test:device
```

`test:device` 跑 `device.test.mjs`（经 `tools/acp-bridge` 的握手类用例：initialize、session/new、prompt 流式、cancel、线上格式、70,000 字符输出的单行上限）和 `gateway.test.mjs`（本机网关和手机各一遍：开关关闭时没有应答、握手前的 ACP 消息得到 `auth_required` 并断开、错误配对码、5 次作废、过期、一次性、令牌重连与关开关作废、超长行、握手超时）。手机上的 Agent 循环在 Pi 接上之前是 C 的 ScriptedAgentCore（不调模型、没有工具，按 prompt 里的 JSON 脚本输出），不认 FakeAgentCore 的指令和测试工具，所以只跑握手类用例；完整的 13 例在电脑上的 `gateway` 目标里跑，走的是同一份网关代码。宿主层没有配置模型时任务以 `model_not_configured` 失败，所以 `device.test.mjs` 开始前如果设备上还没有可用的模型，会借 C 的设备测试执行器（`tests/device/acp-channel/inapp` 的 `AgentScenarioActivity`，debug 包里有）配一个回环地址上的测试端点（ScriptedAgentCore 不会去连它；key 是代码里的占位值，不经 adb 命令行）。

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
- 失败 → JSON-RPC 错误 -32051，`data.agentosCode`、`retryable`；`max_tokens`、`max_turn_requests`（工具轮次上限 12）；
- 不支持的输入明确拒绝：`mcpServers`、图片、`session/load`；`resource_link` 和嵌入的文字资源可以用；
- 线上格式：每行一条 JSON-RPC 2.0；长输出切分后单行不超过 65,536 字符；
- 自动选会话扩展：没协商时拒绝；协商后第一次新建、第二次选中已有会话，结果在 `session_info_update` 的 `_meta` 里。

线上格式：电脑上的 stdio 和网关都用 `LineTransport`（手机上的网关用 C 的 `JsonRpcCodec` 编码，电脑上用同样实现的 `TestLineCodec`），不再经过 SDK 的 `StdioTransport`（它每条多一个 `"type"` 字段，S3 问题 3）；“每行一条消息”这条用例已收紧为不允许任何多余字段，手机上同样检查。
