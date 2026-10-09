package org.agentos.runtime.i18n

/**
 * 核心层给用户看的一句话，**不是文字**：一个文案 key 加参数。核心层是纯 Kotlin / JVM，用不了 Android 资源，也不该替界面选语言；
 * app 层按 key 找到 `values/strings.xml`（中文）或 `values-en/strings.xml`（英文）里的模板，把 [args] 依次填进去
 * （`org.agentos.app.i18n.CoreMessages`）。
 *
 * - [key]：和 app 资源名相同的小写下划线名（如 `consent_title`），常量集中在使用它的子系统里（确认链：[org.agentos.runtime.consent.ConsentMessages]）。
 * - [args]：**已经清理过的**字符串，按顺序对应模板里的 `%1$s`、`%2$s`…。第三方文字（工具名、插件名、包名）在这里已经过 `ConsentText.singleLine`，
 *   所以模板用什么引号、括号把它们框起来都伪造不了结尾（见 `FrameChars`）。模板和参数数量对不上时 app 层补空串，不崩。
 * - 数据类：测试直接比较 `MessageRef("consent_title", listOf("note_create"))`，不比较任何一种语言的整句。
 */
data class MessageRef(val key: String, val args: List<String> = emptyList()) {
    init {
        require(key.isNotEmpty()) { "a message needs a key" }
    }

    companion object {
        fun of(key: String, vararg args: String): MessageRef = MessageRef(key, args.toList())
    }
}
