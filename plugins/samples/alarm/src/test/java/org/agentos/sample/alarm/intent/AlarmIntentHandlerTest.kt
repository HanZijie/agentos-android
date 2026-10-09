package org.agentos.sample.alarm.intent

import java.time.DayOfWeek
import org.agentos.sample.alarm.data.AlarmDraft
import org.agentos.sample.alarm.data.Reuse
import org.agentos.sample.alarm.data.TestEnv
import org.agentos.sample.alarm.data.at
import org.agentos.sample.alarm.tools.FakeRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求 → 仓库调用：新建 / 去重 / 关闭 / 贪睡。 */
class AlarmIntentHandlerTest {
    private val env = TestEnv() // 2026-10-07（周三）08:00
    private val ring = FakeRing()
    private val handler = AlarmIntentHandler(env.repository, ring)

    private fun set(hour: Int, minutes: Int = 0, label: String = "", days: Set<DayOfWeek> = emptySet(), skipUi: Boolean = false, vibrate: Boolean = true) =
        handler.handle(AlarmIntentRequest.SetAlarm(hour, minutes, label, days, vibrate, skipUi))

    private fun dismiss(target: DismissTarget) = handler.handle(AlarmIntentRequest.DismissAlarm(target))

    private fun ringNow(alarmId: String) {
        env.time.current = at(8, 0)
        ring.state.value = env.repository.onFired(alarmId)
    }

    // ---- SET ----

    @Test
    fun setCreatesAnEnabledAlarmAndSchedulesIt() {
        val out = set(7, 30, "Run", setOf(DayOfWeek.MONDAY), vibrate = false) as IntentOutcome.AlarmSet
        assertEquals(Reuse.CREATED, out.status)
        val a = out.alarm
        assertEquals(7, a.hour); assertEquals(30, a.minute); assertEquals("Run", a.label)
        assertEquals(setOf(DayOfWeek.MONDAY), a.days)
        assertTrue(a.enabled)
        assertFalse(a.vibrate)
        assertEquals(env.millis(at(7, 30, day = 12)), env.scheduler.scheduled[a.id]) // 下个周一
        assertEquals(listOf(a), env.repository.alarms.value)
    }

    @Test
    fun skipUiIsPassedThroughToTheOutcome() {
        assertTrue((set(7, skipUi = true) as IntentOutcome.AlarmSet).skipUi)
        assertFalse((set(8, skipUi = false) as IntentOutcome.AlarmSet).skipUi)
    }

    @Test
    fun theSameRequestTwiceDoesNotCreateAnotherAlarm() {
        val first = set(7, 30, "Run", setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)) as IntentOutcome.AlarmSet
        val second = set(7, 30, "Run", setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)) as IntentOutcome.AlarmSet
        assertEquals(Reuse.CREATED, first.status)
        assertEquals(Reuse.EXISTING, second.status)
        assertEquals(first.alarm.id, second.alarm.id)
        assertEquals(1, env.repository.alarms.value.size)
        assertEquals(1, env.scheduler.scheduled.size)
    }

    @Test
    fun aDisabledTwinIsTurnedBackOnBecauseSetAlarmAlwaysEnables() {
        val off = env.repository.create(AlarmDraft(7, 30, enabled = false))
        val out = set(7, 30) as IntentOutcome.AlarmSet
        assertEquals(Reuse.REENABLED, out.status)
        assertEquals(off.id, out.alarm.id)
        assertTrue(env.repository.get(off.id)!!.enabled)
        assertTrue(env.scheduler.scheduled.containsKey(off.id))
    }

    @Test
    fun setWithDifferentLabelOrDaysMakesAnotherAlarm() {
        set(7, 30, "Run")
        set(7, 30, "Gym")
        set(7, 30, "Run", setOf(DayOfWeek.SATURDAY))
        assertEquals(3, env.repository.alarms.value.size)
    }

    @Test
    fun rejectedAndEditorRequestsTouchNothing() {
        assertEquals(IntentOutcome.Rejected(RejectReason.INVALID_HOUR), handler.handle(AlarmIntentRequest.Rejected(RejectReason.INVALID_HOUR)))
        assertEquals(IntentOutcome.OpenEditor, handler.handle(AlarmIntentRequest.OpenEditor))
        assertTrue(env.repository.alarms.value.isEmpty())
        assertTrue(env.scheduler.scheduled.isEmpty())
    }

    // ---- DISMISS ----

    @Test
    fun dismissWithoutSearchClosesTheRingingAlarmFirst() {
        val a = env.repository.create(AlarmDraft(8, 0))
        env.repository.create(AlarmDraft(9, 0))
        ringNow(a.id)
        assertEquals(IntentOutcome.Dismissed(1, 0), dismiss(DismissTarget.Unspecified))
        assertEquals(1, ring.dismissed)
    }

    @Test
    fun dismissWithoutSearchClosesTheOnlyActiveAlarm() {
        val a = env.repository.create(AlarmDraft(9, 0))
        env.repository.create(AlarmDraft(10, 0, enabled = false)) // 关着的不算
        assertEquals(IntentOutcome.Dismissed(1, 0), dismiss(DismissTarget.Unspecified))
        assertFalse(env.repository.get(a.id)!!.enabled)
        assertFalse(env.scheduler.scheduled.containsKey(a.id))
    }

    @Test
    fun dismissWithoutSearchAsksTheUserWhenSeveralAreActive() {
        val a = env.repository.create(AlarmDraft(9, 0))
        val b = env.repository.create(AlarmDraft(10, 0))
        assertEquals(IntentOutcome.NeedsChoice(2), dismiss(DismissTarget.Unspecified))
        assertTrue(env.repository.get(a.id)!!.enabled && env.repository.get(b.id)!!.enabled)
        assertEquals(0, ring.dismissed)
    }

    @Test
    fun dismissNextTakesTheEarliestUpcomingOne() {
        val soon = env.repository.create(AlarmDraft(9, 0))
        val later = env.repository.create(AlarmDraft(10, 0))
        assertEquals(IntentOutcome.Dismissed(1, 0), dismiss(DismissTarget.Next))
        assertFalse(env.repository.get(soon.id)!!.enabled)
        assertTrue(env.repository.get(later.id)!!.enabled)
    }

    @Test
    fun dismissNextWithNoAlarmsSaysSo() {
        assertEquals(IntentOutcome.NothingToDismiss, dismiss(DismissTarget.Next))
        assertEquals(IntentOutcome.NothingToDismiss, dismiss(DismissTarget.Unspecified))
        assertEquals(IntentOutcome.NothingToDismiss, dismiss(DismissTarget.All))
    }

    @Test
    fun dismissByLabelMatchesAPhraseCaseInsensitively() {
        val run = env.repository.create(AlarmDraft(9, 0, label = "Morning Run"))
        val gym = env.repository.create(AlarmDraft(10, 0, label = "Gym"))
        assertEquals(IntentOutcome.Dismissed(1, 0), dismiss(DismissTarget.Label("run")))
        assertFalse(env.repository.get(run.id)!!.enabled)
        assertTrue(env.repository.get(gym.id)!!.enabled)
        assertEquals(IntentOutcome.NothingToDismiss, dismiss(DismissTarget.Label("nope")))
    }

    @Test
    fun dismissByLabelWithSeveralMatchesLeavesThemAloneAndAsks() {
        env.repository.create(AlarmDraft(9, 0, label = "Run A"))
        env.repository.create(AlarmDraft(10, 0, label = "Run B"))
        assertEquals(IntentOutcome.NeedsChoice(2), dismiss(DismissTarget.Label("Run")))
        assertTrue(env.repository.alarms.value.all { it.enabled })
    }

    @Test
    fun dismissByTimeUsesTwelveHourPmAndAmbiguousHours() {
        val morning = env.repository.create(AlarmDraft(7, 30))
        val evening = env.repository.create(AlarmDraft(19, 30))
        // 7:30 PM → 只匹配晚上那个
        assertEquals(IntentOutcome.Dismissed(1, 0), dismiss(DismissTarget.Time(7, 30, isPm = true)))
        assertTrue(env.repository.get(morning.id)!!.enabled)
        assertFalse(env.repository.get(evening.id)!!.enabled)
        env.repository.setEnabled(evening.id, true)
        // 7:30 没说上下午：两个都匹配 → 让用户选
        assertEquals(IntentOutcome.NeedsChoice(2), dismiss(DismissTarget.Time(7, 30, isPm = null)))
        // 19:30（24 小时制，不含糊）
        assertEquals(IntentOutcome.Dismissed(1, 0), dismiss(DismissTarget.Time(19, 30, isPm = null)))
        assertTrue(env.repository.get(morning.id)!!.enabled)
        // 只给分钟：任意小时的 :30
        env.repository.setEnabled(evening.id, false)
        assertEquals(IntentOutcome.Dismissed(1, 0), dismiss(DismissTarget.Time(null, 30, isPm = null)))
        assertEquals(IntentOutcome.NothingToDismiss, dismiss(DismissTarget.Time(3, 15, isPm = false)))
    }

    @Test
    fun midnightAndNoonAreNotMixedUpByTwelveHourSearch() {
        val midnight = env.repository.create(AlarmDraft(0, 0))
        val noon = env.repository.create(AlarmDraft(12, 0))
        assertEquals(IntentOutcome.Dismissed(1, 0), dismiss(DismissTarget.Time(12, 0, isPm = false)))
        assertFalse(env.repository.get(midnight.id)!!.enabled)
        assertTrue(env.repository.get(noon.id)!!.enabled)
        assertEquals(IntentOutcome.Dismissed(1, 0), dismiss(DismissTarget.Time(12, 0, isPm = true)))
        assertFalse(env.repository.get(noon.id)!!.enabled)
    }

    @Test
    fun aRepeatingAlarmThatIsNotRingingIsKeptOnBecauseThereIsNoSkipOnce() {
        val daily = env.repository.create(AlarmDraft(9, 0, days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)))
        val result = dismiss(DismissTarget.Next)
        assertEquals(IntentOutcome.Dismissed(0, 1), result)
        assertTrue(env.repository.get(daily.id)!!.enabled)
        assertTrue(env.scheduler.scheduled.containsKey(daily.id))
        assertEquals(UiAction.SHOW_LIST, IntentFeedback.uiAction(result))
    }

    @Test
    fun dismissAllClosesOneShotsKeepsRepeatingAndStopsTheRingingOne() {
        val ringingOne = env.repository.create(AlarmDraft(8, 0))
        val oneShot = env.repository.create(AlarmDraft(9, 0))
        val repeating = env.repository.create(AlarmDraft(10, 0, days = setOf(DayOfWeek.FRIDAY)))
        ringNow(ringingOne.id)
        assertEquals(IntentOutcome.Dismissed(2, 1), dismiss(DismissTarget.All))
        assertEquals(1, ring.dismissed)
        assertFalse(env.repository.get(oneShot.id)!!.enabled)
        assertTrue(env.repository.get(repeating.id)!!.enabled)
    }

    @Test
    fun dismissingASnoozedAlarmEndsTheSnoozeAndKeepsARepeatingOneOn() {
        val once = env.repository.create(AlarmDraft(8, 0))
        val weekly = env.repository.create(AlarmDraft(8, 0, label = "w", days = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)))
        env.time.current = at(8, 0)
        env.repository.onFired(once.id); env.repository.snooze(once.id)
        env.repository.onFired(weekly.id); env.repository.snooze(weekly.id)
        assertEquals(IntentOutcome.Dismissed(2, 0), dismiss(DismissTarget.All))
        assertFalse(env.repository.get(once.id)!!.enabled)
        assertNull(env.repository.get(once.id)!!.snoozedUntil)
        assertTrue(env.repository.get(weekly.id)!!.enabled) // 重复的只取消贪睡
        assertNull(env.repository.get(weekly.id)!!.snoozedUntil)
    }

    // ---- SNOOZE ----

    @Test
    fun snoozeWithNothingRingingIsANoOpWithAnExplanation() {
        env.repository.create(AlarmDraft(9, 0))
        assertEquals(IntentOutcome.NothingRinging, handler.handle(AlarmIntentRequest.SnoozeAlarm(null)))
        assertEquals(0, ring.snoozed)
    }

    @Test
    fun snoozeUsesTheAlarmsOwnLengthUnlessTheIntentGivesOne() {
        val a = env.repository.create(AlarmDraft(8, 0, snoozeMinutes = 7))
        ringNow(a.id)
        val own = handler.handle(AlarmIntentRequest.SnoozeAlarm(null)) as IntentOutcome.Snoozed
        assertEquals(7, own.minutes)
        assertNull(ring.lastSnoozeMinutes)

        ring.state.value = a // 假的响铃控制不会真去改仓库，这里直接让它“又响了”
        val custom = handler.handle(AlarmIntentRequest.SnoozeAlarm(3)) as IntentOutcome.Snoozed
        assertEquals(3, custom.minutes)
        assertEquals(3, ring.lastSnoozeMinutes)
        assertEquals(2, ring.snoozed)
    }
}
