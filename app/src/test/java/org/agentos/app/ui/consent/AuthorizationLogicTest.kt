package org.agentos.app.ui.consent

import org.agentos.app.agent.consent.ConsentWire
import org.agentos.app.agent.consent.ConsentWire.AuthRequest
import org.agentos.app.agent.consent.ConsentWire.Card
import org.agentos.app.agent.consent.ConsentWire.Option
import org.agentos.runtime.consent.ConsentChoice
import org.agentos.runtime.consent.ConsentMessages
import org.agentos.runtime.consent.ConsentSeverity
import org.agentos.runtime.i18n.MessageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationLogicTest {
    private val digest = "7920a1b2c3d4" + "e".repeat(52)

    private fun req(
        id: String = "a1", pkg: String = "org.example.notes", label: String? = "备忘录", d: String = digest, changed: Boolean = false,
        deadline: Long = 90_000, timeout: Long = 60_000,
    ) = AuthRequest(id, pkg, label, d, changed, deadline, timeout)

    private fun card(id: String, deadline: Long, timeout: Long = 60_000) = Card(
        requestId = id, title = MessageRef.of(ConsentMessages.TITLE, "t"), initiatorLine = MessageRef.of(ConsentMessages.INITIATOR_NAMED, "x"), callerKind = "APP", callerPackage = "org.example.notes", sourceLine = null,
        toolDisplayName = "x", argumentsPreview = "", argumentsTruncated = false, risk = "WRITE", severity = ConsentSeverity.ELEVATED,
        riskLabel = "", riskDescription = "",
        options = listOf(Option(ConsentChoice.ALLOW_ONCE, "允许一次", false), Option(ConsentChoice.DENY, "拒绝", true)),
        deadlineMillis = deadline, timeoutMillis = timeout, queuePosition = 0, queueSize = 1,
    )

    // ------------------------------------------------------------ 文字

    @Test
    fun titleNamesTheAppAndTheCardAlwaysShowsPackageAndDigest() {
        val r = req()
        assertEquals("允许「备忘录」使用 AgentOS 吗？", AuthorizationLabels.title(r, null))
        assertEquals("包名：org.example.notes", AuthorizationLabels.packageLine(r))
        assertEquals("签名：7920 a1b2 c3d4…", AuthorizationLabels.digestLine(r.signingDigest))
        assertTrue(AuthorizationLabels.EXPLANATION.contains("替你回答问题"))
        assertTrue(AuthorizationLabels.EXPLANATION.contains("每次都会再问你"))
    }

    @Test
    fun digestShowsExactlyTheFirst12Chars() {
        assertEquals("7920 a1b2 c3d4…", AuthorizationLabels.digestHead(digest))
        assertEquals("（无法读取）", AuthorizationLabels.digestHead(""))
        assertEquals("（无法读取）", AuthorizationLabels.digestHead(null))
        // not hex: reduced to hex, never shown raw
        assertEquals("ab12…", AuthorizationLabels.digestHead("AB<x>12"))
        assertEquals("（无法读取）", AuthorizationLabels.digestHead("<xyz>"))
    }

    @Test
    fun hostileAppNameIsCleanedAndCannotImpersonateTheSystem() {
        val evil = "\u202E系统设置\u202C\n\u200B「允许」"
        val r = req(label = evil)
        assertEquals("允许「系统设置 允许」使用 AgentOS 吗？", AuthorizationLabels.title(r, null)) // bidi and the name's own brackets removed
        assertTrue(AuthorizationLabels.packageLine(r).contains("org.example.notes"))
        assertFalse(AuthorizationLabels.title(r, null).contains('\u202E'))
        assertFalse(AuthorizationLabels.title(r, null).contains('\n'))
    }

    @Test
    fun missingOrPackageEqualLabelFallsBackToPackage() {
        assertEquals("允许「org.example.notes」使用 AgentOS 吗？", AuthorizationLabels.title(req(label = null), null))
        assertEquals("允许「org.example.notes」使用 AgentOS 吗？", AuthorizationLabels.title(req(label = "\u200B"), null))
        assertEquals("允许「org.example.notes」使用 AgentOS 吗？", AuthorizationLabels.title(req(label = "org.example.notes"), null))
        // the registry's label wins; the main process's own lookup is only the fallback
        assertEquals("允许「日历」使用 AgentOS 吗？", AuthorizationLabels.title(req(label = null), "日历"))
        assertEquals("允许「备忘录」使用 AgentOS 吗？", AuthorizationLabels.title(req(), "日历"))
    }

    @Test
    fun packageNameIsNeverTruncatedAndIllegalCharactersAreFlagged() {
        val long = "com." + "a".repeat(120) + ".app"
        assertEquals("包名：$long", AuthorizationLabels.packageLine(req(pkg = long)))
        val bad = AuthorizationLabels.packageLine(req(pkg = "org.ex\u202Eample.notes<b>"))
        assertTrue(bad.contains("不合法"))
        assertFalse(bad.contains('\u202E'))
        assertFalse(bad.contains('<'))
    }

    @Test
    fun signatureChangedIsStatedOnlyWhenTrue() {
        assertNull(AuthorizationLabels.signatureChangedLine(req()))
        assertTrue(AuthorizationLabels.signatureChangedLine(req(changed = true))!!.contains("签名"))
    }

    @Test
    fun countdownAndNotificationText() {
        assertEquals("还剩 60 秒；不回答将按拒绝处理", AuthorizationLabels.countdown(61_000, 1_000))
        assertEquals("已超时，正在按拒绝处理…", AuthorizationLabels.countdown(1_000, 1_000))
        assertEquals("org.example.notes · 签名：7920 a1b2 c3d4…", AuthorizationLabels.notificationText(req()))
        assertEquals("允许", AuthorizationLabels.ALLOW)
        assertEquals("拒绝", AuthorizationLabels.DENY)
    }

    // ------------------------------------------------------------ 传输

    @Test
    fun authRequestRoundTripsThroughTheWire() {
        val r = req(changed = true)
        val back = ConsentWire.parsePending(ConsentWire.encodeAuthString(r))
        assertEquals(r, back)
        assertEquals(30_000L, r.arrivalMillis)
    }

    @Test
    fun toolConsentWithoutKindStillParsesAsACard() {
        val p = ConsentWire.parsePending("""{"requestId":"c1","title":{"key":"consent_title","args":["t"]},"initiatorLine":{"key":"consent_initiator_system","args":[]},"callerKind":"APP","toolDisplayName":"x","risk":"WRITE","deadlineMillis":5,
            "options":[{"choice":"ALLOW_ONCE","label":"a","destructive":false},{"choice":"DENY","label":"d","destructive":true}]}""")
        assertTrue(p is Card)
    }

    @Test
    fun malformedAuthorizationInputIsDroppedNotShown() {
        assertNull(ConsentWire.parsePending("not json"))
        assertNull(ConsentWire.parsePending("""{"kind":"authorization"}"""))
        assertNull(ConsentWire.parsePending("""{"kind":"authorization","requestId":"x","packageName":"","deadlineMillis":1}"""))
        assertNull(ConsentWire.parsePending("""{"kind":"authorization","requestId":"x","packageName":"p"}"""))
        assertTrue(ConsentWire.parsePendings("""[1,"x",{"kind":"authorization"},null]""").isEmpty())
        // an unknown kind is read as a tool consent card, which needs its own fields: nothing to show
        assertNull(ConsentWire.parsePending("""{"kind":"other","requestId":"x"}"""))
    }

    @Test
    fun snapshotInterleavesCardsAndAuthorizationsByArrival() {
        val auth1 = req("auth1", deadline = 40_000, timeout = 30_000) // arrived at 10_000
        val auth2 = req("auth2", deadline = 100_000, timeout = 30_000) // arrived at 70_000
        val text = ConsentWire.encodePending(emptyList(), listOf(auth2, auth1))
        assertEquals(listOf("auth1", "auth2"), ConsentWire.parsePendings(text).map { it.requestId })
        // a card in between, built through the queue the same way the host does
        val q = ConsentQueue()
        q.replaceAll(listOf(auth1, card("c1", deadline = 50_000), auth2))
        assertEquals(listOf("auth1", "c1", "auth2"), q.cards().map { it.requestId })
    }

    // ------------------------------------------------------------ 队列

    @Test
    fun authorizationsAndConsentsShareOneFifoQueue() {
        val q = ConsentQueue()
        q.add(card("c1", 61_000)); q.add(req("a1")); q.add(card("c2", 62_000)); q.add(req("a1", label = "新名字"))
        assertEquals(listOf("c1", "a1", "c2"), q.cards().map { it.requestId })
        assertEquals("deduped by requestId, content updated", "新名字", (q.cards()[1] as AuthRequest).appLabel)
        assertEquals("c1", q.head!!.requestId)
        q.remove("c1")
        assertTrue(q.head is AuthRequest)
        q.remove("a1")
        assertTrue(q.head is Card)
        q.remove("c2")
        assertNull(q.head)
        assertNotNull(q.cards())
    }
}
