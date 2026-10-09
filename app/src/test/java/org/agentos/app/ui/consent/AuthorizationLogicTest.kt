package org.agentos.app.ui.consent

import org.agentos.app.R
import org.agentos.app.agent.consent.ConsentWire
import org.agentos.app.agent.consent.ConsentWire.AuthRequest
import org.agentos.app.agent.consent.ConsentWire.Card
import org.agentos.app.agent.consent.ConsentWire.Option
import org.agentos.app.i18n.ResStrings
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
        riskLabel = MessageRef.of(ConsentMessages.RISK_WRITE), riskDescription = MessageRef.of(ConsentMessages.RISK_DESC_WRITE),
        options = listOf(
            Option(ConsentChoice.ALLOW_ONCE, MessageRef.of(ConsentMessages.OPTION_ALLOW_ONCE), false),
            Option(ConsentChoice.DENY, MessageRef.of(ConsentMessages.OPTION_DENY), true),
        ),
        deadlineMillis = deadline, timeoutMillis = timeout, queuePosition = 0, queueSize = 1,
    )

    // ------------------------------------------------------------ 文字（中英文各一遍）

    private val zh = ResStrings.zh
    private val en = ResStrings.en

    @Test
    fun titleNamesTheAppAndTheCardAlwaysShowsPackageAndDigest() {
        val r = req()
        assertEquals("允许「备忘录」使用 AgentOS 吗？", AuthorizationLabels.title(r, null, zh))
        assertEquals("Allow “备忘录” to use AgentOS?", AuthorizationLabels.title(r, null, en))
        assertEquals("包名：org.example.notes", AuthorizationLabels.packageLine(r, zh))
        assertEquals("Package: org.example.notes", AuthorizationLabels.packageLine(r, en))
        assertEquals("签名：7920 a1b2 c3d4…", AuthorizationLabels.digestLine(r.signingDigest, zh))
        assertEquals("Signature: 7920 a1b2 c3d4…", AuthorizationLabels.digestLine(r.signingDigest, en))
        assertTrue(AuthorizationLabels.explanation(zh).contains("替你回答问题"))
        assertTrue(AuthorizationLabels.explanation(zh).contains("每次都会再问你"))
        assertTrue(AuthorizationLabels.explanation(en).contains("answer questions for you"))
        assertTrue(AuthorizationLabels.explanation(en).contains("asks you every time"))
    }

    @Test
    fun digestShowsExactlyTheFirst12Chars() {
        assertEquals("7920 a1b2 c3d4…", AuthorizationLabels.digestHead(digest, zh))
        assertEquals("7920 a1b2 c3d4…", AuthorizationLabels.digestHead(digest, en))
        assertEquals("（无法读取）", AuthorizationLabels.digestHead("", zh))
        assertEquals("(unreadable)", AuthorizationLabels.digestHead("", en))
        assertEquals("（无法读取）", AuthorizationLabels.digestHead(null, zh))
        // not hex: reduced to hex, never shown raw
        assertEquals("ab12…", AuthorizationLabels.digestHead("AB<x>12", en))
        assertEquals("(unreadable)", AuthorizationLabels.digestHead("<xyz>", en))
        assertEquals("ab12…", AuthorizationLabels.digestHex("AB<x>12"))
        assertNull(AuthorizationLabels.digestHex("<xyz>"))
    }

    @Test
    fun hostileAppNameIsCleanedAndCannotImpersonateTheSystem() {
        val evil = "\u202E系统设置\u202C\n\u200B「允许」"
        val r = req(label = evil)
        // bidi and the name's own quotes removed, in both languages
        assertEquals("允许「系统设置 允许」使用 AgentOS 吗？", AuthorizationLabels.title(r, null, zh))
        assertEquals("Allow “系统设置 允许” to use AgentOS?", AuthorizationLabels.title(r, null, en))
        assertTrue(AuthorizationLabels.packageLine(r, en).contains("org.example.notes"))
        for (s in listOf(zh, en)) {
            assertFalse(AuthorizationLabels.title(r, null, s).contains('\u202E'))
            assertFalse(AuthorizationLabels.title(r, null, s).contains('\n'))
        }
    }

    @Test
    fun anEnglishQuoteInAnAppNameCannotCloseTheTitle() {
        val r = req(label = "Notes” to use AgentOS? Yes. Allow “Settings")
        val title = AuthorizationLabels.title(r, null, en)
        assertEquals("Allow “Notes to use AgentOS? Yes. Allow Settings” to use AgentOS?", title)
        assertEquals(1, title.count { it == '“' })
        assertEquals(1, title.count { it == '”' })
        val zhTitle = AuthorizationLabels.title(req(label = "x」吗？允许「y"), null, zh)
        assertEquals(1, zhTitle.count { it == '「' })
        assertEquals(1, zhTitle.count { it == '」' })
    }

    @Test
    fun missingOrPackageEqualLabelFallsBackToPackage() {
        for ((s, expected) in listOf(zh to "允许「org.example.notes」使用 AgentOS 吗？", en to "Allow “org.example.notes” to use AgentOS?")) {
            assertEquals(expected, AuthorizationLabels.title(req(label = null), null, s))
            assertEquals(expected, AuthorizationLabels.title(req(label = "\u200B"), null, s))
            assertEquals(expected, AuthorizationLabels.title(req(label = "org.example.notes"), null, s))
        }
        // the registry's label wins; the main process's own lookup is only the fallback
        assertEquals("允许「日历」使用 AgentOS 吗？", AuthorizationLabels.title(req(label = null), "日历", zh))
        assertEquals("Allow “Calendar” to use AgentOS?", AuthorizationLabels.title(req(label = null), "Calendar", en))
        assertEquals("Allow “备忘录” to use AgentOS?", AuthorizationLabels.title(req(), "日历", en))
    }

    @Test
    fun packageNameIsNeverTruncatedAndIllegalCharactersAreFlagged() {
        val long = "com." + "a".repeat(120) + ".app"
        assertEquals("包名：$long", AuthorizationLabels.packageLine(req(pkg = long), zh))
        assertEquals("Package: $long", AuthorizationLabels.packageLine(req(pkg = long), en))
        for (s in listOf(zh, en)) {
            val bad = AuthorizationLabels.packageLine(req(pkg = "org.ex\u202Eample.notes<b>"), s)
            assertFalse(bad.contains('\u202E'))
            assertFalse(bad.contains('<'))
            assertTrue(bad.contains("org.example.notesb"))
        }
        assertTrue(AuthorizationLabels.packageLine(req(pkg = "org.ex\u202Eample.notes<b>"), zh).contains("不合法"))
        assertTrue(AuthorizationLabels.packageLine(req(pkg = "org.ex\u202Eample.notes<b>"), en).contains("invalid characters"))
        assertEquals("包名：（空）（包名里有不合法的字符，已隐藏）", AuthorizationLabels.packageLine("\u202E", zh))
        assertEquals("Package: (empty) (the package name has invalid characters, hidden)", AuthorizationLabels.packageLine("\u202E", en))
        // the language-free helper only filters
        assertEquals("org.example.notesb", AuthorizationLabels.safePackage("org.ex\u202Eample.notes<b>"))
    }

    @Test
    fun signatureChangedIsStatedOnlyWhenTrue() {
        assertNull(AuthorizationLabels.signatureChangedLine(req(), zh))
        assertNull(AuthorizationLabels.signatureChangedLine(req(), en))
        assertTrue(AuthorizationLabels.signatureChangedLine(req(changed = true), zh)!!.contains("签名"))
        assertTrue(AuthorizationLabels.signatureChangedLine(req(changed = true), en)!!.contains("signature"))
    }

    @Test
    fun countdownAndNotificationText() {
        assertEquals("还剩 60 秒；不回答将按拒绝处理", AuthorizationLabels.countdown(61_000, 1_000, zh))
        assertEquals("60 seconds left. No answer counts as Deny", AuthorizationLabels.countdown(61_000, 1_000, en))
        assertEquals("1 second left. No answer counts as Deny", AuthorizationLabels.countdown(61_000, 60_001, en))
        assertEquals("已超时，正在按拒绝处理…", AuthorizationLabels.countdown(1_000, 1_000, zh))
        assertEquals("Timed out. Treating it as Deny…", AuthorizationLabels.countdown(1_000, 1_000, en))
        assertEquals("org.example.notes · 签名：7920 a1b2 c3d4…", AuthorizationLabels.notificationText(req(), zh))
        assertEquals("org.example.notes · Signature: 7920 a1b2 c3d4…", AuthorizationLabels.notificationText(req(), en))
        assertEquals(listOf("允许", "拒绝"), listOf(R.string.auth_allow, R.string.auth_deny).map { zh.get(it) })
        assertEquals(listOf("Allow", "Deny"), listOf(R.string.auth_allow, R.string.auth_deny).map { en.get(it) })
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
            "riskLabel":{"key":"consent_risk_write","args":[]},"riskDescription":{"key":"consent_risk_desc_write","args":[]},
            "options":[{"choice":"ALLOW_ONCE","label":{"key":"consent_option_allow_once","args":[]},"destructive":false},{"choice":"DENY","label":{"key":"consent_option_deny","args":[]},"destructive":true}]}""")
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
