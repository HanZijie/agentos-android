package org.agentos.sample.sms.tools

import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.sample.sms.data.Drafts
import org.agentos.sample.sms.data.InMemoryDraftStore
import org.agentos.sample.sms.data.InMemoryOutboxStore
import org.agentos.sample.sms.data.Outbox
import org.agentos.sample.sms.data.SmsAccess
import org.agentos.sample.sms.data.SmsSettingsValues
import org.agentos.sample.sms.ui.MessageTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsDumpTest {
    private var now = 1_760_000_000_000L
    private val outbox = Outbox(InMemoryOutboxStore()) { now }
    private val drafts = Drafts(InMemoryDraftStore()) { now }
    private val zone = ZoneOffset.ofHours(8)

    private fun dump(access: SmsAccess = SmsAccess(true, true), settings: SmsSettingsValues = SmsSettingsValues(), offset: Int = 0, limit: Int? = null): JsonObject =
        SmsDump.build(access, settings, outbox, drafts, now, zone, offset, limit)

    @Test fun `an empty dump still has mode permissions and settings`() {
        val d = dump()
        assertEquals("full", d["mode"]!!.jsonPrimitive.content)
        assertTrue(d["permissions"]!!.jsonObject["read_sms"]!!.jsonPrimitive.boolean)
        assertTrue(d["permissions"]!!.jsonObject["send_sms"]!!.jsonPrimitive.boolean)
        val s = d["settings"]!!.jsonObject
        assertTrue(s["mask_codes"]!!.jsonPrimitive.boolean)
        assertFalse(s["allow_short_numbers"]!!.jsonPrimitive.boolean)
        assertEquals(5, s["rate_limit"]!!.jsonPrimitive.int)
        assertEquals(0, d["total"]!!.jsonPrimitive.int)
        assertTrue(d["outbox"]!!.jsonArray.isEmpty())
        assertTrue(d["next_offset"] is JsonNull)
        assertEquals("+08:00", d["time_zone"]!!.jsonPrimitive.content)
    }

    @Test fun `modes follow the permissions`() {
        assertEquals("compose_only", dump(SmsAccess(false, false))["mode"]!!.jsonPrimitive.content)
        assertEquals("partial", dump(SmsAccess(true, false))["mode"]!!.jsonPrimitive.content)
        assertEquals("partial", dump(SmsAccess(false, true))["mode"]!!.jsonPrimitive.content)
    }

    @Test fun `the outbox entries are listed newest first with their text and state`() {
        val a = outbox.create("+8613800138000", "first", 1)
        now += 1000
        val b = outbox.create("+8613800138001", "second", 2)
        outbox.onSent(a.id, 0)
        outbox.onFailed(b.id, "no_service")
        val items = dump()["outbox"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("second", "first"), items.map { it["text"]!!.jsonPrimitive.content })
        assertEquals(listOf("failed", "sent"), items.map { it["state"]!!.jsonPrimitive.content })
        assertEquals("no_service", items[0]["error"]!!.jsonPrimitive.content)
        assertTrue(items[1]["error"] is JsonNull)
        assertEquals(2, items[0]["parts"]!!.jsonPrimitive.int)
        assertEquals(1, items[1]["sent_parts"]!!.jsonPrimitive.int)
        assertTrue(items[0]["created_at"]!!.jsonPrimitive.content.endsWith("+08:00"))
    }

    @Test fun `the dump pages with limit and next_offset`() {
        repeat(5) {
            now += 1000
            outbox.create("1380013800$it", "m$it", 1)
        }
        val p1 = dump(limit = 2)
        assertEquals(listOf("m4", "m3"), p1["outbox"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content })
        assertEquals(2, p1["next_offset"]!!.jsonPrimitive.int)
        assertEquals(5, p1["total"]!!.jsonPrimitive.int)
        val p3 = dump(offset = 4, limit = 2)
        assertEquals(listOf("m0"), p3["outbox"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content })
        assertTrue(p3["next_offset"] is JsonNull)
        // an offset past the end is clamped, not an error
        assertEquals(0, dump(offset = 99)["count"]!!.jsonPrimitive.int)
    }

    @Test fun `a page stops at the character budget and points at the next entry`() {
        repeat(400) {
            now += 1000
            outbox.create("1380013800$it", "z".repeat(500), 1)
        }
        val d = dump(limit = 500)
        val count = d["count"]!!.jsonPrimitive.int
        assertTrue("count $count", count in 1 until 400)
        assertTrue(d.toString().length < 1_000_000) // the broadcast result limit is about 1 MB
        assertEquals(count, d["next_offset"]!!.jsonPrimitive.int)
        val rest = dump(offset = count, limit = 500)
        assertEquals(400 - count, rest["count"]!!.jsonPrimitive.int.coerceAtMost(400 - count))
    }

    @Test fun `pending compose drafts are listed too`() {
        drafts.add("+8613800138000", "draft for review")
        val items = dump()["drafts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, items.size)
        assertEquals("draft for review", items[0]["text"]!!.jsonPrimitive.content)
        assertEquals("+8613800138000", items[0]["to"]!!.jsonPrimitive.content)
    }

    @Test fun `the dump never contains system messages - only what this app sent`() {
        outbox.create("+8613800138000", "sent by the agent", 1)
        val text = dump().toString()
        assertTrue(text.contains("sent by the agent"))
        assertFalse(text.contains("inbox"))
    }
}

class MessageTimeTest {
    private val zone = ZoneOffset.ofHours(8)
    private val now = java.time.OffsetDateTime.parse("2026-10-08T20:00:00+08:00").toInstant().toEpochMilli()

    private fun at(iso: String) = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test fun `today shows the time of day in the locale's style`() {
        val t = at("2026-10-08T14:30:00+08:00")
        val zh = MessageTime.format(t, now, zone, Locale.SIMPLIFIED_CHINESE)
        val en = MessageTime.format(t, now, zone, Locale.US)
        assertTrue(zh, zh.contains("14:30") || zh.contains("2:30"))
        assertTrue(en, en.contains("2:30") && en.contains("PM"))
        assertNotEquals(zh, en)
    }

    @Test fun `other days show the date in the locale's style`() {
        val t = at("2026-10-05T14:30:00+08:00")
        val zh = MessageTime.format(t, now, zone, Locale.SIMPLIFIED_CHINESE)
        val en = MessageTime.format(t, now, zone, Locale.US)
        assertTrue(zh, zh.contains("10") && zh.contains("5"))
        assertEquals("10/5/26", en)
        assertFalse(zh.contains(":"))
    }

    @Test fun `the day boundary follows the time zone`() {
        val t = at("2026-10-08T23:30:00+08:00")
        val laterZone = ZoneOffset.ofHours(9) // 00:30 on the 9th there; the clock "now" is 21:00 on the 8th
        assertNotEquals(MessageTime.format(t, now, zone, Locale.US), MessageTime.format(t, now, laterZone, Locale.US))
    }
}
