package org.agentos.sample.alarm.intent

import android.provider.AlarmClock
import java.time.DayOfWeek
import java.util.Calendar
import org.agentos.sample.alarm.data.Alarm

/**
 * 系统标准闹钟 Intent（[AlarmClock]）的解析：extras → [AlarmIntentRequest]。纯函数，不依赖 Android 对象
 * （只用 [AlarmClock] 里的字符串常量，编译期内联），JVM 测试直接传一个 Map。
 *
 * 语义按 AlarmClock 的官方文档（API 37 源码注释）：
 * - ACTION_SET_ALARM：没有任何时间参数 → 应该打开能设闹钟的界面（[AlarmIntentRequest.OpenEditor]，此时 EXTRA_SKIP_UI 被忽略）；
 *   EXTRA_MINUTES 默认 0；EXTRA_VIBRATE 默认 true；EXTRA_SKIP_UI 默认 false；EXTRA_DAYS 是 `ArrayList<Integer>`，
 *   元素是 [Calendar.SUNDAY]..[Calendar.SATURDAY]（1..7，星期日是 1）。
 * - ACTION_DISMISS_ALARM：EXTRA_ALARM_SEARCH_MODE 决定找哪个闹钟；没给时见 [DismissTarget.Unspecified]。
 * - ACTION_SNOOZE_ALARM：EXTRA_ALARM_SNOOZE_DURATION（分钟）可选，只对这一次贪睡生效。
 *
 * 严格：类型不对、越界一律 [AlarmIntentRequest.Rejected]（带原因），不抛异常、不猜。
 * 不支持：EXTRA_RINGTONE（一律用默认铃声，没有“静音闹钟”）、数据 URI 深链、语音交互模式回报、计时器。
 */
object AlarmIntentParser {
    fun parse(action: String?, extras: Map<String, Any?>): AlarmIntentRequest = when (action) {
        AlarmClock.ACTION_SET_ALARM -> parseSet(extras)
        AlarmClock.ACTION_DISMISS_ALARM -> parseDismiss(extras)
        AlarmClock.ACTION_SNOOZE_ALARM -> parseSnooze(extras)
        else -> AlarmIntentRequest.Rejected(RejectReason.UNSUPPORTED_ACTION)
    }

    // ---- SET_ALARM ----

    private fun parseSet(extras: Map<String, Any?>): AlarmIntentRequest {
        val hourRaw = extras[AlarmClock.EXTRA_HOUR]
        val minutesRaw = extras[AlarmClock.EXTRA_MINUTES]
        // 官方：没给时间 → 打开设闹钟的界面。只给了分钟没给小时则是残缺请求，拒绝
        if (hourRaw == null && minutesRaw == null) return AlarmIntentRequest.OpenEditor
        if (hourRaw == null) return AlarmIntentRequest.Rejected(RejectReason.MISSING_HOUR)
        val hour = (hourRaw as? Int)?.takeIf { it in 0..23 }
            ?: return AlarmIntentRequest.Rejected(RejectReason.INVALID_HOUR)
        val minutes = if (minutesRaw == null) {
            0
        } else {
            (minutesRaw as? Int)?.takeIf { it in 0..59 } ?: return AlarmIntentRequest.Rejected(RejectReason.INVALID_MINUTES)
        }
        val days = when (val raw = extras[AlarmClock.EXTRA_DAYS]) {
            null -> emptySet()
            else -> parseDays(raw) ?: return AlarmIntentRequest.Rejected(RejectReason.INVALID_DAYS)
        }
        val label = when (val raw = extras[AlarmClock.EXTRA_MESSAGE]) {
            null -> ""
            is CharSequence -> raw.toString().trim()
            else -> return AlarmIntentRequest.Rejected(RejectReason.INVALID_PARAM)
        }
        if (label.length > Alarm.MAX_LABEL_LENGTH) return AlarmIntentRequest.Rejected(RejectReason.LABEL_TOO_LONG)
        val vibrate = when (val raw = extras[AlarmClock.EXTRA_VIBRATE]) {
            null -> true
            is Boolean -> raw
            else -> return AlarmIntentRequest.Rejected(RejectReason.INVALID_PARAM)
        }
        val skipUi = when (val raw = extras[AlarmClock.EXTRA_SKIP_UI]) {
            null -> false
            is Boolean -> raw
            else -> return AlarmIntentRequest.Rejected(RejectReason.INVALID_PARAM)
        }
        return AlarmIntentRequest.SetAlarm(hour, minutes, label, days, vibrate, skipUi)
    }

    /** EXTRA_DAYS：`ArrayList<Integer>`（官方类型），也接受 `int[]`（`am start --eia` 给的就是它）；任何元素不在 1..7 → null。 */
    private fun parseDays(raw: Any): Set<DayOfWeek>? {
        val numbers: List<Any?> = when (raw) {
            is IntArray -> raw.toList()
            is List<*> -> raw
            else -> return null
        }
        val days = LinkedHashSet<DayOfWeek>()
        for (n in numbers) {
            val value = (n as? Int)?.takeIf { it in Calendar.SUNDAY..Calendar.SATURDAY } ?: return null
            days += fromCalendarDay(value)
        }
        return days
    }

    /** [Calendar.SUNDAY]=1 … [Calendar.SATURDAY]=7 → [DayOfWeek]（周一=1 … 周日=7）。 */
    fun fromCalendarDay(calendarDay: Int): DayOfWeek {
        require(calendarDay in Calendar.SUNDAY..Calendar.SATURDAY) { "not a Calendar day: $calendarDay" }
        return DayOfWeek.of((calendarDay + 5) % 7 + 1)
    }

    // ---- DISMISS_ALARM ----

    private fun parseDismiss(extras: Map<String, Any?>): AlarmIntentRequest {
        val target = when (val mode = extras[AlarmClock.EXTRA_ALARM_SEARCH_MODE]) {
            null -> DismissTarget.Unspecified
            AlarmClock.ALARM_SEARCH_MODE_NEXT -> DismissTarget.Next
            AlarmClock.ALARM_SEARCH_MODE_ALL -> DismissTarget.All
            AlarmClock.ALARM_SEARCH_MODE_LABEL -> {
                val phrase = (extras[AlarmClock.EXTRA_MESSAGE] as? CharSequence)?.toString()?.trim().orEmpty()
                if (phrase.isEmpty()) return AlarmIntentRequest.Rejected(RejectReason.MISSING_LABEL)
                DismissTarget.Label(phrase)
            }
            AlarmClock.ALARM_SEARCH_MODE_TIME -> {
                val hourRaw = extras[AlarmClock.EXTRA_HOUR]
                val minutesRaw = extras[AlarmClock.EXTRA_MINUTES]
                val pmRaw = extras[AlarmClock.EXTRA_IS_PM]
                // 官方：这个模式下小时 / 分钟 / 上下午至少要给一个
                if (hourRaw == null && minutesRaw == null && pmRaw == null) {
                    return AlarmIntentRequest.Rejected(RejectReason.MISSING_HOUR)
                }
                val hour = if (hourRaw == null) null else (hourRaw as? Int)?.takeIf { it in 0..23 }
                    ?: return AlarmIntentRequest.Rejected(RejectReason.INVALID_HOUR)
                val minutes = if (minutesRaw == null) null else (minutesRaw as? Int)?.takeIf { it in 0..59 }
                    ?: return AlarmIntentRequest.Rejected(RejectReason.INVALID_MINUTES)
                val isPm = when (pmRaw) {
                    null -> null
                    is Boolean -> pmRaw
                    else -> return AlarmIntentRequest.Rejected(RejectReason.INVALID_PARAM)
                }
                DismissTarget.Time(hour, minutes, isPm)
            }
            else -> return AlarmIntentRequest.Rejected(RejectReason.INVALID_PARAM)
        }
        return AlarmIntentRequest.DismissAlarm(target)
    }

    // ---- SNOOZE_ALARM ----

    private fun parseSnooze(extras: Map<String, Any?>): AlarmIntentRequest {
        val minutes = when (val raw = extras[AlarmClock.EXTRA_ALARM_SNOOZE_DURATION]) {
            null -> null
            is Int -> raw.takeIf { it in 1..Alarm.MAX_SNOOZE_MINUTES }
                ?: return AlarmIntentRequest.Rejected(RejectReason.INVALID_SNOOZE)
            else -> return AlarmIntentRequest.Rejected(RejectReason.INVALID_SNOOZE)
        }
        return AlarmIntentRequest.SnoozeAlarm(minutes)
    }
}

/** 解析后的请求。 */
sealed interface AlarmIntentRequest {
    /** ACTION_SET_ALARM，带时间。[days] 已转成 [DayOfWeek]。 */
    data class SetAlarm(
        val hour: Int,
        val minutes: Int,
        val label: String,
        val days: Set<DayOfWeek>,
        val vibrate: Boolean,
        val skipUi: Boolean,
    ) : AlarmIntentRequest

    /** ACTION_SET_ALARM 没有时间参数：打开新建闹钟的界面，什么都不创建。 */
    data object OpenEditor : AlarmIntentRequest

    data class DismissAlarm(val target: DismissTarget) : AlarmIntentRequest

    /** [minutes] 为 null = 用被贪睡闹钟自己的贪睡时长。 */
    data class SnoozeAlarm(val minutes: Int?) : AlarmIntentRequest

    data class Rejected(val reason: RejectReason) : AlarmIntentRequest
}

/** ACTION_DISMISS_ALARM 要关哪个。 */
sealed interface DismissTarget {
    /** 没给搜索模式：正在响的先关；否则恰好一个开着的就关它，多个则请用户在列表里选。 */
    data object Unspecified : DismissTarget

    /** ALARM_SEARCH_MODE_NEXT：正在响的，或下一个要响的。 */
    data object Next : DismissTarget

    data object All : DismissTarget

    /** ALARM_SEARCH_MODE_LABEL：标签里含这个词 / 短语（不分大小写）。 */
    data class Label(val phrase: String) : DismissTarget

    /** ALARM_SEARCH_MODE_TIME：[hour] 是 0..23（可空）；[isPm] 非空时按 12 小时制理解 [hour]；分钟为空表示任意分钟。 */
    data class Time(val hour: Int?, val minutes: Int?, val isPm: Boolean?) : DismissTarget
}

/** 拒绝的原因（界面按它选提示文案；英文说明只用于日志）。 */
enum class RejectReason(val log: String) {
    UNSUPPORTED_ACTION("unsupported action"),
    MISSING_HOUR("EXTRA_HOUR is missing"),
    INVALID_HOUR("EXTRA_HOUR must be an integer 0-23"),
    INVALID_MINUTES("EXTRA_MINUTES must be an integer 0-59"),
    INVALID_DAYS("EXTRA_DAYS must be a list of Calendar day constants 1-7"),
    LABEL_TOO_LONG("EXTRA_MESSAGE is longer than ${Alarm.MAX_LABEL_LENGTH} characters"),
    MISSING_LABEL("label search needs a non-empty EXTRA_MESSAGE"),
    INVALID_SNOOZE("EXTRA_ALARM_SNOOZE_DURATION must be an integer 1-${Alarm.MAX_SNOOZE_MINUTES}"),
    INVALID_PARAM("a parameter has the wrong type or an unknown value"),
}
