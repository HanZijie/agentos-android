package org.agentos.runtime.scheduler

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.agentos.runtime.ports.ContentPart
import org.agentos.runtime.ports.TurnInput

/**
 * 一轮输入（ACP 的 ContentBlock 数组，原样存在 tasks.input）→ 交给 Pi 的 [TurnInput]。
 *
 * - text：原样；
 * - image：交给 Pi（ACP 层只在声明了图片能力时才接受图片，W4）；
 * - resource_link / resource：Android 上没有工作目录语义，转成一段文字说明链接或嵌入的文本内容；
 * - 其他类型：忽略（ACP 层已经拒绝了不支持的类型）。
 */
object PromptContent {

    fun toTurnInput(blocks: JsonArray): TurnInput {
        val text = StringBuilder()
        val images = mutableListOf<ContentPart.Image>()
        fun para(s: String) {
            if (s.isEmpty()) return
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(s)
        }
        for (el in blocks) {
            val b = el as? JsonObject ?: continue
            when (b.str("type")) {
                "text" -> para(b.str("text").orEmpty())
                "image" -> {
                    val data = b.str("data")
                    val mime = b.str("mimeType")
                    if (data != null && mime != null) images += ContentPart.Image(data, mime)
                }
                "resource_link" -> para("[resource] ${b.str("name") ?: b.str("title") ?: ""} <${b.str("uri").orEmpty()}>".replace("  ", " "))
                "resource" -> {
                    val r = b["resource"] as? JsonObject ?: continue
                    val uri = r.str("uri").orEmpty()
                    val body = r.str("text")
                    para(if (body != null) "[resource <$uri>]\n$body" else "[resource <$uri>]")
                }
            }
        }
        return TurnInput(text.toString(), images)
    }

    /** 给会话选择和诊断用的纯文字（只取 text 块）。 */
    fun plainText(blocks: JsonArray, limit: Int = 8_000): String =
        blocks.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }
            .joinToString("\n").trim().take(limit)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
