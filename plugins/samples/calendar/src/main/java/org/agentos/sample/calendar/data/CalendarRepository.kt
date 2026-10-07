package org.agentos.sample.calendar.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId
import java.util.UUID

/** 持久化接口：数据量很小，仓库整表加载到内存，存储层只管落盘。 */
interface CalendarStore {
    fun loadCalendars(): List<CalendarInfo>
    fun loadEvents(): List<EventSeries>
    fun upsertCalendar(calendar: CalendarInfo)

    /** 删除日历及其全部日程，返回删掉的日程数。 */
    fun deleteCalendar(id: String): Int
    fun upsertEvent(event: EventSeries)
    fun deleteEvent(id: String)
}

/**
 * 日历与日程的唯一数据入口：界面、提醒调度、MCP 工具共用同一个进程内实例。
 * 所有写操作先校验、再落盘、最后更新 [calendars] / [events]；失败抛 [CalendarException]。
 * 线程安全：读写都在同一把锁里，StateFlow 的值是不可变快照。
 */
class CalendarRepository(
    private val store: CalendarStore,
    val time: TimeEnv = TimeEnv.Default,
    private val defaultCalendarName: String = "My Calendar",
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    private val _events = MutableStateFlow<List<EventSeries>>(emptyList())

    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()
    val events: StateFlow<List<EventSeries>> = _events.asStateFlow()

    init {
        synchronized(lock) {
            var cals = store.loadCalendars()
            if (cals.isEmpty()) {
                val first = CalendarInfo(newId(), defaultCalendarName, Palette.colors[0], visible = true, isDefault = true, createdAt = time.nowMs())
                store.upsertCalendar(first)
                cals = listOf(first)
            }
            _calendars.value = cals
            _events.value = store.loadEvents()
        }
    }

    val zone: ZoneId get() = time.zone()
    val defaultCalendar: CalendarInfo get() = _calendars.value.firstOrNull { it.isDefault } ?: _calendars.value.first()

    // ---- 日历 ----

    fun calendar(id: String): CalendarInfo? = _calendars.value.firstOrNull { it.id == id }

    fun createCalendar(name: String, color: Int? = null): CalendarInfo = synchronized(lock) {
        val clean = validateCalendarName(name, exceptId = null)
        val cal = CalendarInfo(
            id = newId(), name = clean,
            color = color ?: Palette.colors[_calendars.value.size % Palette.colors.size],
            visible = true, isDefault = false, createdAt = time.nowMs(),
        )
        store.upsertCalendar(cal)
        _calendars.value = _calendars.value + cal
        cal
    }

    fun updateCalendar(id: String, name: String? = null, color: Int? = null, visible: Boolean? = null): CalendarInfo = synchronized(lock) {
        val old = calendar(id) ?: throw CalendarException("Calendar not found: $id")
        val updated = old.copy(
            name = name?.let { validateCalendarName(it, exceptId = id) } ?: old.name,
            color = color ?: old.color,
            visible = visible ?: old.visible,
        )
        store.upsertCalendar(updated)
        _calendars.value = _calendars.value.map { if (it.id == id) updated else it }
        updated
    }

    /** 返回连带删除的日程数；默认日历不能删。 */
    fun deleteCalendar(id: String): Int = synchronized(lock) {
        val cal = calendar(id) ?: throw CalendarException("Calendar not found: $id")
        if (cal.isDefault) throw CalendarException("The default calendar cannot be deleted")
        val removed = store.deleteCalendar(id)
        _calendars.value = _calendars.value.filter { it.id != id }
        _events.value = _events.value.filter { it.calendarId != id }
        removed
    }

    fun eventCount(calendarId: String): Int = _events.value.count { it.calendarId == calendarId }

    private fun validateCalendarName(name: String, exceptId: String?): String {
        val clean = name.trim()
        if (clean.isEmpty()) throw CalendarException("Calendar name must not be empty")
        if (clean.length > 60) throw CalendarException("Calendar name is too long (max 60 characters)")
        if (_calendars.value.any { it.id != exceptId && it.name.equals(clean, ignoreCase = true) }) {
            throw CalendarException("A calendar named \"$clean\" already exists")
        }
        return clean
    }

    // ---- 日程 ----

    fun event(id: String): EventSeries? {
        val seriesId = Occurrences.splitId(id).first
        return _events.value.firstOrNull { it.id == seriesId }
    }

    /**
     * 新建或整体更新（按 id 判断；id 为空串表示新建）。校验失败抛 [CalendarException]。
     * 返回落盘后的系列（补上了 id 与时间戳）。
     */
    fun saveEvent(draft: EventSeries): EventSeries = synchronized(lock) {
        val existing = if (draft.id.isEmpty()) null else _events.value.firstOrNull { it.id == draft.id }
        val clean = normalize(draft)
        validate(clean)
        val now = time.nowMs()
        val saved = clean.copy(
            id = existing?.id ?: draft.id.ifEmpty { newId() },
            createdAt = existing?.createdAt ?: if (draft.createdAt != 0L) draft.createdAt else now,
            updatedAt = now,
        )
        store.upsertEvent(saved)
        _events.value = if (existing != null) _events.value.map { if (it.id == saved.id) saved else it } else _events.value + saved
        saved
    }

    /** 删除整个系列（重复日程整体删除），返回被删的系列。 */
    fun deleteEvent(id: String): EventSeries = synchronized(lock) {
        val seriesId = Occurrences.splitId(id).first
        val old = _events.value.firstOrNull { it.id == seriesId } ?: throw CalendarException("Event not found: $id")
        store.deleteEvent(seriesId)
        _events.value = _events.value.filter { it.id != seriesId }
        old
    }

    /** 撤销删除：原样放回（保留 id 和时间戳）。日历已不存在时抛错。 */
    fun restoreEvent(event: EventSeries): EventSeries = synchronized(lock) {
        if (calendar(event.calendarId) == null) throw CalendarException("Calendar not found: ${event.calendarId}")
        store.upsertEvent(event)
        _events.value = _events.value.filter { it.id != event.id } + event
        event
    }

    // ---- 查询 ----

    fun occurrences(
        fromMs: Long,
        toMs: Long,
        calendarId: String? = null,
        onlyVisible: Boolean = false,
        query: String? = null,
    ): List<Occurrence> {
        val cals = _calendars.value
        val hidden = if (onlyVisible) cals.filter { !it.visible }.map { it.id }.toSet() else emptySet()
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        return Occurrences.query(_events.value, fromMs, toMs, zone) { s ->
            (calendarId == null || s.calendarId == calendarId) && s.calendarId !in hidden && (q == null || matches(s, q))
        }
    }

    /** 某一次出现；id 可以是 series id 或 `series@key`。series id 返回第一次（开始最早的）出现。 */
    fun occurrence(id: String): Occurrence? {
        val (seriesId, key) = Occurrences.splitId(id)
        val series = _events.value.firstOrNull { it.id == seriesId } ?: return null
        return if (key == null) firstOccurrence(series) else Occurrences.findByKey(series, key, zone)
    }

    fun firstOccurrence(series: EventSeries): Occurrence = Occurrences.first(series, zone)

    /** 标题 / 地点 / 备注的包含匹配（不区分大小写）；每个系列返回一条：最近将开始的那次，没有就取最后一次。 */
    fun search(query: String, limit: Int = 50): List<Occurrence> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val now = time.nowMs()
        val z = zone
        val upcoming = ArrayList<Occurrence>()
        val past = ArrayList<Occurrence>()
        for (s in _events.value) {
            if (!matches(s, q)) continue
            val next = Occurrences.nextAtOrAfter(s, now, z)
            if (next != null) upcoming += next else (Occurrences.lastBefore(s, now, z) ?: firstOccurrence(s)).let { past += it }
        }
        return (upcoming.sortedBy { it.startMs } + past.sortedByDescending { it.startMs }).take(limit)
    }

    private fun matches(s: EventSeries, q: String): Boolean =
        s.title.contains(q, ignoreCase = true) || s.location.contains(q, ignoreCase = true) || s.description.contains(q, ignoreCase = true)

    // ---- 校验 ----

    private fun normalize(e: EventSeries): EventSeries {
        val reminders = e.reminders.distinct().sorted()
        var out = e.copy(
            title = e.title.trim(), location = e.location.trim(), description = e.description.trim(),
            reminders = reminders,
            recurrenceUntilUtc = if (e.isRecurring) e.recurrenceUntilUtc else null,
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
        if (calendar(e.calendarId) == null) throw CalendarException("Calendar not found: ${e.calendarId}")
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
}
