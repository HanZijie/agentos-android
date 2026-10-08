package org.agentos.sample.sms.rules

/**
 * 收件人解析与短号判断（S1）。纯函数，JVM 可测；规则可配置（[ShortNumberRules]），设置页可以显式放行短号。
 *
 * 为什么默认拒绝短号：发给 10086、106xxxx、95xxx 这类服务号码，可能是付费短信或订阅，而且一旦发出无法撤回。
 */
object Recipient {
    sealed interface Parsed {
        /** [number] 是规范化后的号码：可选的开头 `+`，后面全是数字。 */
        data class Ok(val number: String) : Parsed

        /** [reason] 是给模型看的英文短语。 */
        data class Bad(val reason: String) : Parsed
    }

    const val MIN_DIGITS = 3
    const val MAX_DIGITS = 15

    private val MULTI_SEPARATORS = charArrayOf(',', ';', '，', '；', '、', '\n', '\r', '/', '|')

    fun parse(raw: String?): Parsed {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Parsed.Bad("Missing recipient: pass the phone number in 'to'.")
        if (text.indexOfAny(MULTI_SEPARATORS) >= 0) {
            return Parsed.Bad("Only one recipient per call is allowed; send separate messages one at a time.")
        }
        val sb = StringBuilder()
        for (ch in text) {
            val digit = toAsciiDigit(ch)
            when {
                digit != null -> sb.append(digit)
                ch == '+' && sb.isEmpty() -> sb.append('+')
                ch == ' ' || ch == '-' || ch == '.' || ch == '(' || ch == ')' || ch == '\u00A0' || ch == '　' -> Unit
                else -> return Parsed.Bad("Invalid recipient: use a phone number with digits only (optionally starting with +), not names or text.")
            }
        }
        var number = sb.toString()
        // 国际冠字 00 → +（"0086138…" → "+86138…"）
        if (!number.startsWith("+") && number.startsWith("00") && number.length > 2 + MIN_DIGITS) number = "+" + number.substring(2)
        val digits = number.removePrefix("+").length
        if (digits < MIN_DIGITS || digits > MAX_DIGITS) {
            return Parsed.Bad("Invalid recipient: a phone number has $MIN_DIGITS to $MAX_DIGITS digits (got $digits). Pass exactly one number.")
        }
        return Parsed.Ok(number)
    }

    private fun toAsciiDigit(ch: Char): Char? = when (ch) {
        in '0'..'9' -> ch
        in '０'..'９' -> '0' + (ch - '０')
        else -> null
    }
}

/**
 * 短号规则。默认：
 * - 不带 `+` 的号码，数字少于 [minFullDigits]（默认 7）位，算短号（各国的 3–6 位服务 / 短码）；
 * - 国内规则：去掉 `+86` 后以 [serviceNationalPrefixes]（默认 `10`、`95`、`96`）开头的算服务号码（10086、10010、1069xxxx、95588 …）。
 *   国内手机号以 13–19 开头，不会撞上这些前缀。`95`、`96` 只在**国内号码**（带 +86）或不超过 [shortCapDigits] 位时判定，
 *   避免把美国 951 区号的十位号码误判成服务号码。
 */
data class ShortNumberRules(
    val minFullDigits: Int = 7,
    val serviceNationalPrefixes: List<String> = listOf("10", "95", "96"),
    val shortCapDigits: Int = 8,
    val cnCountryPrefix: String = "+86",
) {
    fun isShort(normalized: String): Boolean {
        val digits = normalized.removePrefix("+")
        if (digits.isEmpty()) return true
        if (!normalized.startsWith("+") && digits.length < minFullDigits) return true
        val national = when {
            normalized.startsWith(cnCountryPrefix) -> normalized.removePrefix(cnCountryPrefix)
            normalized.startsWith("+") -> return false // 其他国家的完整号码
            else -> digits
        }
        val cn = normalized.startsWith(cnCountryPrefix)
        // 不带 + 的号码：只有不超过 shortCapDigits 位的才按前缀判；带 +86 的按前缀判
        val applies = cn || national.length <= shortCapDigits || national.startsWith("106")
        return applies && serviceNationalPrefixes.any { national.startsWith(it) } && !looksLikeCnMobile(national)
    }

    private fun looksLikeCnMobile(national: String) =
        national.length == 11 && national[0] == '1' && national[1] in '3'..'9'
}
