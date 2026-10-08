package org.agentos.app.ui.consent

import org.agentos.app.agent.consent.ConsentWire.Card
import org.agentos.app.agent.consent.ConsentWire.Option
import org.agentos.app.i18n.ResStrings
import org.agentos.runtime.consent.ConsentChoice
import org.agentos.runtime.consent.ConsentMessages
import org.agentos.runtime.consent.ConsentSeverity
import org.agentos.runtime.i18n.MessageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentUiLogicTest {
    private fun card(id: String, kind: String = "APP", pkg: String? = "com.example.app") = Card(
        requestId = id, title = MessageRef.of(ConsentMessages.TITLE, "t"), initiatorLine = MessageRef.of(ConsentMessages.INITIATOR_NAMED, "com.example.app"), callerKind = kind, callerPackage = pkg, sourceLine = null,
        toolDisplayName = "x", argumentsPreview = "", argumentsTruncated = false, risk = "WRITE", severity = ConsentSeverity.ELEVATED,
        riskLabel = "", riskDescription = "",
        options = listOf(Option(ConsentChoice.ALLOW_ONCE, "允许一次", false), Option(ConsentChoice.DENY, "拒绝", true)),
        deadlineMillis = 61_000, timeoutMillis = 60_000, queuePosition = 0, queueSize = 1,
    )

    @Test
    fun queueIsFifoDedupedAndSnapshotReplaces() {
        val q = ConsentQueue()
        q.add(card("a")); q.add(card("b")); q.add(card("a"))
        assertEquals(listOf("a", "b"), q.cards().map { it.requestId })
        assertEquals("a", q.head!!.requestId)
        assertTrue("removing the head reports it", q.remove("a"))
        assertFalse(q.remove("zzz"))
        assertEquals("b", q.head!!.requestId)
        q.replaceAll(listOf(card("c"), card("d"), card("c")))
        assertEquals(listOf("c", "d"), q.cards().map { it.requestId })
        q.clear()
        assertNull(q.head)
    }

    @Test
    fun appLabelIsUntrustedAndAlwaysShownWithThePackage() {
        val c = card("a")
        val zh = ResStrings.zh
        val en = ResStrings.en
        assertEquals("由「我的日历」（com.example.app）发起", ConsentLabels.initiator(c, "我的日历", zh))
        assertEquals("Requested by “My Calendar” (com.example.app)", ConsentLabels.initiator(c, "My Calendar", en))
        // a label that imitates the system, with bidi controls and newlines: cleaned, package still shown
        val evil = "\u202E系统设置\u202C\n\u200B"
        assertEquals("系统设置", ConsentLabels.cleanLabel(evil))
        assertEquals("由「系统设置」（com.example.app）发起", ConsentLabels.initiator(c, evil, zh))
        // unresolvable / empty / equal-to-package labels fall back to the coordinator's own line (a key + arguments, in the language of the screen)
        for (s in listOf(zh, en)) {
            val own = s.get(c.initiatorLine)
            assertEquals(own, ConsentLabels.initiator(c, null, s))
            assertEquals(own, ConsentLabels.initiator(c, "\u200B\u202E", s))
            assertEquals(own, ConsentLabels.initiator(c, "com.example.app", s))
        }
        assertEquals("由 com.example.app 发起", zh.get(c.initiatorLine))
        assertEquals("Requested by com.example.app", en.get(c.initiatorLine))
        // quotes of every language used as our own framing cannot be smuggled in
        assertEquals("a b", ConsentLabels.cleanLabel("a「」 b"))
        assertEquals("a b", ConsentLabels.cleanLabel("a“” b"))
        assertEquals("a b", ConsentLabels.cleanLabel("a\"«» b"))
        // desktop / self keep the coordinator's wording
        val d = card("d", kind = "DESKTOP", pkg = null).copy(initiatorLine = MessageRef.of(ConsentMessages.INITIATOR_DESKTOP))
        assertEquals("由电脑端发起", ConsentLabels.initiator(d, "anything", zh))
        assertEquals("Requested by your computer", ConsentLabels.initiator(d, "anything", en))
        assertTrue(ConsentLabels.cleanLabel("x".repeat(500))!!.length <= 40)
    }

    @Test
    fun countdownAndQueueNote() {
        assertEquals("还剩 60 秒；不回答将按拒绝处理", ConsentLabels.countdown(61_000, 1_000))
        assertEquals("还剩 1 秒；不回答将按拒绝处理", ConsentLabels.countdown(61_000, 60_001))
        assertTrue(ConsentLabels.countdown(61_000, 61_000).contains("已超时"))
        assertNull(ConsentLabels.queueNote(1))
        assertEquals("还有 2 条待确认，答复后会依次显示", ConsentLabels.queueNote(3))
    }
}
