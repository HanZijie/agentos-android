package org.agentos.sample.calendar.data

enum class BackendKind { LOCAL, SYSTEM }

/**
 * 日历 / 日程的一个来源。[CalendarRepository] 按日历 id 的前缀（见 [CalendarIds]）路由到对应的后端；
 * 实现有三种：[LocalBackend]（本 App 的 SQLite）、`SystemBackend`（CalendarContract Provider）、测试里的假实现。
 *
 * 约定：所有 id 进出都带来源前缀；区间都是 [fromMs, toMs)（UTC 毫秒）；方法可能阻塞（Provider 查询），不要在主线程调。
 * 校验、权限提示、默认写入日历这些规则在仓库里，后端只管读写。
 */
interface CalendarBackend {
    val kind: BackendKind

    /** 是否有权访问。没有时 [calendars] 返回空，其余读写方法抛 [CalendarPermissionException]。本机后端永远 true。 */
    fun hasAccess(): Boolean

    /** 日历列表（名字未做 R3 翻译，由仓库处理）。 */
    fun calendars(): List<CalendarInfo>

    fun eventCount(calendarId: String): Int

    /** 与 [fromMs, toMs) 有重叠的所有出现（重复日程已展开）。[calendarId] 为 null 表示本后端全部日历；[query] 是标题 / 地点 / 备注的包含匹配。 */
    fun instances(fromMs: Long, toMs: Long, calendarId: String? = null, query: String? = null): List<Occurrence>

    /** 按系列 id 取（不带 `@key`）。 */
    fun event(id: String): EventSeries?

    /** id 可以是系列 id 或 `series@key`；系列 id 返回第一次出现。 */
    fun occurrence(id: String): Occurrence?

    /** 标题 / 地点 / 备注包含 [query] 的系列，每个系列一条：[nowMs] 之后最近的一次，没有就取最近过去的一次。不排序。 */
    fun searchNextOrLast(query: String, calendarId: String?, nowMs: Long, limit: Int): List<Occurrence>

    /** 新建（[create] = true，id 为空）或整体更新；返回落盘后的系列。时间戳与 id 由后端补。 */
    fun save(event: EventSeries, create: Boolean): EventSeries

    /** 删除整个系列，返回被删的系列；不存在抛 [CalendarException]。 */
    fun delete(id: String): EventSeries

    /** 撤销删除：原样放回。系统日历不支持（重新插入会得到新 id、丢掉参会人等），抛 [CalendarException]。 */
    fun restore(event: EventSeries): EventSeries

    // ---- 日历本身的增删改：只有本机后端支持 ----

    fun saveCalendar(calendar: CalendarInfo): Unit = throw CalendarException("This calendar source does not support editing calendars")

    /** 返回连带删掉的日程数。 */
    fun deleteCalendar(id: String): Int = throw CalendarException("This calendar source does not support deleting calendars")

    /** 数据在本 App 之外变了（云端同步、别的 App 写入）时回调。返回的句柄用来取消。 */
    fun addChangeListener(listener: () -> Unit): AutoCloseable

    /** 调试复位用：只删本 App 创建的日程（系统日历靠 `CUSTOM_APP_PACKAGE` 打标），返回删掉的条数。 */
    fun clearOwnedEvents(): Int

    /** 本 App 创建的系列（调试 dump 用）；系统日历只返回自己打了标的那些，绝不列出别人的日程。 */
    fun ownedEvents(): List<EventSeries>

    /** 不属于本 App 的日程总数（只数不读，调试 dump 报告“没有碰这些”）。 */
    fun foreignEventCount(): Int = 0
}
