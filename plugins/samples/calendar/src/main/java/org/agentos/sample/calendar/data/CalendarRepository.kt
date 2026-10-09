package org.agentos.sample.calendar.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 一些用户可调的选项（默认写入日历、系统日历在本 App 里的显隐）。Android 里存 SharedPreferences，测试里存内存。 */
interface CalendarSettings {
    /** 用户指定的“默认写入日历”；null = 自动选（见 [DefaultCalendar.pick]）。 */
    var defaultWriteCalendarId: String?

    /** 用户在本 App 里对某个系统日历显隐的选择；null = 没选过，跟随系统日历库里的 VISIBLE。 */
    fun visibility(id: String): Boolean?
    fun setVisibility(id: String, visible: Boolean)
}

class MemoryCalendarSettings : CalendarSettings {
    override var defaultWriteCalendarId: String? = null
    private val vis = HashMap<String, Boolean>()
    override fun visibility(id: String): Boolean? = vis[id]
    override fun setVisibility(id: String, visible: Boolean) {
        vis[id] = visible
    }
}

object EnglishTexts : CalendarTexts {
    override fun defaultCalendarName(): String = "My Calendar"
}

/** 视窗快照：界面当前需要的区间 [fromMs, toMs) 内、所有日历的出现（不分可见与否，由界面按显隐过滤）。[version] 每次刷新加一。 */
data class WindowSnapshot(val fromMs: Long, val toMs: Long, val occurrences: List<Occurrence>, val version: Long) {
    companion object {
        val EMPTY = WindowSnapshot(0, 0, emptyList(), 0)
    }
}

/** “默认写入日历”的选法（纯函数）。 */
object DefaultCalendar {
    /**
     * 1. 用户指定的（要求存在且可写）；
     * 2. 第一个“可写、可见、非本机”的日历——也就是账号日历（Google、CalDAV 等）。**不能按“主日历”选**：国内机没有 Google 主日历；
     * 3. 找不到就用本机的默认日历。
     */
    fun pick(calendars: List<CalendarInfo>, configuredId: String?): CalendarInfo {
        if (configuredId != null) calendars.firstOrNull { it.id == configuredId && it.writable }?.let { return it }
        calendars.firstOrNull { it.writable && it.visible && it.isAccountCalendar }?.let { return it }
        return calendars.firstOrNull { !it.system && it.isDefault } ?: calendars.first { !it.system }
    }
}

/**
 * 日历与日程的唯一数据入口：界面、提醒调度、MCP 工具共用同一个进程内实例。
 *
 * 结构：一个本机后端（[LocalBackend]，本 App 的 SQLite）+ 一个可选的系统后端（CalendarContract），按日历 id 前缀路由（[CalendarIds]）。
 * - 工具侧方法（[occurrences]、[search]、[saveEvent] 等）是阻塞的，必须在后台线程调；系统日历没授权时抛 [CalendarPermissionException]，
 *   写操作先校验、再落盘，失败抛 [CalendarException]。
 * - 界面侧：[window] 是“当前视窗快照”，界面用 [setWindow] 报告需要的区间，系统日历数据变化（云端同步随时会改）时由后端的变化回调触发 [refresh]。
 *   没权限时视窗里只有本机日历，不报错。
 */
class CalendarRepository(
    val local: LocalBackend,
    private val system: CalendarBackend? = null,
    val time: TimeEnv = TimeEnv.Default,
    private val settings: CalendarSettings = MemoryCalendarSettings(),
    private val texts: CalendarTexts = EnglishTexts,
    private val loader: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "calendar-window").apply { isDaemon = true } },
) {
    private val lock = Any()
    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    private val _window = MutableStateFlow(WindowSnapshot.EMPTY)
    private val _access = MutableStateFlow(false)
    private val _defaultWrite = MutableStateFlow("")
    private var wantFrom = 0L
    private var wantTo = 0L
    private var generation = 0L
    private var version = 0L

    /** 全部日历（本机 + 系统；没权限时只有本机）。名字已按当前语言翻译，显隐已套用用户选择。 */
    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()
    val window: StateFlow<WindowSnapshot> = _window.asStateFlow()

    /** 本机日历里全部日程（提醒调度只管这些；系统日历的提醒由系统日历 App 发）。 */
    val localEvents: StateFlow<List<EventSeries>> get() = local.events

    /** 是否已有系统日历的访问权限。 */
    val systemAccess: StateFlow<Boolean> = _access.asStateFlow()

    /** 当前生效的“默认写入日历”的 id（用户指定的，或自动选出的）。 */
    val defaultWriteId: StateFlow<String> = _defaultWrite.asStateFlow()

    val zone: ZoneId get() = time.zone()

    init {
        // 构造函数在主线程也会被调到：只同步读本机（和以前一样），系统日历稍后在后台补上
        _calendars.value = merge(local.calendars(), emptyList())
        _defaultWrite.value = DefaultCalendar.pick(_calendars.value, settings.defaultWriteCalendarId).id
        _access.value = system?.hasAccess() == true
        system?.addChangeListener { refresh() }
        if (system != null) refresh()
    }

    // ---- 日历 ----

    /** 重新读取全部日历（阻塞）。工具每次调用前后、权限变化、云端同步后都会走这里。 */
    fun reloadCalendars(): List<CalendarInfo> {
        val s = system
        val access = s?.hasAccess() == true
        val sys = if (access) runCatching { s!!.calendars() }.getOrDefault(emptyList()) else emptyList()
        val merged = merge(local.calendars(), sys)
        _access.value = access
        _calendars.value = merged
        _defaultWrite.value = DefaultCalendar.pick(merged, settings.defaultWriteCalendarId).id
        return merged
    }

    private fun merge(localCals: List<CalendarInfo>, sys: List<CalendarInfo>): List<CalendarInfo> =
        (localCals + sys.sortedWith(compareBy({ it.account.lowercase() }, { it.name.lowercase() }))).map { resolve(it) }

    private fun resolve(c: CalendarInfo): CalendarInfo {
        var out = c
        if (c.nameKey == CalendarInfo.NAME_KEY_DEFAULT) out = out.copy(name = texts.defaultCalendarName())
        if (c.system) settings.visibility(c.id)?.let { out = out.copy(visible = it) }
        return out
    }

    fun calendar(id: String): CalendarInfo? {
        val nid = CalendarIds.normalize(id)
        _calendars.value.firstOrNull { it.id == nid }?.let { return it }
        // 缓存里没有：可能是刚同步下来的系统日历，读一次再找
        return if (CalendarIds.isSystem(nid) && system?.hasAccess() == true) reloadCalendars().firstOrNull { it.id == nid } else null
    }

    /** 存在才返回；系统日历没权限抛 [CalendarPermissionException]，不存在抛 [CalendarException]。 */
    fun requireCalendar(id: String): CalendarInfo {
        val nid = CalendarIds.normalize(id)
        if (CalendarIds.isSystem(nid) && system != null && !system.hasAccess()) throw CalendarPermissionException()
        return calendar(nid) ?: throw CalendarException("Calendar not found: $id")
    }

    /** 本机里不能删的那个默认日历。 */
    val localDefaultCalendar: CalendarInfo get() = _calendars.value.first { !it.system && it.isDefault }

    /** event_create 不指定日历时写入的地方，见 [DefaultCalendar.pick]。 */
    fun defaultWriteCalendar(): CalendarInfo = DefaultCalendar.pick(_calendars.value, settings.defaultWriteCalendarId)

    /** 同上（旧名字，工具和界面都在用）。 */
    val defaultCalendar: CalendarInfo get() = defaultWriteCalendar()

    /** 用户指定的默认写入日历；null = 自动。 */
    val configuredDefaultId: String? get() = settings.defaultWriteCalendarId

    fun setDefaultWriteCalendar(id: String?) {
        if (id != null) {
            val cal = requireCalendar(id)
            if (!cal.writable) throw CalendarException("Calendar \"${cal.name}\" is read-only")
            settings.defaultWriteCalendarId = cal.id
        } else {
            settings.defaultWriteCalendarId = null
        }
        _defaultWrite.value = DefaultCalendar.pick(_calendars.value, settings.defaultWriteCalendarId).id
    }

    /** 只能建本机日历：系统日历库建不出账号服务端的日历。 */
    fun createCalendar(name: String, color: Int? = null): CalendarInfo = synchronized(lock) {
        val clean = validateCalendarName(name, exceptId = null)
        val cal = CalendarInfo(
            id = local.newCalendarId(), name = clean,
            color = color ?: Palette.colors[local.calendars().size % Palette.colors.size],
            visible = true, isDefault = false, createdAt = time.nowMs(),
        )
        local.saveCalendar(cal)
        reloadCalendars()
        requireCalendar(cal.id)
    }

    /**
     * 本机日历：可改名、颜色、显隐。账号 / 系统日历：只能在本 App 里显示或隐藏（名字、颜色属于账号，由账号所在的日历 App 管）；
     * 隐藏只影响本 App 的界面，不会动系统日历库里的 VISIBLE。
     */
    fun updateCalendar(id: String, name: String? = null, color: Int? = null, visible: Boolean? = null): CalendarInfo = synchronized(lock) {
        val old = requireCalendar(id)
        if (old.system) {
            if (name != null && name != old.name || color != null && color != old.color) {
                throw CalendarException("Account and system calendars can only be shown or hidden here; rename or recolor them in the calendar app of that account")
            }
            if (visible != null) settings.setVisibility(old.id, visible)
        } else {
            // 把原名字原样传回来（界面改颜色时就是这样）不算改名：默认日历要保持“跟着语言走”的标记
            val renamed = name != null && name != old.name
            val updated = old.copy(
                // 没改名且是“跟着语言走”的默认名：库里只放占位文字，不把当前语言的译文写进去
                name = if (renamed) validateCalendarName(name!!, exceptId = old.id) else if (old.nameKey != null) LegacyDefaultNames.FILLER else old.name,
                nameKey = if (renamed) null else old.nameKey,
                color = color ?: old.color,
                visible = visible ?: old.visible,
            )
            local.saveCalendar(updated)
        }
        reloadCalendars()
        refreshWindow()
        requireCalendar(old.id)
    }

    /** 只能删本机日历（连同日程一起删，返回删掉的日程数）；本机默认日历和账号 / 系统日历都拒绝。 */
    fun deleteCalendar(id: String): Int = synchronized(lock) {
        val cal = requireCalendar(id)
        if (cal.system) throw CalendarException("Account and system calendars cannot be deleted here; remove the calendar or account in the calendar app that owns it")
        if (cal.isDefault) throw CalendarException("The default calendar cannot be deleted")
        val removed = local.deleteCalendar(cal.id)
        if (settings.defaultWriteCalendarId == cal.id) settings.defaultWriteCalendarId = null
        reloadCalendars()
        refreshWindow()
        removed
    }

    /** 某个日历里的日程数（阻塞；系统日历要查库）。 */
    fun eventCount(calendarId: String): Int {
        val cal = requireCalendar(calendarId)
        return backendFor(cal.id).eventCount(cal.id)
    }

    private fun validateCalendarName(name: String, exceptId: String?): String {
        val clean = name.trim()
        if (clean.isEmpty()) throw CalendarException("Calendar name must not be empty")
        if (clean.length > 60) throw CalendarException("Calendar name is too long (max 60 characters)")
        if (_calendars.value.any { it.id != exceptId && it.name.equals(clean, ignoreCase = true) }) {
            throw CalendarException("A calendar named \"$clean\" already exists")
        }
        return clean
    }

    // ---- 路由 ----

    private fun backendFor(id: String): CalendarBackend =
        if (CalendarIds.isSystem(id)) (system ?: throw CalendarException("Calendar not found: $id")) else local

    /** 要读的后端。指定了日历就只读它所在的；没指定则读全部，系统日历没权限时报错而不是悄悄少一块。 */
    private fun readable(calendarId: String?): List<Pair<CalendarBackend, String?>> {
        if (calendarId != null) {
            val cal = requireCalendar(calendarId)
            return listOf(backendFor(cal.id) to cal.id)
        }
        val s = system
        if (s != null && !s.hasAccess()) throw CalendarPermissionException()
        return listOfNotNull(local to null, s?.let { it to null })
    }

    // ---- 日程 ----

    fun event(id: String): EventSeries? {
        val nid = CalendarIds.normalize(id)
        if (CalendarIds.isSystem(nid) && system != null && !system.hasAccess()) throw CalendarPermissionException()
        return backendFor(nid).event(nid)
    }

    /** 某一次出现；id 可以是 series id 或 `series@key`。series id 返回第一次出现。 */
    fun occurrence(id: String): Occurrence? {
        val nid = CalendarIds.normalize(id)
        if (CalendarIds.isSystem(nid) && system != null && !system.hasAccess()) throw CalendarPermissionException()
        return backendFor(nid).occurrence(nid)
    }

    fun firstOccurrence(series: EventSeries): Occurrence = Occurrences.first(series, zone)

    /**
     * 新建或整体更新（按 id 判断；id 为空串表示新建）。校验失败抛 [CalendarException]。
     * 返回落盘后的系列（补上了 id 与时间戳；系统日历的是重新读出来的）。
     */
    fun saveEvent(draft: EventSeries): EventSeries {
        val creating = draft.id.isEmpty()
        val calId = CalendarIds.normalize(draft.calendarId)
        val cal = requireCalendar(calId)
        if (!cal.writable) throw CalendarException("Calendar \"${cal.name}\" is read-only")
        if (draft.recurrence == Recurrence.CUSTOM) throw CalendarException("recurrence 'custom' is read-only: it cannot be set, only the five simple rules can")
        val clean = normalize(draft.copy(calendarId = cal.id, id = if (creating) "" else CalendarIds.normalize(draft.id)))
        validate(clean)
        // 更新：按日程自己 id 所在的后端找（目标日历可能在别的后端——那种“搬家”下面拒绝）
        val backend = backendFor(if (creating) cal.id else clean.id)
        if (!creating) {
            if (CalendarIds.isSystem(clean.id) && system != null && !system.hasAccess()) throw CalendarPermissionException()
            val existing = backend.event(clean.id) ?: throw CalendarException("Event not found: ${draft.id}")
            if (existing.recurrence == Recurrence.CUSTOM) throw CalendarException(customMessage(existing))
            if (existing.calendarId != clean.calendarId && (CalendarIds.isSystem(existing.calendarId) || CalendarIds.isSystem(clean.calendarId))) {
                throw CalendarException("Moving an event to another calendar is not supported for account or system calendars; create it in the target calendar and delete the original")
            }
        }
        val saved = synchronized(lock) { backend.save(clean, creating) }
        refreshWindow()
        return saved
    }

    /** 删除整个系列（重复日程整体删除），返回被删的系列。账号日历里的删除会同步到云端。 */
    fun deleteEvent(id: String): EventSeries {
        val nid = CalendarIds.normalize(id)
        if (CalendarIds.isSystem(nid) && system != null && !system.hasAccess()) throw CalendarPermissionException()
        val backend = backendFor(nid)
        val existing = backend.event(nid) ?: throw CalendarException("Event not found: $id")
        calendar(existing.calendarId)?.let { if (!it.writable) throw CalendarException("Calendar \"${it.name}\" is read-only") }
        val removed = synchronized(lock) { backend.delete(nid) }
        refreshWindow()
        return removed
    }

    /** 撤销删除：原样放回（保留 id 和时间戳）。只有本机日历支持。 */
    fun restoreEvent(event: EventSeries): EventSeries {
        val cal = requireCalendar(event.calendarId)
        val restored = synchronized(lock) { backendFor(cal.id).restore(event.copy(calendarId = cal.id)) }
        refreshWindow()
        return restored
    }

    // ---- 查询（工具侧，阻塞） ----

    fun occurrences(
        fromMs: Long,
        toMs: Long,
        calendarId: String? = null,
        onlyVisible: Boolean = false,
        query: String? = null,
    ): List<Occurrence> {
        val hidden = if (onlyVisible) _calendars.value.filter { !it.visible }.map { it.id }.toSet() else emptySet()
        val all = readable(calendarId).flatMap { (b, cal) -> b.instances(fromMs, toMs, cal, query) }
        return all.filter { it.series.calendarId !in hidden }.sortedWith(Occurrences.displayOrder)
    }

    /** 标题 / 地点 / 备注的包含匹配（不区分大小写）；每个系列返回一条：最近将开始的那次，没有就取最近过去的一次。 */
    fun search(query: String, limit: Int = 50, calendarId: String? = null): List<Occurrence> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val now = time.nowMs()
        val all = readable(calendarId).flatMap { (b, cal) -> b.searchNextOrLast(q, cal, now, SEARCH_CAP) }
        val (upcoming, past) = all.partition { it.startMs >= now }
        return (upcoming.sortedBy { it.startMs } + past.sortedByDescending { it.startMs }).take(limit)
    }

    // ---- 视窗（界面侧，异步） ----

    /** 界面报告它现在需要 [fromMs, toMs) 的数据；加载在后台，完成后 [window] 更新。 */
    fun setWindow(fromMs: Long, toMs: Long) {
        synchronized(lock) {
            if (wantFrom == fromMs && wantTo == toMs && _window.value.fromMs == fromMs && _window.value.toMs == toMs) return
            wantFrom = fromMs
            wantTo = toMs
            generation++
        }
        loader.execute { loadWindow() }
    }

    /** 数据在别处变了（云端同步、权限变化、语言变化）：后台重读日历和视窗。 */
    fun refresh() {
        loader.execute {
            reloadCalendars()
            loadWindow()
        }
    }

    private fun refreshWindow() {
        loader.execute { loadWindow() }
    }

    private fun loadWindow() {
        val from: Long
        val to: Long
        val gen: Long
        synchronized(lock) {
            from = wantFrom
            to = wantTo
            gen = generation
        }
        if (to <= from) return
        val occ = ArrayList<Occurrence>(local.instances(from, to))
        val s = system
        if (s != null && s.hasAccess()) occ += runCatching { s.instances(from, to) }.getOrDefault(emptyList())
        synchronized(lock) {
            if (gen == generation) {
                version++
                _window.value = WindowSnapshot(from, to, occ, version)
            }
        }
    }

    // ---- 调试 ----

    data class Cleared(val local: Int, val system: Int)

    /** 只清本 App 创建的日程（本机全部 + 系统日历里打了标的）；本机日历本身和别人的日程不动。 */
    fun clearOwnedEvents(): Cleared {
        val l = local.clearOwnedEvents()
        val s = if (system != null && system.hasAccess()) system.clearOwnedEvents() else 0
        refreshWindow()
        return Cleared(l, s)
    }

    /** 本 App 创建的全部系列（本机 + 系统日历里打了标的）。 */
    fun ownedEvents(): List<EventSeries> =
        local.ownedEvents() + (if (system != null && system.hasAccess()) system.ownedEvents() else emptyList())

    fun foreignEventCount(): Int = if (system != null && system.hasAccess()) system.foreignEventCount() else 0

    // ---- 校验 ----

    private fun normalize(e: EventSeries): EventSeries {
        val reminders = e.reminders.distinct().sorted()
        var out = e.copy(
            title = e.title.trim(), location = e.location.trim(), description = e.description.trim(),
            reminders = reminders,
            recurrenceUntilUtc = if (e.isRecurring) e.recurrenceUntilUtc else null,
            rrule = null,
        )
        if (out.allDay) {
            val zoneId = runCatching { ZoneId.of(out.zoneId) }.getOrDefault(zone)
            out = out.copy(
                zoneId = zoneId.id,
                startUtc = out.startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                endUtc = out.endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            )
        }
        return out
    }

    private fun validate(e: EventSeries) {
        if (e.title.isEmpty()) throw CalendarException("Title must not be empty")
        if (e.title.length > EventSeries.MAX_TITLE) throw CalendarException("Title is too long (max ${EventSeries.MAX_TITLE} characters)")
        if (e.location.length > EventSeries.MAX_LOCATION) throw CalendarException("Location is too long (max ${EventSeries.MAX_LOCATION} characters)")
        if (e.description.length > EventSeries.MAX_DESCRIPTION) throw CalendarException("Description is too long (max ${EventSeries.MAX_DESCRIPTION} characters)")
        if (runCatching { ZoneId.of(e.zoneId) }.isFailure) throw CalendarException("Unknown time zone: ${e.zoneId}")
        if (e.allDay) {
            if (e.endDay < e.startDay) throw CalendarException("End date must not be before start date")
        } else if (e.endUtc < e.startUtc) {
            throw CalendarException("End must not be before start")
        }
        if (e.reminders.size > EventSeries.MAX_REMINDERS) throw CalendarException("At most ${EventSeries.MAX_REMINDERS} reminders per event")
        e.reminders.firstOrNull { it < 0 || it > EventSeries.MAX_REMINDER_MINUTES }?.let {
            throw CalendarException("reminder_minutes must be between 0 and ${EventSeries.MAX_REMINDER_MINUTES}, got $it")
        }
        if (e.isRecurring && e.recurrenceUntilUtc != null) {
            val tooEarly = if (e.allDay) {
                java.time.Instant.ofEpochMilli(e.recurrenceUntilUtc).atZone(ZoneId.of(e.zoneId)).toLocalDate().isBefore(e.startDate)
            } else {
                e.recurrenceUntilUtc < e.startUtc
            }
            if (tooEarly) throw CalendarException("recurrence_until must not be before the start")
        }
    }

    companion object {
        /** 搜索时每个后端最多返回的系列数（再由 limit 截断）。 */
        const val SEARCH_CAP = 500

        /** 读到 custom 重复的日程要改时的错误；工具层提前检查也用它。 */
        fun customMessage(e: EventSeries): String =
            "This event repeats with a custom rule (RRULE ${e.rrule ?: "unknown"}) that this app cannot express; changing it here could break the series. " +
                "Edit it in the calendar app that owns it, or delete it and create a new one"
    }
}
