package org.agentos.sample.notes.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** 与 SDK 无关的工具注解；McpBinderService 那一层再映射到 SDK 的 McpToolAnnotations。 */
data class ToolAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

/** 工具返回值：成功时是一个 JSON 值（对象），失败时是一句话原因。 */
sealed interface ToolOutput {
    data class Ok(val value: JsonElement) : ToolOutput
    data class Error(val message: String) : ToolOutput
}

/** 一个 MCP 工具的定义：名字、给模型看的英文描述、输入 schema、注解、处理函数。 */
class ToolDefinition(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: ToolAnnotations,
    val handler: suspend (JsonObject) -> ToolOutput,
)

/** 参数不合法（缺必填、类型不对、超范围……）。消息就是返回给模型的那句话。 */
class ToolArgumentException(message: String) : Exception(message)

/** 从 arguments 里按类型取参数。宽松接受字符串形式的数字 / 布尔（模型常这样给），其余不合法就报错。 */
class Args(private val json: JsonObject) {
    private fun raw(name: String): JsonElement? = json[name]?.takeUnless { it is JsonNull }

    fun has(name: String): Boolean = raw(name) != null

    fun string(name: String): String =
        optString(name) ?: throw ToolArgumentException("Missing required argument: $name")

    fun optString(name: String): String? {
        val v = raw(name) ?: return null
        if (v !is JsonPrimitive) throw ToolArgumentException("$name must be a string.")
        return v.contentOrNull
    }

    fun optBoolean(name: String): Boolean? {
        val v = raw(name) ?: return null
        if (v !is JsonPrimitive) throw ToolArgumentException("$name must be a boolean.")
        return v.booleanOrNull
            ?: when (v.contentOrNull?.trim()?.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw ToolArgumentException("$name must be a boolean (true or false).")
            }
    }

    /** 整数；超过 [max] 的按 [max] 处理，小于 [min] 或不是整数则报错。 */
    fun optInt(name: String, min: Int, max: Int): Int? {
        val v = raw(name) ?: return null
        if (v !is JsonPrimitive) throw ToolArgumentException("$name must be an integer.")
        val n = v.longOrNull
            ?: v.doubleOrNull?.takeIf { it == Math.floor(it) && !it.isInfinite() }?.toLong()
            ?: v.contentOrNull?.trim()?.toLongOrNull()
            ?: throw ToolArgumentException("$name must be an integer.")
        if (n < min) throw ToolArgumentException("$name must be at least $min.")
        return minOf(n, max.toLong()).toInt()
    }

    fun optStringList(name: String): List<String>? {
        val v = raw(name) ?: return null
        val array = v as? kotlinx.serialization.json.JsonArray
            ?: throw ToolArgumentException("$name must be an array of strings.")
        return array.map { item ->
            val p = item as? JsonPrimitive
            if (p == null || p is JsonNull || !p.isString) throw ToolArgumentException("$name must be an array of strings.")
            p.content
        }
    }
}
