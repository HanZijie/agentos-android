package org.agentos.sample.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.sample.calendar.data.CalendarRepository
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.Recurrence
import org.agentos.sample.calendar.tools.CalendarTools
import org.agentos.sample.calendar.tools.ToolOutput
import org.agentos.sample.calendar.tools.WireSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 列表类工具的结果必须在 MCP 线上真实成本内：单条消息上限 65,536 字符，`McpToolResult.json(对象)` 把同一段 JSON 在 content
 * （转义成字符串）和 structuredContent 里各放一份。这里不用 WireSize 自己量自己，而是按 C7a 的真实结构拼出整条 JSON-RPC 响应再量长度。
 */
class WireLimitTest {
    private lateinit var repo: CalendarRepository
    private lateinit var tools: CalendarTools

    @Before fun setUp() {
        repo = newRepo(FakeTime())
        tools = CalendarTools(repo, Dispatchers.Unconfined)
    }

    private fun call(name: String, json: String = "{}"): ToolOutput =
        runBlocking { tools.all().first { it.name == name }.handler(Json.parseToJsonElement(json).jsonObject) }

    /** 一条 tools/call 响应在线上的字符数：{"jsonrpc","id","result":{"content":[{"type":"text","text":<紧凑 JSON 作为字符串>}],"structuredContent":<对象>}}。 */
    private fun wireChars(out: ToolOutput): Int {
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1234567)
            put(
                "result",
                buildJsonObject {
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", out.text) }) })
                    put("structuredContent", out.structured!!)
                },
            )
        }
        return response.toString().length
    }

    private fun obj(out: ToolOutput): JsonObject {
        assertFalse(out.text, out.isError)
        // 文本必须是合法 JSON，而且与 structuredContent 完全相同（没有截出半个对象）
        assertEquals(Json.parseToJsonElement(out.text), out.structured)
        return out.structured!!
    }

    /** 造 n 个带长备注（含引号和反斜杠，转义成本高）的日程，每天一个。 */
    private fun fill(n: Int, descriptionChars: Int = 2500) {
        val note = buildString { while (length < descriptionChars) append("会议纪要 \"重点\" C:\\path\\to 备注 ") }.take(descriptionChars)
        for (i in 0 until n) {
            val day = java.time.LocalDate.of(2026, 10, 8).plusDays(i.toLong())
            repo.saveEvent(
                EventSeries(
                    id = "", calendarId = repo.defaultCalendar.id, title = "Event $i", description = note, location = "Room $i",
                    startUtc = ms("${day}T10:00", "Asia/Shanghai"), endUtc = ms("${day}T11:00", "Asia/Shanghai"), zoneId = "Asia/Shanghai",
                ),
            )
        }
    }

    private fun ids(page: JsonObject, key: String = "events"): List<String> = page[key]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test fun twoHundredLargeEventsNeverExceedTheChannelLimitAndCanBePagedToTheEnd() {
        fill(200)
        val seen = ArrayList<String>()
        val titles = ArrayList<String>()
        var offset = 0
        var pages = 0
        while (true) {
            val out = call("event_list", """{"from":"2026-10-01","to":"2027-06-30","limit":200,"offset":$offset}""")
            val page = obj(out)
            assertTrue("page $pages costs ${wireChars(out)} chars on the wire", wireChars(out) <= WireSize.CHANNEL_LIMIT)
            assertTrue("a fitted page stays under our own budget too", WireSize.cost(page) <= WireSize.CHANNEL_LIMIT)
            assertEquals(200, page["total"]!!.jsonPrimitive.int)
            assertEquals(page["events"]!!.jsonArray.size, page["count"]!!.jsonPrimitive.int)
            assertTrue("every page returns at least one event", page["count"]!!.jsonPrimitive.int >= 1)
            assertEquals(offset, page["offset"]!!.jsonPrimitive.int)
            seen += ids(page)
            titles += page["events"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
            pages++
            if (!page["truncated"]!!.jsonPrimitive.boolean) {
                assertFalse("no next_offset on the last page", page.containsKey("next_offset"))
                break
            }
            val next = page["next_offset"]!!.jsonPrimitive.int
            assertEquals("next_offset continues exactly where this page stopped", offset + page["count"]!!.jsonPrimitive.int, next)
            offset = next
            assertTrue("paging terminates", pages < 200)
        }
        assertTrue("200 events of ~2.5k chars cannot fit in one message", pages > 1)
        assertEquals("every event is delivered exactly once, in order", 200, seen.size)
        assertEquals(seen.size, seen.toSet().size)
        assertEquals("pages follow start-time order without gaps or repeats", (0 until 200).map { "Event $it" }, titles)
    }

    @Test fun pagesAreContiguousAndOrderedByStartTime() {
        fill(60)
        val titles = ArrayList<String>()
        var offset = 0
        do {
            val page = obj(call("event_list", """{"from":"2026-10-01","to":"2027-06-30","limit":200,"offset":$offset}"""))
            titles += page["events"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
            offset = page["next_offset"]?.jsonPrimitive?.int ?: -1
        } while (offset >= 0)
        assertEquals((0 until 60).map { "Event $it" }, titles)
    }

    @Test fun smallResultsAreNotTruncated() {
        fill(5, descriptionChars = 100)
        val page = obj(call("event_list", """{"from":"2026-10-01","to":"2026-12-31"}"""))
        assertEquals(5, page["count"]!!.jsonPrimitive.int)
        assertFalse(page["truncated"]!!.jsonPrimitive.boolean)
        assertFalse(page.containsKey("next_offset"))
    }

    @Test fun userLimitAndSizeCutCombine() {
        fill(40)
        val page = obj(call("event_list", """{"from":"2026-10-01","to":"2027-06-30","limit":10}"""))
        assertEquals(10, page["limit"]!!.jsonPrimitive.int)
        assertTrue(page["count"]!!.jsonPrimitive.int in 1..10)
        assertTrue(page["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(page["count"]!!.jsonPrimitive.int, page["next_offset"]!!.jsonPrimitive.int)
    }

    @Test fun offsetPastTheEndReturnsAnEmptyPageAndNegativeOffsetIsRejected() {
        fill(3, descriptionChars = 50)
        val past = obj(call("event_list", """{"from":"2026-10-01","to":"2026-12-31","offset":50}"""))
        assertEquals(0, past["count"]!!.jsonPrimitive.int)
        assertFalse(past["truncated"]!!.jsonPrimitive.boolean)
        val bad = call("event_list", """{"offset":-1}""")
        assertTrue(bad.isError)
        assertTrue(bad.text.contains("offset"))
    }

    @Test fun searchPagesThroughLargeResultsToo() {
        fill(150)
        val seen = ArrayList<String>()
        var offset = 0
        var pages = 0
        do {
            val out = call("event_search", """{"query":"会议纪要","limit":200,"offset":$offset}""")
            val page = obj(out)
            assertTrue("search page $pages costs ${wireChars(out)}", wireChars(out) <= WireSize.CHANNEL_LIMIT)
            assertEquals(150, page["total"]!!.jsonPrimitive.int)
            assertEquals("会议纪要", page["query"]!!.jsonPrimitive.content)
            seen += ids(page)
            offset = page["next_offset"]?.jsonPrimitive?.int ?: -1
            pages++
        } while (offset >= 0 && pages < 200)
        assertTrue(pages > 1)
        assertEquals(150, seen.toSet().size)
    }

    @Test fun agendaTodayPagesWhenTodayIsPacked() {
        // 今天（2026-10-07，FakeTime 的 now）塞 200 个带长备注的日程
        val note = buildString { while (length < 3000) append("今天的安排 \"重点\" ") }.take(3000)
        for (i in 0 until 200) {
            val minute = i * 7
            val start = ms("2026-10-07T00:00", "Asia/Shanghai") + minute * 60_000L
            repo.saveEvent(
                EventSeries(
                    id = "", calendarId = repo.defaultCalendar.id, title = "Today $i", description = note,
                    startUtc = start, endUtc = start + 5 * 60_000L, zoneId = "Asia/Shanghai",
                ),
            )
        }
        val titles = ArrayList<String>()
        var offset = 0
        var pages = 0
        do {
            val out = call("agenda_today", """{"offset":$offset}""")
            val page = obj(out)
            assertTrue("agenda page $pages costs ${wireChars(out)}", wireChars(out) <= WireSize.CHANNEL_LIMIT)
            assertEquals("2026-10-07", page["date"]!!.jsonPrimitive.content)
            assertEquals(200, page["total"]!!.jsonPrimitive.int)
            titles += page["events"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
            offset = page["next_offset"]?.jsonPrimitive?.int ?: -1
            pages++
        } while (offset >= 0 && pages < 200)
        assertTrue(pages > 1)
        assertEquals((0 until 200).map { "Today $it" }, titles)
    }

    @Test fun recurringEventsExpandedToManyItemsArePagedToo() {
        repo.saveEvent(
            EventSeries(
                id = "", calendarId = repo.defaultCalendar.id, title = "Daily", description = "x".repeat(3500),
                startUtc = ms("2026-10-08T09:00", "Asia/Shanghai"), endUtc = ms("2026-10-08T09:30", "Asia/Shanghai"),
                zoneId = "Asia/Shanghai", recurrence = Recurrence.DAILY,
            ),
        )
        var offset = 0
        var total = 0
        var pages = 0
        do {
            val out = call("event_list", """{"from":"2026-10-01","to":"2027-12-31","limit":200,"offset":$offset}""")
            val page = obj(out)
            assertTrue(wireChars(out) <= WireSize.CHANNEL_LIMIT)
            total += page["count"]!!.jsonPrimitive.int
            offset = page["next_offset"]?.jsonPrimitive?.int ?: -1
            pages++
        } while (offset >= 0 && pages < 200)
        assertTrue(pages > 1)
        // 2026-10-08 到 2027-12-31，每天一次：24 + 30 + 31 + 365 = 450
        assertEquals("every occurrence of the daily event is delivered across pages", 450, total)
    }

    @Test fun calendarListPagesWhenThereAreHundredsOfCalendars() {
        // 每个日历一项约 150 字符（线上 ×2），600 个远超 60,000
        for (i in 0 until 600) repo.createCalendar("Calendar $i ${"n".repeat(40)}")
        val names = ArrayList<String>()
        var offset = 0
        var pages = 0
        do {
            val out = call("calendar_list", """{"offset":$offset}""")
            val page = obj(out)
            assertTrue("calendar_list page $pages costs ${wireChars(out)}", wireChars(out) <= WireSize.CHANNEL_LIMIT)
            assertEquals(601, page["total"]!!.jsonPrimitive.int)
            names += page["calendars"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            offset = page["next_offset"]?.jsonPrimitive?.int ?: -1
            pages++
        } while (offset >= 0 && pages < 50)
        assertEquals(601, names.size)
        assertEquals(601, names.toSet().size)
        assertTrue("600 calendars need more than one page", pages > 1)
    }

    @Test fun freeSlotsWithAHugeBusyListStaysInsideTheLimit() {
        // 一天里 600 个很短的日程：忙碌清单很长，时段本身不多
        val d = "2026-10-08"
        for (i in 0 until 600) {
            val start = ms("${d}T00:00", "Asia/Shanghai") + i * 2 * 60_000L
            repo.saveEvent(
                EventSeries(
                    id = "", calendarId = repo.defaultCalendar.id, title = "Tiny ${"t".repeat(150)} $i",
                    startUtc = start, endUtc = start + 60_000L, zoneId = "Asia/Shanghai",
                ),
            )
        }
        val out = call("free_slots", """{"date":"$d","duration_minutes":1,"day_start":"00:00","day_end":"24:00"}""")
        val page = obj(out)
        assertTrue("free_slots costs ${wireChars(out)}", wireChars(out) <= WireSize.CHANNEL_LIMIT)
        assertEquals(600, page["busy_total"]!!.jsonPrimitive.int)
        assertTrue(page["busy_truncated"]!!.jsonPrimitive.boolean)
        assertTrue(page["busy"]!!.jsonArray.size < 600)
        // 每两分钟里有一分钟空：时段很多，也能分页取完
        val total = page["total"]!!.jsonPrimitive.int
        assertTrue(total >= 600)
        var got = 0
        var offset = 0
        var pages = 0
        do {
            val p = obj(call("free_slots", """{"date":"$d","duration_minutes":1,"day_start":"00:00","day_end":"24:00","offset":$offset}"""))
            got += p["count"]!!.jsonPrimitive.int
            offset = p["next_offset"]?.jsonPrimitive?.int ?: -1
            pages++
        } while (offset >= 0 && pages < 50)
        assertEquals(total, got)
    }

    @Test fun wireSizeCostMatchesTheRealEnvelopeWithinItsMargin() {
        fill(20, descriptionChars = 800)
        val out = call("event_list", """{"from":"2026-10-01","to":"2027-06-30","limit":200}""")
        val page = obj(out)
        val real = wireChars(out)
        val estimated = WireSize.cost(page)
        assertTrue("estimate $estimated must not undercount the real $real", estimated >= real)
        assertTrue("estimate $estimated should be close to the real $real", estimated - real < 400)
    }
}
