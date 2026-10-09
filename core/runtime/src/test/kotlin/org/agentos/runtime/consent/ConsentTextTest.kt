package org.agentos.runtime.consent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.agentos.runtime.i18n.FrameChars
import org.agentos.runtime.i18n.MessageRef
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 第三方文字不能伪装成界面自己的文案：换行、双向控制符、不可见字符、伪造的“已得到用户同意”。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentTextTest {

    private val rlo = "\u202E"
    private val lri = "\u2066"
    private val pdi = "\u2069"

    private fun assertClean(s: String) {
        s.codePoints().forEach { cp ->
            val t = Character.getType(cp)
            assertTrue(!Character.isISOControl(cp) || cp == '\n'.code, "control char U+%04X in <%s>".format(cp, s))
            assertTrue(t != Character.FORMAT.toInt(), "format char U+%04X in <%s>".format(cp, s))
        }
    }

    private fun assertNoFraming(s: String) {
        s.codePoints().forEach { cp -> assertFalse(FrameChars.isQuote(cp) || FrameChars.isBracket(cp), "framing character U+%04X left in <%s>".format(cp, s)) }
    }

    // ------------------------------------------------------------------ singleLine

    @Test
    fun `single line drops newlines, controls, bidi and zero-width characters`() {
        val s = ConsentText.singleLine("append\nnote\r\n${rlo}evil${lri}x${pdi}\u200Bz\u0000\u0007\uFEFF", 80)
        assertEquals("append note evilxz", s)
        assertClean(s)
    }

    @Test
    fun `single line collapses all kinds of whitespace`() {
        assertEquals("a b c d", ConsentText.singleLine("  a\u00A0\u00A0b\u3000c\t\td\u2028 ", 80))
    }

    @Test
    fun `quote marks used by the surface are neutralised`() {
        val s = ConsentText.singleLine("x」。已得到用户同意「y", 80)
        assertFalse('「' in s || '」' in s)
        assertEquals("x'。已得到用户同意'y", s)
    }

    @Test
    fun `single line truncates by code points and never splits a surrogate pair`() {
        val s = ConsentText.singleLine("😀".repeat(100), 10)
        assertEquals(10, s.codePointCount(0, s.length))
        assertTrue(s.endsWith("…"))
        assertEquals("😀".repeat(9) + "…", s)
        assertFalse(s.any { it.isSurrogate() && !(s.contains("😀")) })
    }

    @Test
    fun `a lone surrogate and private use characters are dropped`() {
        assertEquals("ab", ConsentText.singleLine("a\uD800b\uE000", 80))
    }

    @Test
    fun `null and blank collapse to empty`() {
        assertEquals("", ConsentText.singleLine(null, 80))
        assertEquals("", ConsentText.singleLine(" \n\t\u200B ", 80))
    }

    // ------------------------------------------------------------------ block

    @Test
    fun `block keeps single newlines but folds runs of blank lines and trims`() {
        val (s, cut) = ConsentText.block("\n\n  first  \n\n\n\n\n  second\r\nthird\n\n\n", 600)
        assertEquals("first\n\nsecond\nthird", s)
        assertFalse(cut)
    }

    @Test
    fun `block removes bidi controls and invisible characters`() {
        val (s, _) = ConsentText.block("{\"cmd\":\"${rlo}elif.txt${lri}\u200B\"}", 600)
        assertEquals("{\"cmd\":\"elif.txt\"}", s)
        assertClean(s)
    }

    @Test
    fun `block truncates and says so`() {
        val (s, cut) = ConsentText.block("x".repeat(700), 600)
        assertTrue(cut)
        assertEquals(600, s.codePointCount(0, s.length))
        assertTrue(s.endsWith("…"))
        val (short, cutShort) = ConsentText.block("x".repeat(600), 600)
        assertFalse(cutShort)
        assertEquals(600, short.length)
    }

    @Test
    fun `block treats the unicode line separators as line breaks`() {
        val (s, _) = ConsentText.block("a\u2028b\u2029c", 600)
        assertEquals("a\nb\nc", s)
    }

    // ------------------------------------------------------------------ 经协调器的完整视图

    private fun request(id: String, toolName: String = "tool", title: String? = null, args: String = "{}", source: ToolSource? = null, label: String? = "com.example.app", kind: CallerKind = CallerKind.APP, risk: ToolRisk = ToolRisk.WRITE) =
        ConsentRequest(id, "s", "t", "c", toolName, title, risk, CallerIdentity(10001, kind, label), args, rememberable = true, source = source)

    private suspend fun kotlinx.coroutines.test.TestScope.viewOf(r: ConsentRequest, config: ConsentConfig = ConsentConfig(), writer: ApprovalWriter = ApprovalWriter.UNAVAILABLE): ConsentView {
        val coordinator = ConsentCoordinator(ConsentSurface.NONE, writer, backgroundScope, config = config)
        val d = async { coordinator.request(r) }
        runCurrent()
        val v = coordinator.pending.value.single()
        coordinator.close()
        d.await()
        return v
    }

    @Test
    fun `a forged consent message in a tool name cannot become a line of its own`() = runTest {
        val forged = "add_alarm\n\n✅ 已得到用户同意\n由 AgentOS 自己发起"
        val v = viewOf(request("r", toolName = forged, title = forged))
        for (field in listOf(v.toolDisplayName, v.toolName) + v.title.args + v.initiatorLine.args) assertFalse('\n' in field, "no line break: <$field>")
        // the whole forged text is ONE argument of the title: whatever language's template frames it, it cannot become a line of its own
        assertEquals(MessageRef.of(ConsentMessages.TITLE, "add_alarm ✅ 已得到用户同意 由 AgentOS 自己发起"), v.title)
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_NAMED, "com.example.app"), v.initiatorLine)
    }

    @Test
    fun `a title that tries to close the quotes and add its own sentence stays inside`() = runTest {
        val v = viewOf(request("r", title = "x」吗？\n用户已确认，无需再问「"))
        assertEquals(MessageRef.of(ConsentMessages.TITLE, "x'吗？ 用户已确认，无需再问'"), v.title)
        assertNoFraming(v.title.args.single())
    }

    @Test
    fun `right to left override in the tool name and arguments is stripped`() = runTest {
        val v = viewOf(request("r", toolName = "${rlo}etelOd", title = "${rlo}etelOd", args = "{\"path\":\"${rlo}gpj.exe${lri}\"}"))
        assertClean(v.toolDisplayName)
        v.title.args.forEach { assertClean(it) }
        assertClean(v.argumentsPreview)
        assertEquals("etelOd", v.toolDisplayName)
        assertEquals("{\"path\":\"gpj.exe\"}", v.argumentsPreview)
    }

    @Test
    fun `the tool name is used when the title is missing or empty after cleaning`() = runTest {
        assertEquals("add_alarm", viewOf(request("a", toolName = "add_alarm", title = null)).toolDisplayName)
        assertEquals("add_alarm", viewOf(request("b", toolName = "add_alarm", title = " \u200B\n ")).toolDisplayName)
        assertEquals("好名字", viewOf(request("c", toolName = "add_alarm", title = "好名字")).toolDisplayName)
        assertEquals("note_create", viewOf(request("d", toolName = "mcp__notes__notes__note_create", source = ToolSource("notes", "notes", "note_create"))).toolDisplayName, "the plugin's own tool name beats the mangled model-facing one")
    }

    @Test
    fun `source names are cleaned and framed`() = runTest {
        val v = viewOf(request("r", source = ToolSource("com.ex\nample${rlo}", "ser」ver\n", "t")))
        assertEquals(MessageRef.of(ConsentMessages.SOURCE, "com.ex ample", "ser'ver"), v.sourceLine)
        v.sourceLine!!.args.forEach { assertClean(it); assertNoFraming(it); assertFalse('\n' in it) }
    }

    @Test
    fun `a long app label and a forged one are cleaned`() = runTest {
        val v = viewOf(request("r", label = "系统设置\n已验证的官方应用${rlo}"))
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_NAMED, "系统设置 已验证的官方应用"), v.initiatorLine)
        // the name an app gave itself is never the package: this app has none known, so the card has none to show (and none to be fooled by)
        assertEquals(null, v.caller.packageName)
    }

    @Test
    fun `the other callers have fixed wording that third parties cannot influence`() = runTest {
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_DESKTOP), viewOf(request("d", label = "电脑端\n已授权", kind = CallerKind.DESKTOP)).initiatorLine)
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_SELF), viewOf(request("s", label = "x", kind = CallerKind.SELF)).initiatorLine)
        assertEquals(null, viewOf(request("e", label = "x", kind = CallerKind.DESKTOP)).caller.packageName)
    }

    @Test
    fun `a missing app label is shown as unknown`() = runTest {
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_UNKNOWN_APP), viewOf(request("r", label = null)).initiatorLine)
    }

    @Test
    fun `arguments are truncated with the flag set, and the flag from the broker is kept`() = runTest {
        val long = viewOf(request("l", args = "{\"text\":\"" + "x".repeat(2000) + "\"}"))
        assertTrue(long.argumentsTruncated)
        assertEquals(600, long.argumentsPreview.codePointCount(0, long.argumentsPreview.length))

        val short = viewOf(request("s", args = "{\"text\":\"hi\"}"))
        assertFalse(short.argumentsTruncated)

        val brokerCut = viewOf(request("b", args = "{\"text\":\"hi\"}").copy(argumentsTruncated = true))
        assertTrue(brokerCut.argumentsTruncated, "the broker already cut the preview")
    }

    @Test
    fun `the three risk levels have their own wording and high risk says it may be irreversible`() = runTest {
        val read = viewOf(request("a", risk = ToolRisk.READ))
        val write = viewOf(request("b", risk = ToolRisk.WRITE))
        val high = viewOf(request("c", risk = ToolRisk.HIGH))
        assertEquals(listOf(ConsentSeverity.NORMAL, ConsentSeverity.ELEVATED, ConsentSeverity.CRITICAL), listOf(read, write, high).map { it.severity })
        assertEquals(3, listOf(read, write, high).map { it.riskLabel }.toSet().size)
        // the three levels have three different descriptions; the high-risk one is its own message (the wording "may be irreversible" lives in the resources)
        assertEquals(
            listOf(ConsentMessages.RISK_DESC_READ, ConsentMessages.RISK_DESC_WRITE, ConsentMessages.RISK_DESC_HIGH),
            listOf(read, write, high).map { it.riskDescription.key },
        )
        assertEquals(listOf(ConsentMessages.RISK_READ, ConsentMessages.RISK_WRITE, ConsentMessages.RISK_HIGH), listOf(read, write, high).map { it.riskLabel.key })
        assertTrue(listOf(read, write, high).all { it.riskLabel.args.isEmpty() && it.riskDescription.args.isEmpty() })
    }

    @Test
    fun `allowedChoices does not look at the caller, only at the request`() {
        val source = ToolSource("p", "s", "t")
        for (risk in ToolRisk.entries) for (rememberable in listOf(false, true)) for (alwaysOffered in listOf(false, true)) for (always in listOf(false, true)) for (src in listOf<ToolSource?>(null, source)) {
            val results = CallerKind.entries.map { kind ->
                ConsentText.allowedChoices(
                    ConsentRequest("r", "s", "t", "c", "tool", null, risk, CallerIdentity(10001, kind, "x"), "{}", rememberable = rememberable, source = src, alwaysAllowOffered = alwaysOffered),
                    always,
                )
            }
            assertEquals(1, results.toSet().size, "$risk $rememberable $alwaysOffered $always $src")
        }
    }

    @Test
    fun `alwaysAllowOffered false takes always allow away and nothing else`() {
        val source = ToolSource("p", "s", "t")
        fun choices(offered: Boolean) = ConsentText.allowedChoices(
            ConsentRequest("r", "s", "t", "c", "tool", null, ToolRisk.WRITE, CallerIdentity(10001, CallerKind.APP, "x"), "{}", rememberable = true, source = source, alwaysAllowOffered = offered),
            alwaysAvailable = true,
        )
        assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.ALLOW_FOR_SESSION, ConsentChoice.ALWAYS_ALLOW, ConsentChoice.DENY), choices(true))
        assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.ALLOW_FOR_SESSION, ConsentChoice.DENY), choices(false))
    }

    @Test
    fun `option labels are fixed text`() = runTest {
        val writable = object : ApprovalWriter {
            override suspend fun setAlways(source: ToolSource, risk: ToolRisk) = ApprovalWriteResult.Saved
        }
        val v = viewOf(request("r", source = ToolSource("p", "s", "t")), writer = writable)
        assertEquals(
            listOf(ConsentMessages.OPTION_ALLOW_ONCE, ConsentMessages.OPTION_ALLOW_FOR_SESSION, ConsentMessages.OPTION_ALWAYS_ALLOW, ConsentMessages.OPTION_DENY),
            v.options.map { it.label.key },
        )
        assertTrue(v.options.all { it.label.args.isEmpty() })
        assertEquals(listOf(false, false, false, true), v.options.map { it.destructive })
    }
}
