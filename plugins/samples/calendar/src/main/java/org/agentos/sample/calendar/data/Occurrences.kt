package org.agentos.sample.calendar.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * 重复日程的展开与区间查询。全部是纯函数，只依赖 java.time。
 *
 * 约定：区间都是 [fromMs, toMs)（UTC 毫秒）；一次出现与区间“有重叠”就算命中（跨日、跨月、跨区间边界的日程都能查到）。
 */
object Occurrences {
    /** 单个系列在一次查询里最多展开的次数，防止“每天重复、查一百年”之类的请求拖垮进程。 */
    const val MAX_PER_SERIES = 5000
    private const val MAX_STEPS = 200_000L

    fun expand(series: EventSeries, fromMs: Long, toMs: Long, zone: ZoneId, maxCount: Int = MAX_PER_SERIES): List<Occurrence> {
        if (toMs <= fromMs) return emptyList()
        return if (series.allDay) expandAllDay(series, fromMs, toMs, zone, maxCount) else expandTimed(series, fromMs, toMs, zone, maxCount)
    }

    /** 系列的第一次出现。 */
    fun first(series: EventSeries, zone: ZoneId): Occurrence =
        if (series.allDay) allDayOccurrence(series, series.startDate, zone) else timedOccurrence(series, series.startUtc, series.endUtc, zone)

    /** 多个系列合并展开，按开始时刻、标题排序。 */
    fun query(
        events: Iterable<EventSeries>,
        fromMs: Long,
        toMs: Long,
        zone: ZoneId,
        filter: (EventSeries) -> Boolean = { true },
    ): List<Occurrence> =
        events.asSequence().filter(filter).flatMap { expand(it, fromMs, toMs, zone).asSequence() }
            .sortedWith(displayOrder).toList()

    /** 一天之内：全天在前，其后按开始时刻，再按标题。 */
    val displayOrder: Comparator<Occurrence> =
        compareBy<Occurrence>({ it.startMs }, { !it.allDay }, { it.series.title.lowercase() }, { it.series.id })

    /** 开始时刻不早于 [fromMs] 的第一次出现；在 [horizonDays] 天内没有则返回 null。 */
    fun nextAtOrAfter(series: EventSeries, fromMs: Long, zone: ZoneId, horizonDays: Long = 366 * 5): Occurrence? {
        val to = fromMs + horizonDays * 86_400_000L
        return expand(series, fromMs - 86_400_000L * 2, to, zone, maxCount = 2000).firstOrNull { it.startMs >= fromMs }
    }

    /** 开始时刻早于 [beforeMs] 的最后一次出现。 */
    fun lastBefore(series: EventSeries, beforeMs: Long, zone: ZoneId): Occurrence? {
        val from = if (series.allDay) LocalDate.ofEpochDay(series.startDay).atStartOfDay(zone).toInstant().toEpochMilli() else series.startUtc
        return expand(series, minOf(from, beforeMs) - 86_400_000L, beforeMs, zone).lastOrNull { it.startMs < beforeMs }
    }

    /** 把 id（series id 或 `series@key`）拆开。 */
    fun splitId(id: String): Pair<String, String?> {
        val at = id.indexOf('@')
        return if (at < 0) id to null else id.substring(0, at) to id.substring(at + 1)
    }

    /** 在系列里找 key 对应的那一次出现；key 为 null 或找不到时返回 null。 */
    fun findByKey(series: EventSeries, key: String, zone: ZoneId): Occurrence? {
        if (series.allDay) {
            val day = runCatching { LocalDate.parse(key, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: return null
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            return expand(series, from, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), zone).firstOrNull { it.key == key }
        }
        val start = runCatching { Instant.from(Occurrence.KEY_FORMAT.parse(key)).toEpochMilli() }.getOrNull() ?: return null
        return expand(series, start, start + 1, zone).firstOrNull { it.key == key }
    }

    /** 按设备时区的日期归并：跨日日程出现在它占据的每一天。只收 [from, to]（含）范围内的日期。 */
    fun bucketByDay(occurrences: List<Occurrence>, from: LocalDate, to: LocalDate): Map<LocalDate, List<Occurrence>> {
        val out = HashMap<LocalDate, MutableList<Occurrence>>()
        for (o in occurrences) {
            var d = if (o.firstDay.isBefore(from)) from else o.firstDay
            val last = if (o.lastDay.isAfter(to)) to else o.lastDay
            while (!d.isAfter(last)) {
                out.getOrPut(d) { ArrayList() }.add(o)
                d = d.plusDays(1)
            }
        }
        return out.mapValues { (_, v) -> v.sortedWith(displayOrder) }
    }

    // ---- 内部 ----

    private fun overlaps(st: Long, en: Long, from: Long, to: Long): Boolean = st < to && (en > from || (en == st && st >= from))

    private fun zoneOf(id: String, fallback: ZoneId): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(fallback)

    private fun shift(base: LocalDateTime, r: Recurrence, n: Long): LocalDateTime = when (r) {
        Recurrence.NONE -> base
        Recurrence.DAILY -> base.plusDays(n)
        Recurrence.WEEKLY -> base.plusWeeks(n)
        Recurrence.MONTHLY -> base.plusMonths(n)
        Recurrence.YEARLY -> base.plusYears(n)
    }

    private fun shift(base: LocalDate, r: Recurrence, n: Long): LocalDate = when (r) {
        Recurrence.NONE -> base
        Recurrence.DAILY -> base.plusDays(n)
        Recurrence.WEEKLY -> base.plusWeeks(n)
        Recurrence.MONTHLY -> base.plusMonths(n)
        Recurrence.YEARLY -> base.plusYears(n)
    }

    private fun periodsBetween(r: Recurrence, a: LocalDate, b: LocalDate): Long = when (r) {
        Recurrence.NONE -> 0
        Recurrence.DAILY -> ChronoUnit.DAYS.between(a, b)
        Recurrence.WEEKLY -> ChronoUnit.DAYS.between(a, b) / 7
        Recurrence.MONTHLY -> ChronoUnit.MONTHS.between(a.withDayOfMonth(1), b.withDayOfMonth(1))
        Recurrence.YEARLY -> ChronoUnit.YEARS.between(a.withDayOfYear(1), b.withDayOfYear(1))
    }

    private fun allDayOccurrence(s: EventSeries, start: LocalDate, zone: ZoneId): Occurrence {
        val last = start.plusDays(s.endDay - s.startDay)
        return Occurrence(s, start.atStartOfDay(zone).toInstant().toEpochMilli(), last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), start, last)
    }

    private fun timedOccurrence(s: EventSeries, st: Long, en: Long, zone: ZoneId): Occurrence {
        val first = Instant.ofEpochMilli(st).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(if (en > st) en - 1 else st).atZone(zone).toLocalDate()
        return Occurrence(s, st, en, first, last)
    }

    private fun expandTimed(s: EventSeries, fromMs: Long, toMs: Long, zone: ZoneId, maxCount: Int): List<Occurrence> {
        val duration = s.endUtc - s.startUtc
        if (!s.isRecurring) {
            return if (overlaps(s.startUtc, s.endUtc, fromMs, toMs)) listOf(timedOccurrence(s, s.startUtc, s.endUtc, zone)) else emptyList()
        }
        val evZone = zoneOf(s.zoneId, zone)
        val startLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(s.startUtc), evZone)
        val floorDay = LocalDateTime.ofInstant(Instant.ofEpochMilli(fromMs - duration), evZone).toLocalDate()
        var n = max(0L, periodsBetween(s.recurrence, startLocal.toLocalDate(), floorDay) - 1)
        val out = ArrayList<Occurrence>()
        var steps = 0L
        while (out.size < maxCount && steps++ < MAX_STEPS) {
            val st = shift(startLocal, s.recurrence, n).atZone(evZone).toInstant().toEpochMilli()
            if (s.recurrenceUntilUtc != null && st > s.recurrenceUntilUtc) break
            if (st >= toMs) break
            val en = st + duration
            if (overlaps(st, en, fromMs, toMs)) out += timedOccurrence(s, st, en, zone)
            n++
        }
        return out
    }

    private fun expandAllDay(s: EventSeries, fromMs: Long, toMs: Long, zone: ZoneId, maxCount: Int): List<Occurrence> {
        val firstQuery = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        val lastQuery = Instant.ofEpochMilli(toMs - 1).atZone(zone).toLocalDate()
        val startDate = LocalDate.ofEpochDay(s.startDay)
        val span = s.endDay - s.startDay
        val untilDay = s.recurrenceUntilUtc?.let { Instant.ofEpochMilli(it).atZone(zoneOf(s.zoneId, zone)).toLocalDate() }
        fun occurrence(start: LocalDate): Occurrence = allDayOccurrence(s, start, zone)
        if (!s.isRecurring) {
            return if (!startDate.isAfter(lastQuery) && !startDate.plusDays(span).isBefore(firstQuery)) listOf(occurrence(startDate)) else emptyList()
        }
        var n = max(0L, periodsBetween(s.recurrence, startDate, firstQuery.minusDays(span)) - 1)
        val out = ArrayList<Occurrence>()
        var steps = 0L
        while (out.size < maxCount && steps++ < MAX_STEPS) {
            val start = shift(startDate, s.recurrence, n)
            if (untilDay != null && start.isAfter(untilDay)) break
            if (start.isAfter(lastQuery)) break
            if (!start.plusDays(span).isBefore(firstQuery)) out += occurrence(start)
            n++
        }
        return out
    }
}

/** 月视图用的日期网格：从包含 1 号的那一周的第一天开始，固定 6 周共 42 格。 */
object MonthGrid {
    fun firstCell(month: java.time.YearMonth, firstDayOfWeek: DayOfWeek): LocalDate {
        val first = month.atDay(1)
        val back = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        return first.minusDays(back.toLong())
    }

    fun cells(month: java.time.YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate> {
        val start = firstCell(month, firstDayOfWeek)
        return List(42) { start.plusDays(it.toLong()) }
    }

    fun weekdayHeaders(firstDayOfWeek: DayOfWeek): List<DayOfWeek> = List(7) { firstDayOfWeek.plus(it.toLong()) }
}
