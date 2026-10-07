package org.agentos.sample.alarm.agent

import kotlinx.serialization.json.JsonNull
import org.agentos.plugin.McpBinderService
import org.agentos.plugin.McpToolAnnotations
import org.agentos.plugin.McpToolRegistry
import org.agentos.plugin.McpToolResult
import org.agentos.sample.alarm.AlarmGraph
import org.agentos.sample.alarm.BuildConfig
import org.agentos.sample.alarm.tools.ToolOutput

/**
 * 导出给 AgentOS 的 MCP 服务（docs/extensions.md 4.1）：只做一件事——把 [org.agentos.sample.alarm.tools.AlarmTools]
 * 的工具逐个注册进 SDK。工具和界面共用 [AlarmGraph] 里的同一个仓库，所以这里改了数据，前台界面立即刷新，
 * 系统闹钟同步重排（见 AlarmRepository）。
 */
class AlarmMcpService : McpBinderService() {
    override val serverName: String = "alarm"
    override val serverVersion: String = BuildConfig.VERSION_NAME

    override fun onRegisterTools(registry: McpToolRegistry) {
        for (tool in AlarmGraph.get(this).tools.tools) {
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
        else -> McpToolResult.json(JsonNull) // alarm_next 没有下一个闹钟：content 里是文本 "null"
    }
}
