package org.agentos.sample.alarm.intent

import java.util.Locale
import org.agentos.sample.alarm.data.Alarm
import org.agentos.sample.alarm.data.Reuse
import org.agentos.sample.alarm.ui.ClockStyle
import org.agentos.sample.alarm.ui.ResourceTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 系统闹钟 Intent 的用户可见反馈：中英文各一份真实资源；带 skip_ui 不弹界面时必须有提示，不能静默。 */
class IntentFeedbackTest {
    private val zhClock = ClockStyle(Locale.CHINA, is24Hour = false, pattern12 = "ah:mm")
    private val enClock = ClockStyle(Locale.US, is24Hour = false, pattern12 = "h:mm\u202Fa")
    private val alarm = Alarm(id = "1", hour = 7, minute = 30)

    private fun zh(outcome: IntentOutcome) = IntentFeedback.message(ResourceTexts.zh, zhClock, outcome)
    private fun en(outcome: IntentOutcome) = IntentFeedback.message(ResourceTexts.en, enClock, outcome)

    @Test
    fun skipUiCreationShowsAToastInsteadOfAScreen() {
        val created = IntentOutcome.AlarmSet(alarm, Reuse.CREATED, skipUi = true)
        assertEquals("已设置闹钟 上午 7:30", zh(created))
        assertEquals("Alarm set for 7:30 AM", en(created))
        assertEquals(UiAction.NONE, IntentFeedback.uiAction(created))
    }

    @Test
    fun skipUiCreationWithALabelNamesIt() {
        val created = IntentOutcome.AlarmSet(alarm.copy(label = "晨跑"), Reuse.CREATED, skipUi = true)
        assertEquals("已设置闹钟 上午 7:30 · 晨跑", zh(created))
        assertEquals("Alarm set for 7:30 AM · 晨跑", en(created)) // 标签是用户的文字，原样显示
    }

    @Test
    fun skipUiReuseAndReenableAreExplainedToo() {
        assertEquals("闹钟 上午 7:30 已存在", zh(IntentOutcome.AlarmSet(alarm, Reuse.EXISTING, skipUi = true)))
        assertEquals("The alarm for 7:30 AM already exists", en(IntentOutcome.AlarmSet(alarm, Reuse.EXISTING, skipUi = true)))
        assertEquals("已重新打开闹钟 上午 7:30", zh(IntentOutcome.AlarmSet(alarm, Reuse.REENABLED, skipUi = true)))
        assertEquals("Alarm for 7:30 AM turned back on", en(IntentOutcome.AlarmSet(alarm, Reuse.REENABLED, skipUi = true)))
    }

    @Test
    fun withoutSkipUiTheListOpensAndNoToastIsNeeded() {
        val created = IntentOutcome.AlarmSet(alarm, Reuse.CREATED, skipUi = false)
        assertNull(zh(created))
        assertNull(en(created))
        assertEquals(UiAction.SHOW_LIST, IntentFeedback.uiAction(created))
    }

    @Test
    fun missingTimeOpensTheNewAlarmPage() {
        assertEquals(UiAction.SHOW_NEW_EDITOR, IntentFeedback.uiAction(IntentOutcome.OpenEditor))
        assertNull(zh(IntentOutcome.OpenEditor))
    }

    @Test
    fun dismissFeedbackUsesPluralsInEnglish() {
        assertEquals("已关闭 1 个闹钟", zh(IntentOutcome.Dismissed(1, 0)))
        assertEquals("Dismissed 1 alarm", en(IntentOutcome.Dismissed(1, 0)))
        assertEquals("已关闭 3 个闹钟", zh(IntentOutcome.Dismissed(3, 0)))
        assertEquals("Dismissed 3 alarms", en(IntentOutcome.Dismissed(3, 0)))
        assertEquals(UiAction.NONE, IntentFeedback.uiAction(IntentOutcome.Dismissed(3, 0)))
    }

    @Test
    fun keptRepeatingAlarmsAreExplainedAndTheListOpens() {
        val kept = IntentOutcome.Dismissed(0, 2)
        assertTrue(zh(kept)!!.startsWith("重复闹钟无法只跳过一次"))
        assertTrue(en(kept)!!.startsWith("A repeating alarm cannot be skipped just once"))
        val both = IntentOutcome.Dismissed(1, 1)
        assertTrue(en(both)!!.startsWith("Dismissed 1 alarm") && en(both)!!.contains("repeating alarm"))
        assertEquals(UiAction.SHOW_LIST, IntentFeedback.uiAction(kept))
    }

    @Test
    fun choiceNothingAndSnoozeMessages() {
        assertEquals("有 2 个闹钟符合，请在列表里选择", zh(IntentOutcome.NeedsChoice(2)))
        assertEquals("2 alarms match; pick one in the list", en(IntentOutcome.NeedsChoice(2)))
        assertEquals(UiAction.SHOW_LIST, IntentFeedback.uiAction(IntentOutcome.NeedsChoice(2)))
        assertEquals("没有找到可关闭的闹钟", zh(IntentOutcome.NothingToDismiss))
        assertEquals("No alarm found to dismiss", en(IntentOutcome.NothingToDismiss))
        assertEquals("已贪睡 5 分钟", zh(IntentOutcome.Snoozed(alarm, 5)))
        assertEquals("Snoozed for 5 min", en(IntentOutcome.Snoozed(alarm, 5)))
        assertEquals("现在没有正在响的闹钟", zh(IntentOutcome.NothingRinging))
        assertEquals("No alarm is ringing right now", en(IntentOutcome.NothingRinging))
    }

    @Test
    fun everyRejectReasonHasAnExplanationInBothLanguages() {
        for (reason in RejectReason.entries) {
            val z = zh(IntentOutcome.Rejected(reason))
            val e = en(IntentOutcome.Rejected(reason))
            assertTrue("zh $reason: $z", z!!.startsWith("无法处理请求：") && z.length > 8)
            assertTrue("en $reason: $e", e!!.startsWith("Could not handle the request: ") && e.length > 35)
            assertFalse("en must have no CJK: $e", e.any { it.code in 0x4E00..0x9FFF })
            assertEquals(UiAction.NONE, IntentFeedback.uiAction(IntentOutcome.Rejected(reason)))
        }
        assertEquals("无法处理请求：小时必须是 0 到 23", zh(IntentOutcome.Rejected(RejectReason.INVALID_HOUR)))
        assertEquals("Could not handle the request: the hour must be 0 to 23", en(IntentOutcome.Rejected(RejectReason.INVALID_HOUR)))
    }
}
