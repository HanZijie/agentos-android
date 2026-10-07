package org.agentos.sample.calendar.tools

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 模型传来的时间字符串。缺少偏移时按设备时区解释。 */
class ParsedTime(
    val instant: java.time.Instant,
    /** 字面上写的本地日期（不换算到设备时区）：全天日程取这个。 */
    val localDate: LocalDate,
    val dateOnly: Boolean,
    /** 字符串里明确带了偏移或时区时，对应的时区；否则 null。 */
    val explicitZone: ZoneId?,
)

object IsoTime {
    private val OUT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    fun parse(text: String, defaultZone: ZoneId, param: String = "time"): ParsedTime {
        var t = text.trim()
        if (t.isEmpty()) throw ToolError("Parameter '$param' must not be empty")
        if (t.length > 10 && t[10] == ' ') t = t.substring(0, 10) + "T" + t.substring(11)
        fun fail(): Nothing = throw ToolError("Invalid time for '$param': \"$text\". Use ISO-8601, e.g. 2026-10-08T15:00:00+08:00 (or a date like 2026-10-08)")
        try {
            if (t.length == 10) {
                val d = LocalDate.parse(t)
                return ParsedTime(d.atStartOfDay(defaultZone).toInstant(), d, true, null)
            }
            runCatching { OffsetDateTime.parse(t) }.getOrNull()?.let {
                return ParsedTime(it.toInstant(), it.toLocalDate(), false, it.offset)
            }
            runCatching { ZonedDateTime.parse(t) }.getOrNull()?.let {
                return ParsedTime(it.toInstant(), it.toLocalDate(), false, it.zone)
            }
            val local = LocalDateTime.parse(t)
            return ParsedTime(local.atZone(defaultZone).toInstant(), local.toLocalDate(), false, null)
        } catch (_: DateTimeException) {
            fail()
        }
    }

    /** 事件应记在哪个时区：偏移与设备时区一致就记设备时区（保证夏令时展开），否则记固定偏移 / 字符串里的区域。 */
    fun eventZone(parsed: ParsedTime, device: ZoneId): ZoneId {
        val z = parsed.explicitZone ?: return device
        if (z is ZoneOffset) {
            return if (device.rules.getOffset(parsed.instant) == z) device else z
        }
        return z
    }

    fun format(ms: Long, zone: ZoneId): String = OUT.format(java.time.Instant.ofEpochMilli(ms).atZone(zone))

    fun formatLocal(dt: LocalDateTime, zone: ZoneId): String = OUT.format(dt.atZone(zone))
}
