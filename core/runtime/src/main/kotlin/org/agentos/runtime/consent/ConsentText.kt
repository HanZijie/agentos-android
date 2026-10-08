package org.agentos.runtime.consent

import org.agentos.runtime.broker.RiskPolicy
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.ToolRisk

/**
 * 确认界面上的文字：**第三方文字的清理**和**界面自己的文案**。
 *
 * 清理规则（[singleLine]、[block]）：
 * - 去掉控制字符、不可见格式字符（Unicode 的 Cf：零宽字符、BOM，以及**双向文字控制符**：LRE/RLE/PDF/LRO/RLO、LRI/RLI/FSI/PDI、LRM/RLM、ALM），
 *   代理对不完整的字符、私用区字符；这些字符可以让文字显示的顺序和实际不同（“删除”显示成“允许”），或者让一行看起来像另一行；
 * - 各种空白（含 U+00A0、U+3000、U+2028、U+2029）统一成普通空格或换行；
 * - [singleLine]：折叠成**单行**（工具显示名、来源、发起者），并去掉「」『』（界面用它们把第三方文字框起来）；
 * - [block]：参数摘要允许换行，但连续的空行折叠成一个、行首行尾空白去掉；
 * - 按字符数（Unicode 码点）截断，不会切开一个字符。
 */
internal object ConsentText {

    fun singleLine(s: String?, max: Int): String {
        if (s == null) return ""
        val sb = StringBuilder(minOf(s.length, max + 1))
        var space = false
        var count = 0
        var i = 0
        while (i < s.length && count <= max) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isBlank(cp) || cp == '\n'.code -> space = sb.isNotEmpty()
                isDropped(cp) -> Unit
                cp == '「'.code || cp == '」'.code || cp == '『'.code || cp == '』'.code -> {
                    if (space) sb.append(' ')
                    space = false
                    sb.append('\'')
                    count++
                }
                else -> {
                    if (space) sb.append(' ')
                    space = false
                    sb.appendCodePoint(cp)
                    count++
                }
            }
        }
        return cut(sb.toString().trim(), max)
    }

    /** 允许换行的文字；返回清理后的文字和是否被截断（超过 [max] 个字符）。 */
    fun block(s: String?, max: Int): Pair<String, Boolean> {
        if (s == null) return "" to false
        val lines = ArrayList<String>()
        val cur = StringBuilder()
        var emptyRun = 0
        fun flush() {
            val line = cur.toString().trim()
            cur.setLength(0)
            if (line.isEmpty()) {
                emptyRun++
                if (emptyRun == 1 && lines.isNotEmpty()) lines += ""
            } else {
                emptyRun = 0
                lines += line
            }
        }
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\n'.code || cp == '\r'.code || cp == 0x2028 || cp == 0x2029 -> {
                    if (cp == '\r'.code && i < s.length && s[i] == '\n') i++
                    flush()
                }
                isBlank(cp) -> cur.append(' ')
                isDropped(cp) -> Unit
                else -> cur.appendCodePoint(cp)
            }
        }
        flush()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        val joined = lines.joinToString("\n")
        val cp = joined.codePointCount(0, joined.length)
        return if (cp > max) cut(joined, max) to true else joined to false
    }

    private fun cut(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        val end = s.offsetByCodePoints(0, max - 1)
        return s.substring(0, end).trimEnd() + "…"
    }

    private fun isBlank(cp: Int): Boolean =
        cp == ' '.code || cp == '\t'.code || cp == 0x00A0 || cp == 0x3000 || cp == 0x2028 || cp == 0x2029 || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    /** 控制字符、格式字符（含双向控制符）、孤立代理、私用区、未分配。 */
    private fun isDropped(cp: Int): Boolean {
        val type = Character.getType(cp)
        return Character.isISOControl(cp) ||
            type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
            type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt()
    }

    // ------------------------------------------------------------------ 界面自己的文案

    fun title(displayName: String) = "要允许「$displayName」吗？"

    fun initiatorLine(caller: CallerIdentity, max: Int): String = when (caller.kind) {
        CallerKind.APP -> "由 ${singleLine(caller.label, max).ifEmpty { "未知应用" }} 发起"
        CallerKind.DESKTOP -> "由电脑端发起"
        CallerKind.SELF -> "由 AgentOS 自己发起"
        CallerKind.SYSTEM -> "由 AgentOS 运行时发起"
    }

    fun sourceLine(plugin: String, server: String, max: Int): String =
        "来自插件「${singleLine(plugin, max)}」 · 服务器「${singleLine(server, max)}」"

    fun severity(risk: ToolRisk) = when (risk) {
        ToolRisk.READ -> ConsentSeverity.NORMAL
        ToolRisk.WRITE -> ConsentSeverity.ELEVATED
        ToolRisk.HIGH -> ConsentSeverity.CRITICAL
    }

    fun riskLabel(risk: ToolRisk) = when (risk) {
        ToolRisk.READ -> "只读"
        ToolRisk.WRITE -> "会修改数据"
        ToolRisk.HIGH -> "高风险"
    }

    fun riskDescription(risk: ToolRisk) = when (risk) {
        ToolRisk.READ -> "这个操作只读取数据，不会修改。"
        ToolRisk.WRITE -> "这个操作会修改手机上的数据。"
        ToolRisk.HIGH -> "这是高风险操作，可能不可恢复（例如永久删除数据）。只有在你清楚它会做什么时才允许。"
    }

    fun optionLabel(choice: ConsentChoice) = when (choice) {
        ConsentChoice.ALLOW_ONCE -> "允许一次"
        ConsentChoice.ALLOW_FOR_SESSION -> "本次对话内不再询问"
        ConsentChoice.ALWAYS_ALLOW -> "始终允许这个工具"
        ConsentChoice.DENY -> "拒绝"
    }

    /**
     * 这条请求能选哪些：高风险只有“允许一次”和“拒绝”；“始终允许”需要有来源插件、写级、并且写回通道可用。
     * 调用方是谁在这里**不看**：所有调用方一视同仁（docs/third-party-acp.md 4.4）。要对某类调用方拿掉“本会话内不再询问”或“始终允许”，
     * 由 `CallerPolicy` 在生成请求时把 [ConsentRequest.rememberable] / [ConsentRequest.alwaysAllowOffered] 设为 false。
     */
    fun allowedChoices(request: ConsentRequest, alwaysAvailable: Boolean): List<ConsentChoice> = buildList {
        add(ConsentChoice.ALLOW_ONCE)
        if (request.rememberable && RiskPolicy.maySessionRemember(request.risk)) add(ConsentChoice.ALLOW_FOR_SESSION)
        if (alwaysAvailable && request.alwaysAllowOffered && request.source != null && request.risk == ToolRisk.WRITE && RiskPolicy.mayAlwaysAllow(request.risk)) add(ConsentChoice.ALWAYS_ALLOW)
        add(ConsentChoice.DENY)
    }
}
