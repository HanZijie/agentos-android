package org.agentos.sample.alarm.data

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRepositoryTest {
    private val env = TestEnv()
    private val repo = env.repository

    @Test
    fun createPersistsSchedulesAndPublishes() {
        val created = repo.create(AlarmDraft(7, 30, label = " Wake up ", days = setOf(DayOfWeek.MONDAY)))
        assertEquals("Wake up", created.label)
        assertTrue(created.id.isNotEmpty())
        assertEquals(created, env.store.loadAll().single())
        assertEquals(env.millis(at(7, 30, day = 12)), env.scheduler.scheduled[created.id])
        assertEquals(created.fireAt, env.scheduler.scheduled[created.id])
        assertEquals(listOf(created), repo.alarms.value)
    }

    @Test
    fun createDisabledDoesNotSchedule() {
        val created = repo.create(AlarmDraft(7, 30, enabled = false))
        assertTrue(env.scheduler.scheduled.isEmpty())
        assertNull(created.fireAt)
    }

    @Test
    fun createValidatesInput() {
        assertThrows(AlarmException::class.java) { repo.create(AlarmDraft(24, 0)) }
        assertThrows(AlarmException::class.java) { repo.create(AlarmDraft(-1, 0)) }
        assertThrows(AlarmException::class.java) { repo.create(AlarmDraft(7, 60)) }
        assertThrows(AlarmException::class.java) { repo.create(AlarmDraft(7, 0, snoozeMinutes = 0)) }
        assertThrows(AlarmException::class.java) { repo.create(AlarmDraft(7, 0, snoozeMinutes = 61)) }
        assertThrows(AlarmException::class.java) { repo.create(AlarmDraft(7, 0, label = "x".repeat(61))) }
        assertTrue(repo.alarms.value.isEmpty())
        assertTrue(env.scheduler.scheduled.isEmpty())
    }

    @Test
    fun updateChangesOnlyGivenFieldsAndReschedules() {
        val a = repo.create(AlarmDraft(7, 0, label = "A", vibrate = true))
        val updated = repo.update(a.id, AlarmPatch(hour = 9, minute = 15))
        assertEquals(9, updated.hour)
        assertEquals(15, updated.minute)
        assertEquals("A", updated.label)
        assertTrue(updated.vibrate)
        assertEquals(env.millis(at(9, 15)), env.scheduler.scheduled[a.id])
    }

    @Test
    fun updateCanClearLabelAndRingtone() {
        val a = repo.create(AlarmDraft(7, 0, label = "A", ringtoneUri = "content://x/1"))
        val cleared = repo.update(a.id, AlarmPatch(label = "", ringtoneUri = ""))
        assertEquals("", cleared.label)
        assertNull(cleared.ringtoneUri)
    }

    @Test
    fun updateUnknownIdAndInvalidValuesFail() {
        assertThrows(AlarmException::class.java) { repo.update("nope", AlarmPatch(hour = 1)) }
        val a = repo.create(AlarmDraft(7, 0))
        assertThrows(AlarmException::class.java) { repo.update(a.id, AlarmPatch(hour = 25)) }
        assertEquals(7, repo.get(a.id)!!.hour)
    }

    @Test
    fun disableCancelsAndEnableReschedules() {
        val a = repo.create(AlarmDraft(7, 0))
        repo.setEnabled(a.id, false)
        assertFalse(env.scheduler.scheduled.containsKey(a.id))
        assertTrue(a.id in env.scheduler.cancelled)
        val on = repo.setEnabled(a.id, true)
        assertEquals(env.millis(at(7, 0, day = 8)), env.scheduler.scheduled[a.id])
        assertEquals(on.fireAt, env.scheduler.scheduled[a.id])
    }

    @Test
    fun deleteCancelsRemovesAndRestoreBringsBack() {
        val a = repo.create(AlarmDraft(7, 0, label = "keep"))
        val deleted = repo.delete(a.id)
        assertEquals(a.id, deleted.id)
        assertTrue(repo.alarms.value.isEmpty())
        assertTrue(env.store.loadAll().isEmpty())
        assertFalse(env.scheduler.scheduled.containsKey(a.id))
        assertNull(repo.get(a.id))

        val restored = repo.restore(deleted)
        assertEquals(a.id, restored.id)
        assertEquals("keep", repo.get(a.id)!!.label)
        assertTrue(env.scheduler.scheduled.containsKey(a.id))
        assertEquals(1, repo.alarms.value.size)
    }

    @Test
    fun deleteUnknownIdFails() {
        assertThrows(AlarmException::class.java) { repo.delete("nope") }
    }

    @Test
    fun listIsOrderedByNextFireThenDisabledLast() {
        val tomorrowMorning = repo.create(AlarmDraft(6, 0, label = "tomorrow"))
        val tonight = repo.create(AlarmDraft(22, 0, label = "tonight"))
        val off = repo.create(AlarmDraft(9, 0, label = "off", enabled = false))
        assertEquals(listOf(tonight.id, tomorrowMorning.id, off.id), repo.list().map { it.id })
        assertEquals(listOf(tonight.id, tomorrowMorning.id), repo.list(enabledOnly = true).map { it.id })
    }

    @Test
    fun stateFlowIsSortedByClockTime() {
        repo.create(AlarmDraft(22, 0))
        repo.create(AlarmDraft(6, 0))
        assertEquals(listOf(6, 22), repo.alarms.value.map { it.hour })
    }

    @Test
    fun nextAlarmPicksEarliestAndNullWhenNoneOn() {
        assertNull(repo.nextAlarm())
        repo.create(AlarmDraft(6, 0))
        val tonight = repo.create(AlarmDraft(22, 0))
        repo.create(AlarmDraft(9, 0, enabled = false))
        val next = repo.nextAlarm()
        assertNotNull(next)
        assertEquals(tonight.id, next!!.alarm.id)
        assertEquals(env.millis(at(22, 0)), next.fireAtMillis)
    }

    @Test
    fun onFiredOneShotSwitchesOff() {
        val a = repo.create(AlarmDraft(8, 30))
        env.time.current = at(8, 30)
        val fired = repo.onFired(a.id)
        assertNotNull(fired)
        assertFalse(repo.get(a.id)!!.enabled)
        assertFalse(env.scheduler.scheduled.containsKey(a.id))
    }

    @Test
    fun onFiredRepeatingStaysOnAndSchedulesNext() {
        val a = repo.create(AlarmDraft(8, 30, days = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)))
        env.time.current = at(8, 30)
        repo.onFired(a.id)
        assertTrue(repo.get(a.id)!!.enabled)
        assertEquals(env.millis(at(8, 30, day = 8)), env.scheduler.scheduled[a.id])
    }

    @Test
    fun onFiredStaleReturnsNull() {
        val a = repo.create(AlarmDraft(8, 30, enabled = false))
        assertNull(repo.onFired(a.id))
        assertNull(repo.onFired("nope"))
    }

    @Test
    fun snoozeSchedulesAfterSnoozeMinutesAndKeepsRegularRepeat() {
        val a = repo.create(AlarmDraft(8, 30, days = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), snoozeMinutes = 7))
        env.time.current = at(8, 30)
        repo.onFired(a.id)
        val snoozed = repo.snooze(a.id)
        assertEquals(env.millis(at(8, 37)), snoozed.snoozedUntil)
        assertEquals(env.millis(at(8, 37)), env.scheduler.scheduled[a.id])
        // 到点后贪睡结束，重新排下一个正常日
        env.time.current = at(8, 37)
        repo.onFired(a.id)
        assertNull(repo.get(a.id)!!.snoozedUntil)
        assertEquals(env.millis(at(8, 30, day = 8)), env.scheduler.scheduled[a.id])
    }

    @Test
    fun snoozeOnOneShotKeepsItAliveUntilSnoozeFires() {
        val a = repo.create(AlarmDraft(8, 30, snoozeMinutes = 5))
        env.time.current = at(8, 30)
        repo.onFired(a.id) // 响了，一次性闹钟关掉开关
        repo.snooze(a.id)
        val snoozed = repo.get(a.id)!!
        assertTrue(snoozed.enabled)
        assertEquals(env.millis(at(8, 35)), env.scheduler.scheduled[a.id])
        env.time.current = at(8, 35)
        repo.onFired(a.id)
        assertFalse(repo.get(a.id)!!.enabled)
        assertFalse(env.scheduler.scheduled.containsKey(a.id))
    }

    @Test
    fun changingTimeEndsSnooze() {
        val a = repo.create(AlarmDraft(8, 30))
        env.time.current = at(8, 30)
        repo.onFired(a.id)
        repo.snooze(a.id)
        val moved = repo.update(a.id, AlarmPatch(hour = 11))
        assertNull(moved.snoozedUntil)
        assertEquals(env.millis(at(11, 30)), env.scheduler.scheduled[a.id])
    }

    @Test
    fun switchingOffCancelsSnooze() {
        val a = repo.create(AlarmDraft(8, 30))
        env.time.current = at(8, 30)
        repo.onFired(a.id)
        repo.snooze(a.id)
        repo.setEnabled(a.id, false)
        assertFalse(env.scheduler.scheduled.containsKey(a.id))
        assertNull(repo.get(a.id)!!.snoozedUntil)
    }

    @Test
    fun rescheduleAllReRegistersEverything() {
        val a = repo.create(AlarmDraft(9, 0))
        val b = repo.create(AlarmDraft(10, 0, days = setOf(DayOfWeek.FRIDAY)))
        env.scheduler.scheduled.clear() // 模拟开机后系统丢了全部登记
        val missed = repo.rescheduleAll(detectMissed = true)
        assertTrue(missed.isEmpty())
        assertEquals(env.millis(at(9, 0)), env.scheduler.scheduled[a.id])
        assertEquals(env.millis(at(10, 0, day = 9)), env.scheduler.scheduled[b.id])
    }

    @Test
    fun rescheduleAllRecomputesAfterTimeZoneChange() {
        val a = repo.create(AlarmDraft(10, 0))
        val before = env.scheduler.scheduled.getValue(a.id)
        // 设备时区从上海换到东京：本地 10:00 对应的绝对时刻提前一小时
        env.time.current = env.time.current.withZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo"))
        repo.rescheduleAll(detectMissed = false)
        assertEquals(before - 3_600_000L, env.scheduler.scheduled.getValue(a.id))
    }

    @Test
    fun rescheduleAllReportsMissedAndAdvancesThem() {
        val oneShot = repo.create(AlarmDraft(9, 0))
        val repeating = repo.create(AlarmDraft(9, 0, days = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)))
        // 关机睡过了 9:00，10:00 开机
        env.time.current = at(10, 0)
        val missed = repo.rescheduleAll(detectMissed = true)
        assertEquals(setOf(oneShot.id, repeating.id), missed.map { it.id }.toSet())
        assertFalse(repo.get(oneShot.id)!!.enabled)
        assertTrue(repo.get(repeating.id)!!.enabled)
        assertEquals(env.millis(at(9, 0, day = 8)), env.scheduler.scheduled[repeating.id])
        assertFalse(env.scheduler.scheduled.containsKey(oneShot.id))
    }

    @Test
    fun rescheduleAllLeavesJustFiredAlarmAlone() {
        val a = repo.create(AlarmDraft(9, 0))
        env.time.current = at(9, 0, second = 20) // 刚到点 20 秒，系统正在投递
        val missed = repo.rescheduleAll(detectMissed = true)
        assertTrue(missed.isEmpty())
        assertTrue(repo.get(a.id)!!.enabled)
    }

    @Test
    fun sqliteMaskBoundaryFields() {
        val a = repo.create(AlarmDraft(0, 0, days = EVERY_DAY, snoozeMinutes = 60))
        assertEquals(EVERY_DAY, a.days)
        assertEquals(60, a.snoozeMinutes)
    }

    @Test
    fun clearAllRemovesEverythingAndCancelsSystemAlarms() {
        val a = repo.create(AlarmDraft(9, 0))
        val b = repo.create(AlarmDraft(10, 0, days = setOf(DayOfWeek.FRIDAY)))
        repo.create(AlarmDraft(11, 0, enabled = false))
        assertEquals(2, env.scheduler.scheduled.size)

        assertEquals(3, repo.clearAll())
        assertTrue(repo.alarms.value.isEmpty())
        assertTrue(env.store.loadAll().isEmpty())
        assertTrue(env.scheduler.scheduled.isEmpty())
        assertTrue(a.id in env.scheduler.cancelled && b.id in env.scheduler.cancelled)
        assertNull(repo.nextAlarm())
        assertEquals(0, repo.clearAll()) // 再清一次：什么都没有，不报错
    }
}
