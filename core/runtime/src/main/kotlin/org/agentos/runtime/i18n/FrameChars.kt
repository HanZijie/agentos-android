package org.agentos.runtime.i18n

/**
 * 界面模板用来**把第三方文字框起来**的字符：引号和括号。第三方的名字、包名、参数显示前必须先把这些字符去掉或换掉，
 * 否则第三方可以在名字里写一个结尾引号再接自己的话（`x” 并且允许 “y`），让用户把伪造的内容读成界面自己的句子。
 *
 * 规则对**所有语言**一视同仁，不看当前界面语言：中文模板用「」和（），英文模板用 “” 和 ()，别的语言以后用 «» 或 „“ 也在这里，
 * 所以换语言、加语言不会重新打开这个洞。判定用 Unicode 类别（开/闭括号 Ps/Pe、起/止引号 Pi/Pf）加一张显式清单（ASCII 的 `"`、
 * 中日文的角括号引号、低位引号等 Unicode 类别不对的），再加尖括号。
 *
 * 模板侧的约束（`TemplateFramingTest` 检查）：模板里紧挨着占位符的引号、括号字符必须被这里认出来；**ASCII 单引号 `'` 不能当定界符**，
 * 因为它是被换进去的字符（[REPLACEMENT]）。
 */
object FrameChars {
    /** 引号被换成它：显示出来仍然看得出原来有个引号，但不再是任何模板的定界符。 */
    const val REPLACEMENT: Char = '\''

    // 写成码点而不是字面量：这是防御清单，不是界面文字（门禁 tools/check-i18n.py 不允许 src/main 的字面量里有中文）。
    // 括号里的名字：「」『』 U+300C–300F，﹁﹂﹃﹄ U+FE41–FE44，｢｣ U+FF62–FF63，〝〞〟 U+301D–301F，＂ U+FF02，＇ U+FF07；
    // 另有 ASCII 的 " 和 `，低位引号 „ ‚ ‟ ‛（U+201E 201A 201F 201B，有的版本的 Unicode 类别不是引号）。
    private val EXPLICIT_QUOTES: Set<Int> = setOf(
        0x22, 0x60, 0x300C, 0x300D, 0x300E, 0x300F, 0xFE41, 0xFE42, 0xFE43, 0xFE44, 0xFF62, 0xFF63, 0x301D, 0x301E, 0x301F, 0xFF02, 0xFF07,
        0x201E, 0x201A, 0x201F, 0x201B,
    )

    // 尖括号：< > 以及全角 ＜ ＞（U+FF1C FF1E）；圆、方、花括号和各种全角括号靠 Unicode 的开闭括号类别
    private val EXPLICIT_BRACKETS: Set<Int> = setOf(0x3C, 0x3E, 0xFF1C, 0xFF1E)

    /** 引号类：换成 [REPLACEMENT]（ASCII 的 `'` 本身不算，它就是替身）。 */
    fun isQuote(cp: Int): Boolean {
        if (cp == '\''.code) return false
        if (cp in EXPLICIT_QUOTES) return true
        val type = Character.getType(cp)
        return type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || type == Character.FINAL_QUOTE_PUNCTUATION.toInt()
    }

    /** 括号类：用在括号里的第三方文字（发起者那一行的包名、名字）要把它们去掉，否则名字里能再造一对括号。 */
    fun isBracket(cp: Int): Boolean {
        if (cp in EXPLICIT_BRACKETS) return true
        val type = Character.getType(cp)
        return type == Character.START_PUNCTUATION.toInt() || type == Character.END_PUNCTUATION.toInt()
    }

    /** 任何一种定界符（引号或括号，包括 ASCII 单引号）：模板侧检查用。 */
    fun isFraming(cp: Int): Boolean = cp == '\''.code || isQuote(cp) || isBracket(cp)
}
