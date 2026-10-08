package org.agentos.sample.sms.rules

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientTest {
    private fun ok(raw: String?): String {
        val p = Recipient.parse(raw)
        assertTrue("expected $raw to parse, got $p", p is Recipient.Parsed.Ok)
        return (p as Recipient.Parsed.Ok).number
    }

    private fun bad(raw: String?): String {
        val p = Recipient.parse(raw)
        assertTrue("expected $raw to be rejected, got $p", p is Recipient.Parsed.Bad)
        return (p as Recipient.Parsed.Bad).reason
    }

    @Test fun `numbers are normalised to an optional plus and digits`() {
        assertEquals("+8613800138000", ok("+8613800138000"))
        assertEquals("13800138000", ok("13800138000"))
        assertEquals("13800138000", ok("138 0013 8000"))
        assertEquals("4155550123", ok("(415) 555-0123"))
        assertEquals("+14155550123", ok("+1 (415) 555-0123"))
        assertEquals("+14155550123", ok("(+1) 415.555.0123"))
        assertEquals("+8613800138000", ok("0086 138 0013 8000"))
        assertEquals("13800138000", ok("１３８００１３８０００"))
        assertEquals("5604", ok(" 5604 "))
    }

    @Test fun `missing or empty recipient`() {
        assertTrue(bad(null).contains("Missing recipient"))
        assertTrue(bad("   ").contains("Missing recipient"))
    }

    @Test fun `names letters and symbols are not phone numbers`() {
        bad("Wang")
        bad("13800138000a")
        bad("138*0013")
        bad("tel:13800138000")
        bad("+")
    }

    @Test fun `several recipients in one call are refused`() {
        for (raw in listOf("13800138000,13900139000", "13800138000;13900139000", "13800138000，13900139000", "13800138000、13900139000", "13800138000\n13900139000", "13800138000/13900139000")) {
            assertTrue(raw, bad(raw).contains("one recipient", ignoreCase = true))
        }
        // two numbers glued with a space look like one very long number
        bad("13800138000 13900139000")
    }

    @Test fun `digit count limits`() {
        bad("12")
        ok("112")
        ok("1".repeat(15))
        bad("1".repeat(16))
    }

    @Test fun `short and service numbers are recognised by the default rules`() {
        val rules = ShortNumberRules()
        for (n in listOf("10086", "10010", "10000", "95588", "112", "5604", "1065", "106575258", "1069012345678", "+8610086", "+8695588", "+86106575258")) {
            assertTrue("$n should be short", rules.isShort(n))
        }
        for (n in listOf("13800138000", "+8613800138000", "+14155550123", "4155550123", "9519999999", "+85212345678", "02012345678", "+447911123456")) {
            assertFalse("$n should not be short", rules.isShort(n))
        }
    }

    @Test fun `the rules are configurable`() {
        val strict = ShortNumberRules(minFullDigits = 11)
        assertTrue(strict.isShort("4155550123")) // 10 digits < 11
        val noPrefixes = ShortNumberRules(minFullDigits = 3, serviceNationalPrefixes = emptyList())
        assertFalse(noPrefixes.isShort("10086"))
        assertFalse(noPrefixes.isShort("+8610086"))
        val custom = ShortNumberRules(serviceNationalPrefixes = listOf("12"))
        assertTrue(custom.isShort("+8612345678"))
        assertFalse(custom.isShort("+8610086"))
    }
}

class SendRulesTest {
    @Test fun `text checks`() {
        assertNull(SendRules.checkText("hello"))
        assertNull(SendRules.checkText("你好\n第二行\t制表"))
        assertNotNull(SendRules.checkText(null))
        assertNotNull(SendRules.checkText(""))
        assertNotNull(SendRules.checkText("   \n "))
    }

    @Test fun `length is counted in characters not UTF-16 units`() {
        assertNull(SendRules.checkText("x".repeat(500)))
        assertTrue(SendRules.checkText("x".repeat(501))!!.contains("501"))
        assertNull(SendRules.checkText("😀".repeat(500))) // 500 code points, 1000 UTF-16 units
        assertNotNull(SendRules.checkText("😀".repeat(501)))
        assertNull(SendRules.checkText("字".repeat(500)))
    }

    @Test fun `invisible and control characters are refused but emoji sequences are fine`() {
        assertNotNull(SendRules.checkText("hi\u0007there"))
        assertNotNull(SendRules.checkText("hi\u200Bthere")) // zero width space
        assertNotNull(SendRules.checkText("pay\u202Egnp.exe")) // right-to-left override
        assertNotNull(SendRules.checkText("a\u2066b")) // isolate
        assertNotNull(SendRules.checkText("soft\u00ADhyphen"))
        assertNotNull(SendRules.checkText("private\uE000use"))
        assertNull(SendRules.checkText("👨\u200D👩\u200D👧 family")) // ZWJ is needed by emoji
        assertNull(SendRules.checkText("heart ❤\uFE0F"))
    }

    @Test fun `arguments must fit what the confirmation screen can show`() {
        val short = buildJsonObject {
            put("to", "+8613800138000")
            put("text", "x".repeat(500))
        }
        assertNull(SendRules.checkFitsConsent(short))
        // 500 quotes become 1000 characters once JSON-escaped
        val heavy = buildJsonObject {
            put("to", "+8613800138000")
            put("text", "\"".repeat(500))
        }
        val reason = SendRules.checkFitsConsent(heavy)
        assertNotNull(reason)
        assertTrue(reason!!.contains("confirmation screen"))
        // the limit is a parameter
        assertNotNull(SendRules.checkFitsConsent(short, visibleChars = 100))
    }
}

class RateLimitTest {
    private val min = 60_000L

    @Test fun `below the limit is allowed`() {
        assertEquals(RateLimit.Decision.Allowed, RateLimit.check(emptyList(), 1_000_000, 5))
        assertEquals(RateLimit.Decision.Allowed, RateLimit.check(List(4) { 990_000L }, 1_000_000, 5))
    }

    @Test fun `at the limit it blocks and says how long to wait`() {
        val now = 100 * min
        val stamps = listOf(now - 9 * min, now - 8 * min, now - 5 * min, now - 3 * min, now - 1 * min)
        val d = RateLimit.check(stamps, now, 5) as RateLimit.Decision.Blocked
        assertEquals(5, d.used)
        assertEquals(5, d.limit)
        assertEquals(60L, d.retryAfterSeconds) // the oldest leaves the 10-minute window in 1 minute
    }

    @Test fun `old submissions slide out of the window`() {
        val now = 100 * min
        val stamps = List(5) { now - 11 * min }
        assertEquals(RateLimit.Decision.Allowed, RateLimit.check(stamps, now, 5))
    }

    @Test fun `a lower limit needs more of the old ones to expire`() {
        val now = 100 * min
        val stamps = listOf(now - 9 * min, now - 2 * min, now - 1 * min)
        // limit 2 with 3 in the window: both older ones must leave, the second one after 8 minutes
        val d = RateLimit.check(stamps, now, 2) as RateLimit.Decision.Blocked
        assertEquals(8L * 60, d.retryAfterSeconds)
        assertEquals(3, d.used)
        // limit 3: exactly full, the oldest leaves in 1 minute
        assertEquals(60L, (RateLimit.check(stamps, now, 3) as RateLimit.Decision.Blocked).retryAfterSeconds)
        assertEquals(RateLimit.Decision.Allowed, RateLimit.check(stamps, now, 4))
    }

    @Test fun `the limit is clamped`() {
        assertEquals(1, RateLimit.clampLimit(0))
        assertEquals(30, RateLimit.clampLimit(99))
        assertEquals(5, RateLimit.clampLimit(5))
    }
}

class AddressAndDedupeTest {
    @Test fun `numbers match across formats`() {
        assertTrue(AddressMatcher.matches("+8613800138000", "13800138000"))
        assertTrue(AddressMatcher.matches("13800138000", "+86 138 0013 8000"))
        assertTrue(AddressMatcher.matches("+14155550123", "(415) 555-0123"))
        assertFalse(AddressMatcher.matches("+8613800138000", "13800138001"))
    }

    @Test fun `short codes and sender names must match as a whole`() {
        assertTrue(AddressMatcher.matches("10086", "10086"))
        assertFalse(AddressMatcher.matches("10086", "010086"))
        assertFalse(AddressMatcher.matches("1008611", "10086"))
        assertTrue(AddressMatcher.matches("ICBC", "icbc"))
        assertFalse(AddressMatcher.matches("ICBC", "ICBC2"))
        assertFalse(AddressMatcher.matches("ICBC", "13800138000"))
        assertFalse(AddressMatcher.matches("", "13800138000"))
    }

    @Test fun `the dedupe key ignores number formatting and surrounding blanks only`() {
        assertEquals(Dedupe.key("+8613800138000", "hi"), Dedupe.key("13800138000", " hi "))
        assertFalse(Dedupe.key("13800138000", "hi") == Dedupe.key("13800138000", "Hi"))
        assertFalse(Dedupe.key("13800138000", "hi") == Dedupe.key("13800138001", "hi"))
    }
}
