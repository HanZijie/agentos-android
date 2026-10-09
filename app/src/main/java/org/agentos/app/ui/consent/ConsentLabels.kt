package org.agentos.app.ui.consent

import org.agentos.app.R
import org.agentos.app.agent.consent.ConsentWire.Card
import org.agentos.app.i18n.Strings
import org.agentos.runtime.i18n.FrameChars

/**
 * 确认框里“谁发起的”的文字（纯 Kotlin，ConsentLabelsTest）。App 的名字是第三方自己取的，也是不可信输入：
 * 去掉控制字符和不可见格式字符（含双向文字控制符）、折叠成单行、截断；并且**总是同时写出包名**，名字再像“系统设置”也冒充不了。
 */
object ConsentLabels {
    private const val MAX_LABEL = 40

    /** App 标签清理成安全的单行文字；清理后为空返回 null。 */
    fun cleanLabel(raw: CharSequence?): String? {
        if (raw == null) return null
        val sb = StringBuilder()
        var space = false
        var count = 0
        var i = 0
        val s = raw.toString()
        while (i < s.length && count < MAX_LABEL) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> space = sb.isNotEmpty()
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.PRIVATE_USE.toInt() ||
                    type == Character.SURROGATE.toInt() || type == Character.UNASSIGNED.toInt() -> Unit
                // 模板用引号把名字框起来：所有语言的引号去掉（和核心层的清理同一份清单，FrameChars）；名字在引号里面，括号伪造不了包名那一对
                FrameChars.isQuote(cp) -> Unit
                else -> {
                    if (space) sb.append(' ')
                    space = false
                    sb.appendCodePoint(cp)
                    count++
                }
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /**
     * 发起者一行。第三方 App 发起：`由「<App 名>」（<包名>）发起`（英文 `Requested by “<App 名>” (<包名>)`，App 名是主进程按包名解析出的标签，清理过）；
     * 解析不到名字、或不是第三方 App：用协调器给的那句（`由 com.example.app 发起` / `由电脑端发起` / `由 AgentOS 自己发起`）。
     */
    fun initiator(card: Card, appLabel: String?, strings: Strings): String {
        val pkg = card.callerPackage
        val label = cleanLabel(appLabel)
        return if (card.callerKind == "APP" && pkg != null && label != null && label != pkg) {
            strings.get(R.string.consent_initiator_app_resolved, label, pkg)
        } else {
            strings.get(card.initiatorLine)
        }
    }

    /** 倒计时文字：还剩几秒（向上取整，复数按语言）；到点后“正在按拒绝处理”。工具确认和授权提示共用。 */
    fun countdown(deadlineMillis: Long, nowMillis: Long, strings: Strings): String {
        val left = deadlineMillis - nowMillis
        if (left <= 0) return strings.get(R.string.consent_timed_out)
        val seconds = ((left + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return strings.plural(R.plurals.consent_countdown, seconds, seconds)
    }

    /** 队列提示：还有别的待确认时才有。 */
    fun queueNote(size: Int, strings: Strings): String? =
        if (size > 1) strings.plural(R.plurals.consent_queue_note, size - 1, size - 1) else null
}
