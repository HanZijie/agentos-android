package org.agentos.plugin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 在 [McpBinderService.onRegisterTools] 里注册工具。 */
interface McpToolRegistry {
    /**
     * 注册一个工具。
     *
     * @param name 工具名，1–128 个字符，只能是 `[A-Za-z0-9_.-]`；同一个服务里不能重名。建议 `<名词>_<动词>` 的 snake_case。
     * @param description 给模型看的英文说明：做什么、参数含义、返回什么。
     * @param inputSchema JSON Schema，`type` 必须是 `"object"`。
     * @param handler 在 SDK 的协程作用域里运行（[kotlinx.coroutines.Dispatchers.IO]），收到 `notifications/cancelled`
     *   时被取消；抛出的异常由 SDK 转成 `isError` 结果，不会让 Service 崩溃。参数不合法时返回 [McpToolResult.error]。
     * @throws IllegalArgumentException 名字不合法、重名、schema 的 type 不是 object。
     */
    fun tool(
        name: String,
        description: String,
        inputSchema: JsonObject,
        annotations: McpToolAnnotations = McpToolAnnotations(),
        title: String? = null,
        handler: suspend (arguments: JsonObject) -> McpToolResult,
    )
}

/** 注册好的一个工具（服务端内部）。 */
internal class RegisteredTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: McpToolAnnotations,
    val title: String?,
    val handler: suspend (JsonObject) -> McpToolResult,
) {
    /** tools/list 里的一项。 */
    val descriptor: JsonObject = buildJsonObject {
        put("name", name)
        title?.let { put("title", it) }
        put("description", description)
        put("inputSchema", inputSchema)
        annotations.toJson(title)?.let { put("annotations", it) }
    }
}

/** 一次注册的结果；注册完就不再变（工具集变化时整张表换掉）。 */
internal class ToolTable(val tools: Map<String, RegisteredTool>) {
    companion object {
        val EMPTY = ToolTable(emptyMap())
        private val NAME = Regex("^[A-Za-z0-9_.-]{1,128}$")

        /** 调用 [register] 把工具注册进一张新表。 */
        fun build(register: (McpToolRegistry) -> Unit): ToolTable {
            val tools = LinkedHashMap<String, RegisteredTool>()
            register(object : McpToolRegistry {
                override fun tool(
                    name: String,
                    description: String,
                    inputSchema: JsonObject,
                    annotations: McpToolAnnotations,
                    title: String?,
                    handler: suspend (arguments: JsonObject) -> McpToolResult,
                ) {
                    require(NAME.matches(name)) { "tool name must be 1-128 characters of [A-Za-z0-9_.-]: $name" }
                    require(name !in tools) { "duplicate tool name: $name" }
                    require((inputSchema["type"] as? JsonPrimitive)?.contentOrNull == "object") {
                        "inputSchema of $name must have \"type\": \"object\""
                    }
                    tools[name] = RegisteredTool(name, description, inputSchema, annotations, title, handler)
                }
            })
            return ToolTable(tools)
        }
    }
}
