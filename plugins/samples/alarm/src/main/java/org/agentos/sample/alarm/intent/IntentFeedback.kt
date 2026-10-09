package org.agentos.sample.alarm.intent

import org.agentos.sample.alarm.R
import org.agentos.sample.alarm.data.Reuse
import org.agentos.sample.alarm.ui.ClockStyle
import org.agentos.sample.alarm.ui.Texts
import org.agentos.sample.alarm.ui.formatClock

/** 处理完系统闹钟 Intent 之后界面要做什么。 */
enum class UiAction {
    /** 不打开任何界面（只靠提示反馈）。 */
    NONE,

    /** 打开闹钟列表。 */
    SHOW_LIST,

    /** 打开新建闹钟页。 */
    SHOW_NEW_EDITOR,
}

/**
 * [IntentOutcome] → 用户看得到的反馈：一条提示文字（Toast）和要不要打开界面。纯函数，文案走 [Texts]（中英文各有测试）。
 *
 * 原则：带 EXTRA_SKIP_UI 的创建不弹界面，所以必须有提示，不能静默；不带的直接打开列表，列表本身就是反馈。
 * 关闭 / 贪睡 / 拒绝没有界面，一律给提示。
 */
object IntentFeedback {
    fun uiAction(outcome: IntentOutcome): UiAction = when (outcome) {
        is IntentOutcome.AlarmSet -> if (outcome.skipUi) UiAction.NONE else UiAction.SHOW_LIST
        IntentOutcome.OpenEditor -> UiAction.SHOW_NEW_EDITOR
        is IntentOutcome.NeedsChoice -> UiAction.SHOW_LIST
        is IntentOutcome.Dismissed -> if (outcome.keptRepeating > 0) UiAction.SHOW_LIST else UiAction.NONE
        IntentOutcome.NothingToDismiss, is IntentOutcome.Snoozed, IntentOutcome.NothingRinging, is IntentOutcome.Rejected -> UiAction.NONE
    }

    /** 提示文字；null = 不提示（界面自己会说明问题）。 */
    fun message(texts: Texts, clock: ClockStyle, outcome: IntentOutcome): String? = when (outcome) {
        is IntentOutcome.AlarmSet -> if (outcome.skipUi) setMessage(texts, clock, outcome) else null
        IntentOutcome.OpenEditor -> null
        is IntentOutcome.NeedsChoice -> texts.plural(R.plurals.intent_choose, outcome.matches, outcome.matches)
        is IntentOutcome.Dismissed -> dismissedMessage(texts, outcome)
        IntentOutcome.NothingToDismiss -> texts.get(R.string.intent_nothing_to_dismiss)
        is IntentOutcome.Snoozed -> texts.get(R.string.intent_snoozed, outcome.minutes)
        IntentOutcome.NothingRinging -> texts.get(R.string.intent_nothing_ringing)
        is IntentOutcome.Rejected -> texts.get(R.string.intent_rejected, texts.get(reasonText(outcome.reason)))
    }

    private fun setMessage(texts: Texts, clock: ClockStyle, outcome: IntentOutcome.AlarmSet): String {
        val alarm = outcome.alarm
        val time = formatClock(clock, alarm.hour, alarm.minute)
        val what = if (alarm.label.isBlank()) time else "$time · ${alarm.label}"
        return when (outcome.status) {
            Reuse.CREATED -> texts.get(R.string.intent_alarm_set, what)
            Reuse.EXISTING -> texts.get(R.string.intent_alarm_exists, what)
            Reuse.REENABLED -> texts.get(R.string.intent_alarm_reenabled, what)
        }
    }

    private fun dismissedMessage(texts: Texts, outcome: IntentOutcome.Dismissed): String {
        val parts = buildList {
            if (outcome.dismissed > 0) add(texts.plural(R.plurals.intent_dismissed, outcome.dismissed, outcome.dismissed))
            if (outcome.keptRepeating > 0) add(texts.get(R.string.intent_repeating_kept))
        }
        return parts.joinToString(" ")
    }

    private fun reasonText(reason: RejectReason): Int = when (reason) {
        RejectReason.MISSING_HOUR -> R.string.intent_reject_missing_hour
        RejectReason.INVALID_HOUR -> R.string.intent_reject_invalid_hour
        RejectReason.INVALID_MINUTES -> R.string.intent_reject_invalid_minutes
        RejectReason.INVALID_DAYS -> R.string.intent_reject_invalid_days
        RejectReason.LABEL_TOO_LONG -> R.string.intent_reject_label_too_long
        RejectReason.MISSING_LABEL -> R.string.intent_reject_missing_label
        RejectReason.INVALID_SNOOZE -> R.string.intent_reject_invalid_snooze
        RejectReason.UNSUPPORTED_ACTION, RejectReason.INVALID_PARAM -> R.string.intent_reject_invalid_param
    }
}
