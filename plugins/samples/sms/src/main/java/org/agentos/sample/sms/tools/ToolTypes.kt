package org.agentos.sample.sms.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull

/**
 * 与 SDK 无关的工具定义（docs/sample-apps.md 第 2 节“工具层的分层”）：
 * 只依赖 kotlinx-serialization-json 和本 App 的仓库，JVM 测试直接调用 [handler]；
 * 接 SDK 时由很薄的 `SmsMcpService` 把它们逐个注册进 McpToolRegistry。
 */
class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: ToolAnnotations,
    val title: String? = null,
    val handler: suspend (arguments: JsonObject) -> ToolOutput,
)

/** MCP 工具注解。null = 不声明。 */
data class ToolAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

/**
 * 工具结果：[text] 是 content 里的一段紧凑 JSON 文本，[structured] 是同样的对象（只有对象才有）。
 * [isError] 为 true 时 [text] 是一句话原因。
 */
class ToolOutput private constructor(
    val text: String,
    val structured: JsonObject?,
    val isError: Boolean,
) {
    companion object {
        fun json(value: JsonElement): ToolOutput =
            ToolOutput(value.toString(), value as? JsonObject, isError = false)

        fun jsonNull(): ToolOutput = ToolOutput(JsonNull.toString(), null, isError = false)

        fun error(message: String): ToolOutput = ToolOutput(message, null, isError = true)
    }
}
