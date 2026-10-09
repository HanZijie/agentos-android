package org.agentos.sample.calendar

import org.agentos.sample.calendar.data.CalendarInfo
import org.agentos.sample.calendar.data.LegacyDefaultNames
import org.agentos.sample.calendar.data.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C3：升级前写进 SQLite 的本机日历 / 日程（裸 UUID id）必须原样保留；R3：默认日历名不冻结在某种语言里。
 * 数据库文件本身的 v1→v2 迁移（加 name_key 列、给旧默认名打标记）由设备上的升级覆盖安装验证（见 README），
 * 这里验证“迁移之后 / 旧库原样”仓库看到的东西，以及迁移用的判定函数。
 */
class MigrationAndLocalizationTest {
    private val cal1 = "0193d6e5-f5ac-46f8-be50-507fc6dd6559"
    private val cal2 = "7a1f0c2e-1111-4222-8333-944455556666"
    private val ev1 = "86808e2e-61ae-46a1-b76a-4734fd847bf4"
    private val ev2 = "327a9ee4-ebe0-4f9c-9558-719277775f3f"

    /** 升级前的库：裸 UUID、没有 name_key、默认日历的名字是创建时那种语言的文字。 */
    private fun legacyStore(defaultName: String = "My Calendar") = InMemoryCalendarStore().apply {
        calendars[cal1] = CalendarInfo(cal1, defaultName, 0xFFE4572E.toInt(), visible = true, isDefault = true, createdAt = 1)
        calendars[cal2] = CalendarInfo(cal2, "Work", 0xFF4A7BDB.toInt(), visible = false, isDefault = false, createdAt = 2)
        events[ev1] = timed(id = ev1, title = "和王总开会", start = "2026-10-09T15:00", end = "2026-10-09T16:00", calendarId = cal1, reminders = listOf(15))
        events[ev2] = timed(id = ev2, title = "周会", start = "2026-10-12T10:00", end = "2026-10-12T11:00", calendarId = cal2, recurrence = Recurrence.WEEKLY)
    }

    @Test fun legacyCalendarsAndEventsSurviveWithPrefixedIdsAndUnchangedStoredRows() {
        val store = legacyStore()
        val repo = newRepo(store = store)
        assertEquals("no second default calendar is created", 2, repo.calendars.value.size)
        assertEquals(setOf("local:$cal1", "local:$cal2"), repo.calendars.value.map { it.id }.toSet())
        assertEquals(setOf("local:$ev1", "local:$ev2"), repo.localEvents.value.map { it.id }.toSet())
        assertEquals("local:$cal1", repo.localDefaultCalendar.id)
        assertEquals(false, repo.calendar("local:$cal2")!!.visible)
        assertEquals(listOf(15), repo.event("local:$ev1")!!.reminders)
        // 库里的行没动：还是裸 id，下次（旧版本）也读得回来
        assertEquals(setOf(ev1, ev2), store.events.keys)
        assertEquals(setOf(cal1, cal2), store.calendars.keys)
        assertTrue(store.events.values.all { !it.id.startsWith("local:") && !it.calendarId.startsWith("local:") })
    }

    @Test fun legacyIdsStillResolveAndNewWritesLeaveOldRowsAlone() {
        val store = legacyStore()
        val repo = newRepo(store = store)
        assertNotNull("a bare id from an old notification still finds the event", repo.event(ev1))
        val second = repo.occurrence("$ev2@20261019T020000Z") // 周会：2026-10-12 10:00+08 起每周，第二次 = 10-19 02:00Z
        assertNotNull("occurrence ids of a recurring legacy series", second)
        assertEquals("local:$ev2@20261019T020000Z", second!!.id)
        repo.saveEvent(timed(id = "", title = "新日程", start = "2026-10-10T10:00", end = "2026-10-10T11:00", calendarId = "local:$cal2"))
        assertEquals(3, store.events.size)
        repo.saveEvent(repo.event(ev1)!!.copy(title = "和王总开会（改）"))
        assertEquals("和王总开会（改）", store.events.getValue(ev1).title)
        assertEquals("stored with the bare id", ev1, store.events.getValue(ev1).id)
        repo.deleteEvent("local:$ev2")
        assertNull(store.events[ev2])
        assertEquals("the other calendar's data is untouched", 2, store.events.size)
    }

    @Test fun theLegacyDefaultNameRuleOnlyMatchesTheDefaultCalendarWithAKnownDefaultName() {
        assertTrue(LegacyDefaultNames.isSystemDefault("我的日历", isDefault = true))
        assertTrue(LegacyDefaultNames.isSystemDefault("My Calendar", isDefault = true))
        assertFalse("renamed by the user: untouched", LegacyDefaultNames.isSystemDefault("Home", isDefault = true))
        assertFalse("a non-default calendar that happens to be called that: untouched", LegacyDefaultNames.isSystemDefault("My Calendar", isDefault = false))
        assertFalse(LegacyDefaultNames.isSystemDefault("my calendar", isDefault = true))
    }

    // ---- R3：默认日历名跟着语言走 ----

    @Test fun theDefaultCalendarNameIsAMarkerTranslatedOnDisplay() {
        val store = InMemoryCalendarStore()
        val texts = FakeTexts("我的日历")
        val repo = newRepo(store = store, texts = texts)
        assertEquals("我的日历", repo.defaultCalendar.name)
        val stored = store.calendars.values.single()
        assertEquals(CalendarInfo.NAME_KEY_DEFAULT, stored.nameKey)
        assertEquals("the stored name is a filler, not the language of the day", LegacyDefaultNames.FILLER, stored.name)
        // 切到英文（应用语言变了 → 仓库刷新）
        texts.defaultName = "My Calendar"
        repo.reloadCalendars()
        assertEquals("My Calendar", repo.calendars.value.single().name)
        // 再切回中文
        texts.defaultName = "我的日历"
        repo.reloadCalendars()
        assertEquals("我的日历", repo.calendars.value.single().name)
        assertEquals("nothing was rewritten while switching", stored, store.calendars.values.single())
    }

    @Test fun aMigratedDefaultCalendarFollowsTheLanguageToo() {
        // 迁移后的库：旧默认名已经被换成标记（name_key = default），名字列还留着旧文字
        val store = legacyStore("我的日历")
        store.calendars[cal1] = store.calendars.getValue(cal1).copy(nameKey = CalendarInfo.NAME_KEY_DEFAULT)
        val texts = FakeTexts("My Calendar")
        val repo = newRepo(store = store, texts = texts)
        assertEquals("My Calendar", repo.calendar("local:$cal1")!!.name)
        texts.defaultName = "我的日历"
        repo.reloadCalendars()
        assertEquals("我的日历", repo.calendar("local:$cal1")!!.name)
        assertEquals("Work", repo.calendar("local:$cal2")!!.name)
    }

    @Test fun aNameTheUserTypedIsNeverTranslatedOrFrozenAgain() {
        val store = InMemoryCalendarStore()
        val texts = FakeTexts("我的日历")
        val repo = newRepo(store = store, texts = texts)
        val id = repo.localDefaultCalendar.id
        repo.updateCalendar(id, name = "Home")
        assertNull(store.calendars.values.single().nameKey)
        assertEquals("Home", store.calendars.values.single().name)
        texts.defaultName = "My Calendar"
        repo.reloadCalendars()
        assertEquals("Home", repo.calendars.value.single().name)
    }

    @Test fun changingOnlyTheColorKeepsTheDefaultNameMarker() {
        val store = InMemoryCalendarStore()
        val texts = FakeTexts("我的日历")
        val repo = newRepo(store = store, texts = texts)
        val id = repo.localDefaultCalendar.id
        // 日历管理界面保存时总会把当前显示的名字一并传回来
        repo.updateCalendar(id, name = "我的日历", color = 0xFF2BA0A4.toInt())
        val stored = store.calendars.values.single()
        assertEquals(CalendarInfo.NAME_KEY_DEFAULT, stored.nameKey)
        assertEquals(LegacyDefaultNames.FILLER, stored.name)
        assertEquals(0xFF2BA0A4.toInt(), stored.color)
        texts.defaultName = "My Calendar"
        repo.reloadCalendars()
        assertEquals("My Calendar", repo.calendars.value.single().name)
    }

    @Test fun eachLanguageGetsItsOwnDefaultNameInTheToolJson() {
        // R8：产生文案的路径接收文字提供者，中英各一个用例（不整句硬断言中文）
        for ((lang, name) in listOf("zh" to "我的日历", "en" to "My Calendar")) {
            val repo = newRepo(texts = FakeTexts(name))
            assertEquals(lang, name, repo.calendars.value.single().name)
            assertTrue(repo.calendars.value.single().isDefault)
        }
    }
}
