package org.agentos.test.mcp.plugin

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
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

/**
 * 测试插件的 MCP 服务（服务器名 test）。工具：
 * - echo(text)：回显，readOnly；
 * - slow(ms)：等待，记录开始 / 取消次数（取消转发）；
 * - stats：计数和 pid；
 * - add_tool / remove_tool：增删 extra 工具并发 tools/list_changed；
 * - die(after_ms)：过一会儿结束自己的进程（进程死亡时进行中的调用应变成“结果未知”）；
 * - wipe：destructiveHint=true（风险应升为 high）。
 */
class TestMcpService : McpBinderService() {
    override val serverName = "test"
    override val serverVersion = "1.0.0"

    override fun onRegisterTools(registry: McpToolRegistry) {
        val empty = buildJsonObject { put("type", "object") }
        registry.tool(
            "echo",
            "Echoes the given text back as {\"text\": ..., \"pid\": ...}.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("text") { put("type", "string") } }
                put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("text")) })
            },
            McpToolAnnotations(readOnlyHint = true),
            title = "Echo",
        ) { args ->
            val text = (args["text"] as? JsonPrimitive)?.contentOrNull ?: return@tool McpToolResult.error("text is required")
            McpToolResult.json(buildJsonObject { put("text", text); put("pid", Process.myPid()) })
        }
        registry.tool("slow", "Waits for ms milliseconds (default 60000), then returns \"done\".", empty) { args ->
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
        registry.tool("stats", "Returns counters of this server process.", empty, McpToolAnnotations(readOnlyHint = true)) {
            McpToolResult.json(stats())
        }
        registry.tool("add_tool", "Adds the tool named extra.", empty, McpToolAnnotations(idempotentHint = true)) {
            extra = true
            notifyToolsChanged()
            McpToolResult.text("added")
        }
        registry.tool("remove_tool", "Removes the tool named extra.", empty, McpToolAnnotations(idempotentHint = true)) {
            extra = false
            notifyToolsChanged()
            McpToolResult.text("removed")
        }
        registry.tool("die", "Kills this server process after after_ms milliseconds (default 300) and never returns.", empty) { args ->
            val ms = (args["after_ms"] as? JsonPrimitive)?.longOrNull ?: 300
            delay(ms)
            Process.killProcess(Process.myPid())
            McpToolResult.text("unreachable")
        }
        registry.tool("wipe", "Pretends to delete everything (does nothing).", empty, McpToolAnnotations(destructiveHint = true)) {
            McpToolResult.text("nothing to wipe")
        }
        if (extra) registry.tool("extra", "Appears after add_tool.", empty) { McpToolResult.text("extra") }
    }

    private fun stats(): JsonObject = buildJsonObject {
        put("pid", Process.myPid())
        put("started", started.get())
        put("cancelled", cancelled.get())
        put("extra", extra)
        put("uptimeMs", SystemClock.elapsedRealtime() - createdAt)
    }

    companion object {
        @Volatile var extra = false
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        private val createdAt = SystemClock.elapsedRealtime()
    }
}
