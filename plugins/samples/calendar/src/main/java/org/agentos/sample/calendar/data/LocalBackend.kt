package org.agentos.sample.calendar.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 本机日历：本 App 自己的 SQLite（[CalendarStore]）。数据量很小，整表放在内存里（这是本机来源的取舍，系统日历不这么做）。
 * 对外的 id 一律带 `local:` 前缀；库里仍是原来的裸 UUID，所以升级前的数据原样保留。
 * 线程安全：读写都在同一把锁里，[events] 的值是不可变快照。
 */
class LocalBackend(
    private val store: CalendarStore,
    private val time: TimeEnv = TimeEnv.Default,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : CalendarBackend {
    override val kind: BackendKind = BackendKind.LOCAL

    private val lock = Any()
    private var cals: List<CalendarInfo>
    private val _events = MutableStateFlow<List<EventSeries>>(emptyList())

    /** 本机日历里全部日程的当前快照（提醒调度用）。 */
    val events: StateFlow<List<EventSeries>> = _events.asStateFlow()

    init {
        synchronized(lock) {
            var loaded = store.loadCalendars().map { it.fromRaw() }
            if (loaded.isEmpty()) {
                // 第一次运行：建默认日历。名字不写死某种语言：存标记，显示时再按当前语言翻译（R3）
                val first = CalendarInfo(
                    id = CalendarIds.local(newId()), name = LegacyDefaultNames.FILLER, color = Palette.colors[0],
                    visible = true, isDefault = true, createdAt = time.nowMs(), nameKey = CalendarInfo.NAME_KEY_DEFAULT,
                )
                store.upsertCalendar(first.toRaw())
                loaded = listOf(first)
            }
            cals = loaded
            _events.value = store.loadEvents().map { it.fromRaw() }
        }
    }

    override fun hasAccess(): Boolean = true

    override fun calendars(): List<CalendarInfo> = synchronized(lock) { cals }

    override fun eventCount(calendarId: String): Int = _events.value.count { it.calendarId == CalendarIds.normalize(calendarId) }

    override fun instances(fromMs: Long, toMs: Long, calendarId: String?, query: String?): List<Occurrence> {
        val cal = calendarId?.let { CalendarIds.normalize(it) }
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        return Occurrences.query(_events.value, fromMs, toMs, time.zone()) { s -> (cal == null || s.calendarId == cal) && (q == null || matches(s, q)) }
    }

    override fun event(id: String): EventSeries? {
        val sid = CalendarIds.normalize(CalendarIds.split(id).first)
        return _events.value.firstOrNull { it.id == sid }
    }

    override fun occurrence(id: String): Occurrence? {
        val (rawId, key) = CalendarIds.split(id)
        val series = event(rawId) ?: return null
        return if (key == null) Occurrences.first(series, time.zone()) else Occurrences.findByKey(series, key, time.zone())
    }

    override fun searchNextOrLast(query: String, calendarId: String?, nowMs: Long, limit: Int): List<Occurrence> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val cal = calendarId?.let { CalendarIds.normalize(it) }
        val z = time.zone()
        val out = ArrayList<Occurrence>()
        for (s in _events.value) {
            if (cal != null && s.calendarId != cal) continue
            if (!matches(s, q)) continue
            out += Occurrences.nextAtOrAfter(s, nowMs, z) ?: (Occurrences.lastBefore(s, nowMs, z) ?: Occurrences.first(s, z))
            if (out.size >= limit) break
        }
        return out
    }

    override fun save(event: EventSeries, create: Boolean): EventSeries = synchronized(lock) {
        val id = if (event.id.isEmpty()) CalendarIds.local(newId()) else CalendarIds.normalize(event.id)
        val existing = if (create) null else _events.value.firstOrNull { it.id == id }
        val now = time.nowMs()
        val saved = event.copy(
            id = id, calendarId = CalendarIds.normalize(event.calendarId),
            createdAt = existing?.createdAt ?: if (event.createdAt != 0L) event.createdAt else now,
            updatedAt = now,
        )
        store.upsertEvent(saved.toRaw())
        _events.value = if (existing != null) _events.value.map { if (it.id == saved.id) saved else it } else _events.value + saved
        saved
    }

    override fun delete(id: String): EventSeries = synchronized(lock) {
        val sid = CalendarIds.normalize(CalendarIds.split(id).first)
        val old = _events.value.firstOrNull { it.id == sid } ?: throw CalendarException("Event not found: $id")
        store.deleteEvent(CalendarIds.rawLocal(sid))
        _events.value = _events.value.filter { it.id != sid }
        old
    }

    override fun restore(event: EventSeries): EventSeries = synchronized(lock) {
        store.upsertEvent(event.toRaw())
        _events.value = _events.value.filter { it.id != event.id } + event
        event
    }

    override fun saveCalendar(calendar: CalendarInfo) {
        synchronized(lock) {
            store.upsertCalendar(calendar.toRaw())
            cals = if (cals.any { it.id == calendar.id }) cals.map { if (it.id == calendar.id) calendar else it } else cals + calendar
        }
    }

    override fun deleteCalendar(id: String): Int = synchronized(lock) {
        val cid = CalendarIds.normalize(id)
        val removed = store.deleteCalendar(CalendarIds.rawLocal(cid))
        cals = cals.filter { it.id != cid }
        _events.value = _events.value.filter { it.calendarId != cid }
        removed
    }

    // 本机数据只有本 App 自己会改（经仓库），没有“外部变化”
    override fun addChangeListener(listener: () -> Unit): AutoCloseable = AutoCloseable { }

    override fun clearOwnedEvents(): Int = synchronized(lock) {
        val all = _events.value
        all.forEach { store.deleteEvent(CalendarIds.rawLocal(it.id)) }
        _events.value = emptyList()
        all.size
    }

    override fun ownedEvents(): List<EventSeries> = _events.value

    fun newCalendarId(): String = CalendarIds.local(newId())

    private fun matches(s: EventSeries, q: String): Boolean =
        s.title.contains(q, ignoreCase = true) || s.location.contains(q, ignoreCase = true) || s.description.contains(q, ignoreCase = true)

    private fun CalendarInfo.fromRaw() = copy(id = CalendarIds.local(CalendarIds.rawLocal(id)), system = false, source = CalendarSource.LOCAL, writable = true)
    private fun CalendarInfo.toRaw() = copy(id = CalendarIds.rawLocal(id))
    private fun EventSeries.fromRaw() = copy(id = CalendarIds.local(CalendarIds.rawLocal(id)), calendarId = CalendarIds.local(CalendarIds.rawLocal(calendarId)))
    private fun EventSeries.toRaw() = copy(id = CalendarIds.rawLocal(id), calendarId = CalendarIds.rawLocal(calendarId))
}
