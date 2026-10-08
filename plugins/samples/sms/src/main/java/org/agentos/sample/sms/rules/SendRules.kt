package org.agentos.sample.sms.rules

import kotlinx.serialization.json.JsonObject

/**
 * 发送内容的规则（S1）。全部是纯函数，返回 null 表示通过，否则是给模型看的英文原因（R2：不本地化）。
 */
object SendRules {
    /** `text` 的字符数上限（Unicode 码点）。 */
    const val MAX_TEXT_CHARS = 500

    /**
     * AgentOS 确认框一次最多显示这么多个字符的调用参数（`ConsentConfig.maxArgumentChars` 的默认值，参数以紧凑 JSON 的形式显示）。
     * 超过的部分用户在确认框里看不到，所以 `sms_send` 在这里就拒绝，让模型缩短正文——用户批准的必须是他完整读过的内容。
     */
    const val CONSENT_VISIBLE_CHARS = 600

    fun checkText(text: String?): String? {
        if (text == null) return "Missing required parameter 'text' (the message body)."
        if (text.isBlank()) return "The message text is empty."
        val chars = text.codePointCount(0, text.length)
        if (chars > MAX_TEXT_CHARS) {
            return "The message is too long: $chars characters, the limit is $MAX_TEXT_CHARS. Shorten it; do not split one request into several messages."
        }
        invisibleCharIn(text)?.let {
            return "The message contains an invisible or control character (U+%04X). Remove it: the confirmation screen must show exactly what is sent.".format(it)
        }
        return null
    }

    /**
     * 确认框会丢掉的字符：控制字符（换行、回车、制表除外）、不可见格式字符（零宽、双向控制符、软连字符 …，ZWJ / ZWNJ 除外，
     * 表情序列要用）、私用区、未分配、孤立代理。含这些字符的正文，用户看到的和实际发出的会不一样，所以拒绝。
     */
    fun invisibleCharIn(text: String): Int? {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\n'.code || cp == '\r'.code || cp == '\t'.code || cp == 0x200C || cp == 0x200D) continue
            val type = Character.getType(cp)
            if (Character.isISOControl(cp) ||
                type == Character.FORMAT.toInt() || type == Character.PRIVATE_USE.toInt() ||
                type == Character.UNASSIGNED.toInt() || type == Character.SURROGATE.toInt()
            ) {
                return cp
            }
        }
        return null
    }

    /** 调用参数的紧凑 JSON 是否能在确认框里完整显示。 */
    fun checkFitsConsent(arguments: JsonObject, visibleChars: Int = CONSENT_VISIBLE_CHARS): String? {
        val shown = arguments.toString()
        val chars = shown.codePointCount(0, shown.length)
        if (chars <= visibleChars) return null
        return "The call arguments take $chars characters once encoded, but the confirmation screen shows at most $visibleChars, " +
            "so the user could not read the whole message before approving. Shorten the text (quotes, backslashes and line breaks count double)."
    }
}

/**
 * 频率上限：[windowMillis] 内最多 [limit] 条。纯函数，输入是窗口内已有提交的时间戳。
 */
object RateLimit {
    const val DEFAULT_LIMIT = 5
    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 30
    const val WINDOW_MILLIS = 10 * 60 * 1000L

    sealed interface Decision {
        data object Allowed : Decision
        data class Blocked(val retryAfterSeconds: Long, val used: Int, val limit: Int) : Decision
    }

    fun check(timestamps: List<Long>, nowMillis: Long, limit: Int, windowMillis: Long = WINDOW_MILLIS): Decision {
        val inWindow = timestamps.filter { it > nowMillis - windowMillis && it <= nowMillis }.sorted()
        if (inWindow.size < limit) return Decision.Allowed
        // 要等最早的那些滑出窗口，才会空出一个名额
        val freeing = inWindow[inWindow.size - limit]
        val retry = ((freeing + windowMillis - nowMillis + 999L) / 1000L).coerceAtLeast(1L)
        return Decision.Blocked(retry, inWindow.size, limit)
    }

    fun clampLimit(value: Int): Int = value.coerceIn(MIN_LIMIT, MAX_LIMIT)
}

/** 相同收件人 + 相同正文的短时去重。 */
object Dedupe {
    const val WINDOW_MILLIS = 2 * 60 * 1000L

    /** 去重键：号码只比对规范化后的数字（`+86 138-0013-8000` 与 `+8613800138000` 相同），正文逐字比较（只去掉首尾空白）。 */
    fun key(normalizedTo: String, text: String): String = AddressMatcher.canonical(normalizedTo) + "\u0000" + text.trim()
}

/**
 * 地址（号码）匹配。系统短信库里的地址格式不统一（`+8613800138000`、`13800138000`、`138 0013 8000`），
 * 数字号码按末尾 [SUFFIX_DIGITS] 位比较；服务号码和字母发件人（`ICBC`）要求整体相同。
 */
object AddressMatcher {
    private const val SUFFIX_DIGITS = 10

    fun canonical(address: String): String {
        val trimmed = address.trim()
        if (trimmed.any { it.isLetter() }) return trimmed.lowercase()
        val digits = trimmed.filter { it in '0'..'9' }
        return digits.takeLast(SUFFIX_DIGITS)
    }

    fun matches(stored: String, query: String): Boolean {
        val a = stored.trim()
        val b = query.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        val aAlpha = a.any { it.isLetter() }
        val bAlpha = b.any { it.isLetter() }
        if (aAlpha || bAlpha) return aAlpha && bAlpha && a.equals(b, ignoreCase = true)
        val da = a.filter { it in '0'..'9' }
        val db = b.filter { it in '0'..'9' }
        if (da.isEmpty() || db.isEmpty()) return false
        if (da.length < 7 || db.length < 7) return da == db // 短号整体相同
        val n = minOf(da.length, db.length, SUFFIX_DIGITS)
        return da.takeLast(n) == db.takeLast(n)
    }
}
