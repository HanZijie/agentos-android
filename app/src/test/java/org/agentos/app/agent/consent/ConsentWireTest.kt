package org.agentos.app.agent.consent

import org.agentos.runtime.consent.ConsentCaller
import org.agentos.runtime.consent.ConsentChoice
import org.agentos.runtime.consent.ConsentEnd
import org.agentos.runtime.consent.ConsentMessages
import org.agentos.runtime.consent.ConsentOption
import org.agentos.runtime.consent.ConsentResolution
import org.agentos.runtime.consent.ConsentSeverity
import org.agentos.runtime.consent.ConsentView
import org.agentos.runtime.i18n.MessageRef
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentWireTest {
    private fun view(id: String = "r1", risk: ToolRisk = ToolRisk.WRITE, args: String = "{\"a\":\"<b>x</b> %s %1\$d\"}") = ConsentView(
        requestId = id, sessionId = "s", taskId = "t", title = MessageRef.of(ConsentMessages.TITLE, "note_create"), initiatorLine = MessageRef.of(ConsentMessages.INITIATOR_NAMED, "com.example.app"),
        caller = ConsentCaller(CallerKind.APP, 10123, "com.example.app"), sourceLine = MessageRef.of(ConsentMessages.SOURCE, "notes", "notes"),
        source = ToolSource("notes", "notes", "note_create"), toolDisplayName = "note_create", toolName = "mcp__notes__note_create",
        argumentsPreview = args, argumentsTruncated = false, risk = risk,
        severity = if (risk == ToolRisk.HIGH) ConsentSeverity.CRITICAL else ConsentSeverity.ELEVATED,
        riskLabel = "写操作", riskDescription = "会修改数据",
        options = buildList {
            add(ConsentOption(ConsentChoice.ALLOW_ONCE, "允许一次"))
            if (risk != ToolRisk.HIGH) add(ConsentOption(ConsentChoice.ALWAYS_ALLOW, "始终允许"))
            add(ConsentOption(ConsentChoice.DENY, "拒绝", destructive = true))
        },
        createdAtMillis = 1_000, deadlineMillis = 61_000, timeoutMillis = 60_000, queuePosition = 0, queueSize = 2,
    )

    @Test
    fun roundTripKeepsEveryDisplayFieldAndTextVerbatim() {
        val v = view()
        val c = ConsentWire.parseCard(ConsentWire.encodeViewString(v))!!
        assertEquals(v.title, c.title)
        assertEquals(v.initiatorLine, c.initiatorLine)
        assertEquals(v.sourceLine, c.sourceLine)
        assertEquals(v.argumentsPreview, c.argumentsPreview)
        assertEquals("com.example.app", c.callerPackage)
        assertEquals("APP", c.callerKind)
        assertEquals(ConsentSeverity.ELEVATED, c.severity)
        assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.ALWAYS_ALLOW, ConsentChoice.DENY), c.options.map { it.choice })
        assertTrue(c.options.last().destructive)
        assertEquals(61_000L, c.deadlineMillis)
        assertEquals(2, c.queueSize)
    }

    @Test
    fun highRiskOffersNoAlwaysAllow() {
        val c = ConsentWire.parseCard(ConsentWire.encodeViewString(view(risk = ToolRisk.HIGH)))!!
        assertFalse(c.options.any { it.choice == ConsentChoice.ALWAYS_ALLOW })
        assertEquals(ConsentSeverity.CRITICAL, c.severity)
    }

    @Test
    fun malformedInputIsDroppedNotThrown() {
        assertNull(ConsentWire.parseCard(null))
        assertNull(ConsentWire.parseCard("not json"))
        assertNull(ConsentWire.parseCard("{}"))
        // an options list without DENY / ALLOW_ONCE is refused
        val noDeny = ConsentWire.encodeViewString(view()).replace("\"DENY\"", "\"NOPE\"")
        assertNull(ConsentWire.parseCard(noDeny))
        assertEquals(emptyList<Any>(), ConsentWire.parseCards("{\"a\":1}"))
        assertEquals(1, ConsentWire.parseCards("[1,\"x\",${ConsentWire.encodeViewString(view())}]").size)
        // unknown severity shows as the most conspicuous
        val odd = ConsentWire.encodeViewString(view()).replace("\"ELEVATED\"", "\"WEIRD\"")
        assertEquals(ConsentSeverity.CRITICAL, ConsentWire.parseCard(odd)!!.severity)
    }

    @Test
    fun resolutionAndChoice() {
        val r = ConsentWire.parseResolution(ConsentWire.encodeResolution(ConsentResolution(ConsentEnd.ANSWERED, ConsentChoice.ALWAYS_ALLOW, "没能保存")))!!
        assertEquals(ConsentEnd.ANSWERED, r.end)
        assertEquals(ConsentChoice.ALWAYS_ALLOW, r.choice)
        assertEquals("没能保存", r.notice)
        val t = ConsentWire.parseResolution(ConsentWire.encodeResolution(ConsentResolution(ConsentEnd.TIMED_OUT)))!!
        assertNull(t.choice)
        assertNull(t.notice)
        assertNull(ConsentWire.parseResolution("{\"end\":\"BOGUS\"}"))
        assertEquals(ConsentChoice.DENY, ConsentWire.parseChoice("DENY"))
        assertNull(ConsentWire.parseChoice("allow"))
    }
}
