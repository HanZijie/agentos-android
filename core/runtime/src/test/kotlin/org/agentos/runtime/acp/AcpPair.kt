@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.runtime.acp

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionInfo
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.AcpConnection
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.OutboundGate
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.testing.TestRuntime
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 测试用：在同一个进程里用两个 StdioTransport（按行收发，与电脑端相同）把 SDK 的 Client 接到运行时的 Agent 端。
 * 记录双方线上的每一行原文，用来检查消息大小和格式。
 */
class AcpPair(
    val rt: TestRuntime,
    caller: CallerIdentity = TestRuntime.APP,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toAgent = Channel<String>(Channel.UNLIMITED)
    private val toClient = Channel<String>(Channel.UNLIMITED)

    /** Agent 发给客户端的每一行。 */
    val agentLines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** 不属于任何一轮 prompt 的通知（例如自动选会话的 session_info_update）。 */
    val stray: MutableList<Pair<SessionUpdate, JsonElement?>> = Collections.synchronizedList(mutableListOf())

    val gateCalls = AtomicInteger()

    private val agentTransport = StdioTransport(scope, Dispatchers.IO, toAgent.consumeAsFlow(), { line -> agentLines += line; toClient.send(line) }, "agent")
    private val clientTransport = StdioTransport(scope, Dispatchers.IO, toClient.consumeAsFlow(), { line -> toAgent.send(line) }, "client")
    private val clientProtocol = Protocol(scope, clientTransport)
    val client = Client(clientProtocol)
    val connection: AcpConnection = rt.engine.serveAcp(agentTransport, caller, OutboundGate { gateCalls.incrementAndGet() })

    init {
        clientProtocol.start()
    }

    /** `initialize`；[extensions] 放在请求的 `_meta."org.agentos".extensions` 里（SDK 的第二个参数才是请求的 `_meta`）。 */
    suspend fun initialize(extensions: List<String> = emptyList()) = client.initialize(
        ClientInfo(),
        if (extensions.isEmpty()) {
            null
        } else {
            buildJsonObject { put(ProfileExtensions.META_KEY, buildJsonObject { put("extensions", buildJsonArray { extensions.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) }) }
        },
    )

    private val recordingOperations = object : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?,
        ): RequestPermissionResponse = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

        override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
            stray += notification to _meta
        }
    }

    suspend fun newSession(meta: JsonObject? = null, mcpServers: List<McpServer> = emptyList()): ClientSession =
        client.newSession(SessionCreationParameters("/sdcard", mcpServers, _meta = meta)) { _, _ -> recordingOperations }

    /** `session/load`：重放的历史在 load 返回之前就进了 [strayUpdates]。 */
    suspend fun loadSession(id: String, meta: JsonObject? = null, mcpServers: List<McpServer> = emptyList()): ClientSession =
        client.loadSession(SessionId(id), SessionCreationParameters("/sdcard", mcpServers, _meta = meta)) { _, _ -> recordingOperations }

    suspend fun resumeSession(id: String, meta: JsonObject? = null, mcpServers: List<McpServer> = emptyList()): ClientSession =
        client.resumeSession(SessionId(id), SessionCreationParameters("/sdcard", mcpServers, _meta = meta)) { _, _ -> recordingOperations }

    suspend fun forkSession(id: String, meta: JsonObject? = null, mcpServers: List<McpServer> = emptyList()): ClientSession =
        client.forkSession(SessionId(id), SessionCreationParameters("/sdcard", mcpServers, _meta = meta)) { _, _ -> recordingOperations }

    suspend fun listSessions(cwd: String? = null): List<SessionInfo> = client.listSessions(cwd, null, null).toList()

    /** 不属于任何一轮 prompt 的通知（重放、会话信息更新…）里的更新，按到达顺序。 */
    fun strayUpdates(): List<SessionUpdate> = synchronized(stray) { stray.map { it.first } }

    /** Agent 发出的每条 session_info_update 的 `_meta."org.agentos"` 对象，按发出顺序。 */
    fun infoMetas(): List<JsonObject> = synchronized(agentLines) {
        agentLines.mapNotNull { line ->
            val o = kotlinx.serialization.json.Json.parseToJsonElement(line) as JsonObject
            val update = ((o["params"] as? JsonObject)?.get("update") as? JsonObject) ?: return@mapNotNull null
            if ((update["sessionUpdate"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "session_info_update") return@mapNotNull null
            (update["_meta"] as? JsonObject)?.get(ProfileExtensions.META_KEY) as? JsonObject
        }
    }

    /** 发一轮 prompt，收集全部事件。 */
    suspend fun prompt(session: ClientSession, text: String): List<Event> =
        session.prompt(listOf(ContentBlock.Text(text))).toList()

    /** Agent 发出的最后一个 JSON-RPC 错误对象（原始行）。 */
    fun lastError(): JsonObject = synchronized(agentLines) {
        agentLines.map { kotlinx.serialization.json.Json.parseToJsonElement(it) as JsonObject }.last { "error" in it }["error"] as JsonObject
    }

    /** Agent 发出的 session_info_update 里 `_meta."org.agentos".selection` 对象（自动选会话的结果），按发出顺序。 */
    fun selections(): List<JsonObject> = synchronized(agentLines) {
        agentLines.mapNotNull { line ->
            val o = kotlinx.serialization.json.Json.parseToJsonElement(line) as JsonObject
            val update = ((o["params"] as? JsonObject)?.get("update") as? JsonObject) ?: return@mapNotNull null
            if ((update["sessionUpdate"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "session_info_update") return@mapNotNull null
            ((update["_meta"] as? JsonObject)?.get(ProfileExtensions.META_KEY) as? JsonObject)?.get("selection") as? JsonObject
        }
    }

    /** 模拟客户端断开：关闭客户端这一侧的传输。 */
    fun disconnect() {
        clientProtocol.close()
        toAgent.close()
    }

    suspend fun close() {
        runCatching { connection.close("test done") }
        runCatching { clientProtocol.close() }
        scope.cancel()
    }
}

/** `session/new` 的 `_meta`：`{"org.agentos": {"toolScope": [{"plugin", "tool"}, ...]}}`（docs/third-party-acp.md 4.5）。 */
fun toolScopeMeta(vararg refs: ToolRef): JsonObject = buildJsonObject {
    put(
        ProfileExtensions.META_KEY,
        buildJsonObject {
            put("toolScope", buildJsonArray { refs.forEach { r -> add(buildJsonObject { put("plugin", r.plugin); put("tool", r.tool) }) } })
        },
    )
}

fun List<Event>.updates(): List<SessionUpdate> = filterIsInstance<Event.SessionUpdateEvent>().map { it.update }

fun List<Event>.response() = filterIsInstance<Event.PromptResponseEvent>().single().response

fun List<Event>.text(): String = updates().filterIsInstance<SessionUpdate.AgentMessageChunk>().joinToString("") { (it.content as ContentBlock.Text).text }
