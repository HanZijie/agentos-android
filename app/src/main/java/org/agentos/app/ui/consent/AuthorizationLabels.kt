package org.agentos.app.ui.consent

import org.agentos.app.agent.consent.ConsentWire.AuthRequest

/**
 * 第三方 App 授权提示的文字（纯 Kotlin，AuthorizationLabelsTest）。App 名和包名都是第三方自己决定的，不可信：
 * 名字清理成安全单行（[ConsentLabels.cleanLabel]），**包名和签名摘要总是同时写出**，名字再像“系统设置”也冒充不了。
 * 全部是纯文本，界面只用 setText(String)。
 */
object AuthorizationLabels {
    const val ALLOW = "允许"
    const val DENY = "拒绝"

    /** 一句话说明：授权的实质，和“工具每次都会再问”。 */
    const val EXPLANATION = "它可以让 AgentOS 替你回答问题；它用到的工具，每次都会再问你。"

    const val SIGNATURE_CHANGED = "这个 App 的签名和你之前处理它时不同。只有确认它来自你信任的来源时才允许。"

    private const val DIGEST_SHOWN = 12

    /** 给人看的名字：清理后的 App 名；没有（解析不到、清理后为空、与包名相同）就用包名。 */
    fun displayName(req: AuthRequest, resolvedLabel: String?): String {
        val label = ConsentLabels.cleanLabel(req.appLabel ?: resolvedLabel)
        return if (label == null || label == req.packageName) safePackage(req.packageName) else label
    }

    fun title(req: AuthRequest, resolvedLabel: String?): String = "允许「${displayName(req, resolvedLabel)}」使用 AgentOS 吗？"

    /**
     * 包名：Android 包名只有 `[A-Za-z0-9_.]`，别的字符一律不显示并写明（不截断：截断的包名会让人认错 App）。
     */
    fun packageLine(req: AuthRequest): String = "包名：${safePackage(req.packageName)}"

    fun safePackage(pkg: String): String {
        val ok = pkg.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.' }
        return if (ok == pkg && ok.isNotEmpty()) ok else (ok.ifEmpty { "（空）" } + "（包名里有不合法的字符，已隐藏）")
    }

    /** 签名摘要前 12 位，四位一组；没有摘要写明，不留空。 */
    fun digestLine(digest: String?): String = "签名：${digestHead(digest)}"

    fun digestHead(digest: String?): String {
        val hex = digest.orEmpty().lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(DIGEST_SHOWN)
        return if (hex.isEmpty()) "（无法读取）" else hex.chunked(4).joinToString(" ") + "…"
    }

    fun signatureChangedLine(req: AuthRequest): String? = if (req.signatureChanged) SIGNATURE_CHANGED else null

    /** 倒计时：到点按拒绝（注册表计时，这里只显示）。 */
    fun countdown(deadlineMillis: Long, nowMillis: Long): String {
        val left = deadlineMillis - nowMillis
        return if (left <= 0) "已超时，正在按拒绝处理…" else "还剩 ${(left + 999) / 1000} 秒；不回答将按拒绝处理"
    }

    /** 通知的一行摘要。 */
    fun notificationText(req: AuthRequest): String = "${safePackage(req.packageName)} · ${digestLine(req.signingDigest)}"
}
