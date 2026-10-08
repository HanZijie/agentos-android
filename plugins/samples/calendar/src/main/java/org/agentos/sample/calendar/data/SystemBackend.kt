package org.agentos.sample.calendar.data

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.os.HandlerThread
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 系统日历库（CalendarContract Provider）。有 Google / CalDAV 等账号的手机上，这里的日程由系统同步到云端，
 * 本 App 不碰任何 token；没有账号时库里可能只有 LOCAL 类型的日历，甚至一个都没有。
 *
 * - 读：重复日程由 Provider 的 Instances 展开（`Occurrences` 不参与）；查询区间两头各放宽 36 小时再自己过滤，
 *   因为全天日程的 BEGIN / END 是 UTC 零点，和设备时区的日界对不上。
 * - 写：新建的日程带 `CUSTOM_APP_PACKAGE = 本包名`，调试复位时只清自己打标的；提醒写 Reminders 表，由系统日历 App 发通知。
 * - 权限：READ_CALENDAR + WRITE_CALENDAR（运行时权限）。没有时读写都抛 [CalendarPermissionException]；
 *   授权被撤销后正在进行的调用抛出的 SecurityException 也换成它。
 *
 * 这个类只做 Android 侧的 I/O；列 ↔ 模型的映射在 [ProviderMapping]（纯函数，有 JVM 测试）。
 */
class SystemBackend(private val context: Context, private val time: TimeEnv = TimeEnv.Default) : CalendarBackend {
    override val kind: BackendKind = BackendKind.SYSTEM

    private val resolver get() = context.contentResolver
    private val owner: String = context.packageName

    override fun hasAccess(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private inline fun <T> guarded(block: () -> T): T {
        if (!hasAccess()) throw CalendarPermissionException()
        try {
            return block()
        } catch (e: SecurityException) {
            throw CalendarPermissionException()
        }
    }

    // ---- 日历 ----

    override fun calendars(): List<CalendarInfo> {
        if (!hasAccess()) return emptyList()
        return guarded {
            val out = ArrayList<CalendarInfo>()
            resolver.query(
                Calendars.CONTENT_URI,
                arrayOf(
                    Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.CALENDAR_DISPLAY_NAME,
                    Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(2).orEmpty()
                    out += CalendarInfo(
                        id = CalendarIds.system(c.getLong(0)), name = c.getString(3).orEmpty().ifBlank { c.getString(1).orEmpty() },
                        color = c.getInt(4), visible = c.getInt(6) != 0, isDefault = false, createdAt = 0,
                        account = c.getString(1).orEmpty(), accountType = type, source = ProviderMapping.classify(type),
                        writable = ProviderMapping.isWritable(c.getInt(5)), system = true,
                    )
                }
            }
            out
        }
    }

    override fun eventCount(calendarId: String): Int = guarded {
        val raw = CalendarIds.rawSystem(calendarId) ?: return@guarded 0
        resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.CALENDAR_ID} = ?", arrayOf(raw.toString()), null)?.use { it.count } ?: 0
    }

    // ---- 读日程 ----

    override fun instances(fromMs: Long, toMs: Long, calendarId: String?, query: String?): List<Occurrence> = guarded {
        val zone = time.zone()
        val rawCal = calendarId?.let { CalendarIds.rawSystem(it) }
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        val rows = queryInstances(fromMs - PAD_MS, toMs + PAD_MS, selection = rawCal?.let { "${Instances.CALENDAR_ID} = $it" })
        val reminders = loadReminders(rows.filter { (it["hasAlarm"] as? Long) == 1L }.mapNotNull { it["event_id"] as? Long })
        val cache = HashMap<Long, EventSeries>()
        val out = ArrayList<Occurrence>()
        for (row in rows) {
            val eventId = row["event_id"] as? Long ?: continue
            val series = cache.getOrPut(eventId) { seriesOf(row + ("_id" to eventId), reminders[eventId].orEmpty(), zone) }
            val occ = ProviderMapping.occurrenceFrom(series, row["begin"] as Long, row["end"] as Long, zone)
            if (!ProviderMapping.overlaps(occ, fromMs, toMs)) continue
            if (q != null && !(series.title.contains(q, true) || series.location.contains(q, true) || series.description.contains(q, true))) continue
            out += occ
        }
        out
    }

    override fun event(id: String): EventSeries? = guarded {
        val raw = CalendarIds.rawSystem(CalendarIds.split(id).first) ?: return@guarded null
        val row = queryEvents(selection = "${Events._ID} = $raw", limit = 1).firstOrNull() ?: return@guarded null
        seriesOf(row, loadReminders(listOf(raw))[raw].orEmpty(), time.zone())
    }

    override fun occurrence(id: String): Occurrence? {
        val (sid, key) = CalendarIds.split(id)
        val series = event(sid) ?: return null
        val zone = time.zone()
        if (key == null) return Occurrences.first(series, zone)
        val raw = CalendarIds.rawSystem(sid) ?: return null
        return guarded {
            // key → 这一次出现的 BEGIN：定时 = UTC 时刻，全天 = 日期（UTC 零点）
            val begin = if (series.allDay) {
                runCatching { LocalDate.parse(key, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
            } else {
                runCatching { Instant.from(Occurrence.KEY_FORMAT.parse(key)).toEpochMilli() }.getOrNull()
            } ?: return@guarded null
            queryInstances(begin - 1, begin + 1, selection = "${Instances.EVENT_ID} = $raw")
                .firstOrNull { it["begin"] == begin }
                ?.let { ProviderMapping.occurrenceFrom(series, it["begin"] as Long, it["end"] as Long, zone) }
        }
    }

    override fun searchNextOrLast(query: String, calendarId: String?, nowMs: Long, limit: Int): List<Occurrence> = guarded {
        val q = query.trim()
        if (q.isEmpty()) return@guarded emptyList()
        val zone = time.zone()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val rawCal = calendarId?.let { CalendarIds.rawSystem(it) }
        val rows = queryEvents(
            selection = "(${Events.TITLE} LIKE ? ESCAPE '\\' OR ${Events.EVENT_LOCATION} LIKE ? ESCAPE '\\' OR ${Events.DESCRIPTION} LIKE ? ESCAPE '\\')" +
                (rawCal?.let { " AND ${Events.CALENDAR_ID} = $it" } ?: ""),
            args = arrayOf(like, like, like), limit = limit,
        ).filter { (it["eventStatus"] as? Long) != 2L }
        val reminders = loadReminders(rows.filter { (it["hasAlarm"] as? Long) == 1L }.mapNotNull { it["_id"] as? Long })
        val out = ArrayList<Occurrence>()
        val recurring = ArrayList<EventSeries>()
        for (row in rows) {
            val id = row["_id"] as? Long ?: continue
            val s = seriesOf(row, reminders[id].orEmpty(), zone)
            val hasRule = !(row["rrule"] as? String).isNullOrBlank() || !(row["rdate"] as? String).isNullOrBlank()
            if (hasRule) recurring += s else out += Occurrences.first(s, zone)
        }
        if (recurring.isNotEmpty()) out += nextOrLastOfRecurring(recurring, nowMs, zone)
        out
    }

    /** 重复日程：先在近 45 天找下一次，没有再看两年内，还没有就取过去两年里最近的一次；都没有（比如全部被例外删掉）就不返回。 */
    private fun nextOrLastOfRecurring(series: List<EventSeries>, nowMs: Long, zone: java.time.ZoneId): List<Occurrence> {
        val byId = series.associateBy { CalendarIds.rawSystem(it.id)!! }
        val found = HashMap<Long, Occurrence>()
        fun scan(from: Long, to: Long, keepLast: Boolean) {
            val remaining = byId.keys.filter { it !in found }
            if (remaining.isEmpty()) return
            val rows = queryInstances(from, to, selection = "${Instances.EVENT_ID} IN (${remaining.joinToString(",")})")
            for (row in rows) {
                val id = row["event_id"] as Long
                val occ = ProviderMapping.occurrenceFrom(byId.getValue(id), row["begin"] as Long, row["end"] as Long, zone)
                val cur = found[id]
                if (keepLast) {
                    if (occ.startMs < nowMs && (cur == null || occ.startMs > cur.startMs)) found[id] = occ
                } else if (occ.startMs >= nowMs && (cur == null || occ.startMs < cur.startMs)) {
                    found[id] = occ
                }
            }
        }
        scan(nowMs, nowMs + 45 * DAY, keepLast = false)
        scan(nowMs + 45 * DAY, nowMs + 2 * 365 * DAY, keepLast = false)
        scan(nowMs - 2 * 365 * DAY, nowMs, keepLast = true)
        return found.values.toList()
    }

    // ---- 写日程 ----

    override fun save(event: EventSeries, create: Boolean): EventSeries = guarded {
        val zone = time.zone()
        val rawCal = CalendarIds.rawSystem(event.calendarId) ?: throw CalendarException("Calendar not found: ${event.calendarId}")
        val values = ProviderMapping.toProviderValues(event, zone, owner, includeOwner = create)
        try {
            if (create) {
                val ops = ArrayList<ContentProviderOperation>()
                ops += ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values.toContentValues().apply { put(Events.CALENDAR_ID, rawCal) }).build()
                for (m in ProviderMapping.remindersToProvider(event.reminders, event.allDay)) ops += reminderInsert(m, backRef = 0)
                val result = resolver.applyBatch(CalendarContract.AUTHORITY, ops)
                val rawId = result.first().uri?.let { ContentUris.parseId(it) } ?: throw CalendarException("The calendar provider did not return the new event")
                eventOrThrow(CalendarIds.system(rawId))
            } else {
                val rawId = CalendarIds.rawSystem(event.id) ?: throw CalendarException("Event not found: ${event.id}")
                val existing = eventOrThrow(event.id)
                val updated = resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, rawId), values.toContentValues(), null, null)
                if (updated == 0) throw CalendarException("Event not found: ${event.id}")
                if (existing.reminders != event.reminders) {
                    resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(rawId.toString()))
                    val ops = ProviderMapping.remindersToProvider(event.reminders, event.allDay).map { reminderInsert(it, eventId = rawId) }
                    if (ops.isNotEmpty()) resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(ops))
                }
                eventOrThrow(event.id)
            }
        } catch (e: CalendarException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            throw CalendarException("The calendar provider rejected the change: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: android.os.RemoteException) {
            throw CalendarException("The calendar provider is not available: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: android.content.OperationApplicationException) {
            throw CalendarException("The calendar provider rejected the change: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun reminderInsert(minutes: Int, backRef: Int? = null, eventId: Long? = null): ContentProviderOperation {
        val b = ContentProviderOperation.newInsert(Reminders.CONTENT_URI).withValue(Reminders.MINUTES, minutes).withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
        if (backRef != null) b.withValueBackReference(Reminders.EVENT_ID, backRef) else b.withValue(Reminders.EVENT_ID, eventId)
        return b.build()
    }

    private fun eventOrThrow(id: String): EventSeries = event(id) ?: throw CalendarException("Event not found: $id")

    override fun delete(id: String): EventSeries = guarded {
        val sid = CalendarIds.split(id).first
        val raw = CalendarIds.rawSystem(sid) ?: throw CalendarException("Event not found: $id")
        val old = event(sid) ?: throw CalendarException("Event not found: $id")
        // 账号日历里这是“标记删除并同步到云端”；LOCAL 账号的日历直接删
        if (resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, raw), null, null) == 0) throw CalendarException("Event not found: $id")
        old
    }

    override fun restore(event: EventSeries): EventSeries =
        throw CalendarException("Events in account or system calendars cannot be restored: re-creating them would lose guests and sync history")

    // ---- 调试：只碰自己打标的 ----

    override fun clearOwnedEvents(): Int = guarded { resolver.delete(Events.CONTENT_URI, "${Events.CUSTOM_APP_PACKAGE} = ?", arrayOf(owner)) }

    override fun ownedEvents(): List<EventSeries> = guarded {
        val rows = queryEvents(selection = "${Events.CUSTOM_APP_PACKAGE} = ?", args = arrayOf(owner), limit = 5000)
        val reminders = loadReminders(rows.filter { (it["hasAlarm"] as? Long) == 1L }.mapNotNull { it["_id"] as? Long })
        val zone = time.zone()
        rows.map { seriesOf(it, reminders[it["_id"] as? Long].orEmpty(), zone) }
    }

    override fun foreignEventCount(): Int = guarded {
        resolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.CUSTOM_APP_PACKAGE} IS NULL OR ${Events.CUSTOM_APP_PACKAGE} != ?", arrayOf(owner), null,
        )?.use { it.count } ?: 0
    }

    // ---- 变化通知 ----

    private val observerThread: HandlerThread by lazy { HandlerThread("calendar-observer").also { it.start() } }

    override fun addChangeListener(listener: () -> Unit): AutoCloseable {
        val handler = Handler(observerThread.looper)
        val fire = Runnable { listener() }
        val observer = object : ContentObserver(handler) {
            // 云端同步一次会连发很多通知：去抖一下再刷新
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(fire)
                handler.postDelayed(fire, OBSERVER_DEBOUNCE_MS)
            }
        }
        resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        return AutoCloseable {
            resolver.unregisterContentObserver(observer)
            handler.removeCallbacks(fire)
        }
    }

    // ---- 查询小工具 ----

    private fun seriesOf(row: Map<String, Any?>, reminderRows: List<Pair<Int, Int>>, zone: java.time.ZoneId): EventSeries {
        val allDay = (row["allDay"] as? Long) == 1L
        return ProviderMapping.seriesFromRow(row, ProviderMapping.remindersFromProvider(reminderRows, allDay), zone)
    }

    private fun queryEvents(selection: String? = null, args: Array<String>? = null, limit: Int = 500): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        resolver.query(Events.CONTENT_URI, EVENT_COLUMNS, selection, args, "${Events.DTSTART} ASC")?.use { c ->
            while (c.moveToNext() && out.size < limit) out += c.row()
        }
        return out
    }

    private fun queryInstances(fromMs: Long, toMs: Long, selection: String?): List<Map<String, Any?>> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val out = ArrayList<Map<String, Any?>>()
        // 已取消的例外（status = 2）不显示
        val where = listOfNotNull(selection, "(${Events.STATUS} IS NULL OR ${Events.STATUS} != 2)").joinToString(" AND ")
        resolver.query(uri, INSTANCE_COLUMNS, where, null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) out += c.row()
        }
        return out
    }

    /** 事件 id → Reminders 行（分钟，方法）。 */
    private fun loadReminders(eventIds: List<Long>): Map<Long, List<Pair<Int, Int>>> {
        if (eventIds.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableList<Pair<Int, Int>>>()
        for (chunk in eventIds.distinct().chunked(400)) {
            resolver.query(
                Reminders.CONTENT_URI, arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD),
                "${Reminders.EVENT_ID} IN (${chunk.joinToString(",")})", null, null,
            )?.use { c ->
                while (c.moveToNext()) out.getOrPut(c.getLong(0)) { ArrayList() } += (c.getInt(1) to c.getInt(2))
            }
        }
        return out
    }

    private fun Cursor.row(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(columnCount * 2)
        for (i in 0 until columnCount) {
            m[getColumnName(i)] = when (getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
                Cursor.FIELD_TYPE_STRING -> getString(i)
                else -> null
            }
        }
        return m
    }

    private fun Map<String, Any?>.toContentValues(): ContentValues {
        val v = ContentValues()
        for ((k, value) in this) {
            when (value) {
                null -> v.putNull(k)
                is Int -> v.put(k, value)
                is Long -> v.put(k, value)
                is String -> v.put(k, value)
                else -> v.put(k, value.toString())
            }
        }
        return v
    }

    private companion object {
        const val DAY = 86_400_000L
        const val PAD_MS = 36L * 3_600_000L
        const val OBSERVER_DEBOUNCE_MS = 300L

        /** Events 与 Instances 共有的列（名字一致，映射层不区分）。 */
        val EVENT_COLUMNS = arrayOf(
            Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.ALL_DAY,
            Events.DTSTART, Events.DTEND, Events.DURATION, Events.EVENT_TIMEZONE, Events.RRULE, Events.RDATE, Events.EXDATE, Events.EXRULE,
            Events.EVENT_COLOR, Events.HAS_ALARM, Events.AVAILABILITY, Events.SELF_ATTENDEE_STATUS, Events.STATUS,
        )

        /** Instances 里事件 id 叫 event_id（_id 是实例行自己的）。 */
        val INSTANCE_COLUMNS = arrayOf(
            Instances.EVENT_ID, Instances.BEGIN, Instances.END,
            Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.ALL_DAY,
            Events.DTSTART, Events.DTEND, Events.DURATION, Events.EVENT_TIMEZONE, Events.RRULE, Events.RDATE, Events.EXDATE, Events.EXRULE,
            Events.EVENT_COLOR, Events.HAS_ALARM, Events.AVAILABILITY, Events.SELF_ATTENDEE_STATUS, Events.STATUS,
        )
    }
}
