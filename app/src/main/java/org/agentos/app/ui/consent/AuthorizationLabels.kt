package org.agentos.app.ui.consent

import org.agentos.app.R
import org.agentos.app.agent.consent.ConsentWire.AuthRequest
import org.agentos.app.i18n.Strings

/**
 * 第三方 App 授权提示的文字（纯 Kotlin，AuthorizationLogicTest）。App 名和包名都是第三方自己决定的，不可信：
 * 名字清理成安全单行（[ConsentLabels.cleanLabel]，所有语言的引号都去掉），**包名和签名摘要总是同时写出**，名字再像“系统设置”也冒充不了。
 * 全部是纯文本，界面只用 setText(String)。文字来自 [Strings]（`values/strings_p1.xml` 的 `auth_*`），所以每个函数都带一个 [Strings]。
 */
object AuthorizationLabels {
    private const val DIGEST_SHOWN = 12

    /** 一句话说明：授权的实质，和“工具每次都会再问”。 */
    fun explanation(strings: Strings): String = strings.get(R.string.auth_explanation)

    /** 给人看的名字：清理后的 App 名；没有（解析不到、清理后为空、与包名相同）就用包名。 */
    fun displayName(req: AuthRequest, resolvedLabel: String?, strings: Strings): String {
        val label = ConsentLabels.cleanLabel(req.appLabel ?: resolvedLabel)
        return if (label == null || label == req.packageName) safePackage(req.packageName, strings) else label
    }

    fun title(req: AuthRequest, resolvedLabel: String?, strings: Strings): String =
        strings.get(R.string.auth_title, displayName(req, resolvedLabel, strings))

    /** 包名那一行（授权提示）。 */
    fun packageLine(req: AuthRequest, strings: Strings): String = packageLine(req.packageName, strings)

    /** 包名那一行（授权提示、已授权 App 页共用）。 */
    fun packageLine(packageName: String, strings: Strings): String = strings.get(R.string.auth_package_line, safePackage(packageName, strings))

    /**
     * 包名里只留 Android 包名允许的字符 `[A-Za-z0-9_.]`，不带任何说明、不依赖语言（用作名字的退路，如已授权 App 页的标题；
     * 说明“有字符被隐藏”的是下一行的 [packageLine]）。不截断：截断的包名会让人认错 App。
     */
    fun safePackage(pkg: String): String = pkg.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.' }

    /** 同 [safePackage]，但有字符被隐藏（或为空）时写明：别的字符一律不显示。 */
    fun safePackage(pkg: String, strings: Strings): String {
        val ok = safePackage(pkg)
        return if (ok == pkg && ok.isNotEmpty()) ok else strings.get(R.string.auth_package_hidden, ok.ifEmpty { strings.get(R.string.auth_package_empty) })
    }

    /** 签名摘要前 12 位，四位一组加省略号；读不出（空、不是十六进制）为 null。不依赖语言。 */
    fun digestHex(digest: String?): String? {
        val hex = digest.orEmpty().lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(DIGEST_SHOWN)
        return if (hex.isEmpty()) null else hex.chunked(4).joinToString(" ") + "…"
    }

    /** 签名那一行；没有摘要写明，不留空。 */
    fun digestLine(digest: String?, strings: Strings): String = strings.get(R.string.auth_digest_line, digestHead(digest, strings))

    fun digestHead(digest: String?, strings: Strings): String = digestHex(digest) ?: strings.get(R.string.auth_digest_unreadable)

    fun signatureChangedLine(req: AuthRequest, strings: Strings): String? =
        if (req.signatureChanged) strings.get(R.string.auth_signature_changed) else null

    /** 倒计时：到点按拒绝（注册表计时，这里只显示）。同工具确认。 */
    fun countdown(deadlineMillis: Long, nowMillis: Long, strings: Strings): String = ConsentLabels.countdown(deadlineMillis, nowMillis, strings)

    /** 通知的一行摘要。 */
    fun notificationText(req: AuthRequest, strings: Strings): String =
        strings.get(R.string.auth_notification_text, safePackage(req.packageName, strings), digestLine(req.signingDigest, strings))
}
