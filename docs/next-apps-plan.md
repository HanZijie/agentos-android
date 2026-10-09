# 下一阶段：系统级闹钟与日历、待办、短信

> **状态**：计划草案（2026-10-08，稼轩与整合会话的调研讨论整理），已按本文实施，结果见文末“实施记录（2026-10-09）”。接口有分歧时以本文为准；要改先报告。
> **范围**：五件事。1) 闹钟 App 迭代；2) 日历 App 迭代；3) 新增待办 App；4) 新增短信 App；5) 中英文界面与示例 App 的发包（第 7 节）。
> **起因**：三个示例 App（闹钟、日历、备忘录）都是自有 SQLite 的独立小应用。要让它们更像“手机上真正的那个 App”：日历接入系统日历、和账号生态打通；闹钟成为系统认可的那个闹钟；再补两个日常高频的 App。
> **约定**：目录、包名、工具分层、注解、`plugin.json`、`SKILL.md`、`dump` / `reset`、验收口径，一律沿用 [sample-apps.md](sample-apps.md)；本文只写与它不同或新增的部分。
> **标记**：【待验证】表示调研时只有资料，没有在设备上确认；开工前先做第 5 节的验证。

## 0. 目标设备与前提

- **首批设备**：Pixel 8（Magisk，API 35 / 36），和仓库现有验证口径一致。国内厂商 ROM 在 `architecture.md` 里标为“社区适配”，本计划按“先 Pixel，再国内机型”排序；国内机型的差异单独列在各节。
- **鸿蒙 NEXT / HarmonyOS 5 不跑 APK**，不在范围内。
- 国内机能否解锁 BL、能否 root，各家策略不同，**本计划没有调研**；要做国内机型，先确定具体机型。

## 1. 闹钟 App 迭代（方案 A：自己的闹钟当唯一来源）

**现状**：`setAlarmClock` + 前台服务响铃 + 全屏页 + 开机重登记，已经是系统认可的真闹钟，状态栏有图标（`SystemAlarmScheduler.kt`）。缺的是它和系统、其他 App 的关系，以及国内机上“保证响”。

**不做**：
- 经标准 Intent 去操作 Google 或厂商自带时钟。官方 `AlarmClock` 只有设置、关闭、贪睡、显示闹钟和计时器，没有列表、修改、删除，做不出现有的 list、get、update、delete。
- 用 root 改厂商时钟的数据库。和“插件和模型不碰 root”的原则冲突，且强绑定时钟版本、改完没有刷新通知。
- 同时让 Agent 使用自己的闹钟和系统时钟，会出现两个闹钟都响。**Agent 只通过本 App 设闹钟。**

**工作项**

| # | 内容 | 说明 |
|---|---|---|
| A1 | 响应 `AlarmClock` 标准 Intent | 声明 `ACTION_SET_ALARM`、`ACTION_DISMISS_ALARM`、`ACTION_SNOOZE_ALARM`、`ACTION_SHOW_ALARMS` 的 Activity，按官方要求加 `com.android.alarm.permission.SET_ALARM`。语音助手或其他 App 说“设个闹钟”可以落到本 App，由用户在选择器里选。映射到现有仓库的创建、关闭、贪睡；相同时间、标签、重复日的闹钟不重复建。**不做计时器**（本 App 没有计时器功能）。 |
| A2 | 新增只读工具 `alarm_system_next` | 返回 `AlarmManager.getNextAlarmClock()`：系统范围的下一个闹钟（能看到 Google 时钟等其他 App 设的）、时间、`owned_by_this_app`（用 `showIntent` 的创建者包名判断）。现有 `alarm_next` 不变。只加不改，符合“可以多，不能少”。 |
| A3 | 国内机“保证准时响铃”检查页 | 检测并引导：精确闹钟权限、通知与全屏通知权限、忽略电池优化（`PowerManager.isIgnoringBatteryOptimizations`）；国内机型额外显示厂商“自启动、后台活动、多任务加锁”的文字指引（华为官方帮助页有明确说明）。**厂商私有开关没有统一命令，v1 只做文字引导，不做按品牌的深链。** 示例 App 不随 zip 安装，所以不由模块脚本加白名单。 |
| A4 | 更新 `SKILL.md` | 写明：Agent 只用本 App 设闹钟；问“明天几点起、有没有闹钟”时，可同时看 `alarm_next` 和 `alarm_system_next`；事件提醒放日历的 `reminder_minutes`，只有“某个钟点响铃”才建闹钟（沿用已有的边界，一件事一个条目）。 |
| A5 | 测试 | JVM：Intent 参数到仓库调用的映射（含 `EXTRA_DAYS` 的星期常量转换）、去重、缺参、非法值。设备：`adb shell am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR 7 --ei android.intent.extra.alarm.MINUTES 30`；Pixel 上装了 Google 时钟时会弹选择器，需分别测两种选择。 |

**风险与待验证**
- 【待验证】Pixel 8 上 `getNextAlarmClock()` 能看到 Google 时钟设的闹钟（预期可以）。
- 【待验证】国内机：厂商时钟是否用 `setAlarmClock`（决定 A2 在国内机是否看得全）；本 App 在厂商后台策略下的触发延迟。**测法**：设 10 个次日清晨的闹钟，记录实际触发偏差。
- 国内机上“可靠性”可能不如厂商自带时钟（系统应用通常在保活白名单里，这是推断，没验证）。若实测命中率不够，再评估“经标准 Intent 设置厂商时钟，只写不读”的受限模式，作为备选，不是 v1。

**验收**：A1 的 Intent 命令在 Pixel 8 上建出闹钟且到点会响；`alarm_system_next` 在系统里另有一个 Google 时钟闹钟时返回它并标明不属于本 App；全部 JVM 测试通过；`assembleDebug`、`assembleRelease`、`lintDebug` 通过；中英文验收按第 7 节通过。

## 2. 日历 App 迭代（数据层换成系统 CalendarContract）

**目标**：日历 App 读写系统日历。有 Google 账号的机型，日程自动与 Google 日历同步，不需要 OAuth、Cloud 项目和 token。国内机走 CalDAV 账号（飞书、钉钉、企业微信、iCloud 等）同步进系统日历，**代码不变，变的是手机上登录了哪个账号**。

**MCP 契约不变**：工具名和必填参数不改，Skill 和 e2e 脚本沿用；只加字段，不删字段。

**不做（v1）**
- Google Calendar REST API。要建 Cloud 项目和 OAuth 同意屏，Calendar 的 scope 基本都是敏感 scope，未验证应用有警告和用户数上限，测试状态下 token 会过期，国内机还要直连 Google 域名。留作第二期，只补 Provider 做不到的（发邀请、Meet 链接）。
- 参会人和邀请工具。

**工作项**

| # | 内容 | 说明 |
|---|---|---|
| C1 | 先做第 5 节的 V2、V3 | 确认写入、同步、飞书 CalDAV 写回，再动存储层。 |
| C2 | 重做存储层 | 现在的 `CalendarStore` 是“整表加载进内存”（`loadEvents(): List<EventSeries>`），只适合小数据。改为：按时间区间查 `Instances`（重复日程由 Provider 展开，`Occurrences.kt` 对 Provider 日程不再需要）+ 单条增删改。`CalendarRepository` 的 `StateFlow` 改为“当前视窗快照”，由 `ContentObserver` 触发刷新（云端同步随时会改数据）。 |
| C3 | 组合存储：Provider + 本机日历 | 保留现有 `SqliteCalendarStore` 作为“本机日历”来源（无账号、无权限时可用），按日历 id 路由。**默认方案，V2 后确认**（备选：用 Provider 的本地账号日历，不留 SQLite，【待验证】 `CALLER_IS_SYNCADAPTER` 的要求）。 |
| C4 | 工具语义调整 | 见下表。 |
| C5 | 权限 | `READ_CALENDAR`、`WRITE_CALENDAR` 是运行时权限，App 内引导授权；未授权时工具返回明确错误（带一句“请在日历 App 里授权”），不抛异常。 |
| C6 | 提醒 | Provider 日程的提醒写 `Reminders` 表，由系统日历 App 发通知；`ReminderScheduler` 只保留给“本机日历”。【待验证】国内机各家日历 App 对非自家写入的提醒是否照常发。 |
| C7 | 重复规则映射 | `none/daily/weekly/monthly/yearly` + `recurrence_until` ↔ RRULE。读到无法表达的 RRULE（如每月第二个周二）时返回 `recurrence: "custom"` 和 `rrule` 原文，**更新时拒绝改动该系列**，避免破坏。 |
| C8 | 国内账号接入文档 | 写进 `plugins/samples/calendar/README.md`：小米日历内置 CalDAV 导入（12.0.6.9 以上）、荣耀 MagicOS 10 日历内置账户管理、其他安卓用 DAVx⁵；飞书服务器地址 `caldav.feishu.cn`，凭证由用户在飞书里自己生成。 |
| C9 | 测试 | JVM：映射层纯函数（全天、时区、RRULE 往返）、用假的 `CalendarBackend` 测仓库；设备：Pixel 8 + Google 账号端到端。`dump` / `reset` 改造：**只清本 App 创建的事件**（用 `Events.CUSTOM_APP_PACKAGE` 打标，【待验证】），且仅 debug 构建；**绝不在带真实账号的设备上整库清理**。 |
| C10 | 文档同步 | 更新 `sample-apps.md` 4.2 的“不用 CalendarContract”和第 5 节 `dump` / `reset` 口径，更新日历 `README.md`、`SKILL.md`，以及 `extensions.md` 4.4（见决定项 D1）。 |

**C4 工具语义调整**

| 工具 | 调整 |
|---|---|
| `calendar_list` | 返回新增字段：`account`、`source`（`google` / `caldav` / `local`）、`writable`。 |
| `calendar_create` | 只能建本机日历；对账号日历的需求返回明确错误。Provider 建不出账号服务端的日历。 |
| `calendar_delete` | 只能删本机日历；拒绝账号日历。 |
| `event_create` | 默认日历改为可配置的“默认写入日历”；未配置时选第一个“可写、可见、非本机”的日历，找不到则用本机日历。**国内机没有 Google 主日历，所以不能再按“主日历”选。** |
| `event_list`、`event_search`、`free_slots`、`agenda_today` | 走 `Instances`；`limit` 与结果体积上限不变。 |
| `event_update`、`event_delete` | 对账号日历日程的删除是**高风险且会同步到云端**，保持 `destructiveHint`；确认文案要写明账号。 |

**风险与待验证**
- **【第一个验证点】飞书 CalDAV 是否支持从手机写回。** 飞书帮助中心旧版标题写的是“单向同步”，新版改成“同步”但流程描述仍是飞书到本地；少数派的实测文章称双向，并在 OPPO 上遇到两个问题：语音助手创建日程时选不到 DAVx⁵ 的日历，创建后日程会短暂消失。若实际只能单向，Agent 写进系统日历的日程飞书里看不到，要在 README 和 Skill 里如实说明，并另行评估飞书 OpenAPI（受租户管理员审批 calendar scope 限制）。
- 【待验证】Pixel 8 上写入 Google 日历后，多久出现在服务端和网页端；写入带参会人的日程会不会真的发邀请（v1 不做参会人工具，但要知道行为）。
- 同步在国内依赖 Google 网络，本地写入离线可用，同步在能连时才发生。
- DAVx⁵ 默认 240 分钟同步一次（可调）；本地写入后多久推到云端【待验证】。
- **隐私**：`event_list` 会把真实日程送给模型。作为非自带插件，读也默认每次确认，首次启用对话框要写明。
- 工具目录里不能同时存在两套日历工具（见 D1）。

**验收**：Pixel 8 登录 Google 账号，经 MCP 建的日程出现在 Google 日历 App，并同步到网页端；`event_list` 能读到手机上原有的 Google 日程；重复日程经 `Instances` 正确展开；撤销权限时工具返回明确错误；在登录了真实账号的设备上，`reset` 只清掉本 App 创建的日程；中英文验收按第 7 节通过（注意 R3：默认日历名不能冻结在创建时的语言）。

## 3. 待办 App（新增）

**定位**：日常闭环里缺的那块。现在 demo 里“下周写三个 PRD”只能塞进备忘录，没有状态、优先级、截止日期。它也是将来“会议记录”抽出的行动项的落点。平台层面没有新东西，价值在完整性，成本照备忘录的模板。

- **目录与包名**：`plugins/samples/todo`，`org.agentos.sample.todo`，插件名和 MCP 服务器名 `todo`。
- **数据**：自有 SQLite（`SQLiteOpenHelper`），仓库单例加 `StateFlow`，界面和 MCP 共用，和其他示例一致。
- **字段**：
  - `title`（必填，≤200）、`notes`
  - `status`：`todo`、`doing`、`done`、`shelved`（对应你的“待办 / 进行中 / 已完成 / 搁置”）
  - `priority`：`high`、`medium`、`low`
  - `due`：ISO-8601 带偏移的时间，或仅日期（`due_all_day`）
  - `tags`、`parent_id`（只支持一层子任务）、`completed_at`

**工具（最低要求）**

| 工具 | 必填 | 可选 | 说明 |
|---|---|---|---|
| `todo_list` | — | `status`、`priority`、`tag`、`due_before`、`due_after`、`overdue_only`、`parent_id`、`include_done`（默认 false）、`limit`、`offset` | 按优先级、截止时间排序；返回 `has_more` |
| `todo_get` | `id` | — | 含子任务 |
| `todo_create` | `title` | `notes`、`priority`、`due`、`due_all_day`、`tags`、`parent_id`、`status` | 建子任务靠 `parent_id` |
| `todo_update` | `id` | 同 create 的各字段 | 只改给出的；`idempotentHint` |
| `todo_set_status` | `id`、`status` | — | 完成时写 `completed_at`；`idempotentHint` |
| `todo_delete` | `id` | — | `destructiveHint`；有子任务时连带删除并返回删除数 |
| `todo_search` | `query` | `status`、`limit` | 标题、备注、标签的包含匹配 |
| `todo_summary` | — | — | 各状态计数、已逾期数、今天到期、本周到期；只读，给 Agent 低成本总览 |

- **命名**：不用 `tag_list` 这类泛名（备忘录已有），避免跨插件混淆。
- **界面**：自己的视觉风格、亮暗两套、edge-to-edge、中英文（默认中文）。分组为今天、即将到来、无日期、已完成；快速添加；优先级色条；子任务展开；左滑完成或删除；筛选 chips；空状态。
- **提醒**：v1 不自带提醒，界面只高亮已逾期；到期提醒由 Agent 经日历或闹钟编排（见 D4）。这样避免第三套提醒实现，也避开国内机的后台限制。
- **边界**：四个 App 的 `SKILL.md` 和备忘录的 `NoteSchedulePrompt` 要同步更新。有明确完成状态的事进待办；纯信息进备忘录；占用时间段进日历日程；到点叫醒才是闹钟。一件事一个条目。
- **不做 v1**：重复待办、自带提醒、清单或项目分组、拖拽排序、附件。
- **测试**：每个工具覆盖正常、缺参数、非法值、不存在的 id；子任务级联删除；状态流转；`todo_summary` 的时区口径；结果体积；`dump` / `reset`；加入 `sample_apps_e2e.py`。
- **演示脚本更新**：“下周写三个 PRD”落成三条待办，需求评审会进日历，提前半小时的提醒按已有边界处理。

**验收**：全部 JVM 测试通过；Pixel 8 上增删改查与 MCP 路径都通；界面截图 4 张以上；`assembleDebug`、`assembleRelease`、`lintDebug` 通过；中英文验收按第 7 节通过（截图中英文各 4 张）。

## 4. 短信 App（新增，能力型路线）

**路线**：**不当默认短信应用**。用 `READ_SMS` 读系统短信库，用 `SmsManager` 发送（官方文档：非默认应用发出的短信，系统自动写入短信库）。不做会话完整客户端、彩信、群发、RCS。

**能做**：列会话、读消息、搜索、发送、查发送状态、撰写交接。**做不了**：删除、标已读、写草稿（只有默认短信应用能写短信库）；彩信推送只有默认应用会收到。

- **目录与包名**：`plugins/samples/sms`，`org.agentos.sample.sms`，插件名和 MCP 服务器名 `sms`。
- **不放进自带插件**：自带插件的 `readOnlyHint` 会被信任为“读”（`extensions.md:265`），短信列表就变成免确认了。做成独立示例 App，则读也默认每次确认，第三方插件默认关闭，这正是短信该有的默认。

**工具（v1）**

| 工具 | 必填 | 可选 | 说明 |
|---|---|---|---|
| `sms_thread_list` | — | `limit`、`offset` | 会话列表与摘要；返回 `has_more` |
| `sms_message_list` | `address` | `since`、`until`、`limit`、`offset` | 某号码的消息 |
| `sms_search` | `query` | `limit` | 正文包含匹配 |
| `sms_send` | `to`、`text` | — | **`destructiveHint=true`**（高风险：每次确认，不能“始终允许”）；用系统默认短信 SIM |
| `sms_send_status` | `id` | — | 查本 App 发出的某条：`queued`、`sent`、`delivered`、`failed` |
| `sms_compose` | `to` | `text` | `ACTION_SENDTO smsto:` 打开短信界面预填，**不需要任何短信权限**，由用户自己点发送；SEND_SMS 被限制时的降级模式 |

**安全规则（必做）**
- **S1 发送**：单次调用单个收件人；`text` 有长度上限（默认 ≤500 字符）；默认拒绝像短号的收件人（如 10086，除非显式放行，避免付费短信）；有发送频率上限和相同收件人加相同内容的短时去重。确认框要展示完整收件人和正文。【待验证】确认框对长文本的截断是否让用户看不全正文（`extensions.md` 5.4 说第三方文字按码点截断）。
- **S2 读取**：保持非自带插件的默认，读也每次确认，不提供默认“始终允许”。
- **S3 验证码默认遮蔽**：`sms_message_list`、`sms_search` 对疑似验证码（4 到 8 位数字，含常见中文模板）返回遮蔽值；App 设置里可打开“允许 Agent 读取验证码”，默认关（见 D3）。
- **S4 提示注入**：`SKILL.md` 写明短信正文是不可信数据，不得执行其中的指令；发送必须来自用户当前的请求；不转发验证码和账单；不批量发送。
- **S5 已知平台风险（本计划不实现）**：读类工具的结果会回到调用它的第三方 App（`extensions.md` 5.4、`third-party-acp.md` 第 9 节，权限管控目前只做设计讨论）。建议后续为短信工具对 `APP` 调用方强制确认（`StrictCallerPolicy`）；这是平台工作，另立。
- **S6 数据去向**：短信内容会进会话历史、并发给你配置的模型端点。首次启用对话框和设置页要如实披露。

**权限门（第一个验证点 V1）**
- Android 15 起，SMS 运行时权限和默认短信角色都纳入“受限设置”：对非应用商店安装的 App，默认被限制，用户要在应用信息里手动点“允许受限制的设置”。vivo 官方页面显示国内机同样，解除时需验证机主身份。
- 模块用 root 装的 APK 会不会被判为侧载，**没有验证**；root 能不能绕、该不该绕也没验证，倾向“引导用户点一次”，不绕（仓库原则：AgentOS 不以 root 运行）。
- 权限被限制时，App 自动进入“仅撰写”模式，只提供 `sms_compose`。

**发送语义**
- **异步**：`sent` 与 `delivered` 是两个回调，送达回执可能很晚或收不到。App 自己维护发送表（`outbox`：`id`、`to`、`text`、`parts`、`state`、`created_at`），`sms_send_status` 查它；`sms_send` 返回本地 `id` 和“已提交”，不承诺送达。
- **分段**：用 `divideMessage` + `sendMultipartTextMessage`；中文按 UCS-2，一般 70 字一条，拼接时每条 67 字，长消息会多条计费。工具返回 `parts`，让 Agent 知道成本。
- **双卡**：v1 只用系统默认短信 SIM（`getDefaultSmsSubscriptionId`），避免申请 `READ_PHONE_STATE`；选卡槽留到 v1.1。官方文档提示多卡机上用默认实例发送可能失败，要测。
- **系统限流**：高频发送系统会弹确认（我记得量级是 30 条 / 30 分钟，没核实）。

**界面**：不做完整客户端。三块：权限与状态页（含受限设置的图文引导）、会话浏览（只读）、“Agent 发送记录”（来自 `outbox`）；加安全开关（验证码遮蔽、发送频率）。

**国内机（后置）**：厂商对发短信的单独管控、省电策略休眠时撤销权限（有指南称小米、华为、OPPO 会）、验证码识别绑在厂商短信 App 里——都只有二手资料，没在机型上验证。

**测试**：JVM：用假的 `SmsGateway`，覆盖正常、缺参数、非法号码、短号拒绝、频率限制、去重、`outbox` 状态机、验证码遮蔽的正则样例。设备：Pixel 8 发送给自己的另一个号码（有费用）。收信列表可试 `adb emu sms send` 模拟器【待验证】。**`reset` 只清 `outbox`，不碰系统短信**（非默认应用也删不掉）。

**不做 v1**：默认短信角色、彩信、删除和标已读、事件触发（来信自动触发 Agent，需要平台新增能力）、群发、选卡槽。

**验收**：V1 通过后：Pixel 8 上能读到手机里已有的短信；`sms_send` 触发高风险确认并成功发出一条；`outbox` 状态从 `queued` 到 `sent`；验证码默认被遮蔽；受限设置未解除时进入仅撰写模式；中英文验收按第 7 节通过，其中验证码遮蔽的规则必须同时覆盖中英文模板，不能跟着界面语言走。

## 5. 先做的验证（并行，各自独立）

| # | 验证 | 判定 | 影响 |
|---|---|---|---|
| V1 | 模块装的 APK 在 Pixel 8（API 35 / 36）上能否被授予 `READ_SMS`、`SEND_SMS`；后台能否收到 `SMS_RECEIVED`；确认框是否完整显示长正文 | 能授予则走完整模式；不能则只做仅撰写 | 决定短信 App 的形态 |
| V2 | Pixel 8 + Google 账号：经 `CalendarContract` 写入的日程是否出现在 Google 日历 App 和网页端、延迟多久；带参会人是否发邀请；`CUSTOM_APP_PACKAGE` 打标与按标记清理是否可行 | 同步成功，且可只清自己创建的日程 | 日历方案的前提 |
| V3 | 飞书 CalDAV：DAVx⁵ 或小米日历、荣耀日历下，在系统日历里新建日程，飞书侧是否出现；改和删是否双向 | 写回成功，才宣称“国内可打通飞书” | 国内日历方案的前提 |
| V4 | Pixel 8：`getNextAlarmClock()` 是否包含 Google 时钟的闹钟；`SET_ALARM` Intent 的选择器行为 | 能看到，选择器可预期 | 闹钟 A1、A2 |

## 6. 顺序、规模、分工

规模用相对大小：S 小，M 中，L 大。

1. V1 到 V4 并行。
2. **闹钟（S）和待办（S 到 M）** 先做，互不依赖。
3. **日历（L）**：依赖 V2、V3；存储层重做和测试是大头。
4. **短信（M，外加安全规则）**：依赖 V1 的结果。
5. 整合验收：一句话场景，见第 9 节。
6. 中英文与发包：`check-i18n` 门禁最先做，主 App 的英文 P1 要在短信 App 发布前完成，见第 7 节。

**分工沿用现有惯例**：每个 App 一条 lane、一个 worktree。已有 `app/alarm`、`app/calendar`；新增 `app/todo`、`app/sms`（对应 `agentos-wt-app-todo`、`agentos-wt-app-sms`）。**共享文档（`sample-apps.md`、`extensions.md`、各 App 的 `SKILL.md` 边界文字）由整合人统一改**，各 lane 不碰，避免冲突。

## 7. 中英文界面与发包

> 本节管两件事：A. 所有 App（现有三个、新增两个、AgentOS 主 App）的中英文界面；B. 示例 App 怎么构建、签名、发包。
> 事实来自读仓库代码和构建脚本，没有跑 lint，也没有在设备上看过界面。Android API 和 Gradle DSL 的名称（`localeConfig`、`LocaleManager`、`localeFilters`、`pseudoLocalesEnabled` 等）来自记忆，开工第一步在 AGP 8.10.1、API 35 上各编译一次确认。

### 7.1 现状

| 项 | 现状 |
|---|---|
| 三个示例 App | 已有中英文：默认 `values/` 是中文，`values-en/` 是英文，key 一一对应（闹钟 58、日历 82、备忘录 200 条，含 plurals）；日期用 `getBestDateTimePattern`，12 / 24 小时跟随系统 |
| 按 App 切换语言 | 没有。全仓库没有 `localeConfig` 和 `LocaleManager`，语言只跟随系统 |
| AgentOS 主 App | 只有 25 条字符串资源，没有 `values-en`；Kotlin 里约 485 行含中文字符串字面量（上限，含少量注释里带引号的行），分布在 31 个文件，集中在错误提示、设置页、插件管理页、首次引导、确认和授权框 |
| 核心层 | `core/runtime` 的确认文案（`ConsentText.kt`）、`core/extensions` 的校验信息里有中文；这是纯 Kotlin / JVM 模块，用不了 Android 资源 |
| 给模型看的文字 | 已是英文：系统提示词（含 “Answer in the user's language.”）、工具描述、`SKILL.md` |
| 发包 | zip 只含 AgentOS App、Runner 和脚本；**示例 App 不在 zip 里，也没有发布流程**；`release.yml`、`tools/release.py`、`docs/runbooks/` 都不存在（W28 未开始） |
| 签名与版本 | 示例 App 的 release 由根 `build.gradle.kts` 统一签名，用同一张项目发布证书，版本号共用 `agentos.version=0.1.0`、`versionCode=1` |

### 7.2 规则（五个示例 App 都遵守；主 App 见 7.3）

| # | 规则 |
|---|---|
| R1 资源 | 中文放 `values/`，英文放 `values-en/`，key 一一对应；英文复数写 `one` / `other`，中文只写 `other`。新增 key 必须在同一次提交里两种语言都有。 |
| R2 不本地化的 | 工具的 name 和 description、`SKILL.md`、MCP 返回给模型的错误信息、参数的枚举值（`todo`、`doing`、`high`）、日志。Agent 用什么语言回复由模型跟随用户决定，和 App 的界面语言无关。 |
| R3 默认名会“冻结” | 创建时写进数据库的默认名会固定在当时的语言。已确认的例子：`CalendarApp.kt:35` 把 `default_calendar_name` 的文字传给仓库，仓库首次初始化时写进 SQLite，之后切英文也不会变。规则：系统自己生成的默认名（默认日历、示例标签）存一个标记，显示时再翻译；用户自己起的名字不动。待办、短信不要重复这个坑。 |
| R4 日期与数字 | 用 `getBestDateTimePattern` / `FormatStyle`，不手拼单位字，不用 `locale.language == "zh"` 判断。已知两处要修：`calendar/.../UiData.kt:97` 的周范围按语言硬补“日”；`alarm/.../Format.kt` 的 12 小时制把上午 / 下午放在时间后面，中文习惯是“上午 7:30”（读代码得出，没看渲染，要真机确认）。 |
| R5 语言选择 | 默认跟随系统。每个 App 加 `res/xml/locales_config.xml`（`zh`、`en`）并在 manifest 声明 `android:localeConfig`，系统设置里就有“应用语言”；App 设置页放一个入口跳过去（minSdk 35，用平台的 `LocaleManager`，不引入 AppCompat）。系统语言既不是中文也不是英文时回落到默认资源，也就是中文（见 D8）。 |
| R6 术语表 | 见下表，五个 App 和主 App 共用。 |
| R7 长度 | 英文通常更长。每个界面在 en 下检查截断和换行；Compose 文本要有 `maxLines` / `overflow` 策略；在 1.3 倍字体下再看一遍列表项。 |
| R8 JVM 测试 | 产生用户文案的纯函数接收 locale 或文本提供者，中英各一个用例；不再整句硬断言中文。 |
| R9 门禁 | 见下。 |

**术语表（草案，翻译前请你过一遍）**

| 中文 | English |
|---|---|
| 插件 / 工具 | Plugin / Tool |
| 允许一次 / 始终允许 / 拒绝 | Allow once / Always allow / Deny |
| 高风险 | High risk |
| 第三方 App | Third-party app |
| 由 X 发起 | Requested by X |
| 撤销授权 | Revoke access |
| 待办 / 进行中 / 已完成 / 搁置 | To do / In progress / Done / On hold |
| 备忘录 / 日历 / 闹钟 / 短信 | Notes / Calendar / Alarm / Messages |
| 默认助理 | Default assistant |

**R9 门禁**
1. `lint.xml` 把 `MissingTranslation`、`ExtraTranslation`、`StringFormatMatches`、`StringFormatInvalid` 设为 error（它们的默认级别我没核实）。
2. 新增 `tools/check-i18n.py`，加进 `portable-tests.yml`（不需要证书，秒级）：每个有 `values-en` 的模块，两边的 key、占位符（`%s`、`%1$s`、`%d`）、plurals 的 quantity 要对得上；英文资源里不能出现中文；`src/main` 的 Kotlin 字符串字面量里出现中文就报错，白名单文件列在脚本旁的配置里（测试和注释不算）。示例 App 现在只命中 1 处（上面的 `UiData.kt`），主 App 命中约 485 行，所以主 App 先整体进白名单，随 7.3 的进度逐个移出。
3. debug 构建打开伪本地化（`pseudoLocalesEnabled`），系统语言切到 `en-XA`：没走资源的字符串不会变形，一眼就能看出。
4. 截图：每个 App 中英文各 4 张，放 `screenshots/`（在 `sample-apps.md` 的要求上翻倍）。切换语言可试 `adb shell cmd locale set-app-locales <包名> --locales en`，不用改系统语言【待验证】。

### 7.3 AgentOS 主 App 的中英文（独立工作包：界面归 D 车道，核心层文案归 A 车道）

**为什么单列**：短信发送的安全，靠确认框把收件人和正文如实展示给用户（第 4 节 S1）。示例 App 都有英文、主 App 的确认框却是中文，英文系统上用户看到的仍是中文确认，等于没做。

**难点（读代码得出）**
- 文案生成多是纯 Kotlin 函数（如 `ConsentLabels`），没有 Context，不能直接用 `R.string`；JVM 测试里有大量中文整句断言，至少 8 个测试文件含中文，最多的一个（`PluginsLogicTest`）52 行。
- 确认文案的一部分在 `core/runtime` 的 `ConsentText`（如 `要允许「…」吗？`、`由 <名字> 发起（<包名>）`），核心层不能依赖 Android 资源。
- **安全点**：第三方的名字和参数显示前要先清理，清理规则会去掉「」『』，因为界面用它们把第三方文字框起来（`ConsentText` 的注释）。英文界面若换成 “ ”、[ ] 之类的定界符，清理规则必须把**所有语言用到的定界符**都去掉，否则第三方可以在名字里伪造结尾。这条要有单元测试。

**方案**：核心层不再产出自然语言，只输出“文案 key + 参数”（参数是清理后的第三方文字）；app 层按资源映射成中英文；纯函数改成接收一个很小的 `Strings` 接口，测试用中、英两个假实现。先在“确认、授权”这一条链上做出样板再铺开；具体接口由 D、A 两个车道动手前先报告。

| 期 | 范围 | 判据 |
|---|---|---|
| P1 | 确认框、授权框、错误提示（`ConsentText`、`Consent*`、`Authorization*`、`AgentErrors`） | 英文系统上完成一次工具确认、一次第三方授权、一次出错，全部英文 |
| P2 | 首次引导、设置页、模型与 key、状态与安全说明 | 英文系统走完首次引导和设置 |
| P3 | 插件管理页、已授权 App 与用量、诊断页 | 其余界面 |

规模：每期 M，总体 L（我的估计，没量过）。

**插件显示名**：`plugin.json` 的 `displayName` 现在写死中文（闹钟示例是“闹钟”）。`extensions.md` 里没提多语言，Agent Plugins 规格本身有没有本地化字段我没核实。建议插件页优先显示 App 自己的标签（`loadLabel`，随系统语言），取不到再退回 `displayName`；App 标签是第三方文字，照旧要清理，并和包名一起显示。动手前先看 `ExtRegistry` 现在怎么取 `displayName`。

**发布线（D13）**：主 App 的 P1 没完成之前，只发中文版。

### 7.4 发包

**每个 tag 的产物**

| 产物 | 说明 |
|---|---|
| `agentos-<ver>.zip`、`.sha256` | 不变 |
| `AgentOS-Sample-<name>-<ver>.apk` × 5 | alarm、calendar、notes、todo、sms；项目发布证书签名 |
| `SHA256SUMS`、`build-info.json` | 各 APK 的 SHA-256、签名证书 SHA-256、versionCode、包含的语言、申请的权限 |
| `RELEASE-NOTES.md` | 中英双语（一份文件，先中文后英文）：变更、已知限制、已验证设备 |
| 安装与卸载手册（`docs/runbooks/`，W28） | 中英双语；短信的“允许受限制的设置”要图文 |

SDK 的 Maven 包（`acp-android`、`plugin-sdk`）的发布与文档语言归 W28，本节不展开。设计文档保持中文。

**三个决定**
- **示例 APK 不进模块 zip（D9）**：`package-module.py` 有文件白名单，`apks.list` 只含 AgentOS 和 Runner；短信 App 应该由用户主动选择；示例装不装不应影响模块的开机安装逻辑。以后需要“一键全装”再加 `--with-samples` 变体。
- **版本号共用（D10）**：五个示例与 AgentOS 同一个 `agentos.version`，发版时一起升，即使某个 App 没改。简单，代价是版本号不反映单个 App 的变化。
- **证书必须固定**：插件页按签名摘要信任，签名变了用户要重新确认（`extensions.md`），所以 CI 的 debug 证书包、`releaseTest`（用调试证书签名，只用于测试）都不能当发布物。

**流程**：新增 `tools/package-samples.py`（或并入 W28 的 `tools/release.py`），顺序如下：
1. 对 `plugins/samples/*` 逐个 `assembleRelease`，签名环境变量与 README「项目发布证书」一致。
2. `apksigner verify --print-certs`：证书摘要必须等于 AgentOS App 的。
3. `aapt2 dump badging`：包名、版本、语言（要含 `en`）、`uses-permission` 与该 App 的权限白名单一致（白名单放配置文件，新增权限要改白名单才能过）。
4. 合并后的 manifest 和 dex 里没有 debug 自测接收器（沿用 `sample-apps.md` 第 5 节的口径）。
5. 用 `core/extensions` 的 `ManifestReader` 校验 `assets/agent-plugin/plugin.json` 和 `SKILL.md`（已有 `SamplePluginsConformanceTest`）。
6. 跑 `tools/check-i18n.py`。
7. 生成 `SHA256SUMS`、`build-info.json`。APK 能否字节级可复现我没验证，不承诺；zip 本身是确定性的。

**CI**

| Workflow | 变化 |
|---|---|
| `portable-tests.yml`（每次提交） | 加 `tools/check-i18n.py` |
| `module-package.yml`（每次提交） | 不变，仍用 debug |
| `release.yml`（打 tag，W28） | 带证书跑上面的流程，附 SHA-256 和发布说明；证书走 GitHub Secrets，不进日志 |

**构建小项**
- `calendar` 的 release 没开 `isShrinkResources`，另外两个开了；确认是有意的，否则对齐。
- 可选：`androidResources.localeFilters` 只留 `zh`、`en`，裁掉 Compose / Material 库带来的其他语言字符串，APK 会小一些；先量体积再定，裁掉后非中英文系统语言下，库自带的文字回落到英文。

**短信 App 的特殊项**
- 不上应用商店。我记得 Play 对 SMS 权限限制很严（基本只给默认短信或电话应用），没核实；本计划本来就只走 GitHub Release 和 zip 旁路。
- SMS 权限受“受限设置”管，安装方式可能影响能否授予（V1 要记录结果）。发布说明要用中英文写明：默认不预装；需要在应用信息里“允许受限制的设置”才能授予；发送是高风险，每次确认；验证码默认遮蔽；短信内容会进会话历史，并发给你配置的模型端点。
- 不用 root 去绕过受限设置（第 4 节）。

**发布前检查清单（全部通过才发）**
- [ ] 上面流程 1 到 7 全绿
- [ ] 全部 JVM 测试、`lint` 通过
- [ ] Pixel 8 上从 zip 装好 AgentOS，再装 5 个 APK，插件页都能发现、能启用
- [ ] 英文系统：主 App 的 P1 和各 App 主界面截图；中文系统同样
- [ ] 第 9 节的一句话场景，中英文系统各跑一遍
- [ ] 从上一版覆盖安装：插件无需重新确认签名、数据保留（日历换存储层后，旧的本机日历数据要保留，见 C3）
- [ ] 发布说明中英文齐全，已知限制如实

### 7.5 顺序

1. `tools/check-i18n.py` 加 `lint.xml` 规则（S）：最先做，它保护后面所有工作。
2. 修 R4 的两处代码问题，四个 App 加 `locales_config` 和语言入口（S）。
3. 主 App 的 P1（M）：之后才能发英文版。
4. 待办、短信从第一天起按 R1 到 R9 做，中英文验收计入各自的验收（第 1 到 4 节）。
5. 主 App 的 P2、P3（M 到 L）：与前面并行。
6. 发包脚本和检查清单（M）：待办、短信出现后做；`release.yml` 随 W28。

## 8. 决定项（每项给了默认值，你改了再通知我）

| # | 决定 | 默认 | 说明 |
|---|---|---|---|
| D1 | 系统日历的落点：迭代日历 App，还是放进自带插件 | **迭代日历 App**；自带插件不再做日历工具组，只保留联系人、通知、Intent / 分享 | 自带插件的规划（`extensions.md` 4.4）里已有“日历、联系人”，且日历一项未实现。两套日历工具并存，模型会选错或重复建。**这与我上一轮倾向“放自带插件”不同**：按你原始需求（把案例 App 改成系统级）走迭代 App；代价是系统日历能力随这个 App 是否安装而定，不是“装上当天就有”。需要同步修改 `extensions.md` 4.4。 |
| D2 | 无账号、无权限时的回退 | 保留 SQLite “本机日历” | V2 后确认 |
| D3 | 短信验证码 | 默认遮蔽，设置里可开 | 敏感度高 |
| D4 | 待办是否自带到期提醒 | 否 | 由 Agent 编排日历或闹钟 |
| D5 | 国内机型 | 先 Pixel 8，国内机型后置 | 需要你提供具体机型和是否可 root |
| D6 | 模块是否对示例 App 用 root 预授权（如 `READ_CALENDAR`） | 否，走用户授权 | 测试脚本里可用 `adb shell pm grant` |
| D7 | 飞书 CalDAV 若只能单向 | 不上 OpenAPI，文档声明限制 | OpenAPI 路线受租户管理员审批限制 |
| D8 | 默认资源的语言（系统语言既非中文也非英文时看到的） | 保持中文，沿用 `sample-apps.md`；发布前再评估 | 国际发布时英文兜底更常见。资源名不变，切换只是把 `values/` 和 `values-en/` 的目录对调改名，不改代码 |
| D9 | 示例 APK 是否进模块 zip | 否，作为独立的 release 资产 | 7.4。以后要一键全装再加 `--with-samples` 变体 |
| D10 | 示例 App 是否各自独立版本号 | 否，与 AgentOS 共用 `agentos.version` | 简单；代价是版本号不反映单个 App 的变化 |
| D11 | 发布说明与安装手册的语言 | 中英双语；设计文档保持中文 | README 是否出英文版另议 |
| D12 | 插件页的显示名 | 优先取 App 标签（随系统语言），退回 `displayName` | 要改 AgentOS 插件页，归 D 车道；7.3 |
| D13 | 英文版的最低发布线 | 主 App 的 P1（确认、授权、错误）完成才发英文版，否则只发中文 | 短信发送的确认文案必须让用户看得懂 |

## 9. 整合验收场景

> 下周三下午 3 点和王总开需求评审会，提前半小时叫我；先把三个 PRD 列成待办；开完会给王总发短信确认纪要。

- 日历：周三 15:00「需求评审会」，写入默认写入日历。
- 闹钟：14:30 一个闹钟（按已有边界，事件提醒与闹钟不重复建）。
- 待办：三条“写 PRD”待办，带截止日期。
- 短信：`sms_send` 弹出高风险确认，写明收件人号码和完整正文，用户允许后发出。
- 联系人：自带插件的联系人组还没做，v1 场景由用户直接给号码。

## 10. 后续候选（本计划不含）

- **会议记录**（`meeting-records`）：`implementation-plan.md:485` 标“未做”；可作为第二个调用 Agent 的第三方 ACP App，把纪要抽成行动项落进待办和日历。语音转写在国内机上系统识别依赖厂商，第一版建议只收文本转写。
- **联系人**：走自带插件（系统 Content Provider）。
- **记账**：汇总、分组、对比类的工具形态，用来验证结果体积控制与分页。
- **出行、天气**：做成远端 MCP 插件，用来验证 Streamable HTTP 通道，三个示例都没走过。
- **工具目录增长**：现在 31 个工具，本计划完成后约 50 个。加到五六个 App 之后要观察模型选错工具的比例，必要时设计按需披露；仓库里目前没有这方面的设计。

## 11. 参考

- 第 7 节用到的仓库文件：`plugins/samples/*/src/main/res/`、`plugins/samples/calendar/.../CalendarApp.kt:35`、根 `build.gradle.kts`（签名与版本）、`gradle.properties`、`tools/package-module.py`、`.github/workflows/module-package.yml`、`core/runtime/.../consent/ConsentText.kt`、`core/runtime/.../scheduler/SchedulerConfig.kt`。
- 仓库：`docs/sample-apps.md`、`docs/extensions.md` 4.4 与 5.4、`docs/third-party-acp.md` 第 9 节、`docs/implementation-plan.md:485`、`docs/architecture.md:22`。
- [AlarmClock API](https://developer.android.com/reference/android/provider/AlarmClock)、[SmsManager](https://developer.android.com/reference/android/telephony/SmsManager)
- [Google Calendar API scopes](https://developers.google.com/calendar/caldav/v2/auth)
- [飞书：本地系统日历与飞书日历同步](https://www.feishu.cn/hc/zh-CN/articles/360043178673)、[少数派：DAVx5 + 钉钉、飞书日历实践](https://sspai.com/post/114520)、[荣耀 MagicOS：添加三方账户同步日程](https://www.honor.com/cn/support/content/zh-cn15893090)
- [华为：闹钟到点后不响铃](https://consumer.huawei.com/cn/support/content/zh-cn00409667/)
- [Android Authority：Android 15 受限设置扩展到 SMS](https://www.androidauthority.com/facebook-for-android-sdk-getting-started-3481098)、[vivo：受限提示框说明](https://m.vivo.com.cn/service/questions/all?categoryId=170&questionId=1836)

## 12. 实施记录（2026-10-09）

> 分支 `integration/next-apps`。只写做了什么、证据在哪、没做到什么；设备结论的出处是各 App 的 README 和 `docs/assets/i18n-p*/`。

**做了（有证据）**
- 闹钟 A1–A5：标准 Intent、`alarm_system_next`、检查页、SKILL/README、146 个 JVM 测试；Pixel 8 上 V4 实测（选择器两种选法都测了）见 `plugins/samples/alarm/README.md` “已知行为”。
- 日历 C1–C10：系统日历库 + 本机日历、视窗仓库、权限、RRULE 映射（`custom` 拒改）、`dump` / `reset` 只清自己创建的、R3 / R4；174 个 JVM 测试；C1 的 Provider 实测表见 `plugins/samples/calendar/README.md`。
- 待办（新）：8 个工具、界面、135 个 JVM 测试、中英文各 8 张以上截图。
- 短信（新）：6 个工具、S1–S6、`outbox` 状态机、仅撰写模式；V1 实测见 `plugins/samples/sms/README.md`；模拟器 5608→5604 `queued → sent → delivered`、飞行模式 `failed`。
- 备忘录：提示词按 日程 / 待办 / 闹钟 / 纯信息 分流，`toolScope` 加了 `todo_create`。
- 第 7 节：`tools/check-i18n.py` 门禁（CI 里有 job、`lint.xml` 翻译类设为 error、debug 伪本地化）；R3、R4、R5（六个 App 都有 `locales_config` 和语言入口）；主 App 的 P1 / P2 / P3 全部完成，`tools/check-i18n.allowlist` 已清空；`tools/package-samples.py`（无证书干跑 5 个 APK 通过，临时证书验证过“证书不符被拒”）；`docs/release-notes/0.1.0.md`（中英）。
- 整合：合并树全量 `./gradlew test lint` 3855 个测试 0 失败、lint 0 error；`SamplePluginsConformanceTest` 23 个（五个 App）；脚本化 e2e 117/117（模拟器，五个 App，含短信互发到 `delivered`）。

**与计划不同或新发现**
- **第 9 节的原句在真实模型下不稳定**：“提前半小时叫我”被模型理解成“叫醒”，建了 14:30 闹钟、**没建日历事件**（5 次里 5 次）。给闹钟 / 日历工具描述加边界说明没有改善（已还原）。改成“在日程里提前 30 分钟提醒我”后，完整五个 App 的 `live.cross` 3 次都过（13 项检查全过：1 个日程 + 事件自带提醒 + 3 条待办 + `sms_send` 触发高风险确认、短号被拒、没有真发）。驱动用后一种说法；**原句要保持 Agent 自己判断的话，需要产品层面的取舍（例如提示词或 Skill 的优先级），不是本计划能解决的**。
- 模型日期算术：“下周三”有 2 次被算成周四（10-15），3 次对（10-14）；偶发，没改断言。
- `--live` 的 `live.cross` 只在模拟器上跑过；真机上没跑（真机有真实数据，也没有账号）。

**没验证 / 没做**
- V2（Google 账号同步到网页端、带参会人是否发邀请）、V3（飞书 CalDAV 写回）：测试机没有账号，**未验证**；验证步骤在日历 README。
- 国内机型：全部未测（D5：先 Pixel）。
- 短信真实 SIM 发送、确认框对长正文的实际渲染（只读了代码）。
- 示例 APK 的签名发包：只做了无证书干跑和临时证书的拒绝/放行验证；**没有用项目发布证书打过真正的发布包**，`release.yml` 仍是 W28。
- 从上一版覆盖安装的演练（插件签名不变、数据保留）：日历的数据保留在模拟器上验证过；真机上的覆盖安装**未做**。
- 主 App 英文在真机上的 P1 / P2 / P3 走查：只在模拟器上做了，真机没有走。
