package org.agentos.sample.alarm.ui

import android.content.Context
import java.util.Locale

/**
 * 产生用户文案的纯函数用的“文本提供者”（计划第 7 节 R8）：函数不碰 Context，JVM 测试给一个读 `values/` / `values-en/`
 * 真实资源的实现，中英文各测一遍。Android 实现是 [ContextTexts]。
 */
interface Texts {
    val locale: Locale

    /** `getString(id, *args)`；[id] 是 `R.string.*`。 */
    fun get(id: Int, vararg args: Any): String

    /** `getQuantityString(id, quantity, *args)`；[id] 是 `R.plurals.*`。 */
    fun plural(id: Int, quantity: Int, vararg args: Any): String

    /** `getStringArray(id)`；[id] 是 `R.array.*`。 */
    fun array(id: Int): List<String>
}

class ContextTexts(private val context: Context) : Texts {
    override val locale: Locale get() = context.resources.configuration.locales[0]

    override fun get(id: Int, vararg args: Any): String = context.getString(id, *args)

    override fun plural(id: Int, quantity: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, quantity, *args)

    override fun array(id: Int): List<String> = context.resources.getStringArray(id).toList()
}
