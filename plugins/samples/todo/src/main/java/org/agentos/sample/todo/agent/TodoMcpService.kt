package org.agentos.sample.todo.agent

import kotlinx.serialization.json.JsonObject
import org.agentos.plugin.McpBinderService
import org.agentos.plugin.McpToolAnnotations
import org.agentos.plugin.McpToolRegistry
import org.agentos.plugin.McpToolResult
import org.agentos.sample.todo.TodoGraph
import org.agentos.sample.todo.tools.TodoTools
import org.agentos.sample.todo.tools.ToolDefinition
import org.agentos.sample.todo.tools.ToolOutput

/**
 * AgentOS 经 Binder 绑定的 MCP 服务（docs/extensions.md 4.1、5.1）。与 SDK 有关的只有这一个薄层：
 * 把与 SDK 无关的工具定义（[TodoTools]）逐个注册进去。工具读写的是界面共用的那个进程内仓库（[TodoGraph.repository]），
 * 所以 MCP 改了数据，前台界面马上刷新。
 */
class TodoMcpService : McpBinderService() {
    override val serverName: String = "todo"

    override fun onRegisterTools(registry: McpToolRegistry) {
        TodoMcpBridge.register(registry, TodoGraph.tools)
    }
}

/** 把工具层注册进 SDK 的注册表（单独成对象，JVM 单测里用假的注册表就能测）。 */
object TodoMcpBridge {
    fun register(registry: McpToolRegistry, tools: TodoTools) {
        for (tool in tools.all) {
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
                handler = { arguments: JsonObject -> toResult(tool, arguments) },
            )
        }
    }

    internal suspend fun toResult(tool: ToolDefinition, arguments: JsonObject): McpToolResult =
        when (val out = tool.handler(arguments)) {
            is ToolOutput.Ok -> McpToolResult.json(out.value)
            is ToolOutput.Error -> McpToolResult.error(out.message)
        }
}
