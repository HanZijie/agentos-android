package org.agentos.sample.alarm.data

import java.time.DayOfWeek

/**
 * 一个闹钟。纯数据，不依赖 Android，JVM 单元测试直接用。
 *
 * - [days] 为空表示“只响一次”：到点响过（或被关闭）后自动关掉开关；不为空表示按星期重复。
 * - [snoozedUntil]：贪睡中，下一次响铃的绝对时刻（epoch 毫秒）；没有贪睡时为 null。
 * - [fireAt]：当前已向系统登记的下一次响铃时刻（epoch 毫秒），用来在开机后识别“关机期间错过的闹钟”。
 */
data class Alarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val days: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    /** 铃声 URI；null = 系统默认闹钟铃声。 */
    val ringtoneUri: String? = null,
    val snoozedUntil: Long? = null,
    val fireAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val isRepeating: Boolean get() = days.isNotEmpty()

    companion object {
        const val DEFAULT_SNOOZE_MINUTES = 10
        const val MAX_SNOOZE_MINUTES = 60
        const val MAX_LABEL_LENGTH = 60
    }
}

/** 新建闹钟时的输入（还没有 id）。 */
data class AlarmDraft(
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val days: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val snoozeMinutes: Int = Alarm.DEFAULT_SNOOZE_MINUTES,
    val ringtoneUri: String? = null,
)

/**
 * 修改闹钟时只给出要改的字段；null = 不改。
 * 想清空标签 / 恢复默认铃声：[label] / [ringtoneUri] 传空字符串。
 */
data class AlarmPatch(
    val hour: Int? = null,
    val minute: Int? = null,
    val label: String? = null,
    val days: Set<DayOfWeek>? = null,
    val enabled: Boolean? = null,
    val vibrate: Boolean? = null,
    val snoozeMinutes: Int? = null,
    val ringtoneUri: String? = null,
) {
    val isEmpty: Boolean
        get() = hour == null && minute == null && label == null && days == null && enabled == null &&
            vibrate == null && snoozeMinutes == null && ringtoneUri == null
}

/** 校验失败或找不到闹钟：消息是给人（和模型）看的一句话，工具层原样放进 isError 结果。 */
class AlarmException(message: String) : Exception(message)

/** “下一个会响的闹钟”及其时刻。 */
data class NextAlarm(val alarm: Alarm, val fireAtMillis: Long)
