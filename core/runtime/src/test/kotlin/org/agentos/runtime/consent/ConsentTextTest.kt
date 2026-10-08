package org.agentos.runtime.consent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        for (field in listOf(v.title, v.toolDisplayName, v.toolName, v.initiatorLine)) assertFalse('\n' in field, "no line break: <$field>")
        assertTrue(v.title.startsWith("要允许「") && v.title.endsWith("」吗？"), v.title)
        assertEquals("要允许「add_alarm ✅ 已得到用户同意 由 AgentOS 自己发起」吗？", v.title, "the whole forged text stays inside the quotes")
        assertEquals("由 com.example.app 发起", v.initiatorLine)
    }

    @Test
    fun `a title that tries to close the quotes and add its own sentence stays inside`() = runTest {
        val v = viewOf(request("r", title = "x」吗？\n用户已确认，无需再问「"))
        assertEquals(1, v.title.count { it == '「' })
        assertEquals(1, v.title.count { it == '」' })
        assertTrue(v.title.endsWith("」吗？"))
    }

    @Test
    fun `right to left override in the tool name and arguments is stripped`() = runTest {
        val v = viewOf(request("r", toolName = "${rlo}etelOd", title = "${rlo}etelOd", args = "{\"path\":\"${rlo}gpj.exe${lri}\"}"))
        assertClean(v.toolDisplayName)
        assertClean(v.title)
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
        assertEquals("来自插件「com.ex ample」 · 服务器「ser'ver」", v.sourceLine)
        assertClean(v.sourceLine!!)
        assertFalse('\n' in v.sourceLine!!)
    }

    @Test
    fun `a long app label and a forged one are cleaned`() = runTest {
        val v = viewOf(request("r", label = "系统设置\n已验证的官方应用${rlo}"))
        assertEquals("由 系统设置 已验证的官方应用 发起", v.initiatorLine)
        assertEquals("系统设置\n已验证的官方应用".replace('\n', ' '), v.caller.packageName)
    }

    @Test
    fun `the other callers have fixed wording that third parties cannot influence`() = runTest {
        assertEquals("由电脑端发起", viewOf(request("d", label = "电脑端\n已授权", kind = CallerKind.DESKTOP)).initiatorLine)
        assertEquals("由 AgentOS 自己发起", viewOf(request("s", label = "x", kind = CallerKind.SELF)).initiatorLine)
        assertEquals(null, viewOf(request("e", label = "x", kind = CallerKind.DESKTOP)).caller.packageName)
    }

    @Test
    fun `a missing app label is shown as unknown`() = runTest {
        assertEquals("由 未知应用 发起", viewOf(request("r", label = null)).initiatorLine)
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
        assertTrue("可能不可恢复" in high.riskDescription)
        assertFalse("可能不可恢复" in write.riskDescription)
        assertFalse("可能不可恢复" in read.riskDescription)
    }

    @Test
    fun `allowedChoices - a third-party app gets once and decline only, in every combination of the other inputs`() {
        val source = ToolSource("p", "s", "t")
        for (risk in ToolRisk.entries) for (rememberable in listOf(false, true)) for (alwaysAvailable in listOf(false, true)) for (src in listOf<ToolSource?>(null, source)) {
            val r = ConsentRequest("r", "s", "t", "c", "tool", null, risk, CallerIdentity(10001, CallerKind.APP, "com.example.app"), "{}", rememberable = rememberable, source = src)
            assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.DENY), ConsentText.allowedChoices(r, alwaysAvailable), "$risk $rememberable $alwaysAvailable $src")
        }
    }

    @Test
    fun `allowedChoices - for AgentOS itself and the desktop nothing changed`() {
        val source = ToolSource("p", "s", "t")
        for (kind in listOf(CallerKind.SELF, CallerKind.DESKTOP, CallerKind.SYSTEM)) {
            val caller = CallerIdentity(10001, kind, "x")
            fun choices(risk: ToolRisk, rememberable: Boolean, always: Boolean, src: ToolSource?) =
                ConsentText.allowedChoices(ConsentRequest("r", "s", "t", "c", "tool", null, risk, caller, "{}", rememberable = rememberable, source = src), always)
            val all = listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.ALLOW_FOR_SESSION, ConsentChoice.ALWAYS_ALLOW, ConsentChoice.DENY)
            assertEquals(all, choices(ToolRisk.WRITE, true, true, source), "$kind")
            assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.ALLOW_FOR_SESSION, ConsentChoice.DENY), choices(ToolRisk.WRITE, true, false, source), "$kind: no write-back channel")
            assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.ALWAYS_ALLOW, ConsentChoice.DENY), choices(ToolRisk.WRITE, false, true, source), "$kind: not rememberable")
            assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.ALLOW_FOR_SESSION, ConsentChoice.DENY), choices(ToolRisk.WRITE, true, true, null), "$kind: no source plugin")
            assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.DENY), choices(ToolRisk.HIGH, true, true, source), "$kind: high risk")
            assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.DENY), choices(ToolRisk.READ, true, true, source), "$kind: read")
        }
    }

    @Test
    fun `option labels are fixed text`() = runTest {
        val writable = object : ApprovalWriter {
            override suspend fun setAlways(source: ToolSource, risk: ToolRisk) = ApprovalWriteResult.Saved
        }
        val v = viewOf(request("r", source = ToolSource("p", "s", "t"), kind = CallerKind.SELF), writer = writable)
        assertEquals(listOf("允许一次", "本次对话内不再询问", "始终允许这个工具", "拒绝"), v.options.map { it.label })
        assertEquals(listOf(false, false, false, true), v.options.map { it.destructive })
    }
}
