# tests/acp-conformance：ACP 一致性测试

用**官方 TypeScript ACP 客户端**（`@agentclientprotocol/sdk` 1.4.0，与 `reference/pi-acp-adapter` 一致）连接 AgentOS 运行时，验证它对外是一个标准的 ACP v1 Agent，并验证 AgentOS Profile 的扩展（`core/protocol/acp-mapping.md`、`acp-extensions.schema.json`）和电脑端接入的配对握手（acp-mapping.md 第 10 节）。

| 阶段 | 被测对象 | 传输 | 状态 |
|---|---|---|---|
| W4 | 电脑上的运行时（`org.agentos.runtime.testing.AcpStdioAgent`：完整的 `RuntimeEngine` + 真实 SQLite + ACP Agent 端），Agent 循环是 FakeAgentCore | stdio，`LineTransport` | ✅ 14 例（`conformance.test.mjs`，目标 `stdio`） |
| W9 | 同上，以网关模式运行（与手机上同一份 `DesktopGatewayCore`：开关、配对码、令牌、握手；监听本机 TCP 代替抽象 socket） | `tools/acp-bridge --connect` | ✅ 14 例（目标 `gateway`）+ 配对与安全 9 例（`gateway.test.mjs`） |
| W9（C4 之后） | 手机上的 `:agent`（debug 包；真实 Pi），模型是电脑上的 FakeModelServer 经 `adb reverse` | `tools/acp-bridge`：`adb forward tcp:0 localabstract:agentos-acp` | ✅ 一致性 14 例中 10 例（4 例依赖工具、确认或 Jev，跳过）+ 设备专属 4 例 + 配对与安全 9 例 + 空闲 15 秒后仍能服务 1 例，模拟器 API 36 |
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

`npm test` 会先检查 `core/runtime/build/acp-conformance/classpath.txt`：文件不存在，或者 `core/runtime` 的 `src/main`、`src/testFixtures`、`build.gradle.kts`、`gradle/libs.versions.toml`、根 `build.gradle.kts`、`settings.gradle.kts` 里有比它新的文件（改了代码、合了 main、切了分支），就调用 `../../gradlew :core:runtime:acpConformanceClasspath` 重新编译并生成它，输出里有一行 `rebuilding the desktop agent classpath (<原因>)`。`AGENTOS_ACP_REBUILD=1` 强制重建；也可以用 `AGENTOS_ACP_CLASSPATH` 直接给出类路径（这时不检查）。

### 手机（W9）

```bash
# 设备上装 debug 包（开关和配对码经 debug 包的测试入口 DesktopGatewayDebugReceiver 操作）。
# 一定带 ANDROID_SERIAL：不带时 :app:installDebug 会装到所有连着的设备上
(cd ../../core/pi-runtime && npm ci && node build.mjs)
ANDROID_SERIAL=<serial> ../../gradlew -p ../.. :app:installDebug
AGENTOS_ACP_DEVICE=<serial> npm run test:device
```

设备用例开始前（`prepareDevice`）先清掉上次中断可能留下的状态：`revoke_all` 和 `disable`（关开关同时清空配对）、`adb reverse --remove tcp:18787`、移除这台设备上指向 `localabstract:agentos-acp` 的 `adb forward`。然后在还没有豁免时执行 `adb shell cmd deviceidle whitelist +org.agentos.app`，模拟 F2 首次引导里用户已允许“忽略电池优化”（M1 靠它）：这样 `:agent` 从后台（debug 广播打开开关、有任务时）也能进入前台服务，不会被 cached-apps freezer 冻结。没有这一步（又没有 root 监督进程 promote）时，系统拒绝后台启动前台服务（logcat：`Background started FGS: Disallowed … uidState: RCVR`），空闲的 `:agent` 约 10 秒后被冻结，用例会卡住。用例结束（或被中断）时恢复：移除 reverse 和指向 `agentos-acp` 的 forward、关开关，只移除测试加的豁免（设备上本来就有时保留；是不是测试加的记在电脑临时目录的 `agentos-acp-battery-exemption-<serial>` 里，强行结束后下次运行照样认得）。

设了 `AGENTOS_ACP_DEVICE` 时，一致性用例的默认目标是 `device`：手机上 `:agent` 里的真实 Pi 经 `tools/acp-bridge`（`adb forward` → `localabstract:agentos-acp`）对外。模型是电脑上的 B 的 FakeModelServer（`FakeModelServerMain`，按同样的 `{"fake":…}` 指令回应），经 `adb reverse tcp:18787 tcp:<端口>` 映射到手机的 `127.0.0.1:18787`；手机上的模型来源是 C 的测试端点（`tests/device/acp-channel/inapp` 的 `ensureTestModel`：`http://127.0.0.1:18787`、`fake-model`、测试 key），不是时借 C 的 `AgentScenarioActivity` 设置（key 在它的代码里，不经 adb 命令行）。

手机上 M1 没有这些，依赖它们的用例在 `device` 目标上跳过（原因写在 `agent.mjs` 的 `SKIP`，测试输出里也有）：

| 用例 | 依赖 | 归属 |
|---|---|---|
| a tool call shows up as tool_call, then tool_call_update in_progress and completed | 工具（电脑上是 AcpStdioAgent 注册的 `add`） | W14 插件工具 / W15 MCP |
| a write tool is confirmed by AgentOS (not by the client) and runs | 工具 + 确认（`send_note`，确认自动通过） | W14 / W15，W16 风险策略与确认 |
| the tool round limit maps to max_turn_requests | 工具（连续 13 轮工具调用） | W14 / W15 |
| creates a session, then selects it again, and reports the selection in _meta | Jev（电脑上是 `--jev=first`） | 手机上的 Jev 接线尚未分配工作包 |

“目录外的工具”一例在手机上照常跑：模型调用的工具从来没有声明过，真实 Pi 自己返回 `Tool <name> not found`。

“失败的一轮”一例在手机上用不可重试的 `model_bad_request`（400）检查错误映射，电脑上用可重试的 `model_rate_limited`（429，顺带检查 `retryable: true`）：手机上的宿主层会重试 429（B6，errors.md 第 4 节：`retry-after` 优先，否则 1、2、4…30 s 退避，每个请求最多 2 分钟；假端点回 429 时带 `retry-after: 1`，最多 10 次，约 9 秒），电脑上的 `AcpStdioAgent` 不重试。重试本身由设备专属用例检查（见下）。

`test:device` 还跑 `device.test.mjs`（设备专属：连接身份是 adbd 的 uid 2000、70,000 字符的模型输出经网关完整送达、可重试的模型错误在手机上被重试且对客户端透明（假端点接下来两次回 429，第三次正常：客户端只看到一次 `end_turn` 和不重复的文字，端点收到 429、429、200 三个请求，中间等了 1 s + 2 s）、手机发出的每一行格式与大小）和 `gateway.test.mjs`（本机网关和手机各一遍：开关关闭时没有应答、握手前的 ACP 消息得到 `auth_required` 并断开、错误配对码、5 次作废、过期、一次性、令牌重连与关开关作废、超长行、握手超时）。

设备模式的假端点（`FakeModelServerMain`）的 stdin 是控制通道，每行一条 JSON，stdout 回一行：`{"op":"failNext","status":429,"times":2,"retryAfter":"1"}`（接下来几个请求不看内容都回这个状态，`retryAfter` 可省略）、`{"op":"requests"}`（到目前为止的请求：序号、剧本、状态码，不含内容和 key）。用例里是 `startDeviceModel()` 返回的 `control(cmd)`。

**cached-apps freezer**：`:agent` 空闲时是 cached 进程，约 10 秒后会被冻结，冻结期间电脑端的连接和请求都得不到服务（A6 发现）。C6 起电脑端接入打开期间 `:agent` 以前台服务运行并显示常驻通知；`gateway.test.mjs` 在手机上空闲 15 秒（期间没有任何广播）后检查新连接能配对、已建立的会话照常应答，握手超时用例的等待期间同样没有任何广播。

没有电池优化豁免时 `:agent` 从后台进不了前台服务，一轮对话只要超过约 10 秒就会在中途被冻结，直到有别的东西（比如一条发给 App 的广播）把它唤醒。`AGENTOS_ACP_BATTERY_EXEMPTION=0 npm run test:device` 不加豁免，模拟用户没有允许，用来复现：模拟器上“失败的一轮”用可重试的 429 时，第 10 次请求刚开始就被冻结（logcat：`freezing <pid> org.agentos.app:agent`），90 秒时 `still running` 的诊断广播把它唤醒（`sync unfroze`）后这一轮才结束；没有任何广播时一直等到整组 300 秒超时，after 钩子关连接，挂着的请求以没有 code 的 `ACP connection closed` 失败（Pixel_8a，main 7028e3d）。

#### 中断与残留状态

`npm test` / `npm run test:device` 经 `test/run.mjs` 调用 `node --test`。测试被中断时（Ctrl-C，或外层工具超时发 SIGTERM / SIGHUP），node 只打印 `Interrupted while running: <文件>`，之后不再转出测试文件进程的输出；被中断的测试文件会把原因写进一份报告，`run.mjs` 在 runner 退出后打印出来，例如：

```
Interrupted while running:

⚠ test/gateway.test.mjs (test/gateway.test.mjs:1:1)

[acp-conformance] INTERRUPTED gateway.test.mjs by SIGINT while running "desktop gateway pairing (device emulator-5590) > a silent connection is closed after the handshake timeout" (for 8s)
[acp-conformance]   device emulator-5590: org.agentos.app:agent pid 3872 frozen=false procState=4 (fg-service); foreground service=true; battery optimization exempt=true; desktop access enabled=true listening=true pairings=1 connections=0; adb reverse []; adb forward [tcp:58672 localabstract:agentos-acp]
[acp-conformance]   stopping 0 child process(es), running 1 cleanup step(s)
[acp-conformance] CLEANED UP gateway.test.mjs
```

第一行是哪一例、已经跑了多久（不在用例里时写“在 before/after 钩子里”和上一例的名字）；第二行是设备当时的状态：AgentOS 各进程是否被冻结（`frozen=true`、`cch-empty` 这类就是 freezer 的问题）、`:agent` 是否前台服务、是否有电池优化豁免、电脑端接入开关与配对和连接数、adb reverse / forward。随后结束子进程并恢复设备（同上）。一例跑了 90 秒还没结束时，也会先打印一行 `still running "<用例>" after 90s; device …`（之后每 90 秒一次），外层工具用 SIGKILL 结束时至少还能看到它。

强行结束（SIGKILL）后设备上可能留着开关、配对、reverse、forward、测试加的豁免；下次 `test:device` 开始时会自动清掉，结束时移除豁免，不用手动处理。只给 `npm` 进程发 SIGTERM 时 npm 自己退出、不转给脚本，测试会在后台照常跑完并恢复设备；要中断就发给整个进程组（Ctrl-C 就是），或者直接运行 `node test/run.mjs --device`。

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
