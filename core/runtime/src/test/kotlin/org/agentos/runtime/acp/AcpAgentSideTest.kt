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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.errors.RpcCodes
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
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

    /**
     * A8：取消漏到下一轮。SDK 0.30.1 处理 session/cancel 的顺序是 `agentSession.cancel()` 返回之后，才
     * `_activePrompt.getAndSet(null)?.promptJob.cancel()`。我们的 cancel() 会等任务停下；如果这期间被取消的这一轮已经
     * 返回、客户端马上发了下一轮，SDK 取消的就是下一轮。这里让会话里另一个任务停得慢（工具 1 秒不可取消），
     * 把这个窗口稳定地拉到约 1 秒：修复之前每次都失败（下一轮 CANCELLED 或 invalid_state）。
     */
    @Test
    fun `a prompt sent right after a cancelled turn is not cancelled by that session cancel`() = test { pair ->
        pair.rt.host.tools.register("slow_stop", ToolRisk.READ) {
            withContext(NonCancellable) { delay(1_000) }
            ToolInvocationResult.Completed(ToolResult.text("stopped late"))
        }
        pair.initialize()
        val session = pair.newSession()
        val sid = session.sessionId.value
        // 同一会话里先跑一个停得慢的任务（同一个调用方，直接提交给运行时），卡在工具调用里
        val blocker = pair.rt.engine.submit(
            TestRuntime.APP, sid,
            TestRuntime.text(directive("tools" to buildJsonArray { add(buildJsonObject { put("name", "slow_stop") }) })),
        )
        pair.rt.awaitEvent(sid) { it.taskId == blocker.id && it.eventType == EventTypes.TOOL_DISPATCHED }
        // 这一轮排在它后面
        val events = mutableListOf<com.agentclientprotocol.common.Event>()
        val turn = async { session.prompt(listOf(ContentBlock.Text("queued turn"))).collect { events += it } }
        pair.rt.until { pair.rt.engine.runState.value.queuedTasks >= 1 }
        // 取消：排队的这一轮立即取消；运行中的那个约 1 秒后才停
        session.cancel()
        turn.await()
        assertEquals(StopReason.CANCELLED, events.response().stopReason)
        // 马上发下一轮：不能被刚才那次 cancel 取消
        val next = pair.prompt(session, "again")
        assertEquals(StopReason.END_TURN, next.response().stopReason)
        assertEquals("echo: again", next.text())
        assertEquals(TaskState.CANCELLED, pair.rt.engine.awaitTask(blocker.id).state)
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

    /**
     * B5（F9 第二层）：用户在设置里清除 key 时，在途的这一轮在宿主层以 Failed(model_not_configured, retryable=false,
     * details.reason=key_revoked) 结束。ACP 的 -32051 错误要把 details 原样带给客户端（errors.md model_not_configured）。
     */
    @Test
    fun `a revoked key ends the turn with model_not_configured and details reason key_revoked in the error data`() = test { pair ->
        pair.initialize()
        val session = pair.newSession()
        val message = "The model key was removed in AgentOS settings; this turn was stopped."
        val prompt = directive(
            "chunks" to 1, "text" to "partial",
            "fail" to "model_not_configured", "failMessage" to message,
            "failDetails" to buildJsonObject { put("reason", "key_revoked") },
        )
        val e = assertFailsWith<JsonRpcException> { pair.prompt(session, prompt) }
        assertEquals(RpcCodes.TASK_FAILED, e.code)
        // Agent 发出的原始错误（不经 SDK 的 Client 转换）
        val raw = pair.lastError()
        assertEquals(RpcCodes.TASK_FAILED, raw["code"]!!.jsonPrimitive.content.toInt())
        assertEquals("model_not_configured: $message", raw["message"]!!.jsonPrimitive.content)
        val data = raw["data"]!!.jsonObject
        assertEquals("model_not_configured", data["agentosCode"]!!.jsonPrimitive.content)
        assertEquals("false", data["retryable"]!!.jsonPrimitive.content)
        assertEquals("key_revoked", data["details"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertTrue(data["taskId"]!!.jsonPrimitive.content.startsWith("tsk_"))
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

    /**
     * C3 在设备上发现的缺陷（oversize-C）：模型一次发来一条超过 65,536 字符的文字增量（非流式返回的厂商），事件日志按
     * events.md 第 5 节截断，客户端丢字。TaskRunner 现在把单条增量切到不超过 coalesceMaxChars 再写日志。
     */
    @Test
    fun `a single 200,000-character delta reaches the client complete, every line within 65,536 characters`() = test { pair ->
        pair.initialize()
        val session = pair.newSession()
        // 引号在 JSON 里要转义成两个字符：最坏情况
        val events = pair.prompt(session, directive("chunks" to 1, "chunkChars" to 200_000, "text" to "\""))
        assertEquals(StopReason.END_TURN, events.response().stopReason)
        assertEquals("\"".repeat(200_000), events.text(), "no character is lost")
        assertTrue(pair.agentLines.all { it.length <= 65_536 }, "longest line ${pair.agentLines.maxOf { it.length }}")
        val logged = pair.rt.engine.readEvents(session.sessionId.value).filter { it.eventType == EventTypes.MESSAGE_UPDATE }
        assertTrue(logged.none { it.payload["truncated"] != null }, "no text_delta event was truncated")
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
