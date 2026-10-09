package org.agentos.sample.calendar.agent

import org.agentos.plugin.McpBinderService
import org.agentos.plugin.McpToolAnnotations
import org.agentos.plugin.McpToolRegistry
import org.agentos.plugin.McpToolResult
import org.agentos.sample.calendar.CalendarGraph

/**
 * 导出的 Binder MCP 服务（docs/extensions.md 4.1）：只做一件事，把 [org.agentos.sample.calendar.tools.CalendarTools] 的工具
 * 逐个注册进 SDK。数据走 [CalendarGraph] 里与界面共用的同一个仓库，所以 MCP 改了数据，前台界面立即刷新、提醒同步重排。
 */
class CalendarMcpService : McpBinderService() {
    override val serverName: String = "calendar"
    override val serverVersion: String = "1.1.0"

    override fun onRegisterTools(registry: McpToolRegistry) {
        for (tool in CalendarGraph.tools(applicationContext).all()) {
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
            ) { arguments ->
                val out = tool.handler(arguments)
                val structured = out.structured
                if (out.isError || structured == null) McpToolResult.error(out.text) else McpToolResult.json(structured)
            }
        }
    }
}
