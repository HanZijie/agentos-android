package org.agentos.sample.sms.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.sample.sms.FakeGateway
import org.agentos.sample.sms.Rig
import org.agentos.sample.sms.data.OutboxState
import org.agentos.sample.sms.data.SmsAccess
import org.agentos.sample.sms.data.SmsBox
import org.agentos.sample.sms.data.SmsSendException
import org.agentos.sample.sms.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具层：正常、缺参数、非法号码、短号拒绝、频率限制、去重、状态查询、仅撰写模式、分页、结果体积。 */
class SmsToolsTest {
    private val rig = Rig()
    private val gateway get() = rig.gateway
    private val day = 24 * 3600 * 1000L

    private fun call(name: String, json: String = "{}") = runBlocking { rig.call(name, json) }

    private fun ok(name: String, json: String = "{}"): JsonObject {
        val out = call(name, json)
        assertFalse("$name failed: ${out.text}", out.isError)
        assertEquals("structuredContent must equal the text JSON", kotlinx.serialization.json.Json.parseToJsonElement(out.text), out.structured)
        return out.obj()
    }

    private fun fail(name: String, json: String = "{}"): String {
        val out = call(name, json)
        assertTrue("$name should fail but returned: ${out.text}", out.isError)
        return out.text
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.arr(key: String): JsonArray = this[key]!!.jsonArray
    private fun JsonObject.bool(key: String) = this[key]!!.jsonPrimitive.boolean

    // ---- 目录 ----

    @Test fun `the catalog has exactly the six contract tools with their required parameters and annotations`() {
        val byName = rig.tools.tools.associateBy { it.name }
        assertEquals(setOf("sms_thread_list", "sms_message_list", "sms_search", "sms_send", "sms_send_status", "sms_compose"), byName.keys)
        val required = mapOf(
            "sms_thread_list" to emptyList(), "sms_message_list" to listOf("address"), "sms_search" to listOf("query"),
            "sms_send" to listOf("to", "text"), "sms_send_status" to listOf("id"), "sms_compose" to listOf("to"),
        )
        for ((name, req) in required) {
            val schema = byName.getValue(name).inputSchema
            assertEquals(name, "object", schema["type"]!!.jsonPrimitive.content)
            assertEquals(name, req, schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(name, false, schema["additionalProperties"]!!.jsonPrimitive.boolean)
            assertTrue(name, byName.getValue(name).description.length in 80..1024)
        }
        for (name in listOf("sms_thread_list", "sms_message_list", "sms_search", "sms_send_status")) {
            assertEquals(name, true, byName.getValue(name).annotations.readOnlyHint)
            assertNull(name, byName.getValue(name).annotations.destructiveHint)
        }
        assertEquals(true, byName.getValue("sms_send").annotations.destructiveHint)
        assertNull(byName.getValue("sms_send").annotations.readOnlyHint)
        assertNull(byName.getValue("sms_compose").annotations.readOnlyHint)
        assertNull(byName.getValue("sms_compose").annotations.destructiveHint)
    }

    @Test fun `tool descriptions are in english and warn about untrusted text and masking`() {
        val list = rig.tools.find("sms_message_list")!!.description
        assertTrue(list.contains("untrusted"))
        assertTrue(list.contains("masked"))
        assertTrue(rig.tools.find("sms_send")!!.description.contains("HIGH RISK"))
        for (tool in rig.tools.tools) assertTrue(tool.name, tool.description.all { it.code < 0x2600 || it == '•' })
    }

    // ---- sms_thread_list ----

    private fun seedThreads() {
        gateway.add("+8613800138000", "see you at 3", rig.now - 3 * 3600_000L, SmsBox.INBOX, read = false)
        gateway.add("+8613800138000", "ok", rig.now - 4 * 3600_000L, SmsBox.SENT, read = true)
        gateway.add("ICBC", "您尾号1234的账户支出50.00元", rig.now - day, SmsBox.INBOX, read = true)
        gateway.add("10086", "话费余额 20 元", rig.now - 3 * day, SmsBox.INBOX, read = false)
    }

    @Test fun `thread list returns summaries newest first`() {
        seedThreads()
        val r = ok("sms_thread_list")
        assertEquals(3, r["count"]!!.jsonPrimitive.int)
        assertFalse(r.bool("has_more"))
        assertTrue(r["next_offset"] is JsonNull)
        val threads = r.arr("threads").map { it.jsonObject }
        assertEquals(listOf("+8613800138000", "ICBC", "10086"), threads.map { it.str("address") })
        val first = threads[0]
        assertEquals("see you at 3", first.str("snippet"))
        assertEquals(2, first["message_count"]!!.jsonPrimitive.int)
        assertEquals(1, first["unread_count"]!!.jsonPrimitive.int)
        assertEquals("inbox", first.str("last_type"))
        assertTrue(first.str("last_date").endsWith("+08:00"))
        assertEquals("masked", r["masking"]!!.jsonObject.str("verification_codes"))
    }

    @Test fun `thread list pages with limit offset and has_more`() {
        seedThreads()
        val p1 = ok("sms_thread_list", """{"limit":2}""")
        assertEquals(2, p1["count"]!!.jsonPrimitive.int)
        assertTrue(p1.bool("has_more"))
        assertEquals(2, p1["next_offset"]!!.jsonPrimitive.int)
        val p2 = ok("sms_thread_list", """{"limit":2,"offset":2}""")
        assertEquals(1, p2["count"]!!.jsonPrimitive.int)
        assertFalse(p2.bool("has_more"))
        assertEquals("10086", p2.arr("threads")[0].jsonObject.str("address"))
        // offset past the end is an empty page, not an error
        assertEquals(0, ok("sms_thread_list", """{"offset":50}""")["count"]!!.jsonPrimitive.int)
    }

    @Test fun `thread list rejects bad parameter types`() {
        assertTrue(fail("sms_thread_list", """{"limit":"many"}""").contains("limit"))
        assertTrue(fail("sms_thread_list", """{"offset":1.5}""").contains("offset"))
    }

    @Test fun `limit is clamped to the documented range`() {
        repeat(60) { gateway.add("100000$it", "m$it", rig.now - it * 1000L, threadId = (it + 1).toLong()) }
        assertEquals(50, ok("sms_thread_list", """{"limit":500}""")["count"]!!.jsonPrimitive.int)
        assertEquals(1, ok("sms_thread_list", """{"limit":0}""")["count"]!!.jsonPrimitive.int)
        assertEquals(20, ok("sms_thread_list")["count"]!!.jsonPrimitive.int)
    }

    @Test fun `thread snippets are masked while masking is on and shown when the user allows codes`() {
        gateway.add("95588", "【工商银行】验证码 482910，请勿泄露", rig.now - 1000)
        val masked = ok("sms_thread_list").arr("threads")[0].jsonObject
        assertEquals("【工商银行】验证码 ••••••，请勿泄露", masked.str("snippet"))
        assertTrue(masked.bool("code_masked"))
        rig.settings.update { it.copy(maskCodes = false) }
        val open = ok("sms_thread_list")
        assertEquals("【工商银行】验证码 482910，请勿泄露", open.arr("threads")[0].jsonObject.str("snippet"))
        assertEquals("visible", open["masking"]!!.jsonObject.str("verification_codes"))
        assertNull(open.arr("threads")[0].jsonObject["code_masked"])
    }

    @Test fun `a scan that hit its cap is reported`() {
        seedThreads()
        gateway.scanTruncated = true
        assertTrue(ok("sms_thread_list").bool("scan_truncated"))
    }

    // ---- sms_message_list ----

    @Test fun `message list returns the messages of one number whatever its formatting`() {
        seedThreads()
        val r = ok("sms_message_list", """{"address":"13800138000"}""") // stored as +86...
        assertEquals("13800138000", r.str("address"))
        val msgs = r.arr("messages").map { it.jsonObject }
        assertEquals(listOf("see you at 3", "ok"), msgs.map { it.str("body") })
        assertEquals(listOf("inbox", "sent"), msgs.map { it.str("type") })
        assertEquals(listOf(false, true), msgs.map { it.bool("read") })
        assertTrue(msgs[0].str("id").isNotEmpty())
        assertFalse(r.bool("has_more"))
    }

    @Test fun `message list works for sender names and short numbers`() {
        seedThreads()
        assertEquals(1, ok("sms_message_list", """{"address":"icbc"}""")["count"]!!.jsonPrimitive.int)
        assertEquals(1, ok("sms_message_list", """{"address":"10086"}""")["count"]!!.jsonPrimitive.int)
        assertEquals(0, ok("sms_message_list", """{"address":"13900139000"}""")["count"]!!.jsonPrimitive.int)
    }

    @Test fun `message list needs an address and validates the time window`() {
        assertTrue(fail("sms_message_list").contains("address"))
        assertTrue(fail("sms_message_list", """{"address":"  "}""").contains("address"))
        assertTrue(fail("sms_message_list", """{"address":"1380","since":"yesterday"}""").contains("since"))
        assertTrue(fail("sms_message_list", """{"address":"1380","until":"2026-13-45"}""").contains("until"))
        assertTrue(fail("sms_message_list", """{"address":"1380","since":"2026-10-08","until":"2026-10-08"}""").contains("earlier"))
        assertTrue(fail("sms_message_list", """{"address":5}""").contains("string"))
    }

    @Test fun `since is inclusive and until exclusive and dates may be plain or full ISO`() {
        fun at(iso: String) = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        gateway.add("+8613800138000", "a", at("2026-10-07T23:59:59+08:00"))
        gateway.add("+8613800138000", "b", at("2026-10-08T00:00:00+08:00"))
        gateway.add("+8613800138000", "c", at("2026-10-08T12:00:00+08:00"))
        gateway.add("+8613800138000", "d", at("2026-10-09T00:00:00+08:00"))
        fun bodies(json: String) = ok("sms_message_list", json).arr("messages").map { it.jsonObject.str("body") }
        assertEquals(listOf("c", "b"), bodies("""{"address":"13800138000","since":"2026-10-08","until":"2026-10-09"}"""))
        assertEquals(listOf("d", "c", "b"), bodies("""{"address":"13800138000","since":"2026-10-08T00:00:00+08:00"}"""))
        assertEquals(listOf("a"), bodies("""{"address":"13800138000","until":"2026-10-08T00:00:00+08:00"}"""))
        assertEquals(listOf("c"), bodies("""{"address":"13800138000","since":"2026-10-08T00:00:01","until":"2026-10-08T23:00:00"}"""))
    }

    @Test fun `message list pages and reports next_offset`() {
        repeat(7) { gateway.add("+8613800138000", "m$it", rig.now - it * 60_000L) }
        val p1 = ok("sms_message_list", """{"address":"13800138000","limit":3}""")
        assertEquals(listOf("m0", "m1", "m2"), p1.arr("messages").map { it.jsonObject.str("body") })
        assertTrue(p1.bool("has_more"))
        assertEquals(3, p1["next_offset"]!!.jsonPrimitive.int)
        val p3 = ok("sms_message_list", """{"address":"13800138000","limit":3,"offset":6}""")
        assertEquals(listOf("m6"), p3.arr("messages").map { it.jsonObject.str("body") })
        assertFalse(p3.bool("has_more"))
        assertTrue(p3["next_offset"] is JsonNull)
        // exactly a full last page is not "more"
        val exact = ok("sms_message_list", """{"address":"13800138000","limit":7}""")
        assertFalse(exact.bool("has_more"))
    }

    @Test fun `message bodies hide verification codes by default - chinese and english templates`() {
        gateway.add("95588", "【工商银行】您的验证码是 482910，5分钟内有效", rig.now - 1000)
        gateway.add("95588", "Your verification code is 771203. Do not share it.", rig.now - 2000)
        gateway.add("95588", "您尾号1234的账户支出5000.00元", rig.now - 3000)
        val r = ok("sms_message_list", """{"address":"95588"}""")
        val msgs = r.arr("messages").map { it.jsonObject }
        assertEquals("【工商银行】您的验证码是 ••••••，5分钟内有效", msgs[0].str("body"))
        assertEquals("Your verification code is ••••••. Do not share it.", msgs[1].str("body"))
        assertEquals("您尾号1234的账户支出5000.00元", msgs[2].str("body"))
        assertTrue(msgs[0].bool("code_masked"))
        assertTrue(msgs[1].bool("code_masked"))
        assertNull(msgs[2]["code_masked"])
        assertEquals(2, r["masking"]!!.jsonObject["masked_count"]!!.jsonPrimitive.int)
        assertFalse("the raw code must not appear anywhere in the result", ok("sms_message_list", """{"address":"95588"}""").toString().let { it.contains("482910") || it.contains("771203") })
    }

    @Test fun `the setting that allows codes shows them as they are`() {
        gateway.add("95588", "验证码 482910", rig.now - 1000)
        rig.settings.update { it.copy(maskCodes = false) }
        val msg = ok("sms_message_list", """{"address":"95588"}""").arr("messages")[0].jsonObject
        assertEquals("验证码 482910", msg.str("body"))
        assertNull(msg["code_masked"])
    }

    @Test fun `long bodies are cut and flagged`() {
        gateway.add("ICBC", "x".repeat(1500), rig.now)
        val msg = ok("sms_message_list", """{"address":"ICBC"}""").arr("messages")[0].jsonObject
        assertEquals(SmsTools.BODY_CHARS, msg.str("body").length)
        assertTrue(msg.bool("body_truncated"))
    }

    @Test fun `results stay under the size budget and say there is more`() {
        repeat(50) { gateway.add("ICBC", "y".repeat(990) + it, rig.now - it * 1000L) }
        val out = call("sms_message_list", """{"address":"ICBC","limit":50}""")
        assertFalse(out.isError)
        assertTrue("text ${out.text.length}", out.text.length <= SmsTools.RESULT_BUDGET_CHARS + 2_000)
        assertTrue(out.text.length < 32_768) // what AgentOS keeps of a tool result
        val r = out.obj()
        val count = r["count"]!!.jsonPrimitive.int
        assertTrue("count $count", count in 1 until 50)
        assertTrue(r.bool("has_more"))
        assertEquals(count, r["next_offset"]!!.jsonPrimitive.int)
        // continuing from next_offset returns the next messages
        val next = ok("sms_message_list", """{"address":"ICBC","limit":50,"offset":$count}""")
        assertTrue(next.arr("messages")[0].jsonObject.str("body").endsWith(count.toString()))
    }

    // ---- sms_search ----

    @Test fun `search finds text case-insensitively newest first and pages with has_more`() {
        gateway.add("A", "Meeting at 3", rig.now - 3000)
        gateway.add("B", "meeting moved", rig.now - 2000)
        gateway.add("C", "lunch?", rig.now - 1000)
        val r = ok("sms_search", """{"query":"MEETING"}""")
        assertEquals(listOf("meeting moved", "Meeting at 3"), r.arr("messages").map { it.jsonObject.str("body") })
        assertFalse(r.bool("has_more"))
        val one = ok("sms_search", """{"query":"meeting","limit":1}""")
        assertEquals(1, one["count"]!!.jsonPrimitive.int)
        assertTrue(one.bool("has_more"))
        assertEquals(0, ok("sms_search", """{"query":"zzz"}""")["count"]!!.jsonPrimitive.int)
    }

    @Test fun `search needs a query`() {
        assertTrue(fail("sms_search").contains("query"))
        assertTrue(fail("sms_search", """{"query":"   "}""").contains("query"))
    }

    @Test fun `search results are masked like everything else`() {
        gateway.add("95588", "验证码 482910 用于登录", rig.now - 1000)
        val hit = ok("sms_search", """{"query":"登录"}""").arr("messages")[0].jsonObject
        assertEquals("验证码 •••••• 用于登录", hit.str("body"))
        assertTrue(hit.bool("code_masked"))
    }

    @Test fun `while masking is on the digits of a code cannot be probed by searching`() {
        gateway.add("95588", "验证码 482910 用于登录", rig.now - 1000)
        assertEquals(0, ok("sms_search", """{"query":"482910"}""")["count"]!!.jsonPrimitive.int)
        assertEquals(0, ok("sms_search", """{"query":"4829"}""")["count"]!!.jsonPrimitive.int)
        assertEquals(1, ok("sms_search", """{"query":"验证码"}""")["count"]!!.jsonPrimitive.int)
        rig.settings.update { it.copy(maskCodes = false) }
        assertEquals(1, ok("sms_search", """{"query":"482910"}""")["count"]!!.jsonPrimitive.int)
    }

    // ---- sms_send ----

    @Test fun `send submits one message and returns an id, the parts and the queued state`() {
        val r = ok("sms_send", """{"to":"+8613800138000","text":"meeting moved to 3pm"}""")
        assertEquals("1", r.str("id"))
        assertEquals("+8613800138000", r.str("to"))
        assertEquals(1, r["parts"]!!.jsonPrimitive.int)
        assertEquals("queued", r.str("state"))
        assertTrue(r.bool("submitted"))
        assertFalse(r.bool("deduplicated"))
        assertTrue(r.str("note").contains("not guaranteed"))
        val sub = gateway.submitted.single()
        assertEquals("1", sub.outboxId)
        assertEquals("+8613800138000", sub.to)
        assertEquals(listOf("meeting moved to 3pm"), sub.parts)
        val stored = rig.outbox.get("1")!!
        assertEquals(OutboxState.QUEUED, stored.state)
        assertEquals("meeting moved to 3pm", stored.text)
    }

    @Test fun `a long text is divided and the part count is reported`() {
        val text = "字".repeat(150)
        val r = ok("sms_send", """{"to":"13800138000","text":"$text"}""")
        assertEquals(3, r["parts"]!!.jsonPrimitive.int) // fake divide: 70 per part
        assertEquals(3, gateway.submitted.single().parts.size)
        assertEquals(3, rig.outbox.get("1")!!.parts)
    }

    @Test fun `numbers are normalised before sending`() {
        ok("sms_send", """{"to":"138 0013 8000","text":"hi"}""")
        assertEquals("13800138000", gateway.submitted.single().to)
    }

    @Test fun `send validates its parameters and never reaches the phone when they are wrong`() {
        assertTrue(fail("sms_send", """{"text":"hi"}""").contains("Missing recipient"))
        assertTrue(fail("sms_send", """{"to":"13800138000"}""").contains("'text'"))
        assertTrue(fail("sms_send", """{"to":"13800138000","text":"   "}""").contains("empty"))
        assertTrue(fail("sms_send", """{"to":"Wang","text":"hi"}""").contains("Invalid recipient"))
        assertTrue(fail("sms_send", """{"to":"12","text":"hi"}""").contains("digits"))
        assertTrue(fail("sms_send", """{"to":13800138000,"text":"hi"}""").contains("string"))
        assertTrue(fail("sms_send", """{"to":"13800138000","text":5}""").contains("string"))
        assertTrue(gateway.submitted.isEmpty())
        assertEquals(0, rig.outbox.count())
    }

    @Test fun `one recipient per call`() {
        assertTrue(fail("sms_send", """{"to":"13800138000,13900139000","text":"hi"}""").contains("one recipient", ignoreCase = true))
        assertTrue(fail("sms_send", """{"to":"13800138000;13900139000","text":"hi"}""").contains("one recipient", ignoreCase = true))
        assertTrue(gateway.submitted.isEmpty())
    }

    @Test fun `text longer than 500 characters is refused`() {
        assertTrue(fail("sms_send", """{"to":"13800138000","text":"${"x".repeat(501)}"}""").contains("too long"))
        ok("sms_send", """{"to":"13800138000","text":"${"x".repeat(500)}"}""")
        assertEquals(1, gateway.submitted.size)
    }

    @Test fun `invisible characters in the text are refused`() {
        assertTrue(fail("sms_send", "{\"to\":\"13800138000\",\"text\":\"pay\\u202Egnp\"}").contains("invisible"))
        assertTrue(gateway.submitted.isEmpty())
    }

    @Test fun `a call the confirmation screen could not show in full is refused`() {
        val heavy = "\\\"".repeat(400) // 400 quotes: 800 characters once escaped, still under the 500-character text limit
        val reason = fail("sms_send", """{"to":"13800138000","text":"$heavy"}""")
        assertTrue(reason, reason.contains("confirmation screen"))
        assertTrue(gateway.submitted.isEmpty())
    }

    @Test fun `short and service numbers are refused by default and allowed by the setting`() {
        for (to in listOf("10086", "106575258", "+8610086", "95588", "5604")) {
            val reason = fail("sms_send", """{"to":"$to","text":"hi"}""")
            assertTrue(reason, reason.contains("short or service number"))
        }
        assertTrue(gateway.submitted.isEmpty())
        assertEquals(0, rig.outbox.count())
        rig.settings.update { it.copy(allowShortNumbers = true) }
        ok("sms_send", """{"to":"5604","text":"hi"}""")
        assertEquals("5604", gateway.submitted.single().to)
    }

    @Test fun `the rate limit blocks the next message and recovers with time`() {
        rig.settings.update { it.copy(rateLimit = 2) }
        ok("sms_send", """{"to":"13800138000","text":"one"}""")
        rig.now += 60_000
        ok("sms_send", """{"to":"13800138001","text":"two"}""")
        rig.now += 60_000
        val reason = fail("sms_send", """{"to":"13800138002","text":"three"}""")
        assertTrue(reason, reason.startsWith("Rate limit reached"))
        assertTrue(reason, reason.contains("limit 2"))
        assertTrue(reason, reason.contains("about 480 seconds")) // the first one leaves the window in 8 more minutes
        assertEquals(2, gateway.submitted.size)
        rig.now += 8 * 60_000
        ok("sms_send", """{"to":"13800138002","text":"three"}""")
        assertEquals(3, gateway.submitted.size)
    }

    @Test fun `the default limit is five per ten minutes and the setting changes it`() {
        repeat(5) { ok("sms_send", """{"to":"1380013800$it","text":"m$it"}""") }
        assertTrue(fail("sms_send", """{"to":"13800138009","text":"m9"}""").startsWith("Rate limit reached"))
        rig.settings.update { it.copy(rateLimit = 6) }
        ok("sms_send", """{"to":"13800138009","text":"m9"}""")
        assertEquals(6, gateway.submitted.size)
    }

    @Test fun `the same recipient and text within the window is not sent twice`() {
        val first = ok("sms_send", """{"to":"+8613800138000","text":"ok"}""")
        rig.now += 30_000
        val again = ok("sms_send", """{"to":"+8613800138000","text":"ok"}""")
        assertEquals(first.str("id"), again.str("id"))
        assertTrue(again.bool("deduplicated"))
        assertEquals(1, gateway.submitted.size)
        assertEquals(1, rig.outbox.count())
        assertTrue(again.str("note").contains("nothing new was sent"))
    }

    @Test fun `dedupe sees through number formatting and trailing blanks but not different text or numbers`() {
        val first = ok("sms_send", """{"to":"+8613800138000","text":"ok"}""")
        assertEquals(first.str("id"), ok("sms_send", """{"to":"138 0013 8000","text":"ok "}""").str("id"))
        assertFalse(ok("sms_send", """{"to":"+8613800138000","text":"ok!"}""").bool("deduplicated"))
        assertFalse(ok("sms_send", """{"to":"+8613800138001","text":"ok"}""").bool("deduplicated"))
        assertEquals(3, gateway.submitted.size)
    }

    @Test fun `dedupe expires with the window and does not use up the rate limit`() {
        rig.settings.update { it.copy(rateLimit = 1) }
        val first = ok("sms_send", """{"to":"13800138000","text":"ok"}""")
        rig.now += 60_000
        // a duplicate is answered before the rate limit is even consulted
        assertTrue(ok("sms_send", """{"to":"13800138000","text":"ok"}""").bool("deduplicated"))
        rig.now += 3 * 60_000 // past the 2-minute dedupe window, still inside the 10-minute rate window
        assertTrue(fail("sms_send", """{"to":"13800138000","text":"ok"}""").startsWith("Rate limit reached"))
        rig.now += 7 * 60_000
        val later = ok("sms_send", """{"to":"13800138000","text":"ok"}""")
        assertFalse(later.bool("deduplicated"))
        assertFalse(first.str("id") == later.str("id"))
    }

    @Test fun `a failed message is not a duplicate - the user may retry it`() {
        val first = ok("sms_send", """{"to":"13800138000","text":"ok"}""")
        rig.outbox.onFailed(first.str("id"), "no_service")
        val retry = ok("sms_send", """{"to":"13800138000","text":"ok"}""")
        assertFalse(retry.bool("deduplicated"))
        assertEquals(2, gateway.submitted.size)
    }

    @Test fun `when the phone refuses the submission the entry is marked failed and the error says so`() {
        gateway.submitFailure = SmsSendException("permission_denied")
        val reason = fail("sms_send", """{"to":"13800138000","text":"hi"}""")
        assertTrue(reason, reason.contains("permission_denied"))
        assertTrue(reason, reason.contains("outbox id 1"))
        val stored = rig.outbox.get("1")!!
        assertEquals(OutboxState.FAILED, stored.state)
        assertEquals("permission_denied", stored.error)
    }

    // ---- sms_send_status ----

    @Test fun `status follows the callbacks from queued to sent to delivered`() {
        val id = ok("sms_send", """{"to":"13800138000","text":"hello"}""").str("id")
        fun status() = ok("sms_send_status", """{"id":"$id"}""")
        assertEquals("queued", status().str("state"))
        rig.now += 2_000
        rig.outbox.onSent(id, 0)
        val sent = status()
        assertEquals("sent", sent.str("state"))
        assertEquals(1, sent["sent_parts"]!!.jsonPrimitive.int)
        assertEquals(0, sent["delivered_parts"]!!.jsonPrimitive.int)
        rig.outbox.onDelivered(id, 0)
        assertEquals("delivered", status().str("state"))
        assertNull(status()["error"])
    }

    @Test fun `status of a failed message carries the reason`() {
        val id = ok("sms_send", """{"to":"13800138000","text":"hello"}""").str("id")
        rig.outbox.onFailed(id, "no_service")
        val s = ok("sms_send_status", """{"id":"$id"}""")
        assertEquals("failed", s.str("state"))
        assertEquals("no_service", s.str("error"))
        assertEquals(1, s["parts"]!!.jsonPrimitive.int)
    }

    @Test fun `status needs an id that this app issued`() {
        assertTrue(fail("sms_send_status").contains("'id'"))
        assertTrue(fail("sms_send_status", """{"id":"  "}""").contains("'id'"))
        assertTrue(fail("sms_send_status", """{"id":"999"}""").contains("Unknown id"))
    }

    // ---- sms_compose ----

    @Test fun `compose hands the draft to the system and sends nothing`() {
        val r = ok("sms_compose", """{"to":"+8613800138000","text":"draft text"}""")
        assertTrue(r.bool("opened"))
        assertTrue(r.bool("prefilled_text"))
        assertEquals(listOf("+8613800138000" to "draft text"), gateway.composed)
        assertTrue(gateway.submitted.isEmpty())
        assertEquals(0, rig.outbox.count())
    }

    @Test fun `compose works without text and for short numbers - the user presses send`() {
        ok("sms_compose", """{"to":"10086"}""")
        assertEquals(listOf("10086" to null), gateway.composed)
        ok("sms_compose", """{"to":"10086","text":"  "}""")
        assertEquals("10086" to null, gateway.composed.last())
    }

    @Test fun `compose validates the recipient and the text`() {
        assertTrue(fail("sms_compose").contains("Missing recipient"))
        assertTrue(fail("sms_compose", """{"to":"Wang"}""").contains("Invalid recipient"))
        assertTrue(fail("sms_compose", """{"to":"1380,1390"}""").contains("one recipient", ignoreCase = true))
        assertTrue(fail("sms_compose", """{"to":"13800138000","text":"${"x".repeat(501)}"}""").contains("too long"))
        assertTrue(gateway.composed.isEmpty())
    }

    @Test fun `compose reports when no messaging screen could be opened`() {
        gateway.composerAvailable = false
        assertTrue(fail("sms_compose", """{"to":"13800138000"}""").contains("No messaging screen"))
    }

    // ---- 权限门 / 仅撰写模式 ----

    @Test fun `compose-only mode - only sms_compose works and every other tool says why`() {
        gateway.access = SmsAccess(canRead = false, canSend = false)
        for ((name, json) in listOf(
            "sms_thread_list" to "{}",
            "sms_message_list" to """{"address":"13800138000"}""",
            "sms_search" to """{"query":"x"}""",
            "sms_send" to """{"to":"13800138000","text":"hi"}""",
            "sms_send_status" to """{"id":"1"}""",
        )) {
            val reason = fail(name, json)
            assertTrue("$name: $reason", reason.startsWith("Compose-only mode"))
            assertTrue("$name: $reason", reason.contains("sms_compose"))
            assertTrue("$name: $reason", reason.contains("Allow restricted settings"))
        }
        assertTrue(gateway.submitted.isEmpty())
        ok("sms_compose", """{"to":"13800138000","text":"hi"}""")
        assertEquals(1, gateway.composed.size)
    }

    @Test fun `the gate runs before parameter checks so the model learns about the mode first`() {
        gateway.access = SmsAccess(canRead = false, canSend = false)
        assertTrue(fail("sms_send").startsWith("Compose-only mode"))
        assertTrue(fail("sms_message_list").startsWith("Compose-only mode"))
    }

    @Test fun `the tool catalog does not shrink in compose-only mode`() {
        val before = rig.tools.tools.map { it.name }
        gateway.access = SmsAccess(canRead = false, canSend = false)
        assertEquals(before, rig.tools.tools.map { it.name })
        assertEquals(6, rig.tools.tools.size)
    }

    @Test fun `read without send - reads work and sending names the missing permission`() {
        gateway.access = SmsAccess(canRead = true, canSend = false)
        seedThreads()
        assertEquals(3, ok("sms_thread_list")["count"]!!.jsonPrimitive.int)
        val reason = fail("sms_send", """{"to":"13800138000","text":"hi"}""")
        assertTrue(reason, reason.contains("SEND_SMS"))
        assertFalse(reason, reason.startsWith("Compose-only"))
        assertTrue(fail("sms_send_status", """{"id":"1"}""").contains("SEND_SMS"))
        assertTrue(gateway.submitted.isEmpty())
    }

    @Test fun `send without read - sending works and reading names the missing permission`() {
        gateway.access = SmsAccess(canRead = false, canSend = true)
        ok("sms_send", """{"to":"13800138000","text":"hi"}""")
        for ((name, json) in listOf("sms_thread_list" to "{}", "sms_message_list" to """{"address":"1380"}""", "sms_search" to """{"query":"x"}""")) {
            val reason = fail(name, json)
            assertTrue("$name: $reason", reason.contains("READ_SMS"))
        }
    }

    @Test fun `permission revoked between the check and the call is reported as an error`() {
        val revoking = object : org.agentos.sample.sms.data.SmsGateway by gateway {
            override fun threads(): org.agentos.sample.sms.data.ThreadScan = throw SecurityException("READ_SMS revoked")
        }
        val tools = SmsTools(revoking, rig.outbox, rig.settings, clock = { rig.now })
        val out = runBlocking { tools.find("sms_thread_list")!!.handler(JsonObject(emptyMap())) }
        assertTrue(out.isError)
        assertTrue(out.text.contains("Permission denied"))
    }

    @Test fun `errors never escape as exceptions`() {
        val broken = object : org.agentos.sample.sms.data.SmsGateway by gateway {
            override fun search(query: String, max: Int): List<org.agentos.sample.sms.data.SmsRecord> = error("boom")
        }
        val tools = SmsTools(broken, rig.outbox, rig.settings)
        val out = runBlocking { tools.find("sms_search")!!.handler(kotlinx.serialization.json.buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive("x")) }) }
        assertTrue(out.isError)
        assertTrue(out.text.startsWith("Unexpected error"))
        assertNotNull(out.text)
    }
}
