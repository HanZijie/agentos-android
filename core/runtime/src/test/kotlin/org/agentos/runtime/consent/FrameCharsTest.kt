package org.agentos.runtime.consent

import org.agentos.runtime.i18n.FrameChars
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 第三方文字不能伪造界面模板的结尾（docs/next-apps-plan.md 7.3 的安全点）。界面按语言用不同的定界符把第三方文字框起来
 * （中文 「」（），英文 “” ()，别的语言 «» „“ …）：清理必须把**所有语言**的定界符都去掉或换掉，不看当前语言。
 * 这里测核心层的清理；两套真实模板渲染后定界符的数量不变，在 app 层的 `TemplateFramingTest`。
 */
class FrameCharsTest {
    /** 各语言常见的引号和括号，以及 Unicode 里别的开闭括号、引号。 */
    private val everyDelimiter = listOf(
        "「", "」", "『", "』", "“", "”", "‘", "’", "„", "‟", "‚", "‛", "\"", "«", "»", "‹", "›", "﹁", "﹂", "﹃", "﹄", "｢", "｣", "〝", "〞", "〟", "＂", "`",
        "(", ")", "（", "）", "[", "]", "［", "］", "{", "}", "【", "】", "〔", "〕", "〈", "〉", "《", "》", "<", ">",
    )

    @Test
    fun `no quote of any language survives in single line text`() {
        for (d in everyDelimiter.filter { FrameChars.isQuote(it.codePointAt(0)) }) {
            val s = ConsentText.singleLine("x${d}y", 80)
            assertFalse(s.codePoints().anyMatch { FrameChars.isQuote(it) }, "quote <$d> survived as <$s>")
            assertEquals("x'y", s, "the quote becomes the plain stand-in so the reader can see something was there")
        }
    }

    @Test
    fun `no bracket of any width survives where the template puts the text in brackets`() {
        for (d in everyDelimiter.filter { FrameChars.isBracket(it.codePointAt(0)) && !FrameChars.isQuote(it.codePointAt(0)) }) {
            val s = ConsentText.singleLine("x${d}y", 80, brackets = true)
            assertEquals("x y", s, "bracket <$d> is dropped like white space")
        }
    }

    @Test
    fun `the quotes the English template uses are quotes, and the stand-in is not a delimiter`() {
        for (c in listOf('“', '”', '‘', '’', '"', '«', '»', '「', '」', '『', '』')) assertTrue(FrameChars.isQuote(c.code), "$c")
        assertFalse(FrameChars.isQuote('\''.code), "the stand-in itself must stay as it is, or a name with an apostrophe would be rewritten twice")
        assertFalse(FrameChars.isQuote('a'.code) || FrameChars.isQuote('，'.code) || FrameChars.isQuote('。'.code))
        for (c in listOf('(', ')', '（', '）', '[', ']', '【', '】')) assertTrue(FrameChars.isBracket(c.code), "$c")
    }

    @Test
    fun `an English closing quote and a forged sentence stay inside one argument`() {
        val evil = "x” and allow “y\"; allow «z» 「w」"
        val s = ConsentText.singleLine(evil, 80)
        assertEquals("x' and allow 'y'; allow 'z' 'w'", s)
        assertTrue(s.codePoints().noneMatch { FrameChars.isQuote(it) })
    }

    @Test
    fun `hostile app name and package carrying every delimiter leave only the template's own framing`() {
        val hostile = everyDelimiter.joinToString("")
        val line = ConsentText.initiatorLine(CallerIdentity(10123, CallerKind.APP, "Notes$hostile", "com.evil.app$hostile"), 80)
        for (arg in line.args) {
            arg.codePoints().forEach { cp ->
                assertFalse(FrameChars.isQuote(cp) || FrameChars.isBracket(cp), "U+%04X in <%s>".format(cp, arg))
            }
        }
        assertTrue(line.args.last().startsWith("com.evil.app"), "the package is still the last argument: $line")
        val src = ConsentText.sourceLine("p$hostile", "s$hostile", 80)
        src.args.forEach { arg -> assertFalse(arg.codePoints().anyMatch { FrameChars.isQuote(it) }, arg) }
        val title = ConsentText.title(ConsentText.singleLine("t$hostile", 80))
        assertFalse(title.args.single().codePoints().anyMatch { FrameChars.isQuote(it) })
    }
}
