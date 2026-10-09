package org.agentos.sample.calendar

import org.agentos.sample.calendar.data.BackendKind
import org.agentos.sample.calendar.data.CalendarBackend
import org.agentos.sample.calendar.data.CalendarException
import org.agentos.sample.calendar.data.CalendarIds
import org.agentos.sample.calendar.data.CalendarInfo
import org.agentos.sample.calendar.data.CalendarPermissionException
import org.agentos.sample.calendar.data.CalendarSource
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.Occurrence
import org.agentos.sample.calendar.data.Occurrences
import org.agentos.sample.calendar.data.Recurrence
import org.agentos.sample.calendar.data.TimeEnv

/**
 * 系统日历后端的假实现：内存里存日历和日程，重复日程用本机展开器代替 Provider 的 Instances。
 * [access] = false 模拟没授权：读写都抛 [CalendarPermissionException]。[ownedIds] 记录“本 App 创建的”（对应 `CUSTOM_APP_PACKAGE` 打标），
 * 直接 [seedEvent] 放进去的是别人的 / 同步下来的（不打标）。
 */
class FakeSystemBackend(var access: Boolean = true, private val time: TimeEnv = FakeTime()) : CalendarBackend {
    override val kind: BackendKind = BackendKind.SYSTEM
    val cals = ArrayList<CalendarInfo>()
    val events = LinkedHashMap<String, EventSeries>()
    val ownedIds = LinkedHashSet<String>()
    private val listeners = ArrayList<() -> Unit>()
    private var next = 100L

    fun addCalendar(
        raw: Long, name: String, account: String = "me@example.com", accountType: String = "com.google", source: CalendarSource = CalendarSource.GOOGLE,
        writable: Boolean = true, visible: Boolean = true,
    ): CalendarInfo = CalendarInfo(
        id = CalendarIds.system(raw), name = name, color = 0xFF4A7BDB.toInt(), visible = visible, isDefault = false, createdAt = 0,
        account = account, accountType = accountType, source = source, writable = writable, system = true,
    ).also { cals += it }

    /** 放一个“别人的 / 同步下来的”日程（不打标）。 */
    fun seedEvent(e: EventSeries): EventSeries {
        val id = CalendarIds.system(next++)
        return e.copy(id = id).also { events[id] = it }
    }

    fun fireChange() = listeners.toList().forEach { it() }

    private fun check() {
        if (!access) throw CalendarPermissionException()
    }

    override fun hasAccess(): Boolean = access
    override fun calendars(): List<CalendarInfo> = if (access) cals.toList() else emptyList()
    override fun eventCount(calendarId: String): Int {
        check()
        return events.values.count { it.calendarId == calendarId }
    }

    override fun instances(fromMs: Long, toMs: Long, calendarId: String?, query: String?): List<Occurrence> {
        check()
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        return Occurrences.query(events.values, fromMs, toMs, time.zone()) { s ->
            (calendarId == null || s.calendarId == calendarId) && (q == null || s.title.contains(q, true) || s.location.contains(q, true) || s.description.contains(q, true))
        }
    }

    override fun event(id: String): EventSeries? {
        check()
        return events[CalendarIds.split(id).first]
    }

    override fun occurrence(id: String): Occurrence? {
        check()
        val (sid, key) = CalendarIds.split(id)
        val s = events[sid] ?: return null
        return if (key == null) Occurrences.first(s, time.zone()) else Occurrences.findByKey(s, key, time.zone())
    }

    override fun searchNextOrLast(query: String, calendarId: String?, nowMs: Long, limit: Int): List<Occurrence> {
        check()
        val z = time.zone()
        return events.values.filter { s ->
            (calendarId == null || s.calendarId == calendarId) && (s.title.contains(query, true) || s.location.contains(query, true) || s.description.contains(query, true))
        }.map { s -> Occurrences.nextAtOrAfter(s, nowMs, z) ?: (Occurrences.lastBefore(s, nowMs, z) ?: Occurrences.first(s, z)) }.take(limit)
    }

    override fun save(event: EventSeries, create: Boolean): EventSeries {
        check()
        val id = if (create) CalendarIds.system(next++) else event.id
        // 和真实的 Provider 一样：custom 不会被写成别的东西；这里的假实现把 rrule 清掉，等同于“只保存本 App 认得的重复规则”
        val saved = event.copy(id = id, rrule = null, updatedAt = 1)
        events[id] = saved
        if (create) ownedIds += id
        return saved
    }

    override fun delete(id: String): EventSeries {
        check()
        val sid = CalendarIds.split(id).first
        ownedIds -= sid
        return events.remove(sid) ?: throw CalendarException("Event not found: $id")
    }

    override fun restore(event: EventSeries): EventSeries = throw CalendarException("Events in account or system calendars cannot be restored")

    override fun addChangeListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun clearOwnedEvents(): Int {
        check()
        val mine = ownedIds.toList()
        mine.forEach { events.remove(it) }
        ownedIds.clear()
        return mine.size
    }

    override fun ownedEvents(): List<EventSeries> {
        check()
        return ownedIds.mapNotNull { events[it] }
    }

    override fun foreignEventCount(): Int {
        check()
        return events.size - ownedIds.count { it in events }
    }
}

/** 一个 custom 重复的系统日程（比如每月第二个周二）。 */
fun customSeries(calendarId: String, title: String = "Second Tuesday") = timed(
    id = "", title = title, start = "2026-10-13T09:00", end = "2026-10-13T10:00", calendarId = calendarId, recurrence = Recurrence.CUSTOM,
).copy(rrule = "FREQ=MONTHLY;BYDAY=2TU")
