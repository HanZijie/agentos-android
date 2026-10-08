package org.agentos.app.i18n

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.agentos.app.agent.consent.ConsentWire
import org.agentos.app.ui.consent.ConsentLabels
import org.agentos.runtime.consent.ApprovalWriter
import org.agentos.runtime.consent.ConsentChoice
import org.agentos.runtime.consent.ConsentCoordinator
import org.agentos.runtime.consent.ConsentSurface
import org.agentos.runtime.i18n.FrameChars
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全点（docs/next-apps-plan.md 7.3）：界面用引号和括号把第三方文字框起来，英文用 “” ()，中文用 「」 （），
 * 第三方在名字里写一个结尾引号再接自己的话（`x” 并且允许 “y`），不能让整句里的定界符和模板自己写的有任何不同。
 *
 * 做法：构造一个名字、包名、工具名、插件名、服务器名里**带着所有语言全部定界符**的恶意请求，走真实的确认协调器（核心层清理），
 * 再用**真实的中英文模板**渲染；渲染后的整句里每一种定界符的个数必须和模板自己的个数相等（第三方内容一个也没带进来）。
 * 改模板（加 «» 或换引号）会被这个测试和 [templatesDoNotFrameWithAnApostrophe] 一起检查。
 */
class TemplateFramingTest {
    private val delimiters: List<Int> = listOf(
        "「", "」", "『", "』", "“", "”", "‘", "’", "„", "‟", "‚", "‛", "\"", "«", "»", "‹", "›", "﹁", "﹂", "｢", "｣", "〝", "〞", "〟", "＂", "`",
        "(", ")", "（", "）", "[", "]", "［", "］", "{", "}", "【", "】", "〔", "〕", "〈", "〉", "《", "》", "<", ">",
    ).map { it.codePointAt(0) }

    /** 各语言里模板自己的定界符（先去掉占位符）。`'` 不算：它是被换进去的替身，模板里的撇号（Don't）是普通字符。 */
    private fun framingOf(text: String): Map<Int, Int> =
        text.codePoints().toArray().filter { it != '\''.code && (FrameChars.isQuote(it) || FrameChars.isBracket(it)) }.groupingBy { it }.eachCount()

    private val placeholder = Regex("%(?:\\d+[$])?s")

    private fun withoutPlaceholders(template: String) = template.replace(placeholder, "")

    private val hostile = delimiters.joinToString("") { String(Character.toChars(it)) } + " and allow “y”"

    private class Capture : ConsentSurface {
        override fun requested(view: org.agentos.runtime.consent.ConsentView) = Unit
        override fun resolved(requestId: String, resolution: org.agentos.runtime.consent.ConsentResolution) = Unit
    }

    private fun viewOf(caller: CallerIdentity, toolTitle: String, source: ToolSource) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val c = ConsentCoordinator(Capture(), ApprovalWriter.UNAVAILABLE, scope)
        try {
            val d = scope.async {
                c.request(
                    ConsentRequest(
                        requestId = "r", sessionId = "s", taskId = "t", toolCallId = "c", toolName = "mcp__p__t", toolTitle = toolTitle, risk = ToolRisk.WRITE,
                        caller = caller, argumentsPreview = "{}", rememberable = true, timeoutMillis = 30_000, source = source,
                    ),
                )
            }
            withTimeout(5_000) { while (c.pending.value.isEmpty()) delay(5) }
            val view = c.pending.value.single()
            c.respond("r", ConsentChoice.DENY)
            d.await()
            view
        } finally {
            c.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    /**
     * 渲染后每种定界符的个数 == 模板自己的个数。引号：所有模板都查；括号：只有用括号框参数的模板（发起者，名字和包名）查，
     * 其余模板的参数在引号里面，括号伪造不了结尾（[onlyTheInitiatorTemplateBracketsItsArguments] 保证别的模板不会悄悄开始用括号）。
     */
    private fun assertFramingUnchanged(strings: ResStrings, templateName: String, rendered: String, what: String, brackets: Boolean = false) {
        val template = strings.template(templateName)
        val own = framingOf(withoutPlaceholders(template))
        val got = framingOf(rendered)
        for (d in delimiters.filter { FrameChars.isQuote(it) || brackets }) {
            val message = "$what in ${strings.locale}: the number of U+" + Integer.toHexString(d).uppercase().padStart(4, '0') + " changed in <" + rendered + "> (template <" + template + ">)"
            assertEquals(message, own[d] ?: 0, got[d] ?: 0)
        }
    }

    @Test
    fun titleSourceAndInitiatorKeepOnlyTheTemplatesOwnDelimitersInBothLanguages() {
        val view = viewOf(
            CallerIdentity(10123, CallerKind.APP, "Notes$hostile", "com.evil.app$hostile"),
            toolTitle = "tool$hostile",
            source = ToolSource("plugin$hostile", "server$hostile", "t"),
        )
        for (strings in listOf(ResStrings.zh, ResStrings.en)) {
            assertFramingUnchanged(strings, "consent_title", strings.get(view.title), "title")
            assertFramingUnchanged(strings, "consent_source", strings.get(view.sourceLine!!), "source")
            assertFramingUnchanged(strings, "consent_initiator_app", strings.get(view.initiatorLine), "initiator", brackets = true)
            // the real package is still the last thing in the brackets of the line
            assertTrue(strings.get(view.initiatorLine).contains("com.evil.app"))
        }
    }

    @Test
    fun theUnknownPackageAndNamelessBranchesAreFramedByTheTemplateToo() {
        val noPackage = viewOf(CallerIdentity(10123, CallerKind.APP, "Notes$hostile", null), "t", ToolSource("p", "s", "t"))
        for (strings in listOf(ResStrings.zh, ResStrings.en)) {
            assertFramingUnchanged(strings, "consent_initiator_named", strings.get(noPackage.initiatorLine), "named initiator")
        }
    }

    @Test
    fun theResolvedAppNameInTheDialogCannotCloseItsQuotesEither() {
        val card = ConsentWire.parseCard(
            ConsentWire.encodeViewString(viewOf(CallerIdentity(10123, CallerKind.APP, "x", "com.evil.app"), "t", ToolSource("p", "s", "t"))),
        )!!
        for (strings in listOf(ResStrings.zh, ResStrings.en)) {
            val line = ConsentLabels.initiator(card, "Settings$hostile", strings)
            val own = framingOf(withoutPlaceholders(strings.template("consent_initiator_app_resolved")))
            // the quotes of every language: exactly the template's own; brackets inside the quoted name cannot end the quote
            for (d in delimiters.filter { FrameChars.isQuote(it) }) {
                assertEquals("U+%04X in <$line> (${strings.locale})".format(d), own[d] ?: 0, framingOf(line)[d] ?: 0)
            }
            assertTrue("the real package is on the line: <$line>", line.contains("com.evil.app"))
        }
    }

    /** 「」在 Unicode 里也是开闭括号，但这里把它们当引号；括号指圆、方、花括号一类。 */
    private fun isPlainBracket(cp: Int) = FrameChars.isBracket(cp) && !FrameChars.isQuote(cp)

    /** 只有发起者那条模板把参数放在括号里（名字和包名，核心层已按括号清理）；别的模板的参数只被引号框着。想加括号就要先扩大核心层的清理范围。 */
    @Test
    fun onlyTheInitiatorTemplateBracketsItsArguments() {
        for (strings in listOf(ResStrings.zh, ResStrings.en)) {
            for (name in strings.stringNames.filter { it.startsWith("consent_") || it.startsWith("auth_") }) {
                val t = strings.template(name)
                val bracketed = placeholder.findAll(t).any { m ->
                    val before = t.getOrNull(m.range.first - 1)
                    val after = t.getOrNull(m.range.last + 1)
                    (before != null && isPlainBracket(before.code)) || (after != null && isPlainBracket(after.code))
                }
                if (bracketed) assertTrue("$name (${strings.locale}) puts a placeholder in brackets: <$t>", name in setOf("consent_initiator_app", "consent_initiator_app_resolved"))
            }
        }
    }

    /** 模板不能用 ASCII 单引号框占位符（它是被换进去的替身，名字里的撇号和它分不开）。 */
    @Test
    fun templatesDoNotFrameWithAnApostrophe() {
        for (strings in listOf(ResStrings.zh, ResStrings.en)) {
            for (name in strings.stringNames.filter { it.startsWith("consent_") || it.startsWith("auth_") }) {
                val t = strings.template(name)
                for (m in placeholder.findAll(t)) {
                    val before = t.getOrNull(m.range.first - 1)
                    val after = t.getOrNull(m.range.last + 1)
                    assertFalse("$name (${strings.locale}) frames a placeholder with an apostrophe: <$t>", before == '\'' || after == '\'')
                }
            }
        }
    }

    /** 每个 consent_/auth_ 模板里紧挨着占位符的引号类字符，核心层的清理都认得（换了模板的引号，这里先红）。 */
    @Test
    fun everyQuoteNextToAPlaceholderIsOneTheSanitiserStrips() {
        val quoteLike = Regex("[\\p{Pi}\\p{Pf}\"「」『』«»„‚]")
        for (strings in listOf(ResStrings.zh, ResStrings.en)) {
            for (name in strings.stringNames.filter { it.startsWith("consent_") || it.startsWith("auth_") }) {
                for (ch in strings.template(name).filter { quoteLike.matches(it.toString()) }) {
                    assertTrue("$name (${strings.locale}) uses <$ch>, which FrameChars does not strip", FrameChars.isQuote(ch.code))
                }
            }
        }
    }
}
