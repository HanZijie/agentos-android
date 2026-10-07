package org.agentos.sample.calendar.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 与 SDK 无关的工具定义层：只依赖 kotlinx-serialization-json 和本 App 的仓库（docs/sample-apps.md 第 2 节）。 */
data class ToolAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

class ToolOutput private constructor(val isError: Boolean, val structured: JsonObject?, val text: String) {
    companion object {
        private val compact = Json { encodeDefaults = true }

        /** 成功：content 放紧凑 JSON 文本，同时 structuredContent 放同一个对象。 */
        fun json(value: JsonObject): ToolOutput = ToolOutput(false, value, compact.encodeToString(JsonObject.serializer(), value))

        /** 失败：isError = true，一句话原因。 */
        fun error(message: String): ToolOutput = ToolOutput(true, null, message)
    }
}

class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: ToolAnnotations,
    val handler: suspend (JsonObject) -> ToolOutput,
)

/** 参数不合法：消息会原样作为 isError 结果返回给模型。 */
class ToolError(message: String) : Exception(message)

/** 读参数的小工具：宽松接受模型常见的“数字写成字符串”，类型不对时给出明确的错误。 */
class Args(private val obj: JsonObject) {
    fun has(name: String): Boolean = obj.containsKey(name)
    fun isNull(name: String): Boolean = obj[name] is JsonNull

    private fun value(name: String): JsonElement? = obj[name]?.takeUnless { it is JsonNull }

    fun string(name: String, required: Boolean = false): String? {
        val v = value(name)
        if (v == null) {
            if (required) throw ToolError("Missing required parameter: $name")
            return null
        }
        val p = v as? JsonPrimitive ?: throw ToolError("Parameter '$name' must be a string")
        val s = p.content
        if (required && s.isBlank()) throw ToolError("Parameter '$name' must not be empty")
        return s
    }

    fun int(name: String, required: Boolean = false): Int? {
        val v = value(name)
        if (v == null) {
            if (required) throw ToolError("Missing required parameter: $name")
            return null
        }
        val p = v as? JsonPrimitive ?: throw ToolError("Parameter '$name' must be an integer")
        return p.intOrNull ?: p.contentOrNull?.trim()?.toIntOrNull() ?: throw ToolError("Parameter '$name' must be an integer, got ${p.content}")
    }

    fun bool(name: String): Boolean? {
        val v = value(name) ?: return null
        val p = v as? JsonPrimitive ?: throw ToolError("Parameter '$name' must be a boolean")
        return p.booleanOrNull ?: when (p.contentOrNull?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw ToolError("Parameter '$name' must be true or false, got ${p.content}")
        }
    }

    fun intList(name: String): List<Int>? {
        val v = value(name) ?: return null
        val arr = v as? JsonArray ?: throw ToolError("Parameter '$name' must be an array of integers")
        return arr.map { e ->
            val p = e as? JsonPrimitive ?: throw ToolError("Parameter '$name' must be an array of integers")
            p.intOrNull ?: p.contentOrNull?.trim()?.toIntOrNull() ?: throw ToolError("Parameter '$name' must be an array of integers, got ${p.content}")
        }
    }
}

// ---- JSON Schema 小构件 ----

internal fun objectSchema(required: List<String> = emptyList(), properties: List<Pair<String, JsonObject>>): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { properties.forEach { (k, v) -> put(k, v) } }
    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
}

internal fun stringProp(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
}

internal fun intProp(description: String, min: Int? = null, max: Int? = null): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    if (min != null) put("minimum", min)
    if (max != null) put("maximum", max)
}

internal fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal fun intArrayProp(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    putJsonObject("items") { put("type", "integer") }
}
