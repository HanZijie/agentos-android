package org.agentos.sample.calendar.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 本 App 的数据模型与系统日历库（CalendarContract.Events / Instances / Reminders）之间的映射。全部是纯函数，只依赖 java.time，
 * 列名用字符串（和 `CalendarContract` 里的常量一一对应），所以 JVM 单元测试能覆盖：全天、时区、RRULE 往返、提醒换算。
 *
 * Provider 的几条约定（模拟器 API 36 上实测，见 README“C1 验证”）：
 * - 不重复的日程写 DTSTART + DTEND（DURATION 为空）；**重复日程写 DTSTART + DURATION + RRULE，DTEND 必须为空**
 *   （带 DTEND 的重复日程 Provider 不报错，但 `lastDate` 会按 DTEND 算错）。
 * - 全天日程：DTSTART / DTEND 是 UTC 零点，EVENT_TIMEZONE = "UTC"，DTEND 是最后一天的次日（不含）；重复的全天日程 DURATION 用天数 `P{n}D`
 *   （`PT86400S` 之类会被 Provider 拒绝）。
 * - RRULE 的 UNTIL：定时日程用 UTC 的 `yyyyMMdd'T'HHmmss'Z'`；全天日程用日期 `yyyyMMdd`（含当天）。
 * - 全天日程的提醒是相对第一天 00:00 的分钟数，可以为负（Provider 接受 -540 之类，表示“当天 09:00”）。
 */
object ProviderMapping {
    const val DAY_MS = 86_400_000L

    /** CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR：达到这个级别才能往里写日程。 */
    const val ACCESS_CONTRIBUTOR = 500

    /** 本 App 对全天日程提醒的约定：相对第一天 09:00（分钟）。 */
    const val ALL_DAY_ANCHOR_MIN = 9 * 60

    private val UTC_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE_STAMP: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    // ---- 账号类型 ----

    /**
     * 账号类型 → 来源。认得的：`LOCAL`（本机，不同步）、`com.google`（Google）、DAVx⁵ / CalDAV-Sync（CalDAV）；
     * 其余（Exchange、厂商账号、不认识的）一律 `other`，原始类型留在 `account_type` 字段里。
     * 注意：这张表来自这些 App 公开的账号类型字符串，没有在国内机上逐一验证；遇到新的类型，在这里加一行。
     */
    fun classify(accountType: String?): CalendarSource = when (accountType) {
        "LOCAL" -> CalendarSource.LOCAL
        "com.google" -> CalendarSource.GOOGLE
        in CALDAV_TYPES -> CalendarSource.CALDAV
        else -> CalendarSource.OTHER
    }

    val CALDAV_TYPES: Set<String> = setOf("bitfire.at.davdroid", "at.bitfire.davdroid", "org.dmfs.account")

    fun isWritable(accessLevel: Int): Boolean = accessLevel >= ACCESS_CONTRIBUTOR

    // ---- 时区 ----

    /** 写进 EVENT_TIMEZONE 的 id：固定偏移（`+08:00`）Android 的 TimeZone 不认，写成 `GMT+08:00`。 */
    fun providerZoneId(zone: ZoneId): String = when {
        zone == ZoneOffset.UTC -> "UTC"
        zone is ZoneOffset -> "GMT" + zone.id
        else -> zone.id
    }

    fun parseZone(id: String?, fallback: ZoneId): ZoneId = id?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallback

    // ---- 时长 ----

    /** 重复日程的 DURATION：定时 `P{秒}S`（Android 自己写的就是这种），全天 `P{天数}D`。 */
    fun durationText(allDay: Boolean, startUtc: Long, endUtc: Long, startDay: Long, endDay: Long): String =
        if (allDay) "P${(endDay - startDay + 1).coerceAtLeast(1)}D" else "P${((endUtc - startUtc) / 1000).coerceAtLeast(0)}S"

    /** 解析 DURATION（`P3600S`、`PT1H`、`P1D`、`P2W`、`P1DT2H30M`）；认不出返回 null。 */
    fun parseDuration(text: String?): Long? {
        val t = text?.trim()?.uppercase() ?: return null
        if (!t.startsWith("P") || t.length < 2) return null
        var total = 0L
        var num = StringBuilder()
        var sawAny = false
        for (c in t.substring(1)) {
            when {
                c.isDigit() -> num.append(c)
                c == 'T' -> if (num.isNotEmpty()) return null
                else -> {
                    val n = num.toString().toLongOrNull() ?: return null
                    total += when (c) {
                        'W' -> n * 7 * DAY_MS
                        'D' -> n * DAY_MS
                        'H' -> n * 3_600_000L
                        'M' -> n * 60_000L
                        'S' -> n * 1000L
                        else -> return null
                    }
                    num = StringBuilder()
                    sawAny = true
                }
            }
        }
        return if (sawAny && num.isEmpty()) total else null
    }

    // ---- RRULE ----

    /** 读 RRULE 的结果：[rrule] 只在 [Recurrence.CUSTOM] 时有值（原文）。 */
    data class Rule(val recurrence: Recurrence, val untilUtc: Long?, val rrule: String?)

    /**
     * 本 App 的重复规则 → RRULE（不含 `RRULE:` 前缀）。[untilUtc] 是含的截止时刻；定时日程写成 UTC 的 `UNTIL=…Z`（秒精度，向下取整），
     * 全天日程写成 [zone] 下那一天的日期。[Recurrence.NONE] 和 [Recurrence.CUSTOM] 返回 null。
     */
    fun buildRrule(recurrence: Recurrence, untilUtc: Long?, allDay: Boolean, zone: ZoneId): String? {
        val freq = when (recurrence) {
            Recurrence.DAILY -> "DAILY"
            Recurrence.WEEKLY -> "WEEKLY"
            Recurrence.MONTHLY -> "MONTHLY"
            Recurrence.YEARLY -> "YEARLY"
            Recurrence.NONE, Recurrence.CUSTOM -> return null
        }
        val until = untilUtc?.let {
            if (allDay) DATE_STAMP.format(Instant.ofEpochMilli(it).atZone(zone).toLocalDate()) else UTC_STAMP.format(Instant.ofEpochMilli(it))
        }
        return "FREQ=$freq" + (until?.let { ";UNTIL=$it" } ?: "")
    }

    /**
     * RRULE → 本 App 的重复规则。**只认能无损往返的几种**：`FREQ` 为 DAILY / WEEKLY / MONTHLY / YEARLY，可带 `UNTIL`，以及不改变含义的
     * `WKST`、`INTERVAL=1`、与开始日期一致的 `BYDAY`（每周、单个星期几）/ `BYMONTHDAY`（每月）/ `BYMONTH`+`BYMONTHDAY`（每年）。
     * 其余一律 [Recurrence.CUSTOM] 并带回原文：COUNT、INTERVAL > 1、BYDAY 多值或带序号（每月第二个周二）、BYSETPOS、
     * 以及日程上有 RDATE / EXDATE / EXRULE 的（改了开始时间会让这些例外错位）。
     *
     * @param start 日程开始的本地日期与星期（定时：事件时区下；全天：UTC 日期），用来判断 BYDAY / BYMONTHDAY 是否和开始日一致。
     * @param zone 解释 UNTIL 里日期部分的时区（定时：事件时区；全天：设备时区）。
     */
    fun parseRule(rrule: String?, extras: String?, start: LocalDate, allDay: Boolean, zone: ZoneId): Rule {
        val text = rrule?.trim()?.removePrefix("RRULE:")?.takeIf { it.isNotEmpty() }
        val extra = extras?.trim()?.takeIf { it.isNotEmpty() }
        if (text == null) {
            return if (extra == null) Rule(Recurrence.NONE, null, null) else Rule(Recurrence.CUSTOM, null, extra)
        }
        fun custom() = Rule(Recurrence.CUSTOM, null, text)
        if (extra != null) return custom()
        val parts = LinkedHashMap<String, String>()
        for (p in text.split(';')) {
            if (p.isBlank()) continue
            val eq = p.indexOf('=')
            if (eq <= 0) return custom()
            parts[p.substring(0, eq).trim().uppercase()] = p.substring(eq + 1).trim().uppercase()
        }
        val recurrence = when (parts["FREQ"]) {
            "DAILY" -> Recurrence.DAILY
            "WEEKLY" -> Recurrence.WEEKLY
            "MONTHLY" -> Recurrence.MONTHLY
            "YEARLY" -> Recurrence.YEARLY
            else -> return custom()
        }
        val allowed = setOf("FREQ", "UNTIL", "WKST", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYMONTH")
        if (parts.keys.any { it !in allowed }) return custom()
        if (parts["INTERVAL"]?.let { it != "1" } == true) return custom()
        val byDay = parts["BYDAY"]
        val byMonthDay = parts["BYMONTHDAY"]
        val byMonth = parts["BYMONTH"]
        when (recurrence) {
            Recurrence.DAILY -> if (byDay != null || byMonthDay != null || byMonth != null) return custom()
            Recurrence.WEEKLY -> {
                if (byMonthDay != null || byMonth != null) return custom()
                if (byDay != null && byDay != dayCode(start.dayOfWeek)) return custom()
            }
            Recurrence.MONTHLY -> {
                if (byDay != null || byMonth != null) return custom()
                if (byMonthDay != null && byMonthDay != start.dayOfMonth.toString()) return custom()
            }
            Recurrence.YEARLY -> {
                if (byDay != null) return custom()
                if (byMonth != null && byMonth != start.monthValue.toString()) return custom()
                if (byMonthDay != null && byMonthDay != start.dayOfMonth.toString()) return custom()
            }
            else -> return custom()
        }
        val until = parts["UNTIL"]?.let { parseUntil(it, allDay, zone) ?: return custom() }
        return Rule(recurrence, until, null)
    }

    private fun dayCode(d: DayOfWeek): String = when (d) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }

    /**
     * UNTIL → 含的截止时刻（UTC 毫秒）。`…Z`：定时日程取那个时刻；全天日程只取日期部分。
     * 纯日期：[zone] 下那一天的最后一刻。不带 Z 的日期时间：当作 [zone] 的本地时间。认不出返回 null。
     */
    fun parseUntil(text: String, allDay: Boolean, zone: ZoneId): Long? = try {
        val t = text.trim().uppercase()
        when {
            t.length == 8 -> endOfDay(LocalDate.parse(t, DATE_STAMP), zone)
            t.endsWith("Z") -> {
                val utc = java.time.LocalDateTime.parse(t.dropLast(1), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                if (allDay) endOfDay(utc.toLocalDate(), zone) else utc.toInstant(ZoneOffset.UTC).toEpochMilli()
            }
            else -> {
                val local = java.time.LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                if (allDay) endOfDay(local.toLocalDate(), zone) else local.atZone(zone).toInstant().toEpochMilli()
            }
        }
    } catch (_: DateTimeParseException) {
        null
    }

    private fun endOfDay(d: LocalDate, zone: ZoneId): Long = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    // ---- 提醒 ----

    /** 本 App 的提前分钟数 → Reminders.MINUTES。定时：原样；全天：相对 09:00 换成相对 00:00（可为负）。 */
    fun remindersToProvider(leads: List<Int>, allDay: Boolean): List<Int> = leads.map { if (allDay) it - ALL_DAY_ANCHOR_MIN else it }

    /**
     * Reminders 行（分钟，方法）→ 本 App 的提前分钟数。只认弹通知的（METHOD_DEFAULT 0 / METHOD_ALERT 1），邮件 / 短信提醒不要；
     * -1（用日历默认）读不到具体值，跳过；换算后为负（比当天 09:00 还晚）的表达不了，也跳过。去重、升序、最多 [EventSeries.MAX_REMINDERS] 个。
     */
    fun remindersFromProvider(rows: List<Pair<Int, Int>>, allDay: Boolean): List<Int> =
        rows.asSequence()
            .filter { (_, method) -> method == 0 || method == 1 }
            .map { (minutes, _) -> if (allDay) minutes + ALL_DAY_ANCHOR_MIN else minutes }
            .filter { it in 0..EventSeries.MAX_REMINDER_MINUTES }
            .distinct().sorted().take(EventSeries.MAX_REMINDERS).toList()

    // ---- Events / Instances 行 ↔ EventSeries ----

    private fun Map<String, Any?>.long(k: String): Long? = (this[k] as? Number)?.toLong()
    private fun Map<String, Any?>.int(k: String): Int? = (this[k] as? Number)?.toInt()
    private fun Map<String, Any?>.str(k: String): String? = (this[k] as? String)

    /**
     * Events（或 Instances，它带同名的 Events 列）的一行 → [EventSeries]。[reminders] 已经是本 App 的提前分钟数。
     * 不需要的东西（参会人、会议链接）不读，所以这里读出的系列只用于展示和“只改给出的字段”的更新；更新时没碰的列不会被写回。
     */
    fun seriesFromRow(row: Map<String, Any?>, reminders: List<Int>, deviceZone: ZoneId): EventSeries {
        val allDay = (row.int("allDay") ?: 0) == 1
        val dtstart = row.long("dtstart") ?: 0L
        val durationMs = parseDuration(row.str("duration"))
        val recurringText = row.str("rrule")
        val extras = listOfNotNull(row.str("rdate"), row.str("exdate"), row.str("exrule")).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { null }
        val eventZone = if (allDay) deviceZone else parseZone(row.str("eventTimezone"), deviceZone)
        val startDay = if (allDay) Math.floorDiv(dtstart, DAY_MS) else 0L
        val rule = parseRule(
            recurringText, extras,
            start = if (allDay) LocalDate.ofEpochDay(startDay) else Instant.ofEpochMilli(dtstart).atZone(eventZone).toLocalDate(),
            allDay = allDay, zone = eventZone,
        )
        val id = row.long("_id")?.let { CalendarIds.system(it) } ?: ""
        val calendarId = row.long("calendar_id")?.let { CalendarIds.system(it) } ?: ""
        val busy = (row.int("availability") ?: 0) != 1 && (row.int("selfAttendeeStatus") ?: 0) != 2 && (row.int("eventStatus") ?: 0) != 2
        val base = EventSeries(
            id = id, calendarId = calendarId, title = row.str("title").orEmpty(),
            description = row.str("description").orEmpty(), location = row.str("eventLocation").orEmpty(),
            allDay = allDay, startUtc = 0, endUtc = 0, zoneId = eventZone.id,
            color = row.int("eventColor"), reminders = reminders,
            recurrence = rule.recurrence, recurrenceUntilUtc = rule.untilUtc, rrule = rule.rrule, busy = busy,
        )
        return if (allDay) {
            val recurring = recurringText != null || extras != null
            val endDay = if (recurring || row.long("dtend") == null) {
                startDay + ((durationMs ?: DAY_MS).coerceAtLeast(DAY_MS) + DAY_MS - 1) / DAY_MS - 1
            } else {
                maxOf(startDay, Math.floorDiv((row.long("dtend") ?: dtstart) - 1, DAY_MS))
            }
            base.copy(
                startDay = startDay, endDay = endDay,
                startUtc = LocalDate.ofEpochDay(startDay).atStartOfDay(deviceZone).toInstant().toEpochMilli(),
                endUtc = LocalDate.ofEpochDay(endDay + 1).atStartOfDay(deviceZone).toInstant().toEpochMilli(),
            )
        } else {
            val end = row.long("dtend") ?: (dtstart + (durationMs ?: 0L))
            base.copy(startUtc = dtstart, endUtc = maxOf(end, dtstart))
        }
    }

    /**
     * Instances 的一行（BEGIN / END）→ 某一次出现。全天日程的 BEGIN 是首日 UTC 零点、END 是末日次日 UTC 零点（不含）；
     * 换成设备时区下的日界，和本机日历的出现口径一致（[Occurrence.startMs] / [Occurrence.endMs]）。
     */
    fun occurrenceFrom(series: EventSeries, begin: Long, end: Long, deviceZone: ZoneId): Occurrence {
        if (series.allDay) {
            val first = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
            val last = if (end > begin) Instant.ofEpochMilli(end - 1).atZone(ZoneOffset.UTC).toLocalDate() else first
            return Occurrence(
                series, first.atStartOfDay(deviceZone).toInstant().toEpochMilli(),
                last.plusDays(1).atStartOfDay(deviceZone).toInstant().toEpochMilli(), first, last,
            )
        }
        val first = Instant.ofEpochMilli(begin).atZone(deviceZone).toLocalDate()
        val last = Instant.ofEpochMilli(if (end > begin) end - 1 else begin).atZone(deviceZone).toLocalDate()
        return Occurrence(series, begin, end, first, last)
    }

    /** 出现 [o] 的区间与 [fromMs, toMs) 有重叠吗（零长度的在区间内也算）。 */
    fun overlaps(o: Occurrence, fromMs: Long, toMs: Long): Boolean =
        o.startMs < toMs && (o.endMs > fromMs || (o.endMs == o.startMs && o.startMs >= fromMs))

    /**
     * [EventSeries] → Events 表要写的列。[includeOwner] 为 true（新建）时带 `customAppPackage`（打标，“只清自己创建的”靠它）。
     * 更新时重复相关的列（rrule / duration / dtend）总是显式写出，包括写 null：从重复改成不重复、或反过来，不会残留旧值。
     * 不写 rdate / exdate / exrule：有这些的日程是 custom，仓库已经拒绝更新。
     */
    fun toProviderValues(e: EventSeries, deviceZone: ZoneId, ownerPackage: String?, includeOwner: Boolean): Map<String, Any?> {
        val recurring = e.recurrence != Recurrence.NONE && e.recurrence != Recurrence.CUSTOM
        val values = LinkedHashMap<String, Any?>()
        values["title"] = e.title
        values["description"] = e.description
        values["eventLocation"] = e.location
        values["allDay"] = if (e.allDay) 1 else 0
        values["eventColor"] = e.color
        if (e.allDay) {
            values["dtstart"] = e.startDay * DAY_MS
            values["eventTimezone"] = "UTC"
            if (recurring) {
                values["dtend"] = null
                values["duration"] = durationText(true, 0, 0, e.startDay, e.endDay)
                values["rrule"] = buildRrule(e.recurrence, e.recurrenceUntilUtc, true, deviceZone)
            } else {
                values["dtend"] = (e.endDay + 1) * DAY_MS
                values["duration"] = null
                values["rrule"] = null
            }
        } else {
            val zone = parseZone(e.zoneId, deviceZone)
            values["dtstart"] = e.startUtc
            values["eventTimezone"] = providerZoneId(zone)
            if (recurring) {
                values["dtend"] = null
                values["duration"] = durationText(false, e.startUtc, e.endUtc, 0, 0)
                values["rrule"] = buildRrule(e.recurrence, e.recurrenceUntilUtc, false, zone)
            } else {
                values["dtend"] = e.endUtc
                values["duration"] = null
                values["rrule"] = null
            }
        }
        if (includeOwner && ownerPackage != null) values["customAppPackage"] = ownerPackage
        return values
    }
}
