# 日历（示例 App）

`org.agentos.sample.calendar` —— AgentOS 的示例 App 之一（[docs/sample-apps.md](../../../docs/sample-apps.md)、[docs/next-apps-plan.md](../../../docs/next-apps-plan.md) 第 2 节）。界面完整、有真实提醒的日历，同时内嵌一个 Agent Plugin 并导出 Binder MCP 服务：装在手机上后，AgentOS 能发现它、列出工具，经 MCP 读写日历和日程，界面实时刷新。

**这一版的变化**：日程不再只存在自己的 SQLite 里。App 读写手机的**系统日历库**（`CalendarContract`），所以手机上登录了 Google、CalDAV（飞书、钉钉……）等账号后，账号里的日历和日程就在这里，写进去的日程由系统同步到云端；没有账号、没有授权时，仍然有一个“本机日历”（原来的 SQLite）可用。**不需要 OAuth、Cloud 项目和 token**。MCP 契约不变：工具名和必填参数没动，只加字段。

| 月视图 | 日程（议程） | 日历管理（账号 · 来源 · 默认写入） |
|---|---|---|
| ![月视图](screenshots/zh-01-month.png) | ![议程](screenshots/zh-02-agenda.png) | ![日历管理](screenshots/zh-03-calendars.png) |

| 编辑（账号日历） | 详情 | 删除确认（写明账号） |
|---|---|---|
| ![编辑](screenshots/zh-04-editor.png) | ![详情](screenshots/zh-05-detail.png) | ![删除确认](screenshots/zh-06-delete-dialog.png) |

| 权限引导 | 设置 | 周视图 |
|---|---|---|
| ![权限引导](screenshots/zh-10-permission.png) | ![设置](screenshots/zh-07-settings.png) | ![周视图](screenshots/zh-09-week.png) |

| 暗色 · 月 | 暗色 · 日历管理 | 小屏（720×1280）· 月 | 小屏 · 日历管理 |
|---|---|---|---|
| ![暗色月](screenshots/zh-08-dark-month.png) | ![暗色日历管理](screenshots/zh-11-dark-calendars.png) | ![小屏月](screenshots/zh-12-small-month.png) | ![小屏日历](screenshots/zh-13-small-calendars.png) |

英文界面（应用语言切到 English，`cmd locale set-app-locales org.agentos.sample.calendar --locales en`）：

| 月 | 议程 | 日历管理 |
|---|---|---|
| ![en 月](screenshots/en-01-month.png) | ![en 议程](screenshots/en-02-agenda.png) | ![en 日历](screenshots/en-03-calendars.png) |

| 编辑 | 详情 | 删除确认 |
|---|---|---|
| ![en 编辑](screenshots/en-04-editor.png) | ![en 详情](screenshots/en-05-detail.png) | ![en 删除](screenshots/en-06-delete-dialog.png) |

| 权限引导 | 被永久拒绝后“去设置” | 设置 | 默认写入日历 |
|---|---|---|---|
| ![en 权限](screenshots/en-08-permission.png) | ![en 拒绝](screenshots/en-11-permission-denied.png) | ![en 设置](screenshots/en-07-settings.png) | ![en 默认](screenshots/en-17-default-picker.png) |

| 周（`Oct 4 – 10`，R4） | 暗色 | 1.3 倍字体 · 日历管理 | 1.3 倍字体 · 编辑 |
|---|---|---|---|
| ![en 周](screenshots/en-09-week.png) | ![en 暗色](screenshots/en-10-dark-month.png) | ![en 1.3x 日历](screenshots/en-13-font13-calendars.png) | ![en 1.3x 编辑](screenshots/en-16-font13-editor.png) |

| 小屏 · 月 | 小屏 · 日历管理 | 小屏 · 编辑 |
|---|---|---|
| ![en 小屏月](screenshots/en-19-small-month.png) | ![en 小屏日历](screenshots/en-20-small-calendars.png) | ![en 小屏编辑](screenshots/en-21-small-editor.png) |

> 截图里的账号日历是**模拟器上造的假账号**（`agentos-test-*@example.com`，调试命令 `provider_setup`），日程是 `seed` 灌的示例数据，没有任何真实日程。

## 两类日历

| | 本机日历 | 系统 / 账号日历 |
|---|---|---|
| 存在哪 | 本 App 自己的 SQLite（`calendar.db`） | 手机的系统日历库（`CalendarContract`），和别的日历 App 共用 |
| 工具里的标记 | `storage: "app"`、`source: "local"`、id 前缀 `local:` | `storage: "system"`、`source: google / caldav / local / other`、id 前缀 `sys:` |
| 需要权限 | 不需要 | `READ_CALENDAR` + `WRITE_CALENDAR`（运行时权限） |
| 同步 | 不同步 | 有账号的日历由系统同步到云端（本 App 不碰 token）；账号类型为 LOCAL 的不同步 |
| 能建 / 删 / 改名 | 能（默认日历不能删） | **不能**：只能在本 App 里显示 / 隐藏，改名改色删除归账号所在的日历 App |
| 提醒 | 本 App 用 `AlarmManager` + 通知 | 写进 `Reminders` 表，由系统日历 App 发通知 |
| 重复日程展开 | `Occurrences`（本 App） | `Instances`（Provider） |

`source` 的归类（`ProviderMapping.classify`）：`LOCAL` → `local`；`com.google` → `google`；DAVx⁵（`bitfire.at.davdroid`）、CalDAV-Sync（`org.dmfs.account`）→ `caldav`；**其他一律 `other`**（Exchange、厂商账号、不认识的类型），原始类型留在 `account_type` 字段里。这张表来自这些 App 公开的账号类型字符串，**没有在国内机上逐一验证**，遇到新类型在 `ProviderMapping.kt` 里加一行。

## 设计

```
CalendarRepository ── 按 id 前缀路由 ──┬─ LocalBackend  ── CalendarStore ── SqliteCalendarStore（本机，整表在内存，数据量小）
 │  window: StateFlow<WindowSnapshot>  └─ SystemBackend ── ContentResolver（Instances / Events / Reminders / Calendars）
 │  calendars / localEvents / defaultWriteId     └─ ProviderMapping（纯函数：RRULE、全天、时区、提醒换算、账号分类）
 └─ 工具侧阻塞读写；界面侧只看“当前视窗快照”
```

- **`CalendarBackend` 接口**有三个实现：`LocalBackend`、`SystemBackend`，以及测试里的 `FakeSystemBackend`。校验、权限提示、默认写入日历、搬家规则在仓库里，后端只管读写。
- **视窗**：界面按标签页报告它要的区间（月 ±1 个月、周 −2…+3 周、议程到展开的天数），`setWindow` 在后台加载 `Instances`，完成后 `window` 更新；`ContentObserver` 在系统日历数据变化（云端同步随时会改）时触发刷新，回到前台时也刷新一次。区间外的日期此刻没有数据，不等于没有日程——界面用 `CalendarData.covers` 判断，议程在数据没到之前不判“空”。
- **id**：对外仍是字符串，带来源前缀（`local:<uuid>`、`sys:<数字>`）。**升级前的数据原样保留**：库里的行没改，仍是裸 UUID，进出仓库时加 / 去前缀；不带前缀的 id（旧通知、旧脚本）一律按本机处理。库结构 v1→v2 只加了 `calendars.name_key` 一列。
- **默认写入日历**：`event_create` 不给 `calendar_id` 时写这里。用户在 App 里选（日历管理 / 设置），没选则取**第一个“可写、可见、非本机”的日历**，找不到就用本机默认日历。**不按“主日历”选**（国内机没有 Google 主日历，`CalendarInfo` 里根本没有这个字段）。没权限时系统日历列表是空的，所以自然落到本机。
- **权限**：见下节。**搬家**：已有的账号 / 系统日历日程不能改日历（Provider 的行为因账号而异，也容易弄丢参会人），本机日历之间可以。
- **撤销删除**：只有本机日历的日程有“撤销”；账号日历里重新插入会得到新 id、丢掉参会人和同步记录，所以不提供，删除确认框里写明了账号。

## 权限（C5）

- 清单里声明 `READ_CALENDAR`、`WRITE_CALENDAR`。首页顶部有引导横幅（说清授权后能做什么、不授权也能用本机日历）；点“授权”弹系统框；被拒两次（系统不再弹）后按钮变成“去设置”，跳应用信息页；从设置回来（`ON_RESUME`）自动重新检查并刷新。设置页里也能看到状态。
- 未授权时：涉及系统日历的工具返回固定的一句话 `Calendar permission not granted; ask the user to grant it in the Calendar app`（`isError`，**不抛异常**）——包括没指定日历的 `event_list` / `event_search` / `agenda_today` / `free_slots`（不悄悄只给一半结果，以免“你这天有空”的误判）。本机日历照常：给本机日历的 `calendar_id` 即可；`event_create` 不指定日历则落到本机默认日历；`calendar_list` 只列本机日历。`event_search` 为此多了一个可选的 `calendar_id`。
- **注意**：没有授权的 App 连 `registerContentObserver` 都会抛 `SecurityException`，所以观察者在授权之后才注册。

## 工具语义（C4）

名字和必填参数不变，只加字段：

| 工具 | 语义 |
|---|---|
| `calendar_list` | 新增 `account`、`account_type`、`source`（`google` / `caldav` / `local` / `other`）、`writable`、`storage`（`app` / `system`）。**`is_default` 现在表示“默认写入日历”**（`event_create` 不给日历时写的地方，只有一个），不再是“不能删的那个”。 |
| `calendar_create` | 只能建本机日历（`source: "local"`，`storage: "app"`）；名字不能和任何日历重复。账号日历在服务器上，Provider 建不出（实测：不带 sync adapter 标志建日历抛 `Only sync adapters may write to account_name`）。 |
| `calendar_update` | 本机日历：改名、颜色、显隐；账号 / 系统日历：只能显隐（只影响本 App 的界面，**不改系统库里的 `VISIBLE`**，存在 App 自己的设置里）。 |
| `calendar_delete` | 只能删本机日历（连日程）；账号 / 系统日历、本机默认日历都拒绝。 |
| `event_create` | 默认写入日历见上；只读日历报错 `read-only`。 |
| `event_list` / `event_search` / `agenda_today` / `free_slots` | 本机 + 系统日历合并，系统日历走 `Instances`；`limit`、结果体积上限（65,536 字符）不变。`free_slots` 不把“显示为有空”“已拒绝”“已取消”的日程算作忙碌。 |
| `event_update` / `event_delete` | 对账号日历是**高风险且会同步到云端**，保持 `destructiveHint`，描述里写明；界面里的删除确认写明账号。`custom` 重复的日程拒绝更新（下节），只读日历拒绝改和删。 |

## 重复规则（C7）

`none / daily / weekly / monthly / yearly` + `recurrence_until` ↔ RRULE，纯函数在 `ProviderMapping.kt`，有往返测试：

| | 写入（Provider 的约定，实测见下） |
|---|---|
| 不重复 | `DTSTART` + `DTEND`，`DURATION` 空 |
| 重复 | `DTSTART` + `DURATION` + `RRULE`，**`DTEND` 必须空**；定时 `P{秒}S`，全天 `P{天数}D` |
| `UNTIL` | 定时：UTC `yyyyMMdd'T'HHmmss'Z'`（含，秒精度）；全天：日期 `yyyyMMdd`（含当天） |
| 全天 | `DTSTART` / `DTEND` 是 UTC 零点，`EVENT_TIMEZONE = "UTC"`，`DTEND` 是最后一天的次日（不含）；读回时按设备时区换成日界 |
| 时区 | 固定偏移（`+05:30`）写成 `GMT+05:30`（Android 的 `TimeZone` 不认前者） |
| 提醒 | 全天日程：本 App 的“相对第一天 09:00”换成 Provider 的“相对 00:00”（`0 → -540`，`1440 → 900`，Provider 接受负值）；邮件 / 短信提醒、“用日历默认”（-1）读不到具体值，跳过；最多 5 个 |

读 RRULE 时**只认能无损往返的**：`FREQ` 为 DAILY / WEEKLY / MONTHLY / YEARLY，可带 `UNTIL`，以及不改变含义的 `WKST`、`INTERVAL=1`、与开始日一致的 `BYDAY`（每周、单个）/ `BYMONTHDAY`（每月）/ `BYMONTH`+`BYMONTHDAY`（每年）（Google 常把每周写成 `FREQ=WEEKLY;WKST=SU;BYDAY=TH`）。其余——COUNT、`INTERVAL` > 1、`BYDAY` 多值或带序号（每月第二个周二 `2TU`）、`BYSETPOS`，以及日程上有 `RDATE` / `EXDATE` / `EXRULE` 的（改了开始时间会让这些例外错位）——一律返回 `recurrence: "custom"` 和 `rrule` 原文，**更新时拒绝**（明确错误，什么都不改；删除整个系列允许）。`custom` 只能读不能写，`recurrence: "custom"` 作为输入会报错。

## 提醒（C6）

- 本机日历：`ReminderScheduler`（`AlarmManager.setExactAndAllowWhileIdle` + 通知）只管本机日历的日程，行为和以前一样。
- 系统 / 账号日历：提醒写进 `Reminders` 表（`METHOD_ALERT`），**由系统日历 App 发通知**，本 App 不排闹钟，详情页里有一句说明。
- 【待验证】国内机各家日历 App（小米、荣耀、OPPO……）对非自家写入的提醒是否照常发：本次只有 Pixel 真机（无账号）和模拟器，**没有验证**。

## C1 验证：系统日历库的实测行为

环境：模拟器 `Pixel_8a`（API 36，Google Play 镜像，`com.android.providers.calendar` 版本 16），无账号；调试命令 `provider_probe` / `provider_watch` 在**本 App 的 uid** 下做实验（另有 shell uid 的 `content` 命令对照）。**只有这些验证过；“同步到 Google / 飞书”没有验证**，见下节。

| 项 | 结论 |
|---|---|
| 建日历 | 持 `WRITE_CALENDAR` 的普通 App 用 `CALLER_IS_SYNCADAPTER=true` + `ACCOUNT_TYPE_LOCAL` 能建（Provider 不检查调用者是不是真的同步适配器）；不带标志：`IllegalArgumentException: Only sync adapters may write to account_name` |
| 增删改查 | 往本地账号日历写入 / 读取 / 修改 / 删除事件，系统模式自测 46 项全过（见“测试”） |
| `Instances` 展开 | 每周重复按区间展开（3 次）；全天重复的 `UNTIL` 用 `yyyyMMdd`、`…T000000Z`、`…T235959Z` 三种写法都展开出含截止日的同样结果；`COUNT`、`EXDATE` 生效；全天实例 `BEGIN` 是首日 UTC 零点、`END` 是末日次日 UTC 零点（不含）；Instances 投影能一次拿到账号类型、访问级别、`hasAlarm`、`rrule`、`eventColor` 等（所以每个区间只查一次再批量补提醒） |
| 重复日程的坑 | 重复日程写 `DTEND`（无 `DURATION`）Provider **不报错**，但 `lastDate` 被算成 `DTEND`（错）→ 必须写 `DURATION`；全天重复用 `PT86400S` 直接抛 `IllegalArgumentException`，要用 `P1D` |
| 负提醒 | `minutes = -540` 被接受并原样保存 |
| `CUSTOM_APP_PACKAGE` | 插入时能写自己的包名并读回；插入后也能改；`DELETE … WHERE customAppPackage = ?` **只删带标记的**，未打标的和标成别的包名的都留下 → 可用于“只清自己创建的事件”。**但任何 App 都能写任意字符串**（实测写入 `com.example.someone.else` 成功）：它是约定，不是安全边界。API 36 上没看到针对这个列的新限制。同步适配器把事件同步回来之后标记是否保留：**未验证** |
| `ContentObserver` | **没有 `READ_CALENDAR` 时 `registerContentObserver` 本身就抛 `SecurityException`**（系统按被观察 URI 的 Provider 权限校验）；有权限后，Provider 的变更通知**延迟约 7–10 秒**才到，并且不是每次写入都立刻通知（别的进程 / shell 的写入、带或不带 sync adapter 标志都一样；`ACTION_PROVIDER_CHANGED` 动态接收器 25 秒内没收到）。所以：自己的写入立刻主动刷新，外部变化靠观察者 + 回到前台时刷新兜底 |
| 无授权 | 任何 `query` 抛 `SecurityException: Permission Denial: opening provider … requires READ_CALENDAR or WRITE_CALENDAR`；App 把它换成固定的工具错误（不抛异常），本机日历照常 |
| 无账号 | 模拟器（`Accounts: 0`）的系统日历库里一个日历都没有；Pixel 8 真机同样：授权后系统日历 0 个、0 条日程，App 只有本机日历（见“测试”） |

### 【未验证】需要有账号的设备（做完请回填）

本次**没有 Google 账号、没有飞书凭证**，下面这些都没有做，不要当作已验证：

1. **Pixel 8 登录 Google 账号后同步到网页端**：授权后 `provider_setup` 不要用（真机会拒绝）；用 MCP / 界面建一个日程（默认写入日历应自动选中 Google 日历），记下时间，看多久出现在 calendar.google.com 和 Google 日历 App；改、删再各看一次。
2. **带参会人是否发邀请**：v1 没有参会人工具，但要知道行为；在系统日历 App 里给一个日程加参会人，再用本 App 改标题，看是否重发邀请、是否丢参会人。
3. **`event_list` 能读到手机上原有的 Google 日程**，每周 / 每月重复的 `Instances` 展开和 Google 日历 App 里一致；每月第二个周二的日程应显示为 `recurrence: "custom"` 且 `event_update` 被拒。
4. **`reset` 只清本 App 的日程**：先 `dump` 看 `other_events`（别人 / 同步下来的数量），`reset` 之后 `other_events_untouched` 应等于它，Google 日历 App 里原有日程都在。
5. **打标是否在同步来回之后保留**：本 App 建一个日程，等它同步到云端再同步回来（改一下标题触发），`dump` 里还能不能看到它（`CUSTOM_APP_PACKAGE` 还在不在）。
6. **DAVx⁵ / 飞书 CalDAV**：见下节的验证步骤。
7. **国内机日历 App 对非自家写入的提醒**是否照常发（C6）。

## 国内账号接入（C8）

国内机没有 Google 日历，走 CalDAV 账号把飞书、钉钉等同步进系统日历。**代码不变，变的是手机上登录了哪个账号**。以下来自飞书帮助中心《设置本地系统日历与飞书日历之间的同步》（<https://www.feishu.cn/hc/zh-CN/articles/360043178673>，本次读到了页面内容，更新于 2025-11）和荣耀官网《添加三方账户同步日程》（<https://www.honor.com/cn/support/content/zh-cn15893090>，只读到标题和摘要）；没核实到的标了“未核实”。

- **飞书凭证**由用户**自己在飞书里生成**：飞书桌面端 个人头像 → 设置 → 日历 → CalDAV 同步配置，选目标设备，点“生成”，得到 CalDAV 用户名和密码（页面原话）。本 App 不保存、不需要这些凭证，只读写系统日历库。
- **服务器地址 `caldav.feishu.cn`**（页面里 DAVx⁵ 步骤的“根地址”）。
- **小米**：小米日历内置 CalDAV 导入：打开小米日历 → 右上角“···” → 设置 → 日程导入 → CalDAV 账户导入，填上面的用户名、密码和服务器地址；页面注明**支持小米日历 12.0.6.9 以上版本**。
- **荣耀**：荣耀日历内置账户管理（日历 → 账户管理，可添加飞书、钉钉、iCloud 等三方账户）。荣耀页面我只读到标题和摘要；**“MagicOS 10 以上”这个版本要求和具体点击路径未核实**，以手机上实际页面为准。
- **其他安卓**（飞书页面写的是“除小米外的其他安卓机型”）：安装 DAVx⁵，打开后授予系统日历写入权限，添加账户选“使用 URL 和用户名登录”，根地址填 `caldav.feishu.cn`，填用户名和密码；添加成功后点账户，在 CARDDAV / CALDAV / WEBCAL 里选 CALDAV，勾选要同步的日历并点同步按钮；同步成功后在系统日历里能看到飞书日程。**DAVx⁵ 默认约 240 分钟同步一次（可调）**——这条来自计划文档，**未核实**；本地写入后多久推到云端也未验证。
- **飞书 CalDAV 能不能从手机写回：未验证。** 飞书页面写的方向是“飞书日历 → 本地系统日历”（标题旧版叫“单向同步”）；少数派一篇文章（<https://sspai.com/post/114520>）的标题称 DAVx⁵ + 飞书可以 CalDAV 双向同步，并（据计划文档）在 OPPO 上遇到“语音助手创建日程时选不到 DAVx⁵ 的日历”“创建后日程会短暂消失”两个问题——我只读到了标题和摘要，正文和这两个问题**没有核实**。**如果实测是单向的，后果是：Agent 写进系统日历的日程飞书里看不到，只在这台手机上有**（本 App 的日历列表里它仍显示为 `caldav` 账号日历）。此时的选择：把默认写入日历改成本机日历 / 别的账号日历；或另行评估飞书 OpenAPI（受租户管理员审批 calendar scope 限制，计划里 D7 默认不做）。
- 验证步骤（有飞书凭证和 DAVx⁵ / 小米 / 荣耀的机器上）：同步好之后在日历管理里确认飞书日历是 `source: caldav`、`writable: true`；用本 App 新建一个日程；等同步（DAVx⁵ 可手动点同步）后看飞书里有没有；再在飞书里改标题、删除，看手机侧；再在手机侧用本 App 改、删，看飞书侧。把结果写回这一节。

## 功能（界面）

- **月视图**：左右滑动翻月，有日程的日期下面是各日历颜色的色条（超出显示小圆点），点日期在下方面板看当天日程；邻月日期淡显；小屏自动压缩网格。
- **周视图**：七列时间轴，重叠的日程并排，全天日程在顶部一行，红色“现在”线；点空白处按半小时取整新建。
- **议程**：从今天起按日期分组的日程流（日期头吸顶，滚到底自动再加载）。
- **新建 / 编辑**：标题、全天、起止、地点、备注、所属日历（显示账号；账号日程不能搬家）、颜色、多个提醒、重复（可设截止日期）；有未保存修改时返回会确认；本机日程删除后可“撤销”。
- **日历管理**：本机日历一组、每个账号一组；开关显示 / 隐藏；本机日历可新建、改名改色、删除；账号日历标出“只读”“默认写入”，可设为默认写入日历。
- **设置**：应用语言（跳系统的“应用语言”页，默认跟随系统）、日历权限状态、默认写入日历。
- **搜索**：标题 / 地点 / 备注的包含匹配（本机 + 系统日历），重复日程每个系列一条。
- **详情**：显示日历的账号和来源；`custom` 重复的日程只能看和删，页面上有说明。

## 中英文（R3 / R4 / R5 / R7 / R8）

- **R3**：默认日历的名字不再写死在创建时的语言里。库里存标记 `name_key = "default"`，显示时按当前应用语言翻译；用户改过名的不动；只改颜色（界面会把当前显示的名字一并传回）不算改名。**旧库的迁移**：v1 的默认日历名恰好等于中文默认名（`我的日历`，不是“日历”）或英文默认名（`My Calendar`）的，当作“没被用户改过”，迁移时标成默认；改成别的名字的不动。取舍：用户恰好把默认日历改回这两个名字之一，也会跟着语言变。**设备证据**：模拟器上旧版（英文系统下被冻结成 `My Calendar`）覆盖安装后 `user_version` 1→2、`name_key = default`，应用语言 en → zh → en → zh 切换时名字依次是 `My Calendar` / `我的日历` / `My Calendar` / `我的日历`，库里的行没被改写。
- **R4**：周范围改用 ICU `DateIntervalFormat`（`Fmt.weekRange`），不再按语言硬补“日”：中文 `10月5日至11日`，英文 `Oct 4 – 10`（周起始日也跟随系统区域）。
- **R5**：`res/xml/locales_config.xml`（`zh`、`en`）+ manifest `android:localeConfig`；设置页有“语言”入口（`Settings.ACTION_APP_LOCALE_SETTINGS`，不引入 AppCompat）。
- **R7**：英文下和 1.3 倍字体下各界面看过（见上面的截图），文字都有 `maxLines` / `overflow`；设置页的“当前值”改成标题下方一行，避免 1.3 倍字体下被截断。
- **R8**：产生文案的数据层通过 `CalendarTexts` 提供默认日历名，JVM 测试中英各一个用例；工具 / 错误信息 / `SKILL.md` 保持英文（R2）。`src/main` 的 Kotlin 里没有中文字面量（旧默认名用 Unicode 转义，见 `LegacyDefaultNames`）。

## MCP 工具

插件名 `calendar`，MCP 服务器名 `calendar`，服务 `org.agentos.sample.calendar.agent.CalendarMcpService`（导出，要求 `org.agentos.permission.BIND_MCP_SERVICE`），插件 1.1.0。工具定义在 `tools/CalendarTools.kt`（与 SDK 无关）。时间参数都是 ISO-8601 带偏移，缺偏移时按设备时区解释。结果的 `content` 是紧凑 JSON，`structuredContent` 是同一个对象；错误是 `isError` + 一句话。

| 工具 | 必填 | 可选 | 注解 |
|---|---|---|---|
| `calendar_list` | — | `limit`, `offset` | readOnly |
| `calendar_create` | `name` | `color` | — |
| `calendar_update`（多出） | `id` | `name`, `color`, `visible` | idempotent |
| `calendar_delete` | `id` | — | **destructive** |
| `event_list` | — | `from`, `to`, `calendar_id`, `query`, `limit`（默认 50，最大 200）, `offset` | readOnly |
| `event_get` | `id` | — | readOnly |
| `event_create` | `title`, `start` | `end`, `all_day`, `location`, `description`, `calendar_id`, `reminder_minutes`, `recurrence`, `recurrence_until`, `color` | — |
| `event_update` | `id` | 同 create 的各字段（整个系列） | idempotent |
| `event_delete` | `id` | — | **destructive**（账号日历：同步到云端） |
| `event_search` | `query` | `calendar_id`（新增）, `limit`, `offset` | readOnly |
| `agenda_today` | — | `calendar_id`, `limit`, `offset` | readOnly |
| `free_slots` | `date`, `duration_minutes` | `day_start`, `day_end`, `calendar_id`, `include_all_day`, `offset` | readOnly |

事件 JSON 里 `id` / `series_id` / `calendar_id` 带前缀；`recurrence` 可能是 `custom`，此时多一个 `rrule`。

## 构建与测试

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21) ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew --max-workers=3 :plugins:samples:calendar:testDebugUnitTest \
  :plugins:samples:calendar:assembleDebug :plugins:samples:calendar:assembleRelease :plugins:samples:calendar:lintDebug
```

JVM 单元测试 174 个（改造前 99 个），不需要设备：`ProviderMappingTest`（账号分类、时区、时长、RRULE 读写与往返、UNTIL、提醒换算、行 ↔ 模型、全天 / 时区 / 跨日实例）、`CalendarRoutingTest`（用 `FakeSystemBackend`：路由、默认写入日历的选法、权限缺失、custom 拒改、只读、搬家、视窗、搜索、只清自己创建的）、`MigrationAndLocalizationTest`（旧库升级后数据和 id 原样保留、旧 id 仍能用、R3）、`CalendarToolsSystemTest`（工具语义与契约不变）、`OccurrencesTest`、`CalendarRepositoryTest`、`CalendarToolsTest`、`FreeSlotsTest`、`ReminderPlannerTest`、`WeekLayoutTest`、`WireLimitTest`、`DebugDump*Test`。库文件本身的 v1→v2 迁移（`ALTER TABLE` + 打标记）要 Android 的 SQLite，JVM 测不了；迁移用的判定函数（`LegacyDefaultNames.isSystemDefault`）有测试，整体用上面的覆盖安装在设备上验过。

## 设备上验证与调试（仅 debug 包）

debug 包带两个只给 `adb` 用的接收器（要求 `DUMP` 权限，只有 shell 能发；release 的合并 manifest 和 dex 里都没有）：

```bash
A=org.agentos.sample.calendar
adb shell pm grant $A android.permission.READ_CALENDAR; adb shell pm grant $A android.permission.WRITE_CALENDAR   # 自测要先授权
# MCP 自测：独立的 :selftest 进程经 McpBinderClient 绑定本 App 的 CalendarMcpService（真跨进程 Binder），结果一行 JSON
adb shell am broadcast -n $A/.debug.McpSelfTestReceiver                      # 本机日历，全部工具（27 项）
adb shell am broadcast -n $A/.debug.McpSelfTestReceiver --es mode system     # 仅模拟器：假账号日历，全部工具（46 项）
adb shell pm revoke $A android.permission.READ_CALENDAR; adb shell pm revoke $A android.permission.WRITE_CALENDAR
adb shell am broadcast -n $A/.debug.McpSelfTestReceiver --es mode denied     # 没授权：固定的权限错误，本机日历照常（18 项）
```

`DebugReceiver`（`-n $A/.debug.DebugReceiver -a x --es cmd …`）：`seed [--es lang zh|en]`（灌示例数据，只动本机日历）、`seed_system`、`remind_test`、`dump`、`reset`、`set_default [--es id …]`；仅模拟器（真机会拒绝）：`provider_setup [--es lang …]` / `provider_teardown`（造 / 删三个假账号日历：Google 可写、CalDAV 只读、LOCAL 类型，账号名都以 `agentos-test` 开头）、`provider_probe`（C1 实验）、`provider_watch`。

### `dump`（只读）与 `reset`

返回都放在**广播的 result data**（JSON 字符串；result code 1 = 成功，2 = 失败，失败时 `{"error":"..."}`），不进 logcat。

```bash
adb shell am broadcast -n $A/.debug.DebugReceiver -a x --es cmd dump [--ei offset N --ei limit M]
adb shell am broadcast -n $A/.debug.DebugReceiver -a x --es cmd reset
```

`dump` 沿用原来的口径（`calendars[]`、`events[]` 每个系列一行、时间是带偏移的 ISO、`reminder_minutes`、`reminders_scheduled[]`、分页字段），**只加字段**：`calendars[]` 多了 `account`、`account_type`、`source`、`writable`、`storage`，id 带前缀；顶层多了 `system_access`、`other_events`。`events[]` 只列**本 App 创建的**日程：本机日历的全部，加上系统日历里带本 App `CUSTOM_APP_PACKAGE` 标记的；别的 App 的、同步下来的日程**从不列出**（真机上可能是真实数据），只在 `other_events` 里数个数。系统日历的日程没有创建 / 更新时间戳，`created_at` / `updated_at` 为 `null`。

`reset` 只删本 App 创建的日程（本机全部 + 系统日历里打了标的）和本机的非默认日历，复位默认写入日历设置，取消提醒闹钟，同步完成：

```json
{"cleared": 7, "calendars_remaining": 1, "remaining_scheduled": 0,
 "system_events_cleared": 1, "system_calendars_untouched": 3, "other_events_untouched": 1}
```

`cleared` = 本机日程数 + 打标的系统日程数；`calendars_remaining` = 本 App 自己的日历数（默认日历永远留着，所以是 1）；`system_calendars_untouched` / `other_events_untouched` 是没碰的系统日历数和别人的日程数。**绝不整库清理，也不删系统日历。** 设备验证：模拟器上 `reset` 删了 6 条本机 + 1 条打标的系统日程，shell 写进同一个日历的未打标日程 `SomeoneElsesMeeting` 和 3 个假账号日历原封不动。

## 目录

```
src/main/java/org/agentos/sample/calendar/
  data/       Model、Ids（id 前缀）、CalendarBackend、LocalBackend、SystemBackend、ProviderMapping（纯函数）、CalendarRepository（路由 + 视窗）、
              Occurrences（本机展开）、SqliteCalendarStore、CalendarStore（含 LegacyDefaultNames）、FreeSlots
  tools/      CalendarTools（与 SDK 无关的工具定义）、IsoTime、ToolTypes、WireSize
  agent/      CalendarMcpService（唯一与 plugin-sdk 有关的类）
  reminder/   ReminderPlanner、ReminderScheduler（只管本机日历）、通知与广播接收器
  ui/         主题、月 / 周 / 议程 / 详情 / 编辑 / 日历管理 / 设置 / 搜索 / 权限
src/main/assets/agent-plugin/   plugin.json + skills/calendar/SKILL.md
src/main/res/xml/locales_config.xml
src/debug/                      DebugReceiver、DebugDump、McpSelfTestReceiver、ProviderFixtures、ProviderProbe（仅 debug）
```
