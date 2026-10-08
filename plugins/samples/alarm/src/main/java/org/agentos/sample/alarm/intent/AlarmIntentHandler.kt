package org.agentos.sample.alarm.intent

import org.agentos.sample.alarm.data.Alarm
import org.agentos.sample.alarm.data.AlarmDraft
import org.agentos.sample.alarm.data.AlarmException
import org.agentos.sample.alarm.data.AlarmPatch
import org.agentos.sample.alarm.data.AlarmRepository
import org.agentos.sample.alarm.data.Reuse
import org.agentos.sample.alarm.tools.RingControl

/**
 * 把解析好的系统闹钟请求（[AlarmIntentRequest]）落到现有仓库上：新建 / 关闭 / 贪睡。纯 Kotlin，JVM 测试用假的响铃控制。
 *
 * - SET：时刻 + 标签 + 重复日相同的闹钟不重复建（见 [AlarmRepository.createOrReuse]）；总是启用。
 * - DISMISS：正在响的 → 关掉它（和点“关闭”一样：重复的留着，一次性的关开关）。没在响的：一次性 / 贪睡中的 → 关掉；
 *   **重复闹钟不动**——官方语义是“只跳过即将到来的这一次”，本 App 没有“跳过一次”，直接关会让它以后都不响，
 *   比“该响还响”更危险，所以保持开着并在 [IntentOutcome.Dismissed.keptRepeating] 里告诉用户去列表里处理。
 * - SNOOZE：只对正在响的闹钟；没有在响是 no-op（[IntentOutcome.NothingRinging]）。
 */
class AlarmIntentHandler(
    private val repository: AlarmRepository,
    private val ring: RingControl,
) {
    fun handle(request: AlarmIntentRequest): IntentOutcome = try {
        when (request) {
            is AlarmIntentRequest.Rejected -> IntentOutcome.Rejected(request.reason)
            AlarmIntentRequest.OpenEditor -> IntentOutcome.OpenEditor
            is AlarmIntentRequest.SetAlarm -> set(request)
            is AlarmIntentRequest.DismissAlarm -> dismiss(request.target)
            is AlarmIntentRequest.SnoozeAlarm -> snooze(request.minutes)
        }
    } catch (e: AlarmException) {
        // 仓库的二次校验（例如标签长度）没挡住的情况；界面只显示通用原因，细节进日志
        IntentOutcome.Rejected(RejectReason.INVALID_PARAM)
    }

    private fun set(request: AlarmIntentRequest.SetAlarm): IntentOutcome {
        val result = repository.createOrReuse(
            AlarmDraft(
                hour = request.hour,
                minute = request.minutes,
                label = request.label,
                days = request.days,
                vibrate = request.vibrate,
            ),
        )
        return IntentOutcome.AlarmSet(result.alarm, result.status, request.skipUi)
    }

    private fun dismiss(target: DismissTarget): IntentOutcome {
        val ringing = ring.ringing.value
        val active = repository.list(enabledOnly = true) // 开着的（含贪睡中的）
        val candidates: List<Alarm> = when (target) {
            DismissTarget.Unspecified -> when {
                ringing != null -> listOf(ringing)
                active.size > 1 -> return IntentOutcome.NeedsChoice(active.size)
                else -> active
            }
            DismissTarget.Next -> listOfNotNull(ringing ?: repository.nextAlarm()?.alarm)
            DismissTarget.All -> (listOfNotNull(ringing) + active).distinctBy { it.id }
            is DismissTarget.Label -> matching(ringing, active) { it.label.contains(target.phrase, ignoreCase = true) }
            is DismissTarget.Time -> matching(ringing, active) { matchesTime(it, target) }
        }
        if (candidates.isEmpty()) return IntentOutcome.NothingToDismiss
        // 搜索结果多于一个：官方要求让用户自己选，不替他决定（ALL 是明确的“全部”）
        if (candidates.size > 1 && target != DismissTarget.All) return IntentOutcome.NeedsChoice(candidates.size)

        var dismissed = 0
        var keptRepeating = 0
        for (alarm in candidates) {
            when {
                alarm.id == ringing?.id -> {
                    ring.dismiss()
                    dismissed++
                }
                alarm.snoozedUntil != null -> {
                    // 贪睡中：结束这次贪睡。一次性的随之关闭，重复的保持开启（只取消贪睡）
                    repository.update(alarm.id, AlarmPatch(enabled = alarm.isRepeating))
                    dismissed++
                }
                alarm.isRepeating -> keptRepeating++
                else -> {
                    repository.setEnabled(alarm.id, false)
                    dismissed++
                }
            }
        }
        return IntentOutcome.Dismissed(dismissed, keptRepeating)
    }

    private fun matching(ringing: Alarm?, active: List<Alarm>, predicate: (Alarm) -> Boolean): List<Alarm> =
        (listOfNotNull(ringing) + active).distinctBy { it.id }.filter(predicate)

    private fun matchesTime(alarm: Alarm, target: DismissTarget.Time): Boolean {
        val hourOk = when {
            target.hour == null -> target.isPm?.let { (alarm.hour >= 12) == it } ?: true
            target.isPm != null -> alarm.hour == target.hour % 12 + if (target.isPm) 12 else 0
            // 小时是 0 或 13..23 时只可能是 24 小时制；1..12 没说上下午 → 上午下午都算（官方：含糊，要用户澄清；多个匹配会走选择）
            target.hour == 0 || target.hour > 12 -> alarm.hour == target.hour
            else -> alarm.hour % 12 == target.hour % 12
        }
        return hourOk && (target.minutes == null || alarm.minute == target.minutes)
    }

    private fun snooze(minutes: Int?): IntentOutcome {
        val alarm = ring.snooze(minutes) ?: return IntentOutcome.NothingRinging
        return IntentOutcome.Snoozed(alarm, minutes ?: alarm.snoozeMinutes)
    }
}

/** 处理结果；界面（Toast / 打开列表）和 JVM 测试都看它。 */
sealed interface IntentOutcome {
    data class AlarmSet(val alarm: Alarm, val status: Reuse, val skipUi: Boolean) : IntentOutcome

    /** 没有时间参数：打开新建界面，什么都没创建。 */
    data object OpenEditor : IntentOutcome

    /** [dismissed] 个已关闭；[keptRepeating] 个重复闹钟因为本 App 不能“只跳过一次”而保持开启。 */
    data class Dismissed(val dismissed: Int, val keptRepeating: Int) : IntentOutcome

    /** 多个闹钟匹配，没有动任何一个，请用户在列表里选。 */
    data class NeedsChoice(val matches: Int) : IntentOutcome

    data object NothingToDismiss : IntentOutcome

    data class Snoozed(val alarm: Alarm, val minutes: Int) : IntentOutcome

    data object NothingRinging : IntentOutcome

    data class Rejected(val reason: RejectReason) : IntentOutcome
}
