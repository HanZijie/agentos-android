package org.agentos.sample.calendar

import org.agentos.sample.calendar.data.CalendarException
import org.agentos.sample.calendar.data.CalendarInfo
import org.agentos.sample.calendar.data.CalendarPermissionException
import org.agentos.sample.calendar.data.CalendarSource
import org.agentos.sample.calendar.data.DefaultCalendar
import org.agentos.sample.calendar.data.MemoryCalendarSettings
import org.agentos.sample.calendar.data.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 本机日历 + 系统日历（假后端）的组合：路由、默认写入日历、权限缺失、custom 重复、只读、视窗、复位。 */
class CalendarRoutingTest {
    private val time = FakeTime()
    private val system = FakeSystemBackend(time = time)
    private val settings = MemoryCalendarSettings()
    private val google = system.addCalendar(1, "Work (Google)", "me@example.com", "com.google", CalendarSource.GOOGLE)
    private val repo = newRepo(time = time, system = system, settings = settings)

    private fun expectError(contains: String, block: () -> Unit) {
        try {
            block()
            fail("expected CalendarException containing '$contains'")
        } catch (e: CalendarException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(contains, ignoreCase = true))
        }
    }

    private val from = ms("2026-10-01T00:00:00+08:00")
    private val to = ms("2026-11-01T00:00:00+08:00")

    // ---- 日历列表 ----

    @Test fun calendarsMergeLocalFirstThenSystemCalendarsByAccountAndName() {
        system.addCalendar(2, "Birthdays", "a@example.com", "com.google", CalendarSource.GOOGLE, writable = false)
        repo.reloadCalendars()
        val cals = repo.calendars.value
        assertEquals(listOf("Personal", "Birthdays", "Work (Google)"), cals.map { it.name })
        assertEquals(listOf(false, true, true), cals.map { it.system })
        assertTrue(cals[0].id.startsWith("local:") && cals[1].id.startsWith("sys:"))
    }

    @Test fun withoutPermissionOnlyLocalCalendarsAreListedAndNothingThrows() {
        system.access = false
        val cals = repo.reloadCalendars()
        assertEquals(listOf("Personal"), cals.map { it.name })
        assertFalse(repo.systemAccess.value)
    }

    // ---- 默认写入日历 ----

    @Test fun defaultWriteIsTheFirstWritableVisibleAccountCalendarNotTheLocalOne() {
        assertEquals(google.id, repo.defaultWriteCalendar().id)
        assertEquals("local default stays the undeletable one", "local:id1", repo.localDefaultCalendar.id)
    }

    @Test fun defaultWriteFallsBackToTheLocalCalendarWhenNoAccountCalendarQualifies() {
        val onlyLocalish = newRepo(
            system = FakeSystemBackend().also {
                it.addCalendar(1, "Read-only", writable = false)
                it.addCalendar(2, "Hidden", visible = false)
                it.addCalendar(3, "Device-only (LOCAL type)", account = "Phone", accountType = "LOCAL", source = CalendarSource.LOCAL)
            },
        )
        assertEquals("local:id1", onlyLocalish.defaultWriteCalendar().id)
        system.access = false
        repo.reloadCalendars()
        assertEquals("no permission -> local", "local:id1", repo.defaultWriteCalendar().id)
    }

    @Test fun defaultWriteDoesNotLookAtAPrimaryFlagAtAll() {
        // 国内机没有 Google 主日历：选法只看 可写 / 可见 / 非本机，与“主日历”无关（CalendarInfo 里根本没有这个字段）
        val cals = listOf(
            info("local:a", system = false, isDefault = true),
            info("sys:7", system = true, source = CalendarSource.CALDAV),
        )
        assertEquals("sys:7", DefaultCalendar.pick(cals, null).id)
    }

    @Test fun aConfiguredDefaultWinsIfItIsWritableOtherwiseTheAutomaticChoiceApplies() {
        val ro = system.addCalendar(5, "Shared (read-only)", writable = false)
        repo.reloadCalendars()
        repo.setDefaultWriteCalendar("local:id1")
        assertEquals("local:id1", repo.defaultWriteCalendar().id)
        assertEquals("local:id1", repo.defaultWriteId.value)
        expectError("read-only") { repo.setDefaultWriteCalendar(ro.id) }
        repo.setDefaultWriteCalendar(null)
        assertEquals(google.id, repo.defaultWriteCalendar().id)
        assertEquals(google.id, repo.defaultWriteId.value)
    }

    private fun info(id: String, system: Boolean, isDefault: Boolean = false, source: CalendarSource = CalendarSource.LOCAL) =
        CalendarInfo(id, id, 0, visible = true, isDefault = isDefault, createdAt = 0, source = source, system = system)

    // ---- 路由 ----

    @Test fun eventsAreRoutedToTheBackendOfTheirCalendar() {
        val a = repo.saveEvent(timed(id = "", title = "Local one", start = "2026-10-08T10:00", end = "2026-10-08T11:00", calendarId = "local:id1"))
        val b = repo.saveEvent(timed(id = "", title = "System one", start = "2026-10-08T12:00", end = "2026-10-08T13:00", calendarId = google.id))
        assertTrue(a.id.startsWith("local:") && b.id.startsWith("sys:"))
        assertEquals(listOf("Local one"), repo.localEvents.value.map { it.title })
        assertEquals(listOf("System one"), system.events.values.map { it.title })
        assertEquals(setOf(a.id, b.id), repo.occurrences(from, to).map { it.seriesId }.toSet())
        assertEquals("by calendar id", listOf("System one"), repo.occurrences(from, to, calendarId = google.id).map { it.series.title })
        assertEquals(b, repo.event(b.id))
        assertNotNull(repo.occurrence(b.id))
    }

    @Test fun occurrencesAreMergedAcrossBackendsInDisplayOrder() {
        repo.saveEvent(timed(id = "", title = "B local", start = "2026-10-08T12:00", end = "2026-10-08T13:00"))
        repo.saveEvent(timed(id = "", title = "A system", start = "2026-10-08T09:00", end = "2026-10-08T10:00", calendarId = google.id))
        repo.saveEvent(timed(id = "", title = "C system", start = "2026-10-08T15:00", end = "2026-10-08T16:00", calendarId = google.id))
        assertEquals(listOf("A system", "B local", "C system"), repo.occurrences(from, to).map { it.series.title })
        repo.updateCalendar(google.id, visible = false)
        assertEquals("hidden calendars are skipped only when asked", listOf("B local"), repo.occurrences(from, to, onlyVisible = true).map { it.series.title })
        assertEquals(3, repo.occurrences(from, to).size)
    }

    @Test fun eventsCannotBeMovedBetweenBackendsOrAmongSystemCalendars() {
        val other = system.addCalendar(2, "Another", "me@example.com")
        repo.reloadCalendars()
        val sysEv = repo.saveEvent(timed(id = "", start = "2026-10-08T12:00", end = "2026-10-08T13:00", calendarId = google.id))
        val locEv = repo.saveEvent(timed(id = "", start = "2026-10-08T14:00", end = "2026-10-08T15:00"))
        expectError("moving") { repo.saveEvent(sysEv.copy(calendarId = other.id)) }
        expectError("moving") { repo.saveEvent(sysEv.copy(calendarId = "local:id1")) }
        expectError("moving") { repo.saveEvent(locEv.copy(calendarId = google.id)) }
        val work = repo.createCalendar("Work")
        assertEquals("local to local is fine", work.id, repo.saveEvent(locEv.copy(calendarId = work.id)).calendarId)
    }

    @Test fun legacyUnprefixedIdsAreTreatedAsLocal() {
        val e = repo.saveEvent(timed(id = "", start = "2026-10-08T12:00", end = "2026-10-08T13:00", recurrence = Recurrence.DAILY))
        val bare = e.id.removePrefix("local:")
        assertEquals(e, repo.event(bare))
        assertNotNull(repo.occurrence("$bare@20261009T040000Z"))
        assertEquals(e, repo.deleteEvent(bare))
    }

    // ---- 权限 ----

    @Test fun withoutPermissionEveryReadAndWriteThatNeedsTheSystemRefusesWithTheFixedMessage() {
        val sysEv = repo.saveEvent(timed(id = "", start = "2026-10-08T12:00", end = "2026-10-08T13:00", calendarId = google.id))
        system.access = false
        val msg = "Calendar permission not granted; ask the user to grant it in the Calendar app"
        fun denied(block: () -> Unit) {
            try {
                block()
                fail("expected a permission error")
            } catch (e: CalendarPermissionException) {
                assertEquals(msg, e.message)
            }
        }
        denied { repo.occurrences(from, to) }
        denied { repo.occurrences(from, to, calendarId = google.id) }
        denied { repo.search("x") }
        denied { repo.event(sysEv.id) }
        denied { repo.occurrence(sysEv.id) }
        denied { repo.deleteEvent(sysEv.id) }
        denied { repo.saveEvent(sysEv.copy(title = "x")) }
        denied { repo.saveEvent(timed(id = "", start = "2026-10-08T12:00", end = "2026-10-08T13:00", calendarId = google.id)) }
        denied { repo.requireCalendar(google.id) }
        // 本机日历照常
        assertEquals(0, repo.occurrences(from, to, calendarId = "local:id1").size)
        assertEquals("local:id1", repo.saveEvent(timed(id = "", start = "2026-10-08T12:00", end = "2026-10-08T13:00")).calendarId)
        assertTrue(repo.search("Event", calendarId = "local:id1").size == 1)
    }

    @Test fun windowLoadingWithoutPermissionShowsLocalEventsAndDoesNotThrow() {
        repo.saveEvent(timed(id = "", title = "Local", start = "2026-10-08T12:00", end = "2026-10-08T13:00"))
        repo.saveEvent(timed(id = "", title = "Sys", start = "2026-10-08T14:00", end = "2026-10-08T15:00", calendarId = google.id))
        system.access = false
        repo.setWindow(from, to)
        assertEquals(listOf("Local"), repo.window.value.occurrences.map { it.series.title })
        system.access = true
        repo.refresh()
        assertEquals(setOf("Local", "Sys"), repo.window.value.occurrences.map { it.series.title }.toSet())
    }

    // ---- custom 重复规则 ----

    @Test fun aCustomSeriesCannotBeChangedButCanBeReadAndDeleted() {
        val c = system.seedEvent(customSeries(google.id))
        val read = repo.event(c.id)!!
        assertEquals(Recurrence.CUSTOM, read.recurrence)
        assertEquals("FREQ=MONTHLY;BYDAY=2TU", read.rrule)
        expectError("custom") { repo.saveEvent(read.copy(title = "Renamed")) }
        expectError("custom") { repo.saveEvent(read.copy(reminders = listOf(10))) }
        assertEquals("nothing changed", "Second Tuesday", system.events.getValue(c.id).title)
        expectError("custom") { repo.saveEvent(timed(id = "", start = "2026-10-08T12:00", end = "2026-10-08T13:00", calendarId = google.id, recurrence = Recurrence.CUSTOM)) }
        assertEquals(c.id, repo.deleteEvent(c.id).id)
    }

    // ---- 只读 ----

    @Test fun readOnlyCalendarsRejectWritesAndDeletes() {
        val ro = system.addCalendar(9, "Holidays", writable = false)
        repo.reloadCalendars()
        val seeded = system.seedEvent(timed(id = "", title = "Holiday", start = "2026-10-01T00:00", end = "2026-10-01T23:00", calendarId = ro.id))
        expectError("read-only") { repo.saveEvent(timed(id = "", start = "2026-10-08T12:00", end = "2026-10-08T13:00", calendarId = ro.id)) }
        expectError("read-only") { repo.saveEvent(seeded.copy(title = "x")) }
        expectError("read-only") { repo.deleteEvent(seeded.id) }
        assertEquals("still there", 1, system.events.values.count { it.calendarId == ro.id })
        assertEquals("but readable", listOf("Holiday"), repo.occurrences(from, to, calendarId = ro.id).map { it.series.title })
    }

    // ---- 日历本身的增删改 ----

    @Test fun accountAndSystemCalendarsCanOnlyBeShownOrHidden() {
        expectError("only be shown or hidden") { repo.updateCalendar(google.id, name = "Renamed") }
        expectError("only be shown or hidden") { repo.updateCalendar(google.id, color = 0xFF000000.toInt()) }
        assertEquals("passing the unchanged name back is fine", "Work (Google)", repo.updateCalendar(google.id, name = "Work (Google)", visible = false).name)
        assertFalse(repo.calendar(google.id)!!.visible)
        assertEquals("visibility is kept in the app settings, not in the system database", false, settings.visibility(google.id))
        assertTrue(repo.updateCalendar(google.id, visible = true).visible)
        expectError("cannot be deleted") { repo.deleteCalendar(google.id) }
        expectError("default calendar cannot be deleted") { repo.deleteCalendar("local:id1") }
        assertEquals("calendar_create only ever creates a local calendar", false, repo.createCalendar("New").system)
        expectError("already exists") { repo.createCalendar("work (google)") }
    }

    @Test fun deletingTheConfiguredDefaultLocalCalendarResetsTheSetting() {
        val work = repo.createCalendar("Work")
        repo.setDefaultWriteCalendar(work.id)
        assertEquals(work.id, repo.defaultWriteCalendar().id)
        repo.deleteCalendar(work.id)
        assertNull(settings.defaultWriteCalendarId)
        assertEquals(google.id, repo.defaultWriteCalendar().id)
    }

    // ---- 视窗 ----

    @Test fun theWindowSnapshotFollowsSetWindowAndExternalChanges() {
        repo.saveEvent(timed(id = "", title = "In", start = "2026-10-08T12:00", end = "2026-10-08T13:00", calendarId = google.id))
        repo.saveEvent(timed(id = "", title = "Out", start = "2026-12-08T12:00", end = "2026-12-08T13:00", calendarId = google.id))
        repo.setWindow(from, to)
        val first = repo.window.value
        assertEquals(listOf("In"), first.occurrences.map { it.series.title })
        assertEquals(from to to, first.fromMs to first.toMs)
        // 云端同步改了数据：变化回调 → 视窗刷新，版本号增加
        system.seedEvent(timed(id = "", title = "Synced down", start = "2026-10-09T12:00", end = "2026-10-09T13:00", calendarId = google.id))
        system.fireChange()
        assertTrue(repo.window.value.version > first.version)
        assertEquals(setOf("In", "Synced down"), repo.window.value.occurrences.map { it.series.title }.toSet())
        // 换一个视窗
        repo.setWindow(ms("2026-12-01T00:00:00+08:00"), ms("2027-01-01T00:00:00+08:00"))
        assertEquals(listOf("Out"), repo.window.value.occurrences.map { it.series.title })
    }

    @Test fun savingAndDeletingRefreshTheWindowImmediately() {
        repo.setWindow(from, to)
        val e = repo.saveEvent(timed(id = "", title = "New", start = "2026-10-08T12:00", end = "2026-10-08T13:00", calendarId = google.id))
        assertEquals(listOf("New"), repo.window.value.occurrences.map { it.series.title })
        repo.deleteEvent(e.id)
        assertTrue(repo.window.value.occurrences.isEmpty())
    }

    // ---- 搜索 ----

    @Test fun searchMergesBackendsUpcomingFirst() {
        repo.saveEvent(timed(id = "", title = "Sync past", start = "2026-09-20T10:00", end = "2026-09-20T11:00", calendarId = google.id))
        repo.saveEvent(timed(id = "", title = "Sync soon", start = "2026-10-20T10:00", end = "2026-10-20T11:00"))
        repo.saveEvent(timed(id = "", title = "Sync sooner", start = "2026-10-10T10:00", end = "2026-10-10T11:00", calendarId = google.id))
        assertEquals(listOf("Sync sooner", "Sync soon", "Sync past"), repo.search("sync").map { it.series.title })
        assertEquals(listOf("Sync soon"), repo.search("sync", calendarId = "local:id1").map { it.series.title })
        assertEquals(1, repo.search("sync", limit = 1).size)
    }

    // ---- 复位 / 打标 ----

    @Test fun clearOwnedEventsOnlyRemovesWhatThisAppCreated() {
        repo.saveEvent(timed(id = "", title = "Mine local", start = "2026-10-08T10:00", end = "2026-10-08T11:00"))
        repo.saveEvent(timed(id = "", title = "Mine system", start = "2026-10-08T12:00", end = "2026-10-08T13:00", calendarId = google.id))
        val theirs = system.seedEvent(timed(id = "", title = "Their meeting", start = "2026-10-08T14:00", end = "2026-10-08T15:00", calendarId = google.id))
        assertEquals(2, repo.ownedEvents().size)
        assertEquals("synced / other apps' events are only counted", 1, repo.foreignEventCount())
        val cleared = repo.clearOwnedEvents()
        assertEquals(1, cleared.local)
        assertEquals(1, cleared.system)
        assertEquals(listOf(theirs.id), system.events.keys.toList())
        assertTrue(repo.localEvents.value.isEmpty())
        assertEquals("system calendars are never deleted", 1, system.cals.size)
    }

    @Test fun deletingASystemEventCannotBeUndone() {
        val e = repo.saveEvent(timed(id = "", start = "2026-10-08T12:00", end = "2026-10-08T13:00", calendarId = google.id))
        val removed = repo.deleteEvent(e.id)
        expectError("cannot be restored") { repo.restoreEvent(removed) }
    }
}
