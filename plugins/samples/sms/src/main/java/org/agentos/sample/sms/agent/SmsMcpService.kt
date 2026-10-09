package org.agentos.sample.sms.agent

import kotlinx.serialization.json.JsonNull
import org.agentos.plugin.McpBinderService
import org.agentos.plugin.McpToolAnnotations
import org.agentos.plugin.McpToolRegistry
import org.agentos.plugin.McpToolResult
import org.agentos.sample.sms.BuildConfig
import org.agentos.sample.sms.SmsGraph
import org.agentos.sample.sms.tools.ToolOutput

/**
 * 导出给 AgentOS 的 MCP 服务（docs/extensions.md 4.1）：只做一件事——把 [org.agentos.sample.sms.tools.SmsTools]
 * 的六个工具逐个注册进 SDK。工具目录**固定**：权限不足时工具返回明确错误，而不是从目录里消失。
 */
class SmsMcpService : McpBinderService() {
    override val serverName: String = "sms"
    override val serverVersion: String = BuildConfig.VERSION_NAME

    override fun onRegisterTools(registry: McpToolRegistry) {
        for (tool in SmsGraph.get(this).tools.tools) {
            registry.tool(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema,
                annotations = McpToolAnnotations(
                    readOnlyHint = tool.annotations.readOnlyHint,
                    destructiveHint = tool.annotations.destructiveHint,
                    idempotentHint = tool.annotations.idempotentHint,
                    openWorldHint = tool.annotations.openWorldHint,
                ),
                title = tool.title,
            ) { arguments -> tool.handler(arguments).toMcp() }
        }
    }

    private fun ToolOutput.toMcp(): McpToolResult = when {
        isError -> McpToolResult.error(text)
        structured != null -> McpToolResult.json(structured)
        else -> McpToolResult.json(JsonNull)
    }
}
