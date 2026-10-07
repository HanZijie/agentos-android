package org.agentos.plugin.test

import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.agentos.plugin.McpBinderService
import org.agentos.plugin.McpToolAnnotations
import org.agentos.plugin.McpToolRegistry
import org.agentos.plugin.McpToolResult
import java.util.concurrent.atomic.AtomicInteger

/** 回环测试用的 MCP 服务（运行在 :mcp 进程）。 */
class LoopbackMcpService : McpBinderService() {
    override val serverName = "loopback"
    override val serverVersion = "0.1"

    override fun onRegisterTools(registry: McpToolRegistry) {
        val obj = buildJsonObject { put("type", "object") }
        registry.tool(
            "echo", "Echoes text back.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("text") { put("type", "string") } }
            },
            McpToolAnnotations(readOnlyHint = true),
        ) { args ->
            val text = (args["text"] as? JsonPrimitive)?.contentOrNull ?: return@tool McpToolResult.error("text is required")
            McpToolResult.json(buildJsonObject { put("text", text); put("pid", Process.myPid()) })
        }
        registry.tool("slow", "Waits ms milliseconds.", obj) { args ->
            val ms = (args["ms"] as? JsonPrimitive)?.longOrNull ?: 60_000
            started.incrementAndGet()
            try {
                delay(ms)
            } catch (e: CancellationException) {
                cancelled.incrementAndGet()
                throw e
            }
            McpToolResult.text("done")
        }
        registry.tool("stats", "Counters.", obj) {
            McpToolResult.json(buildJsonObject {
                put("started", started.get())
                put("cancelled", cancelled.get())
                put("pid", Process.myPid())
            })
        }
        registry.tool("add_tool", "Adds the extra tool and notifies the client.", obj, McpToolAnnotations(idempotentHint = true)) {
            extra = true
            notifyToolsChanged()
            McpToolResult.text("added")
        }
        if (extra) registry.tool("extra", "Appears after add_tool.", obj) { McpToolResult.text("extra") }
    }

    companion object {
        @Volatile var extra = false
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
    }
}
