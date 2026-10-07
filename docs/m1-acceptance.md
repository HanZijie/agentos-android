# M1 验收清单

最后更新：2026-10-07，main `ea38eeb`。真机（Pixel 8，Magisk 30.7，API 35）验证已跑完，见第三节之后的“真机验证”；还差发布证书、内测和 KernelSU / 其他 API 的真机。

M1 的目标：一个 zip 跑通对话。代码项已经全部进 main。**现在还差的是发布证书、内测，以及 KernelSU 和 API 36 / 37 的真机。**

## 一、出口条件

| 出口条件 | 状态 | 证据 |
|---|---|---|
| 从刷 zip 到完成第一次对话不超过 10 分钟 | 未验证，要内测用户计时 | 模拟器上的流程已通了：安装 → 首次引导 → 填 key → 对话（见下面的“用户流程”）。真机上：刷 zip 重启后 App 自动装好（开机 11 s 内），真实 MiniMax 对话首字 2.3 s；首次引导和填 key 要点界面，这一轮真机只用 adb，没有走 |
| 安装、重启、禁用、重新启用、卸载全部通过，卸载后系统干净 | **真机通过**（Pixel 8，Magisk 30.7，API 35） | `tools/smoke-test.sh all` 0 FAIL：刷入、重启、禁用（禁用状态重启后无监督进程）、重新启用、卸载、清理检查（模块目录、`/data/adb/agentos`、监督进程、`/data/local/tmp` 都干净）。root 模拟器（API 35 / 37）也通过；`module/test/unit.sh` 26/26、`service-sim.sh` 57/57；`package-module.py --check` 通过 |
| 官方 ACP 客户端经电脑端接入能完成对话和取消 | **真机通过**（Pixel 8，API 35） | 官方 TypeScript 客户端的一致性测试在手机上 38 项，33 过、0 败、5 跳（跳过的依赖工具、确认或 Jev，归 W14 / W15 / W16）；`desktop_idle.py` 20/20（空闲 60 s 后握手、对话、取消，进程不被冻结；真机上关开关走调试入口，没有点通知栏） |

## 二、M1 工作包

| 工作包 | 状态 | 说明 |
|---|---|---|
| W6 `:agent` 进程接线 | 完成 | Agent 核心是 Pi；`--suite app` 26 项；release（R8）包经 ACP 完成真实对话 |
| W7 模块与打包 | 完成（模拟器 + Pixel 8 真机） | 只能打 debug 签名的 zip；Magisk 真机生命周期通过，KernelSU 待测 |
| W8 自带界面 | 完成 | 对话、流式、取消、Markdown、设置页、首次引导 |
| W9 电脑端接入 | 完成（模拟器 + Pixel 8 真机） | 打开期间 `:agent` 前台运行，需要电池优化豁免 |

## 三、最新一轮回归（Pixel_8a 模拟器，API 36，main `09ed2ac`）

| 项目 | 结果 |
|---|---|
| `clean test lint assembleDebug assembleRelease :app:assembleReleaseTest` | 546 项测试，0 失败，跳过 1 项（A9 合入后 549 项，0 失败） |
| 打包 | `agentos-0.1.0.zip` 13.8 MB，`--check` 通过 |
| 模块脚本 | `unit.sh` 26/26，`service-sim.sh` 57/57 |
| `--suite app` | 25/26：唯一失败是 `live-minimax`，原因是本机 Clash TUN 的 fake-ip 让模拟器 TLS 失败，不是代码问题 |
| `--suite sdk --build release` | 16/16 |
| `desktop_idle.py` | 20/20 |
| 一致性测试设备模式 | 38 项，33 过、0 败、5 跳；A9 修复合入后在 Pixel_8a 上连跑 3 次都是这个结果，没有再出现关闭开关失败 |
| key 泄漏扫描 | 日志和结果文件都是 0 |

## 三之二、真机验证（Pixel 8，Magisk 30.7，API 35，2026-10-07）

设备：Pixel 8（shiba），Android 15，build `BP1A.250505.005.B1`，内核 6.1.99，user 构建，bootloader 已解锁，Magisk 30.7（R），SELinux Enforcing，有锁屏 PIN。app 是 debug 签名的 zip 里带的 debug 包。这一轮手机上有人在用，所以**只用 adb，不点界面**：电池优化豁免用 `cmd deviceidle whitelist +org.agentos.app` 代替引导里的那一步，重启后用 adb 输 PIN 解锁。

| 项目 | 结果 |
|---|---|
| `tools/check-device.sh` | 0 FAIL，1 WARN（指纹不在 support-matrix，按社区适配提示） |
| 刷入、重启后自动安装 App 并拉起运行时 | 通过：开机 11 s 内装好 App（`pm install` 10.5 s），解锁后 up 19 s 拉起运行时 |
| `--suite app`（排除需点界面的用例） | **25/25**，含真实 MiniMax 对话 `live-minimax`（`minimax-cn` / `MiniMax-M2.7`，END_TURN，首字 2.3 s） |
| `--suite sdk --build release` | **16/16** |
| 一致性测试设备模式 | 38 项，33 过、0 败、5 跳 |
| `desktop_idle.py`（`AGENTOS_NO_UI_TAP=1`） | **20/20**：空闲 60 s 握手 244 ms，空闲后对话与取消正常，36 个采样没有一次被冻结，关开关后 14.8 s 进入冻结 |
| 监督进程（`real_supervisor.py`） | **21/21**：有任务时 `kill -9` 后 1.3–6.0 s 拉起；每轮重连并发任务再杀，第 5 次死亡进入 `safe_mode / crash_loop`，15 s 内不拉起，删 `safe_mode` 标记后退出并拉起；Magisk root 拉前台服务按 `SYSTEM_UID` 豁免（`Allowed … callingUid: 0`），App 自己在后台被拒（`Disallowed … DENIED`） |
| 模块生命周期（`smoke-test.sh all`） | **0 FAIL**；之后重新刷回并验证 |
| key 泄漏扫描 | 日志和结果文件命中 0 次；key 只经 stdin 进设备 |

**没做**：KernelSU；API 36 / 37 真机；灭屏 30 分钟与 24 小时驻留；真机上的 `run-android.sh` 冷启动 / 内存数值；Play Protect 界面观察（S1 M8）；首次引导、设置页填 key、通知栏点“关闭”、界面里的真实对话（都要点界面）。

**Jev**：用户给的 Jev key 对原默认端点 `omnilabs.vibeadmin.cn` 返回 401，对 `https://api.typesafe.ai/v1/systemone` 返回 200；默认端点已改为后者（可配置），HTTP 超时 3 秒，路由等待 3.5 秒。**2026-10-07 真机实测通过**（Pixel 8，WLAN 直连，Jev key 经 stdin 写入、只显示首尾 4 位，真实 minimax-cn / MiniMax-M3）：三个互不相关的会话（东京三日游、Kotlin 协程取消、红烧肉），带问题的 `autoSelect` 全部选回对应会话，不相关的问题得到新会话；每次选择约 1.0–1.6 秒；两把 key 在日志和结果里 0 命中。已知现象：`session_info_update` 里的 `selection` 通知比请求晚一拍到达（SDK 0.30.1），客户端要以返回的 sessionId 为准。脚本 `tests/device/acp-channel/jev_real_autoselect.py`。

## 三之三、示例 App 与 MCP（闹钟、日历、备忘录，Pixel 8，API 35，2026-10-07）

三个独立 App 在 `plugins/samples/{alarm,calendar,notes}/`：Compose 界面（亮暗主题、中英文）、各自带 `McpBinderService`（`BIND_MCP_SERVICE`，signature 级权限，不开 HTTP 端口）、`plugin.json` 和 `SKILL.md`。工具数 9 / 12 / 10，共 31 个，增删改查齐全，名字与必填参数见 [sample-apps.md](sample-apps.md)。每个 App 的 debug 构建带 `dump` / `reset` 接收器，验收驱动靠它读状态、复位（真机没有 `sqlite3`）；三个 App 的 `dump` 都分页、只读，日历的 `dump` 带 `reminders_scheduled`（`AlarmManager` 实测登记，最多一项：最早的未触发提醒）。

| 项目 | 结果 |
|---|---|
| 三个 App 被发现、默认关闭、启用后目录 31 个工具 | 通过（delete 为 HIGH，其余 WRITE） |
| 脚本模式 `sample_apps_e2e.py`（假模型按脚本调工具，经 Broker 与确认） | **58/58**：增删改查、闹钟启停、错误参数（缺字段、非法时间、未知 id）、拒绝后不执行、插件关 / 开后目录变化、每个 App 的审计；闹钟、日历的提醒都用 `AlarmManager` 的登记状态（dump 里的 `registered`）核对：创建、改提醒、再建更早的日程、删除之后，系统里只剩应有的那一个闹钟 |
| `--live`，真实 `minimax-cn` / `MiniMax-M3`，手机直连 `https://api.minimaxi.com/anthropic`（每次先开电脑端接入、最后配模型、发 prompt 前核对 `modelBaseUrl`） | **连跑 3 次，每次 13/13**：“帮我设一个明天早上 7 点的闹钟，叫我起床，只响这一次”→ `alarm_create` 07:00、已登记到系统；“下周三下午 3 点到 4 点和王总开会，地点在 3 号会议室，提前 15 分钟提醒我”→ 先 `agenda_today` / `calendar_list` / `event_list` 看一眼，再 `event_create`，2026-10-14 15:00（+08:00）、提醒 15 分钟；“帮我记一条备忘：新品发布会要准备三件事——演示稿、嘉宾名单、物料清单。打上‘工作’标签”→ 有时先 `note_search`，再 `note_create`、标签“工作”。判定只看 App 的状态，不要求工具顺序 |
| 自然语言多步（`sample_apps_live_nl.py`，同样真实 M3 直连） | 21/21：闹钟建 → 改 → 删，备忘录先搜再建并追加，日历先查再建（3 号会议室、提醒） |
| 直接经 `ExtensionDebugReceiver` 的 MCP 增删改查（`sample_apps_mcp_crud.py`） | 36/36 |
| 假模型 + 确认协调器全链路（`sample_apps_consent_e2e.py`） | 15/15：HIGH 只有“允许一次 / 拒绝”，拒绝在新会话里仍然生效 |
| 单测 | 日历 99 项（含 `dump` 5 项），`core:extensions` 168 项，合入 main 后重跑 0 失败 |
| key 泄漏扫描 | `--live` 的 14358 行 logcat 与结果文件命中 0 次 |
| main 全量回归（`e4b98fc`：`clean test lint assembleDebug assembleRelease :app:assembleReleaseTest`） | 1575 项测试，0 失败、0 错误、跳过 1 项；lint 通过；release 包的 dex 里没有 `ConsentDebugReceiver`、`ExtensionDebugReceiver`、`DesktopGatewayDebugReceiver`、`JevDebugReceiver`；仓库里两把 key 命中 0 次 |

**没做 / 限制**：
- **release 构建里需要确认的调用仍一律拒绝**（D5.2 真实确认界面未合入）。上面所有写操作都是 debug 构建的 `ConsentDebugReceiver` 放行的；这一步完成前，真实用户还不能用这三个 App。
- 插件管理页（D5.3）未合入，启用 / 关闭目前靠 `ExtensionDebugReceiver`。
- 三个 App 的界面没有在这台真机上点过（有人在用）；界面与截图见各自 README，是模拟器上做的。
- `--live` 只覆盖三条典型指令；没有测多轮纠错、同时操作两个 App、模型选错工具之后的恢复。
- **自然语言验收的已知偶发**：提示词不带内容时模型会反问而不动手（2026-10-08 凌晨备忘录那条“记一条新品发布的备忘，打上工作标签”一次没建笔记，属正常模型行为）；驱动的提示词已改成带上标题、内容、时间和地点，之后连跑 3 次全过。另有一次驱动在启用三个插件后 149 ms 就读目录、备忘录工具还没出齐，已改成先等目录完整（最多 30 秒，超时点名缺哪些工具）；那次的现象像“目录先有后掉”，若再出现，要抓 `ExtensionDebugReceiver diag` 看 Extension Host。
- `--no-reset` 且备忘录里已有一条关于“新品发布”的备忘时，模型会先搜再追加，“新建了一条备忘”的检查会判失败；默认的 `reset` 之后不会出现。

## 四、已验证的用户流程（Pixel_8a，用 MiniMax 国内平台真实 key）

首次引导 → 设置页选 MiniMax CN 并填 key（防截屏页面）→ 对话流式输出 → 点“停止”（显示“已取消”）→ 马上再发一条能正常回答 → 设置页清除 key。清除后 logcat 和 App 私有数据里都搜不到 key。截图在工作区 `demo/2026-09-29-pixel8a-ui/`。

## 五、已知限制

- 只有 debug 签名的 zip；发布证书要维护者生成。
- 没有电池优化豁免时，`:agent` 在后台被拉起会进不了前台：电脑端的长对话会被冻结，首次引导和设置页都会提示授予。
- 监督进程只在有任务时拉起 `:agent`；电脑端接入开着但没有任务时被杀，要等开机、打开界面或有人绑定才回来。W11 在心跳加 `hold=desktop`。
- 插件页和真实确认界面（W14 / W15 / W16 的界面部分，D5.2 / D5.3）未完成：release 构建里需要确认的工具调用一律拒绝，只有 debug 构建能放行；Jev 已接入，详见三之二。
- 本机用 Clash TUN 时，模拟器访问模型端点会 TLS 失败。

## 六、需要人来做的事

1. **更多真机**：Pixel 8（Magisk 30.7，API 35）已跑完；还差 KernelSU，以及 API 36 / 37 的真机。灭屏 30 分钟、24 小时驻留（S2 的 B1、L1）和真机上的冷启动 / 内存数值（S8）也没做。真机上首次引导、填 key、界面对话要有人点界面，这一轮没有走。
2. **发布证书**：按 README 生成项目发布证书，重新打包。
3. **内测**：找 10 名极客，计从刷 zip 到第一次对话的时间。
4. 可选：国际平台的 MiniMax key，用来验证国际预设。
