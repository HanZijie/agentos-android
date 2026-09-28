@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.runtime.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.errors.RpcCodes
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.router.JevProvider
import org.agentos.runtime.scheduler.SchedulerConfig
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W4：官方 SDK 的 Client ↔ 运行时的 Agent 端（AgentSide），经按行收发的 StdioTransport，Agent 循环是 FakeAgentCore。
 * 覆盖 initialize → session/new → session/prompt 流式 → session/cancel，以及错误映射和自动选会话扩展。
 */
class AcpAgentSideTest {

    private fun test(jev: JevProvider? = null, block: suspend CoroutineScope.(AcpPair) -> Unit) = runBlocking {
        val rt = TestRuntime(
            FakeScripts.directives(),
            config = RuntimeConfig(scheduler = SchedulerConfig(tickMillis = 20), jev = jev),
        )
        rt.host.tools.registerSimple("add") { args ->
            ToolResult.text(((args["a"] as JsonPrimitive).content.toInt() + (args["b"] as JsonPrimitive).content.toInt()).toString())
        }
        rt.start()
        val pair = AcpPair(rt)
        try {
            withTimeout(20_000) { block(this, pair) }
        } finally {
            pair.close()
            rt.stop()
            rt.host.deleteDatabase()
        }
    }

    private fun directive(vararg pairs: Pair<String, Any>): String = buildJsonObject {
        put(
            "fake",
            buildJsonObject {
                pairs.forEach { (k, v) ->
                    when (v) {
                        is Int -> put(k, v)
                        is Long -> put(k, v)
                        is Boolean -> put(k, v)
                        is String -> put(k, v)
                        is kotlinx.serialization.json.JsonElement -> put(k, v)
                    }
                }
            },
        )
    }.toString()

    @Test
    fun `initialize declares ACP v1, the capabilities and the AgentOS profile`() = test { pair ->
        val info = pair.initialize()
        assertEquals(1, info.protocolVersion)
        assertEquals(false, info.capabilities.loadSession)
        assertEquals(true, info.capabilities.promptCapabilities.embeddedContext)
        assertEquals(false, info.capabilities.promptCapabilities.image)
        assertEquals(false, info.capabilities.mcpCapabilities.http)
        assertEquals("agentos", info.implementation?.name)
        val meta = info._meta!!.jsonObject[ProfileExtensions.META_KEY]!!.jsonObject
        assertEquals(1, meta["profile"]!!.jsonPrimitive.content.toInt())
        assertEquals("best_effort", meta["securityLevel"]!!.jsonPrimitive.content)
        assertNotNull(meta["extensions"]!!.jsonObject[ProfileExtensions.SESSION_AUTO_SELECT])
    }

    @Test
    fun `prompt streams agent_message_chunk and returns end_turn after the turn`() = test { pair ->
        pair.initialize()
        val session = pair.newSession()
        assertTrue(session.sessionId.value.startsWith("ses_"))
        val events = pair.prompt(session, directive("chunks" to 30, "chunkChars" to 4, "text" to "abcd", "intervalMs" to 3))
        assertEquals(StopReason.END_TURN, events.response().stopReason)
        assertEquals("abcd".repeat(30), events.text())
        val chunks = events.updates().filterIsInstance<SessionUpdate.AgentMessageChunk>().size
        assertTrue(chunks in 1 until 30, "30 raw deltas were coalesced into $chunks updates")
        assertTrue(pair.gateCalls.get() >= chunks, "every streamed update passed the outbound gate")
        val taskId = events.response()._meta!!.jsonObject[ProfileExtensions.META_KEY]!!.jsonObject["taskId"]!!.jsonPrimitive.content
        assertEquals(TaskState.COMPLETED, pair.rt.engine.task(taskId)!!.state)
    }

    @Test
    fun `tool calls appear as tool_call then tool_call_update in_progress and completed`() = test { pair ->
        pair.initialize()
        val session = pair.newSession()
        val tools = kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject { put("name", "add"); put("arguments", buildJsonObject { put("a", 2); put("b", 3) }) })
        }
        val events = pair.prompt(session, directive("tools" to tools))
        assertEquals(StopReason.END_TURN, events.response().stopReason)
        val updates = events.updates()
        val call = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
        assertEquals("add", call.title)
        assertEquals(ToolCallStatus.PENDING, call.status)
        val statuses = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().filter { it.toolCallId == call.toolCallId }.map { it.status }
        assertEquals(listOf(ToolCallStatus.IN_PROGRESS, ToolCallStatus.COMPLETED), statuses)
        val done = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().last()
        assertEquals("5", ((done.content!!.single() as ToolCallContent.Content).content as ContentBlock.Text).text)
        assertTrue(events.text().endsWith("(fake) done"))
    }

    @Test
    fun `session cancel stops the turn with cancelled and the session keeps working`() = test { pair ->
        pair.initialize()
        val session = pair.newSession()
        val events = mutableListOf<com.agentclientprotocol.common.Event>()
        val turn = async {
            session.prompt(listOf(ContentBlock.Text(directive("chunks" to 2, "chunkChars" to 3, "awaitAbort" to true)))).collect { events += it }
        }
        pair.rt.until { events.any { it is com.agentclientprotocol.common.Event.SessionUpdateEvent } }
        session.cancel()
        turn.await()
        assertEquals(StopReason.CANCELLED, events.response().stopReason)
        val next = pair.prompt(session, "again")
        assertEquals(StopReason.END_TURN, next.response().stopReason)
        assertEquals("echo: again", next.text())
    }

    @Test
    fun `a failed turn becomes a JSON-RPC error with the AgentOS error code`() = test { pair ->
        pair.initialize()
        val session = pair.newSession()
        val e = assertFailsWith<JsonRpcException> { pair.prompt(session, directive("fail" to "model_rate_limited")) }
        assertEquals(RpcCodes.TASK_FAILED, e.code)
        val data = e.data!!.jsonObject
        assertEquals("model_rate_limited", data["agentosCode"]!!.jsonPrimitive.content)
        assertEquals("true", data["retryable"]!!.jsonPrimitive.content)
        assertEquals(StopReason.END_TURN, pair.prompt(session, "still fine").response().stopReason)
    }

    @Test
    fun `unsupported inputs are rejected explicitly`() = test { pair ->
        pair.initialize()
        // SDK 的 Client 把 -32602 转成 AcpExpectedError；这里直接检查 Agent 发出的原始错误
        assertFailsWith<Exception> {
            pair.newSession(mcpServers = listOf(McpServer.Stdio("fs", "/bin/fs", emptyList(), emptyList())))
        }
        val mcp = pair.lastError()
        assertEquals(RpcCodes.INVALID_PARAMS, mcp["code"]!!.jsonPrimitive.content.toInt())
        assertEquals("unsupported", mcp["data"]!!.jsonObject["agentosCode"]!!.jsonPrimitive.content)

        val session = pair.newSession()
        assertFailsWith<Exception> {
            session.prompt(listOf(ContentBlock.Image(data = "AAAA", mimeType = "image/png"))).toList()
        }
        assertEquals("unsupported", pair.lastError()["data"]!!.jsonObject["agentosCode"]!!.jsonPrimitive.content)
        // 资源链接和嵌入的文字资源可以用
        val ok = session.prompt(listOf(ContentBlock.ResourceLink(name = "notes", uri = "content://notes/1"), ContentBlock.Text("summarise"))).toList()
        assertEquals(StopReason.END_TURN, ok.response().stopReason)
        assertTrue("content://notes/1" in ok.text())
    }

    @Test
    fun `long deltas are split so every line stays under the binder message limit`() = test { pair ->
        pair.initialize()
        val session = pair.newSession()
        val events = pair.prompt(session, directive("chunks" to 1, "chunkChars" to 20_000, "text" to "\""))
        assertEquals("\"".repeat(20_000), events.text())
        val chunks = events.updates().filterIsInstance<SessionUpdate.AgentMessageChunk>()
        assertTrue(chunks.all { (it.content as ContentBlock.Text).text.length <= UpdateMapper.DEFAULT_MAX_CHUNK_CHARS })
        assertTrue(pair.agentLines.all { it.length <= 65_536 }, "longest line ${pair.agentLines.maxOf { it.length }}")
    }

    @Test
    fun `a disconnected client does not cancel the task`() = test { pair ->
        pair.initialize()
        val session = pair.newSession()
        val turn = async { runCatching { pair.prompt(session, directive("chunks" to 40, "chunkChars" to 2, "intervalMs" to 10)) } }
        pair.rt.until { pair.agentLines.size > 5 }
        pair.disconnect()
        turn.await()
        val tasks = pair.rt.engine.readEvents(session.sessionId.value).filter { it.eventType == EventTypes.TASK_QUEUED }
        val done = pair.rt.engine.awaitTask(tasks.single().taskId!!)
        assertEquals(TaskState.COMPLETED, done.state, "F7: disconnecting is not closing the session")
    }

    @Test
    fun `auto-select requires negotiation and reports the selection in _meta`() {
        lateinit var first: String
        var jevCalls = 0
        val jev = JevProvider { req -> jevCalls++; req.choices.first { it.id != JevProvider.NEW_SESSION }.id }
        val autoMeta = { q: String -> buildJsonObject { put(ProfileExtensions.META_KEY, buildJsonObject { put("autoSelect", buildJsonObject { put("query", q) }) }) } }

        // 没协商：明确拒绝
        test(jev) { pair ->
            pair.initialize()
            assertFailsWith<Exception> { pair.newSession(autoMeta("hello")) }
            val e = pair.lastError()
            assertEquals(RpcCodes.INVALID_PARAMS, e["code"]!!.jsonPrimitive.content.toInt())
            assertEquals("invalid_params", e["data"]!!.jsonObject["agentosCode"]!!.jsonPrimitive.content)
        }

        test(jev) { pair ->
            pair.initialize(listOf(ProfileExtensions.SESSION_AUTO_SELECT))
            // 没有候选：新建
            val s1 = pair.newSession(autoMeta("plan a trip to Kyoto"))
            first = s1.sessionId.value
            pair.prompt(s1, "plan a trip to Kyoto")
            // SDK 的 Kotlin Client 会把 prompt 之前到达的通知并进下一轮的事件流；这里直接检查线上的通知
            val sel1 = pair.selections().single()
            assertEquals(first, sel1["sessionId"]!!.jsonPrimitive.content)
            assertEquals(true, sel1["created"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("no_candidates", sel1["method"]!!.jsonPrimitive.content)
            assertEquals(0, jevCalls)

            // 有候选：Jev 选中已有会话，同一个 sessionId，可以继续 prompt
            val s2 = pair.newSession(autoMeta("hotels in Kyoto?"))
            assertEquals(first, s2.sessionId.value)
            pair.rt.until { pair.selections().size >= 2 }
            val sel2 = pair.selections()[1]
            assertEquals(false, sel2["created"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("jev", sel2["method"]!!.jsonPrimitive.content)
            assertEquals(StopReason.END_TURN, pair.prompt(s2, "hotels in Kyoto?").response().stopReason)
            assertEquals(1, jevCalls)
        }
    }

}
