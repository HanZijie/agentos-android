package org.agentos.app.settings.plugins

/**
 * 签名摘要在界面上的样子：前 12 位十六进制，四位一组，不依赖语言（插件页和已授权 App 页共用）。
 * 摘要来自别的进程或第三方 App，所以先把非十六进制的字符丢掉。
 */
object Digest {
    private const val SHOWN = 12

    /** `ab12 cd34 ef56`；读不出（空、没有十六进制字符）为 null。 */
    fun hex(digest: String?): String? {
        val hex = digest.orEmpty().lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(SHOWN)
        return if (hex.isEmpty()) null else hex.chunked(4).joinToString(" ")
    }

    /** `ab12 cd34 ef56…`（省略号表示只显示了前 12 位）；读不出为 null。 */
    fun head(digest: String?): String? = hex(digest)?.let { "$it…" }
}
