package org.agentos.app.i18n

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import org.agentos.runtime.i18n.MessageRef

/**
 * 界面文字的来源（docs/next-apps-plan.md 7.3）。**产生用户文案的纯函数接收它，不碰 Context、不写中文字面量**：
 * 运行时用 [AndroidStrings]（`Resources`，跟随系统或“应用语言”），JVM 测试用中、英两个实现（测试目录的 `ResStrings`，读真实的
 * `values/strings.xml` 和 `values-en/strings.xml`，所以资源改了测试会发现）。
 *
 * ```
 * fun title(req: AuthRequest, strings: Strings) = strings.get(R.string.auth_title, name)    // app 自己的文案：R.string + 参数
 * fun line(view: ConsentView, strings: Strings) = strings.get(view.initiatorLine)           // 核心层给的：key + 参数
 * fun seconds(n: Int, strings: Strings) = strings.plural(R.plurals.consent_countdown, n, n)  // 复数
 * ```
 *
 * 参数当**纯文本**填进 `%1$s`、`%2$d`…，不解析、不当格式串；第三方文字必须先清理（核心层的 `ConsentText`、app 层的 `ConsentLabels.cleanLabel`）。
 */
interface Strings {
    /** 字符串资源（`R.string.*`）[id] 的文字，[args] 依次填进 `%1$s`、`%2$d`…；没有参数时原样返回（和 `Resources.getString(id)` 一样不处理 `%%`）。 */
    fun get(id: Int, vararg args: Any): String

    /** 复数资源（`R.plurals.*`）[id]：按 [quantity] 选 one / other（英文）或 other（中文），[args] 同 [get]（数量本身要自己再传一次）。 */
    fun plural(id: Int, quantity: Int, vararg args: Any): String

    /**
     * 核心层给的一句话（key + 参数，见 [CoreMessages]）。key 不认识时显示 key 本身：不会崩，也一眼看得出，
     * `CoreMessagesTest` 保证核心层产出的 key 都有中英文模板。参数不够时补空串。
     */
    fun get(ref: MessageRef): String {
        val entry = CoreMessages.entry(ref.key) ?: return ref.key
        val args = ref.args + List((entry.arity - ref.args.size).coerceAtLeast(0)) { "" }
        return get(entry.id, *args.toTypedArray())
    }
}

/** 运行时实现：当前资源配置的语言。每次都从 [context] 取 `Resources`，应用语言切换后不用重建。 */
class AndroidStrings(private val context: Context) : Strings {
    override fun get(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.resources.getString(id) else context.resources.getString(id, *args)

    override fun plural(id: Int, quantity: Int, vararg args: Any): String =
        if (args.isEmpty()) context.resources.getQuantityString(id, quantity) else context.resources.getQuantityString(id, quantity, *args)

    companion object {
        /** 固定语言的实现（不跟随界面）：debug 测试入口要输出和界面语言无关的文字时用。 */
        fun forLocale(context: Context, locale: Locale): Strings =
            AndroidStrings(context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }))
    }
}
