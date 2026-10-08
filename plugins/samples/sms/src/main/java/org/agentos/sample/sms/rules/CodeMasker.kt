package org.agentos.sample.sms.rules

/**
 * 验证码遮蔽（S3）。**规则不跟界面语言走**：中文和英文模板同时覆盖，所以设备在哪种语言下结果都一样。
 *
 * ## 规则
 * 1. 先找“验证码语境”的关键词：
 *    - 中文：验证码 / 校验码 / 动态码 / 动态密码 / 安全码 / 确认码 / 认证码 / 登录码 / 授权码 / 随机码 / 识别码 / 验证 / 校验 / 口令；
 *    - 英文（大小写不敏感，按单词）：code(s) / OTP / PIN / passcode / password / verification（verify …）/ 2FA / MFA / token / one-time / sign in / log in。
 * 2. 再找“候选数字串”：独立的 4–8 位数字（全角数字也算），或 3+3 / 4+4 位中间隔一个空格或连字符的写法（`123 456`、`1234-5678`）。
 *    数字串前后不能紧挨着别的数字；排除日期 / 时间 / 小数 / 电话的片段（紧跟或紧接 `-/.:：` 加数字）、
 *    金额和计量（前面是 ¥ ￥ $ € £ RMB USD …，后面是 元 块 万 年 月 日 号 点 时 分 秒 个 条 次 % USD …）。
 * 3. 离关键词近的候选才遮蔽：对每个关键词，取它**后面**间隔不超过 [WINDOW] 个字符的第一个候选；后面没有再取**前面**最近的一个
 *    （覆盖 “123456 is your verification code” 的写法）。
 * 4. 遮蔽值：数字换成 `•`，保留位数和分隔符（`••••••`、`G-••••••`、`••• •••`），结构不变。
 *
 * ## 局限（诚实写明）
 * - 只认数字验证码；字母数字混合的码（`A7K9Q2`）、用文字拼写的码、日韩等其他语言的模板不处理。
 * - 宁可多遮：同一条短信里离关键词很近的另一串 4–8 位数字（比如订单号）也可能被遮蔽。
 * - 没有关键词的纯数字（金额、日期、尾号、订单号）一律不动。
 */
object CodeMasker {
    const val MASK = '•'

    /** 候选数字串与关键词之间最多隔开这么多个字符。 */
    const val WINDOW = 30

    data class Result(val text: String, val maskedCount: Int) {
        val masked: Boolean get() = maskedCount > 0
    }

    // 下面的中文关键词和全角字符都写成 \uXXXX（遮蔽规则的数据，不是界面文字）；对应的词见文件头注释里的规则 1 和 2。
    private const val D = "[0-9\uff10-\uff19]"  // i18n-ok: verification-code detection pattern (covers Chinese and English templates, independent of the UI language), not UI text

    private val KEYWORDS = Regex(
        "\u9a8c\u8bc1\u7801|\u6821\u9a8c\u7801|\u52a8\u6001\u7801|\u52a8\u6001\u5bc6\u7801|\u5b89\u5168\u7801|\u786e\u8ba4\u7801|\u8ba4\u8bc1\u7801|\u767b\u5f55\u7801|\u6388\u6743\u7801|\u968f\u673a\u7801|\u8bc6\u522b\u7801|\u9a8c\u8bc1|\u6821\u9a8c|\u53e3\u4ee4|" +  // i18n-ok: verification-code detection pattern (covers Chinese and English templates, independent of the UI language), not UI text
            "(?<![A-Za-z])(?:codes?|otp|pin|passcode|password|passwd|verif(?:y|ied|ying|ication)|2fa|mfa|tokens?)(?![A-Za-z])|" +
            "(?<![A-Za-z])(?:one[- ]?time|sign[- ]?in|log[- ]?in)(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )

    private val CANDIDATE = Regex("(?<!$D)($D{3}[ -]$D{3}|$D{4}[ -]$D{4}|$D{4,8})(?!$D)")

    private val UNIT_AFTER = Regex(
        "^\\s?(?:\u5143|\u5757|\u4e07|\u5186|\u5e74|\u6708|\u65e5|\u53f7|\u70b9|\u65f6|\u5206|\u79d2|\u4e2a|\u6761|\u6b21|\u7b14|\u4f4d|\u4eba\u6c11\u5e01|%|\uff05|rmb|cny|usd|eur|gbp|yuan|dollars?|euros?)",  // i18n-ok: verification-code detection pattern (covers Chinese and English templates, independent of the UI language), not UI text
        RegexOption.IGNORE_CASE,
    )
    private val CURRENCY_BEFORE = Regex("(?:[\u00a5\uffe5$\u20ac\u00a3]|rmb|cny|usd|eur|gbp)\\s?$", RegexOption.IGNORE_CASE)  // i18n-ok: verification-code detection pattern (covers Chinese and English templates, independent of the UI language), not UI text
    private val SEP_DIGIT_AFTER = Regex("^[-/.:\uff1a]$D")  // i18n-ok: verification-code detection pattern (covers Chinese and English templates, independent of the UI language), not UI text
    private val DIGIT_SEP_BEFORE = Regex("$D[-/.:\uff1a]$")  // i18n-ok: verification-code detection pattern (covers Chinese and English templates, independent of the UI language), not UI text

    fun mask(body: String): Result {
        if (body.isEmpty()) return Result(body, 0)
        val keywords = KEYWORDS.findAll(body).map { it.range }.toList()
        if (keywords.isEmpty()) return Result(body, 0)
        val candidates = CANDIDATE.findAll(body).map { it.groups[1]!!.range }.filter { isCodeLike(body, it) }.toList()
        if (candidates.isEmpty()) return Result(body, 0)

        val chosen = LinkedHashSet<IntRange>()
        for (kw in keywords) {
            val after = candidates.firstOrNull { it.first > kw.last && it.first - kw.last - 1 <= WINDOW }
            if (after != null) {
                chosen += after
                continue
            }
            val before = candidates.lastOrNull { it.last < kw.first && kw.first - it.last - 1 <= WINDOW }
            if (before != null) chosen += before
        }
        if (chosen.isEmpty()) return Result(body, 0)

        val out = StringBuilder(body)
        for (range in chosen) {
            for (i in range) if (isDigit(out[i])) out.setCharAt(i, MASK)
        }
        return Result(out.toString(), chosen.size)
    }

    /** 这条正文里有没有会被遮蔽的验证码（不改正文）。 */
    fun containsCode(body: String): Boolean = mask(body).masked

    private fun isCodeLike(body: String, range: IntRange): Boolean {
        val after = body.substring(range.last + 1)
        if (SEP_DIGIT_AFTER.containsMatchIn(after)) return false
        if (UNIT_AFTER.containsMatchIn(after)) return false
        val before = body.substring(0, range.first)
        if (DIGIT_SEP_BEFORE.containsMatchIn(before)) return false
        if (CURRENCY_BEFORE.containsMatchIn(before)) return false
        return true
    }

    private fun isDigit(c: Char) = c in '0'..'9' || c in '\uFF10'..'\uFF19'
}
