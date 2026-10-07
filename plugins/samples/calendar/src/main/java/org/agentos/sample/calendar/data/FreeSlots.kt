package org.agentos.sample.calendar.data

/** 给定忙碌区间，求窗口内不短于指定时长的空闲时段。区间都是 [start, end) 毫秒。 */
object FreeSlots {
    data class Span(val start: Long, val end: Long)

    /** 把忙碌时段裁到窗口内并合并（相邻或重叠的并成一段）。 */
    fun mergeBusy(busy: List<Span>, windowStart: Long, windowEnd: Long): List<Span> {
        val clipped = busy.map { Span(maxOf(it.start, windowStart), minOf(it.end, windowEnd)) }
            .filter { it.end > it.start }
            .sortedBy { it.start }
        val merged = ArrayList<Span>()
        for (s in clipped) {
            val last = merged.lastOrNull()
            if (last != null && s.start <= last.end) merged[merged.size - 1] = Span(last.start, maxOf(last.end, s.end)) else merged += s
        }
        return merged
    }

    fun compute(busy: List<Span>, windowStart: Long, windowEnd: Long, minDurationMs: Long): List<Span> {
        if (windowEnd <= windowStart) return emptyList()
        val out = ArrayList<Span>()
        var cursor = windowStart
        for (b in mergeBusy(busy, windowStart, windowEnd)) {
            if (b.start - cursor >= minDurationMs) out += Span(cursor, b.start)
            cursor = maxOf(cursor, b.end)
        }
        if (windowEnd - cursor >= minDurationMs) out += Span(cursor, windowEnd)
        return out
    }
}
