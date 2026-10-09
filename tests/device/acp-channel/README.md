# tests/device/acp-channel：ACP 通道的设备测试

adb 驱动，在模拟器或真机上跑。只用于测试，不进 zip。

| 模块 | 是什么 |
|---|---|
| `common/` | 公共部分：场景执行框架（`ScenarioActivityBase`）、ACP 连接封装（`AcpConn`，经 `BinderAcpTransport.connect`）、通道场景（`ChannelScenarios`）、测量工具、测试 Agent 的探针 AIDL |
| `agent/` | 测试 Agent App（`org.agentos.test.acp.agent`）：官方 SDK 的 Agent 端跑在 `:agent` 进程，经 `sdk:acp-android` 导出 `IAcpService`；假 Agent 按 prompt 里的 JSON 指令流式输出；探针可以读统计、调通道参数、自杀 |
| `client/` | 第三方身份的测试客户端（`org.agentos.test.acp.client`）：对测试 Agent 跑全部通道场景；W6 里用它以第三方 UID 调 AgentOS 的 `IAcpService`，验证被拒 |
| `inapp/` | W6：注入 AgentOS **debug 包**的场景执行器（`:acptest` 进程，AgentOS 自己的 UID），对真正的 `:agent` 跑通道和 `AgentService` 用例。release 包里没有 |
| `run.py` | 主机端驱动：安装、逐个执行场景、拼回 logcat 里的结果（tag `ACPTEST`）、汇总 |
| `third_party_sms_e2e.py` | 短信“让 AgentOS 安排”的真机验收（真实模型，需要 root）：授权、从原始短信建日程 / 待办 / 闹钟、只发没处理过的、提示词里的用户要求、注入、始终允许、撤销。日历只能整库清理，所以日历里已有数据时拒绝运行，除非 `--allow-calendar-reset` |
| `sms_demo_seed.py` | 在有 root 的手机上往系统短信库写 10 条演示短信（`seed` / `clean` / `status`），录屏用；只动演示号码的行 |

## 两套用例

**`--suite sdk`（W5 回归）**：测试 Agent ↔ 测试客户端，两个都是第三方 App，覆盖 `sdk:binder-channel` 和 `sdk:acp-android`。由 `spikes/S3` 的测试 App 改写：去掉了只为测量存在的部分（原始 oneway 压测、电脑端网关），测量仍在 `spikes/S3` 里做。

| 用例 | 通过标准 |
|---|---|
| handshake | initialize → session/new → prompt（20 条，顺序正确）→ 本端 close，关闭原因为 `local` |
| stream-realtime / stream-peak-bp / stream-bigchunks-bp / stream-cjk-bp / stream-window-8x16384 | 条数和顺序正确、以 `END_TURN` 结束；两端主线程上的 Binder 调用为 0 |
| cancel-realtime / cancel-peak-bp | 本轮 `CANCELLED`，同一会话下一轮 `END_TURN` |
| reconnect（× 20）/ reconnect-noclose（× 5，不调 close 直接丢弃） | 每轮都能对话；结束后服务端连接、通道、协程、在跑的 prompt 都为 0，本端通道为 0 |
| server-kill | 流式中途 SIGKILL 服务端：本端通道以 `peer_died` 关闭、prompt 不挂住，服务重建后能重新对话，本端无残留 |
| client-kill | 流式中途客户端自杀：服务端以 `peer_died` 关闭通道，连接、通道、在跑的 prompt 归零 |
| oversize | 本端超长 prompt 本地报错；上限内的 prompt 正常；服务端超长通知被丢弃、本轮照常；绕过 SDK 塞超长消息，服务端判违规关闭 |
| window-violation | 绕过流控连发，服务端以 `window exceeded` 关闭 |
| stream-noflow（负向） | 关掉流控打满：允许写爆，但必须在超时内结束，不能挂住（S3 问题 1） |

**`--suite app`（W6）**：AgentOS 的 `:agent`（`AcpService`、`AgentService`、`AgentControlService`）。执行器是注入 debug 包（或 releaseTest 包，见下）的 `inapp/`（AgentOS 自己的 UID，`:acptest` 进程），第三方身份的用例由 `client/` 跑。宿主层是真正的 `RuntimeEngine`（A3：ACP、Store、调度、恢复），Agent core 是真正的 Pi（B3 的 `PiAgentCores`：PiAdapter + QuickJsEngine）。Pi 的模型请求经 HostFetch 发到 run.py 在电脑上起的**假模型端点** `fake_model.py`（Anthropic Messages 流式，经 `adb reverse` 映射到设备的 `127.0.0.1:18787`）：它按 prompt 里的同一套 JSON 脚本出字（chunks、chunkChars、intervalMs、burst、cjk、bigChunkChars，`tool` 让第一轮以 tool_use 结束），不是 JSON 时回显 20 行；只认测试 key，记录每个请求（只记 key 的类别），`GET /_log` 给执行器核对。A12 起还有 `toolCalls` 脚本（见下一节“假模型的多步工具脚本”）。

与 SDK 回归的差别（`AcpTarget.hostRuntime`）：宿主层按 32 ms 合并文字增量、把长文字切成不超过 8,192 字符的块、不带 `_meta.seq / t`，所以通道用例按**文字总字符数**校验，不按条数和顺序号，也测不出端到端延迟；连接断开不取消任务（F7）。宿主层没有配置模型时拒绝任务（`model_not_configured`），所以执行器在每个非 BYOK / live 用例之前把模型来源设成假模型端点（自定义端点 `http://127.0.0.1:18787`，固定的测试 key）。

| 用例 | 执行器 | 通过标准 |
|---|---|---|
| handshake | inapp | 本 App 的 UID：initialize → session/new → prompt → close，文字完整 |
| foreign-uid-rejected | client | 第三方 UID 调 `open`：`SecurityException`，原因码 `agentos.acp.not_open`，本端不留通道 |
| foreign-no-leak | inapp | 被拒之后 `:agent` 没有连接和通道，`rejectedOpens` ≥ 1 |
| oversize | inapp | A、B、D 同 SDK 回归；C：宿主层把 65,636 字符的文字切成 ≤ 8,192 字符的块发出，没有超长通知、没有丢弃，文字完整送达（A4 修复了单条超长增量被事件日志截断的问题，C3 发现） |
| client-kill | inapp | 执行器进程在流式中途自杀：`:agent` 以 `peer_died` 关闭连接和通道；任务不随连接取消，run.py 轮询到它自己结束（`promptsActive=0`） |
| agent-kill-rebind | inapp | 流式中途 SIGKILL `:agent`：执行器感知、prompt 不挂住；系统重建服务后重新 bind 能对话 |
| stream-realtime / cancel-realtime / reconnect / window-violation | inapp | 同 SDK 回归（stream 按字符数） |
| cold-task-foreground | inapp | 先杀 `:agent`，让恢复多等 1.5 s；从连接之前就采样。session/new 在恢复期间到达、已计入任务（S2 整合时发现的缺陷的先后顺序）：恢复期间服务已在前台，恢复结束后直到任务结束一直在前台，心跳 `tasks≥1 fg=1 state=busy`；结束后 2 s 宽限期过后退出前台、服务停止，心跳 `tasks=0 fg=0 state=idle`，空闲停止恰好 1 次（session/new 与 prompt 之间不退出前台） |
| warm-task-foreground | inapp | 同上，`:agent` 已在运行 |
| supervisor-start-idle | inapp | 监督进程的命令（`SUPERVISOR_START`、`REASON=boot`），恢复多等 1 s：服务先进前台，恢复期间不停，恢复结束后没有任务就停止，心跳 `state=idle` |
| restart-exit-info | inapp | SIGKILL `:agent` 后用 `REASON=restart` 拉起：诊断里的上次退出原因是 `SIGNALED`、pid 对得上，`userStopped=false` |
| control-foreign-rejected | client | 第三方绑定 `AgentControlService`、启动 `AgentService` 都被系统拒绝 |
| user-stop-recovery | inapp + run.py | 两个会话在跑、一个排队，run.py `am force-stop`：新进程里上次退出原因 `USER_REQUESTED`，宿主层拿到 `previousExitStoppedByUser`，排队的任务取消（`by: user_stop`）、运行中的两个标为结果未知；没有任务、不进前台、心跳 `tasks=0` |
| store-restart | inapp | 删库 → ACP 冷启动建两个会话、跑一轮 → SIGKILL 后直接读数据库文件（只读）：会话、已完成的任务、事件、Pi messages 都在，库在 CE、WAL；再冷启动：`runtime.started` / `runtime.recovered` 各加 1、系统流 sequence 连续 |
| byok-roundtrip | inapp + run.py | IAgentControl v2：设置预设 → 读取只有首尾 4 位 → 同厂商换模型沿用 key → 8 种拒绝（换端点要 key、http、换行、Bearer、未知厂商、key 填错字段、坏 JSON）且消息里没有 key → 自定义端点 → 诊断里连掩码都没有；全程不重启 `:agent` |
| byok-restart | inapp + run.py | SIGKILL 后冷启动：key 仍能解密、按端点匹配（`credentialResolves`），Keystore 主密钥在；`model-source.json` 在 CE、只有密文；App 的 CE / DE 私有目录下所有文件里没有明文 key |
| byok-clear-inflight | inapp + run.py | 清除 = 立即作废（F9 两层；C5 起第二层也接上）：模型来源是假端点 + BYOK 测试 key，模型流式约 2.4 s；流式中清除 → 第一层：KeystoreSecrets 立即丢掉 key，清除后 `served` 不再增加；第二层：KeystoreSecrets 在 `revocations` 上发出被撤销的 Credential，HostFetch 中止在途调用，假端点看到连接在流完之前断开（`completed=false`、`disconnected=true`），本轮 1 s 内以 `model_not_configured` 结束，错误 data 里 `details.reason=key_revoked`，错误消息和 data 里没有 key；撤销信号有订阅者且 `signalsDropped=0`；假端点只见到一个带 key 的请求；下一轮同样失败 |
| byok-clear | inapp + run.py | 清除 → 重启后仍未配置，文件已删，Keystore 主密钥已删 |
| pi-tool-round | inapp | 模型第一轮末尾要调用工具 `fs_read`（M1 工具目录为空）。按实际行为核对：客户端看到 tool_call，状态 FAILED；Pi 把错误结果（“Tool fs_read not found”）交回模型、发出第二个请求（带 tool_result）；两轮文字都送达，本轮 end_turn；两个请求的 key 都是宿主层注入的测试 key |
| pi-context | inapp | 同一会话两轮：第二个请求带着第一轮的 user 和 assistant 消息，assistant 文字与客户端收到的一致 |
| recovery-context | inapp | 恢复后上下文一致（F8）：会话 A 完成一轮；另两个会话占满本调用方的并发，A 的第二轮排队；SIGKILL `:agent`。冷启动后恢复流程把排队的任务交还调度器，Pi 用 Store 里的 messages 重建会话 A，发给模型的请求带着崩溃前的那一轮（与客户端当时收到的一致）；另两个运行中的任务标为结果未知。M1 没有 `session/load`，所以在模型端核对 |
| desktop-access | run.py + desktop_idle.py | C6（F11 第 4 点）：从前台界面打开电脑端接入 → 空闲 60 s → 经 tools/acp-bridge 配对握手和一轮对话 → 连接不断再空闲 60 s、同一会话再一轮 → 取消（期间通知副标题“正在运行任务”）→ 开关开着时杀掉 :agent，按开机拉起和 bind 冷启动各拉起一次 → 带着已建立的连接点通知上的“关闭”：连接断开、退出前台、通知消失；全程 `isFrozen=false`，反向对照关掉后会被冻结。releaseTest 包没有 debug 入口、拿不到配对码，跳过经 acp-bridge 的部分。`AGENTOS_DESKTOP_IDLE` 可以缩短空闲时长 |
| live-minimax | inapp + run.py | 真实对话：MiniMax 国内平台（`minimax-cn` 预设），key 只从 run.py 的环境变量 `MINIMAX_API_KEY` 读、经 stdin 投递；一轮 end_turn、有文字；对话后清除模型来源，key 不留在设备上；整个 logcat 和结果里搜这把 key 为 0。没有设置环境变量时跳过 |

BYOK 用例的 key 由 run.py 每次随机生成（`agtest-` + 48 字符，不是真实 key），live 用例的 key 来自环境变量；两者都经 stdin 写进 App 私有目录的 `files/test/`（`adb shell content write --uri content://org.agentos.test.acp.inapp.keydrop/<槽位>`，inapp 的 `KeyDropProvider`，要求 DUMP；只能写和查长度，不能读内容、不能列目录）。写完 run.py 用 `content query` 核对文件长度，不对就重写一次，两次都不对判失败（结果里记 `keyPushAttempts`）。执行器读完立即删除，全部用例结束后 run.py 再跑一次 `drop-keys` 兜底。用 `adb shell` 而不是 `adb exec-in`：后者不等设备上的命令结束就返回，key 会晚于用例落盘。**不要把 key 放在 `adb shell` 的命令行里**：API 37 的 adbd 会把整条命令行写进 logcat（`adbd service requested 'shell,v2,…:am start … --es args …'`），C3 就是在 API 37 上这样发现的。执行器基类回显参数时也会把 `apiKey` 换成 `<redacted>`。每个 BYOK / live 用例结束后 run.py 在整个 logcat（`-b all`）和结果 JSON 里搜 key 的全文和中段，命中就判失败，命中的行（key 已替换）记在 `leakScan.hitLines`。

`inapp/` 的 Activity 导出但要求 `android.permission.DUMP`，只有 shell 和系统能启动；它只在 debug 包里。

## 怎么跑

```sh
# 仓库根目录
./gradlew :tests:device:acp-channel:agent:assembleDebug :tests:device:acp-channel:client:assembleDebug \
          :tests:device:acp-channel:agent:assembleRelease :tests:device:acp-channel:client:assembleRelease
python3 tests/device/acp-channel/run.py --serial $ANDROID_SERIAL --suite sdk --build debug
python3 tests/device/acp-channel/run.py --serial $ANDROID_SERIAL --suite sdk --build release

./gradlew :app:assembleDebug :tests:device:acp-channel:client:assembleDebug
python3 tests/device/acp-channel/run.py --serial $ANDROID_SERIAL --suite app

# R8 下再跑一遍：releaseTest（与 release 同样的 R8 规则、不可调试，调试证书签名，带 in-app 执行器；回环明文由 main 的网络安全配置放行）
./gradlew :app:assembleReleaseTest
python3 tests/device/acp-channel/run.py --serial $ANDROID_SERIAL --suite app --app-build releaseTest

# 真实对话（live-minimax）：key 只从环境变量读，不要写在命令行上
set -a; . ../.secrets/minimax.env; set +a
python3 tests/device/acp-channel/run.py --serial $ANDROID_SERIAL --suite app --only live-minimax
```

`--only a,b` 只跑指定用例。结果写在 `results/raw/`（不进仓库），定稿的结果复制到 `results/` 提交。全部通过时退出码为 0。

## 电脑端接入打开期间不被冻结（desktop_idle.py，C6）

architecture F11 第 4 点：电脑端接入打开期间 `:agent` 以前台服务运行，否则空闲的 `:agent` 是 cached 进程，约 10 秒后被 cached-apps freezer 冻结，抽象 socket `agentos-acp` 上的连接得不到服务（A6 查明）。`desktop_idle.py` 在 debug 包上从电脑端走一遍：

1. 像设置页一样从前台界面打开开关（inapp `desktop-access`，IAgentControl v3），回到桌面；配对码从 debug 入口 `DesktopGatewayDebugReceiver` 的广播结果里取（不进设备日志、不上命令行）；检查前台服务和通知（渠道 `desktop_access`、“电脑端接入已开启”、“关闭”按钮）。
2. 空闲 `--idle` 秒（默认 60），只用 `dumpsys` 旁观（`isFrozen`、进程状态、前台服务），不碰 App。
3. 经 `tools/acp-bridge` 连接：配对握手、initialize、session/new、一轮对话（假模型端点，fake_model.py 经 adb reverse）。
4. 连接不断再空闲同样久，同一会话再一轮；再测一次取消。
5. 开关开着时杀掉 `:agent`，按“监督进程开机拉起”（inapp `desktop-restart` path=boot，SUPERVISOR_START）和“bind 冷启动”（path=bind）各拉起一次：恢复后留在前台、重新监听，空闲 20 秒不被冻结；用保存的令牌重新连上。
6. 点通知上的“关闭”（uiautomator）：开关关闭、宽限期后退出前台、通知消失；反向对照：之后进程确实会被冻结（点通知按钮给 App 30 秒临时白名单，所以要等 40 秒左右）。

```sh
./gradlew :app:assembleDebug
python3 tests/device/acp-channel/desktop_idle.py --serial $ANDROID_SERIAL [--idle 60] [--no-install]
```

脚本给 App 授予通知权限（首次引导里请求的），故意不给电池优化豁免：验证的是从前台界面打开开关这条正常路径。后台打开（例如 debug 入口的广播）时系统不允许进入前台（`Background started FGS: Disallowed`），有电池优化豁免时允许。

app 用例要求 APK 里有 `assets/model-catalog.json`（BYOK 的厂商预设和自定义端点模板都来自它）：先在 `core/pi-runtime` 里 `npm ci`，构建时不加 `-Pagentos.skipPiBundle=true`，或者先单独跑一次 `node build.mjs`。

## 假模型的多步工具脚本（A12，`scripted_tools.py`）

`tool: NAME` 只能发一个空参数的工具。验收要跑确定性的多步流程，所以 prompt 里可以写：

```json
{"toolCalls": [{"mcp": ["alarm", "alarm", "alarm_create"], "arguments": {"time": "07:00", "label": "起床"}},
               {"name": "mcp__alarm__alarm__alarm_set_enabled", "arguments": {"id": "$result[0].id", "enabled": false}}],
 "final": "闹钟 ${result[0].id} 已建好并关闭"}
```

- 每一轮（Pi 的一次模型请求）按顺序发**一个** tool_use，工具结果回来后进入下一个，`toolCalls` 用完后输出 `final`（默认空）并 end_turn。
- 工具名：`name` 是目录里的最终名字；`mcp: [插件, 服务器, 工具]`（或 `{"plugin","server","tool"}`）是原始三元组，由 Python 按 `ToolNaming.nameOf` 的规则算（`mcp__插件__服务器__工具`，非法字符换 `_`，超过 64 个字符截断加 6 位哈希）。两边共用 `tool_naming_golden.json`（Kotlin 侧 `ToolNamingGoldenTest`）。同一目录里撞名的消解（`ToolNaming.assign`）这边不做；三个示例 App 不撞名。
- 占位符（只在 `arguments` 和 `final` 里；结果是本轮工具结果的 JSON 文本）：整个字符串是 `$result[N]<路径>` 时换成原值（保持类型；`N` 可为负，-1 是最近一个；路径是 `.键` 和 `[下标]` 的任意组合）；字符串里嵌 `${result[N]<路径>}` 时换成文字（字符串原样，其他是紧凑 JSON）；以 `$$` 开头的字符串是字面量（去掉一个 `$`，不替换）。
- 某一步的占位符解不出来（引用的结果是错误、不是 JSON、没有这个键或下标）时，**不发**半成品的工具调用：这一轮输出 `script-error: step N: …` 并结束，驱动据此判断哪一步出的错。结果是错误但脚本没引用它时照常往下走（失败路径用例就是这样让模型“传了错参数、看到错误、继续”）。
- `GET /_log` 的每条记录多了 `plan`（这一轮发了什么：kind、工具名、解析后的参数）和 `toolResults`（本轮已经回来的结果，每条最多 4,000 字符）。
- 原有脚本（chunks、`tool` 等）不变；`unit/` 里有不需要设备的单元测试（`python3 -m unittest discover -s unit -p 'test_*.py'`，CI 的 `device-test-scripts` 任务也跑）。

## 示例 App 端到端验收（A12，`sample_apps_e2e.py`）

目标：闹钟、日历、备忘录、待办、短信五个示例 App（`plugins/samples/*`）能被 AgentOS 经 MCP 完整操作，可重复、有证据。只用 adb，不点界面。需要 AgentOS 与五个示例 App 的 **debug** 包。判断结果的依据是各 App 自己的 debug `dump` 入口（`adb shell am broadcast -n <包名>/<接收器> --es cmd dump [--ei offset N --ei limit M]`，结果在广播的 result data，按 `next_offset` 翻页）：真机（user 构建）上没有 `sqlite3`，`run-as … sqlite3` 用不了。闹钟 `.debug.DebugToolReceiver`（`scheduled[].registered` 是向 AlarmManager 实测的，证明“通过 MCP 设的闹钟真的在系统里排了”）、备忘录 `.debug.DebugCallReceiver`、待办 `.debug.DebugToolReceiver`（`todos[]` 是 `todo_get` 的同一批字段，`reset` 返回 `remaining_in_db`）、短信 `.debug.DebugToolReceiver`（`mode`、`permissions`、`settings`、`outbox[]`，不读系统短信库；另有 `set` 改设置）、日历 `.debug.DebugReceiver`（`reminders_scheduled[]`：日历只向 AlarmManager 交出**一个**提醒闹钟，即所有日程里最早的下一条提醒，`registered` 同样是 FLAG_NO_CREATE 实测的）。五个 App 的状态都经 dump 读，驱动没有任何读 App 私有文件的办法。\n\n**驱动会在运行前后对五个 App 做 `--es cmd reset`（清空 App 里全部闹钟、备忘录、日程、非默认日历和待办，并取消系统里已排的闹钟和日历提醒；短信只清它自己的发送记录和草稿，不碰系统短信库、设置和权限）**，请在测试机上跑；`--no-reset` 则保留原有数据、只清理本次运行创建的（标记 `e2e-<run id>`）。

```sh
./gradlew :app:assembleDebug :plugins:samples:alarm:assembleDebug :plugins:samples:calendar:assembleDebug :plugins:samples:notes:assembleDebug :plugins:samples:todo:assembleDebug :plugins:samples:sms:assembleDebug :tests:device:acp-channel:client:assembleDebug
python3 tests/device/acp-channel/sample_apps_e2e.py --serial emulator-5604              # 确定性脚本（假模型），五个 App
python3 tests/device/acp-channel/sample_apps_e2e.py --serial $ANDROID_SERIAL            # 真机：四个 App，短信跳过并在报告里标 skipped
set -a; . ../.secrets/minimax.env; set +a
python3 tests/device/acp-channel/sample_apps_e2e.py --serial $ANDROID_SERIAL --live     # 自然语言，真实 MiniMax
```

选项：`--only alarm,calendar,notes,todo,sms`、`--sms-on-device`（短信步骤默认只在序列号以 `emulator-` 开头的设备上跑；真机要显式加它）、`--sms-peer 5616|none|auto`（短信发给哪个号码：另一台模拟器的控制台端口；`none` 只验证确认框和拒绝；默认 `auto` = `adb devices` 里第一台别的模拟器）、`--no-install`、`--allow-enabled`（上次中断后插件还开着）、`--no-reset`、`--keep-data`（运行后什么都不清）、`--live`（真实 MiniMax-M3）、`--tunnel`（`--live` 时手机没有互联网：真实 key 留在电脑上，由 `live_tunnel.py` 转发）、`--tell-date`（`--live` 时每条 prompt 前面加上今天的日期；不加则模型要自己查，`agenda_today`）、`--label`。

流程与判定：`ExtensionDebugReceiver list`（五个插件被发现且默认关）→ `enable` → `catalog`（44 个文档要求的工具以最终名字出现，风险按 RiskPolicy：第三方 MCP 工具 WRITE——读类工具包括 `sms_thread_list` 也是，`readOnlyHint` 不降级——`*_delete` 和 `sms_send` 为 HIGH）→ 配对 + `ConsentDebugReceiver mode=allow` → 每一步一个假模型脚本（`toolCalls`），步骤后读 App 的 dump 对比（闹钟还核对 `registered`：建、改、开之后为真，关、删之后不再有；日历核对它的那一个提醒闹钟，见下）。脚本模式覆盖：闹钟 建→列→改→关→开→get→next→删；日历 建日历→建日程（每周重复+提醒）→列区间（展开 3 次）→搜索→free_slots→改→删日程→删日历；备忘录 建→追加→搜索→改标签→tag_list→进回收站→恢复→再回收站→永久删除。失败路径：缺参数、非法值、不存在的 id（错误回到模型、数据不变）、拒绝确认（`tool_denied`、数据不变）、插件关闭（目录里没有它的工具、调用被拒）再打开（工具回来）、备忘录 `note_delete` 非回收站笔记被拒。`--live` 三条 prompt（“明早 7 点叫我起床”“下周三下午 3 点和王总开会，提前 15 分钟提醒”“记一条关于新品发布的备忘，打上工作标签”）：**只看 App 状态**，不要求固定的工具序列（真实 M3 会先 `note_search` 再 `note_create`、先 `event_list` 再 `event_create`，也可能出错后重试）；实际的工具序列（`toolSequence`）、失败的调用（`failedCalls`）和耗时只作记录。

**模型的顺序很重要**：名字不以 `live-` / `byok-` 开头的 inapp 场景（`desktop-access`、`handshake` 等）会先跑 `ensureTestModel`，把模型源改回回环假端点（127.0.0.1:18787）。所以驱动先开电脑端接入、**最后**才配真实模型（预设 `minimax-cn` / `MiniMax-M3`，key 经 stdin，`live-model-set`），并在发第一条 prompt 前用 `DesktopGatewayDebugReceiver status` 核对 `modelBaseUrl` 以 `https://api.minimaxi.com` 开头、`modelId=MiniMax-M3`（`setup.model` 步骤，不对就整个运行停在这里）；结束后 `live-model-clear`，在 logcat 和结果里搜 key（`leakScan`）。

结果 `results/raw/sample-apps-<serial>-api<N>-<mode>-<时间>.json`：每步 `checks[{name, ok, expected, actual}]`、`turn`（停止原因、工具调用与结果摘要）、失败摘要在 `summary.failures`（“哪一步失败、期望什么、实际什么”）；退出码 0 = 全部通过。默认的清场是运行前后的 reset（`setup.reset` / `teardown.reset` 两步，核对 App 确实空了、闹钟在系统里也取消了）；`--no-reset` 时本次运行创建的数据带标记 `e2e-<run id>`，结束（含失败）时自动清理，清理不掉的在 `notes` 里写明。

调试入口（main 里已有，形状见 `sample_apps_lib.py` 开头）：`ExtensionDebugReceiver`（`list` 的 `plugins[{id, packageName, name, enabled, status}]`；`enable`/`disable` 用 `--es id <包名或插件 ID>`；`catalog` 的 `catalog.tools[{name, risk:"read|write|high"}]`；`wait_catalog` 在 App 里等工具出现/消失）、`ConsentDebugReceiver`（`mode`、`status`、`recent[{requestId, tool, risk, source, args, options, answeredWith, end, notice}]`）、`DesktopGatewayDebugReceiver`。组件名可用 `AGENTOS_EXT_RECEIVER`、`AGENTOS_CONSENT_RECEIVER` 覆盖。

确认行为（整合在 Pixel 8 上发现，驱动按它写）：
1. `mode=allow` 对 WRITE 工具自动选“本会话内不再询问”（`ALLOW_FOR_SESSION`），该工具在这个 ACP 会话里被记住；同一会话里再切 `mode=deny`，同一工具直接执行、不再确认。所以**拒绝路径一律先 `session/new` 再跑**（`deny_pre`），并且用 `recent` 核对这条请求确实被记成 `DENY`（`declined()`）；`alarm.switch_on` 还核对 `alarm_set_enabled` 在会话里只被问了一次。
2. HIGH（`*_delete`）只提供 `ALLOW_ONCE` / `DENY`，`mode=allow` 选 `ALLOW_ONCE`，照样放行。
3. 每个 App 结束后有一步 `audit.<app>`（`sms_send` 与 `*_delete` 一样是 HIGH、只有 `ALLOW_ONCE` / `DENY`）：读 `recent` 里本次运行的请求（以开始前的 requestId 为基线，不受上次运行影响），核对来源行 `来自插件「alarm」 · 服务器「alarm」`、风险（`*_delete` 为 HIGH，其余全是 WRITE）、选项、自动应答的选择（在选项内、从不是 `ALWAYS_ALLOW`、没有超时）。`--live` 结束时对每个 App 也做这一步。
4. 第三方 App 的所有工具默认 WRITE，包括 `*_list` / `*_get`：查询也要确认；只有自带插件的 `readOnlyHint` 才降为 READ。

不需要设备的测试：`unit/test_sample_apps_driver.py` 用 `unit/fake_world.py`（五个 App 的状态——闹钟、日历、备忘录、待办的 SQLite，短信的发送记录/草稿/设置/权限/系统收件箱——加它们真实形状的 dump / reset 入口——闹钟的 `scheduled[].registered`、翻页的 `next_offset`、只认 Int 的 `offset` / `limit`——AgentOS 的调试入口含“本会话内不再询问”的记忆，和 acp-bridge 的假实现，脚本由真正的 `scripted_tools` 规划）跑完整流程，并验证各种故障（App 没写入、闹钟存了但没在系统里排、删除后仍登记、reset 清不干净、dump 失败或不翻页、目录缺工具、风险被降低、模型源被换回假端点、模型选错时间…）会在对应步骤被指出。假手机不允许拷闹钟和备忘录的数据库：只要驱动那样读，单测就会失败。

**待办与短信的脚本化步骤**（`sample_apps_scenarios.todo_steps` / `sms_steps`）

- 待办：建三条“写 PRD”（全天 `due`、`priority:high`）和一条带偏移时刻的、一条已逾期的；`parent_id` 建子任务，对子任务再建子任务被拒绝；非法 `due`（`2026-02-30`、不带偏移的时间）、缺 `title`、不存在的 id、缺 `status`、什么都不改的 `todo_update`、没有 `query` 的搜索都返回错误且数据不变；`todo_set_status` doing → done（写 `completed_at`）→ 再 done（什么都不动）→ 改回 todo（清掉 `completed_at`）；`todo_update`、`due:""` 清截止；`todo_get` 带子任务；`todo_list`（默认不含 done、`include_done`、`status`、`overdue_only`、`due_after`/`due_before` 闭区间、`parent_id`）、`todo_search`、`todo_summary` 都对着 dump 核对（只看带本次标记的行，`--no-reset` 也成立；`counts` 与 dump 的计数一致）；`todo_delete` 父任务（`deleted` = 1 + 子任务数）；拒绝确认（新会话）后数据不变，`todo_delete` 被拒后待办还在。
- 闹钟新增 `alarm.system_next`：同一轮先 `alarm_next` 再 `alarm_system_next`，`owned_by_this_app` 为真时两者是同一时刻，为假时（别的时钟 App 的闹钟）系统的下一个不晚于本 App 的；删掉闹钟后返回 null 或不属于本 App。
- 短信（只在模拟器，`--sms-on-device` 才在真机）：`sms.prepare`（`pm grant` 之后 `mode=full`；设置：遮蔽开、短号拒绝、频率 30）→ 错误路径（10086 被当作短号拒绝、缺 `text`、缺 `to`——缺必填参数由 Pi 在工具之前按 schema 拒绝，错误文字是 “must have required properties to”——、501 字超长；都核对 outbox 没有新行）→ `sms.seed_inbox`（`adb emu sms send` 造两条来信，一条带验证码，用 App 自己的调试入口等它们进库）→ `sms_thread_list` / `sms_message_list` / `sms_search` 读到，验证码默认被遮蔽成 `••••••`、搜验证码数字搜不到，关掉 `mask_codes` 后可读 → 放行短号 → （有第二台模拟器时）`sms_send` 发给它：确认卡片是 HIGH、只有“允许一次/拒绝”、参数里有完整收件人和正文，outbox 多一行，状态 queued/sent/delivered，`sms_send_status` 与 dump 一致，相同内容重发 `deduplicated:true` 且 outbox 不变 → `sms.denied`（新会话、`mode=deny`，卡片内容照样核对，outbox 不变）→ `sms.compose_only`（`pm revoke` 两个权限：dump `mode=compose_only`，其他工具返回 “Compose-only mode”，`sms_compose` 可用并留下草稿；`post` 里一定 `pm grant` 回来）→ 插件关/开 → `sms.restore`（设置恢复成运行前的值）。短信文本的标记把 run id 里的数字换成字母（验证码遮蔽会遮 4–8 位数字，标记不能被它吃掉）。来信留在模拟器的系统短信库里（非默认短信 App 删不掉，也不该动）；`--no-reset` 时 outbox 里本次发的行同样删不掉。
- 没有第二台模拟器时 `sms.send` / `sms.status` / `sms.dedup` 不跑，报告 `skipped` 和 `notes` 里写明；真机上整个短信 App 不装、不启用、不重置、不驱动，`skipped` 里写明。报告新增三个键（原有结构不变）：`apps`（本次驱动的 App）、`skipped`、`smsPeer`。
- `--live` 新增三条：待办（“帮我加一条待办：周五之前把季度复盘写完，优先级高。”）、短信（“给 <号码> 发一条短信，内容是：纪要已发出，请查收。”：驱动先把回答方式切成 `deny`，核对 `sms_send` 的确认被弹出（HIGH、有号码和正文）并被拒绝、outbox 不变）、整合场景（计划第 9 节：“下周三下午 3 点和王总开需求评审会，提前半小时叫我；先把三个 PRD（…）列成待办，下周五前写完；开完会要给王总（<号码>）发短信确认纪要，这条现在就帮我发出去。”：助手不能等到会议结束，所以加了“现在就发”）。判定只看各 App 状态：日历有一个下周三 15:00 的会议；“提前半小时”恰好一份——14:30 的闹钟或日程的 30 分钟提醒，二选一、不重复；三条 PRD 待办都带两周内的 `due`、不重复；`sms_send` 的高风险确认被弹出且带号码，outbox 没有新行（模拟器端口是短号，live 用例前驱动把 `allow_short_numbers` 关掉，所以 App 自己拒绝，用例从不真发）。号码是第二台模拟器的端口，没有就用一个不是本机的假端口。每个用例后面跟它的 `audit.<app>`（控制台只保留最近 50 条请求）；整合场景后面是 `audit.cross.<app>`，没被用到的 App 不算失败。

日历提醒的核对（`calendar.create_event` / `update_event` / `update_reminder` / `create_earlier` / `delete_earlier` / `delete_event`）：日历在数据变化后约 250 毫秒才重新排提醒，所以核对会重新读 dump（最多 5 秒）直到对上，第一次读到旧的不算失败。检查的是 `reminders_scheduled`：恰好一条（App 只排下一条提醒）、属于这个日程、`fire_at` 是开始时刻减提前分钟（10 → 09:50，改成 `[30, 5]` 后移到 09:30）、`minutes_before`、标题是当前标题、`registered` 为真；再建一个更早的日程，那一个提醒闹钟换到它身上（提前 15 分钟）；删掉它又回到原来的系列；删掉最后一个日程后不再有任何已登记的提醒闹钟，也不再有属于已删除日程的提醒。`--no-reset`（App 里原来就有别的日程）时，别人的更早提醒可能占着这一个位置：这时只要求它比本日程的更早。

目录读取不能只读一次（`await_catalog`）：启用插件之后，各插件的工具是**一个插件接着一个插件**出现的（真机上出现过：`setup.enable` 返回 539 毫秒后 `setup.catalog` 读到的目录里一个备忘录工具都没有）。所以 `setup.enable`、`setup.catalog`、`*.plugin_off`、`*.plugin_on` 都先等：每一轮读完整的目录，还缺什么就在 App 里等下一次目录变化（`wait_catalog`，每轮最多 5 秒），一共最多 30 秒；全部文档要求的 29 个工具都在才继续（停用时是它们都不在）。超时不抛异常，检查里点名还缺哪些工具（按插件分组，用工具自己的名字）和等了多久，例如 `{"missing": {"notes": ["note_trash"]}, "waitedMs": 30012}`；缺了工具时整个运行停在 setup，不会在不完整的目录上去跑 App 步骤。`setup.enable` 原来只检查“目录不空”，现在检查全部 29 个工具都在。

`--live` 的三条 prompt 在 `sample_apps_scenarios.LIVE_PROMPTS` 里，句子里带着检查需要的全部事实：模型缺了信息会先反问，这是合理的模型行为，不是 App 的问题，不该算失败。闹钟：“帮我设一个明天早上 7 点的闹钟，叫我起床，只响这一次。”（检查：07:00、启用、没有重复日、系统里真的排了）；日历：“下周三下午 3 点到 4 点和王总开会，地点在 3 号会议室，提前 15 分钟提醒我。”（检查：含“王总”、下周三 15:00、15 分钟提醒、提醒闹钟排上了）；备忘录：“帮我记一条备忘：新品发布会要准备三件事——演示稿、嘉宾名单、物料清单。打上“工作”标签。”（检查：新备忘提到“新品发布”、带“工作”标签）。检查本身没变，仍然只看 App 状态。`--no-reset` 且 App 里已有一条关于“新品发布”的备忘时，模型可能追加到那一条而不是新建，检查会判失败：默认的 reset 之后不会有这种情况。

## 把真实 key 填进手机上的 AgentOS 设置（`configure_real_keys.py`、`verify_real_config.py`）

和 `sample_apps_e2e.py --live`、`jev_real_autoselect.py` 不同，这两个脚本**填完之后不清除**：MiniMax 国内站 key 进模型设置（`minimax-cn` / `MiniMax-M3`），Jev key 进 Jev 设置。走的是设置页同一条存储路径（`IAgentControl.setModelSource`、`JevSources.set`，写进 Keystore），用 debug 包的入口驱动，不点界面。

```bash
set -a; . .secrets/minimax.env; . .secrets/jev.env; set +a
python3 tests/device/acp-channel/configure_real_keys.py <序列号>   # 填入；key 只从环境变量读，只经 stdin 进设备，不回显
python3 tests/device/acp-channel/verify_real_config.py <序列号>    # 不读任何 key：真实 M3 一轮对话 + Jev 一次选会话，开关电脑端接入但不动 key
```

- 顺序：先 Jev，**最后**配模型来源。名字不以 `live-` / `byok-` 开头的场景（如 `desktop-access`）会先把模型来源改回回环假端点，所以配完之后不要再跑它们；`verify_real_config.py` 只用调试接收器开接入。
- 要清掉：`live-model-clear` 场景和 `JevDebugReceiver --es op clear`（`sample_apps_e2e.py --live`、`jev_real_autoselect.py` 收尾时会这样做）。
- 只有 debug 包有这些入口；release 包里没有。
