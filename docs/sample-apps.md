# 示例 App：闹钟、日历、备忘录（W17 的一部分，提前做）

> **目的**：三个界面精美、有完整增删改查的独立 App；每个 App 内嵌一个 Agent Plugin（`assets/agent-plugin/`）并导出 Binder MCP 服务，装在手机上后，AgentOS 能发现它们、列出工具、经 MCP 完整操作它们的数据，App 界面实时刷新。
> **路线**：严格按 [extensions.md](extensions.md) 第 4.1、5.1 节（App 内嵌插件 + `McpBinderService` + `BIND_MCP_SERVICE`），不开回环 HTTP 端口。平台侧由 C 车道做 `sdk/plugin-sdk` 与 Extension Host，A 车道做 `core/extensions`，D 车道做确认界面与插件管理页；三个 App 各自一个 SubAgent。
> **整合人**：稼轩的整合会话。接口有分歧时以本文和 `docs/extensions.md` 为准，要改先报告。

## 1. 目录、包名、构建

| App | 目录 | applicationId | 插件名（`plugin.json` 的 `name`） | MCP 服务器名 |
|---|---|---|---|---|
| 闹钟 | `plugins/samples/alarm` | `org.agentos.sample.alarm` | `alarm` | `alarm` |
| 日历 | `plugins/samples/calendar` | `org.agentos.sample.calendar` | `calendar` | `calendar` |
| 备忘录 | `plugins/samples/notes` | `org.agentos.sample.notes` | `notes` | `notes` |

- 目录里有 `build.gradle.kts` 就自动成为 Gradle 模块 `:plugins:samples:<name>`（`settings.gradle.kts` 已写好）。根 `build.gradle.kts` 已统一 SDK 级别（minSdk 35、compileSdk 36）、字节码版本和签名。
- 界面用 **Jetpack Compose + Material 3**。依赖已锁在 `gradle/libs.versions.toml`（`androidx-compose-bom` 等，Kotlin Compose 插件 `libs.plugins.kotlin.compose`）。**不要自己加别的界面库，也不要升级这几项**（BOM 2026.09 要 AGP 9.1 和 compileSdk 37，用不了）；确实需要新依赖，写进报告第 5 节，由整合人决定。数据库用平台自带的 `SQLiteOpenHelper`，不引入 Room / KSP。
- 对 `:sdk:plugin-sdk` 的依赖在 C7a 检查点合入 main 后再加（见第 3 节）；之前先把界面、数据层、工具层做完。

## 2. 每个 App 的要求

**界面**（“精美”的验收是看截图的）：
- 自己的视觉风格，不是默认的 Material 样板：定制配色（亮 / 暗两套）、字体层级、圆角与间距、有意义的图标、空状态插画或图形、合理的动画（列表项进出、状态切换、对话框）。
- 适配 Pixel 8（1080×2400）与小屏；暗色模式；系统栏 edge-to-edge；中英文都能用（字符串放资源里，默认中文，`values-en` 英文）。
- 每个 App 在 `plugins/samples/<name>/README.md` 里写功能说明和工具清单，并引用 4 张以上截图；截图用 `adb exec-out screencap -p` 从模拟器取，放进 `plugins/samples/<name>/screenshots/`。

**数据与增删改查**：
- 数据存在 App 自己的 SQLite 里；界面和 MCP 服务**共用同一个仓库对象**（进程内单例，`StateFlow` / `Flow` 对外），所以 AgentOS 通过 MCP 增删改之后，如果界面在前台，列表立即刷新。
- 所有写操作都有输入校验和明确的错误信息（MCP 工具返回 `isError=true` 和一句话原因，不抛异常到框架里）。
- 数据层与工具层要有 JVM 单元测试（不需要设备）：每个工具至少覆盖正常、缺参数、非法值、不存在的 id。

**工具层的分层**（让 SDK 到位之前可以并行开发，也让测试不依赖 Android）：
- 工具定义写成与 SDK 无关的类型：`name`、`description`、`inputSchema: JsonObject`、注解（readOnly / destructive / idempotent）、`suspend (JsonObject) -> ToolOutput`。放在 `org.agentos.sample.<name>.tools` 包里，只依赖 `kotlinx-serialization-json` 和本 App 的仓库。
- 与 SDK 有关的只有一个很薄的 `XxxMcpService : McpBinderService`，把上面的工具逐个注册进去。SDK 还没合入前这个文件可以先不写。
- 工具名一律 `snake_case`，`<名词>_<动词>`；描述用英文写给模型看（一句话说清做什么、参数含义、返回什么），App 界面文字才用中文。
- 返回值：`content` 里一段 JSON 文本（紧凑），同时在结果的 `structuredContent` 里给同样的对象（SDK 支持时）。时间一律 ISO-8601 带时区偏移（`2026-10-08T07:00:00+08:00`），闹钟的“时刻”用本地 `HH:mm`。id 用字符串（UUID 或自增转字符串都行，但对外是字符串）。
- 注解必须准确：`*_list` / `*_get` / `*_search` 是 `readOnlyHint=true`；`*_delete`（以及批量清空类）是 `destructiveHint=true`；`*_update` / `*_set_*` 是 `idempotentHint=true`。AgentOS 只会按注解调高风险，不会因 readOnly 放宽（extensions.md 5.4）。

**Manifest 与插件包**（照 extensions.md 4.1）：
- 导出的 Service 要求 `org.agentos.permission.BIND_MCP_SERVICE`，带 `org.agentos.intent.action.PLUGIN` 的 intent-filter 和 `<meta-data android:name="org.agentos.plugin.assets" android:value="agent-plugin"/>`。
- `src/main/assets/agent-plugin/plugin.json`：`$schema` 固定为 `https://agent-plugins.org/schemas/1.0.0/plugin.schema.json`，`name`、`version`、`description`、`extensions."com.openai".interface.displayName`、`extensions."org.agentos".mcpServers.<名>.service` = Service 完整类名。另带 `skills/<名>/SKILL.md`：一页说明，告诉模型这个 App 能做什么、典型流程、参数格式的坑（含时间格式）。
- 不申请与功能无关的权限。闹钟需要 `USE_EXACT_ALARM`（闹钟类 App 允许）、`POST_NOTIFICATIONS`、全屏响铃的 `USE_FULL_SCREEN_INTENT`；日历的提醒同理用通知。

## 3. 平台侧接口（草案，C7a 以此实现，改动要先报告）

```kotlin
package org.agentos.plugin            // :sdk:plugin-sdk，namespace org.agentos.plugin

abstract class McpBinderService : Service() {
    protected abstract val serverName: String            // MCP initialize 里的 serverInfo.name
    protected open val serverVersion: String get() = "1.0.0"
    protected abstract fun onRegisterTools(registry: McpToolRegistry)
    /** 工具集变化后调用：向已连接的客户端发 notifications/tools/list_changed。 */
    protected fun notifyToolsChanged()
    final override fun onBind(intent: Intent): IBinder   // 实现 org.agentos.channel.IMcpService
}

interface McpToolRegistry {
    fun tool(
        name: String,
        description: String,
        inputSchema: JsonObject,                          // JSON Schema，type 必须是 object
        annotations: McpToolAnnotations = McpToolAnnotations(),
        title: String? = null,
        handler: suspend (arguments: JsonObject) -> McpToolResult,
    )
}

data class McpToolAnnotations(
    val readOnlyHint: Boolean? = null, val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null, val openWorldHint: Boolean? = null,
)

class McpToolResult {
    companion object {
        fun text(text: String): McpToolResult
        fun json(value: JsonElement): McpToolResult       // content 里放紧凑 JSON 文本，同时填 structuredContent（对象时）
        fun error(message: String): McpToolResult         // isError = true
    }
}
```

- handler 在 SDK 的协程作用域里运行，收到 `notifications/cancelled` 时被取消；handler 抛出的异常由 SDK 转成 `isError` 结果，不会让 Service 崩溃。
- **实际实现（C7a，已在 main）**：`McpToolResult` 有只读属性 `content` / `structuredContent` / `isError` / `text`；客户端侧的工具描述是 `McpTool`，失败用四种异常区分：`McpRpcException`（服务端回了错误）、`McpClosedException(dispatched)`、`McpTimeoutException`、`McpRequestTooLargeException`。工具名 1–128 个 `[A-Za-z0-9_.-]` 字符，不能重名，`inputSchema.type` 必须是 `object`，违反时第一次 bind 就抛 `IllegalArgumentException`。单个结果编码后超过 65,536 字符会被换成一个 `isError` 结果；`McpToolResult.json(对象)` 的文本在 `content` 和 `structuredContent` 里各出现一次，列表类工具要控制条数。完整范例见 `tests/device/mcp-plugin/plugin/`。
- C7a 同时提供测试用的客户端（`McpBinderClient`，Extension Host 也用它）：给定一个 `ComponentName` 或 `IMcpService`，可以 `initialize`、`listTools`、`callTool`，方便示例 App 在 debug 构建里做“自测入口”和设备上的 androidTest。
- 实现上用不用官方 MCP Kotlin SDK 由 C 在 S5 里定，不影响上面的公开接口。

## 4. 三个 App 的工具清单（最低要求；可以多，不能少，名字和必填参数不能改）

### 4.1 闹钟 `alarm`

界面：闹钟列表（时间大字、标签、重复日、开关）、新建 / 编辑（时间选择、重复、标签、铃声、振动、贪睡）、响铃全屏页（关闭 / 贪睡）、下次响铃提示。**必须是真的会响**：`AlarmManager.setAlarmClock`，到点响铃（前台服务 + 全屏通知 + 铃声 + 振动），开机重新注册（`BOOT_COMPLETED`）。

| 工具 | 必填参数 | 可选参数 | 说明 |
|---|---|---|---|
| `alarm_list` | — | `enabled_only`: boolean | 全部闹钟，按下次响铃时间排序 |
| `alarm_get` | `id` | — | |
| `alarm_create` | `time`: "HH:mm" | `label`, `days`: ["mon".."sun"]（空 = 只响一次）, `enabled`（默认 true）, `vibrate`, `snooze_minutes` | 返回新建的闹钟，含 `next_fire_at` |
| `alarm_update` | `id` | 同 create 的各字段（只改给出的） | |
| `alarm_set_enabled` | `id`, `enabled` | — | |
| `alarm_delete` | `id` | — | destructive |
| `alarm_next` | — | — | 下一个会响的闹钟和时间；没有则返回 null |
| `alarm_dismiss` | — | `id` | 关闭正在响的闹钟（没有在响则返回错误说明） |

### 4.2 日历 `calendar`

界面：月视图（有日程的日期带点 / 色条）、日程列表（议程）、周视图或日视图至少一种、新建 / 编辑日程（标题、起止、全天、地点、备注、颜色、提醒、重复）、多个日历（可显示 / 隐藏）、搜索。提醒到点发通知（`AlarmManager` + 通知）。数据用自己的 SQLite，不用系统 `CalendarContract`。

| 工具 | 必填参数 | 可选参数 | 说明 |
|---|---|---|---|
| `calendar_list` | — | — | 日历列表（id、名称、颜色、是否可见） |
| `calendar_create` | `name` | `color` | |
| `calendar_delete` | `id` | — | destructive；连同其日程一起删除，返回删掉的日程数；默认日历不能删 |
| `event_list` | — | `from`, `to`（ISO-8601）, `calendar_id`, `query`, `limit`（默认 50，最大 200） | 区间内的日程，重复日程按区间展开（每个出现一条，带 `series_id`） |
| `event_get` | `id` | — | |
| `event_create` | `title`, `start` | `end`（缺省 = start + 1 小时）, `all_day`, `location`, `description`, `calendar_id`（缺省 = 默认日历）, `reminder_minutes`: [int], `recurrence`: "none" \| "daily" \| "weekly" \| "monthly" \| "yearly"（可带 `recurrence_until`）, `color` | |
| `event_update` | `id` | 同 create 的各字段 | 重复日程整个系列修改 |
| `event_delete` | `id` | — | destructive |
| `event_search` | `query` | `limit` | 标题 / 地点 / 备注的包含匹配 |
| `agenda_today` | — | `calendar_id` | 今天（设备时区）的日程，按开始时间排序 |
| `free_slots` | `date`, `duration_minutes` | `day_start`（默认 09:00）, `day_end`（默认 18:00） | 当天可用的空闲时段 |

### 4.3 备忘录 `notes`

界面：瀑布流 / 列表卡片（标题、摘要、标签、颜色、置顶标记）、编辑页（标题 + Markdown 正文、标签、颜色、置顶）、Markdown 预览、搜索、标签筛选、归档与回收站（删除先进回收站，回收站里再删才是永久删除）、空状态。

| 工具 | 必填参数 | 可选参数 | 说明 |
|---|---|---|---|
| `note_list` | — | `tag`, `pinned`, `archived`（默认 false）, `trashed`（默认 false）, `limit`（默认 50，最大 200）, `offset` | 按置顶、更新时间排序；返回摘要（正文前 200 字）和 `has_more` |
| `note_get` | `id` | — | 完整正文 |
| `note_create` | `content`（Markdown） | `title`（缺省取正文首行）, `tags`: [string], `color`, `pinned` | |
| `note_update` | `id` | `title`, `content`, `tags`（整体替换）, `color`, `pinned`, `archived` | |
| `note_append` | `id`, `text` | `separator`（默认 "\n"） | 在正文末尾追加 |
| `note_search` | `query` | `tag`, `limit` | 标题 / 正文 / 标签包含匹配，返回命中片段 |
| `note_trash` | `id` | — | 放进回收站（可恢复） |
| `note_restore` | `id` | — | 从回收站或归档恢复 |
| `note_delete` | `id` | — | destructive：永久删除；只允许删回收站里的备忘录，否则返回错误提示先 `note_trash` |
| `tag_list` | — | — | 全部标签及各自的备忘录数 |

## 5. 怎么验证

1. **JVM 单元测试**：数据层 + 工具层，`./gradlew :plugins:samples:<name>:testDebugUnitTest`。
2. **设备（模拟器）**：装 debug 包，界面逐页走一遍，截图；SDK 合入后加“自测入口”（debug 构建的导出 `BroadcastReceiver` 或 `adb shell am start` 的 Activity，经 `McpBinderClient` 绑自己的 Service，依次 `tools/list`、增删改查全部工具，结果写 logcat 一行 JSON 摘要），确认 MCP 路径通。
3. **和 AgentOS 联调**（整合人在 Pixel 8 上做）：装好三个 App，AgentOS 的插件页启用，经 acp-bridge 发自然语言（“明早 7 点叫我”“下周三下午 3 点和王总开会”“把刚才那条备忘录加上标签”），用 adb 读各 App 的数据库确认结果。
4. 提交前：`./gradlew :plugins:samples:<name>:assembleDebug :plugins:samples:<name>:assembleRelease :plugins:samples:<name>:lintDebug` 通过；`git diff | grep -c` 自查没有任何 key。
