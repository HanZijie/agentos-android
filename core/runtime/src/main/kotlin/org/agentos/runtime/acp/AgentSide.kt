// ACP SDK 0.30.1 把部分模型类的构造参数标为 UnstableApi；版本已锁定（libs.versions.toml），升级时整体复核。
@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.runtime.acp

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.DeleteSessionResponse
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.McpCapabilities
import com.agentclientprotocol.model.PromptCapabilities
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionInfo
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.agentos.runtime.AcpConnection
import org.agentos.runtime.RuntimeEngine
import org.agentos.runtime.errors.AgentOsException
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.errors.RpcErrorData
import org.agentos.runtime.events.EventEnvelope
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.events.RuntimeJson
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.OutboundGate
import org.agentos.runtime.router.SessionRouter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** ACP Agent 端的参数（acp-mapping.md）。 */
data class AcpConfig(
    /** 是否接受图片输入（promptCapabilities.image）。Profile：验证后再声明，M1 关闭。 */
    val promptImages: Boolean = false,
    /** 一轮 prompt 的文字上限（字符），超过返回 payload_too_large。 */
    val maxPromptChars: Int = 200_000,
    /** session/cancel 等运行中的任务停下的上限；超过不再等（任务由调度器按宽限期处理）。 */
    val cancelWaitMillis: Long = 12_000,
)

/**
 * 在一条传输上提供 ACP Agent 端：官方 SDK 0.30.1 的 [Agent] + [AcpAgentSupport]。
 *
 * - Protocol 随 Transport 一起关闭（S3 问题 4），挂起的请求随之结束；连接断开**不取消**任务（F7）。
 * - 身份（[CallerIdentity]）来自可信的连接上下文，消息里自报的任何身份都不看。
 */
internal object AcpServer {
    fun serve(
        engine: RuntimeEngine,
        transport: Transport,
        caller: CallerIdentity,
        gate: OutboundGate,
        scope: CoroutineScope,
        config: AcpConfig,
        runtimeVersion: String,
    ): AcpConnection {
        val closed = CompletableDeferred<Unit>()
        val closing = AtomicBoolean(false)
        val protocol = Protocol(scope, transport, ProtocolOptions(protocolDebugName = "acp-${caller.ownerKey}"))
        Agent(protocol, AcpAgentSupport(engine, caller, gate, config, runtimeVersion))
        transport.onClose {
            if (closing.compareAndSet(false, true)) protocol.close()
            closed.complete(Unit)
        }
        protocol.start()
        return object : AcpConnection {
            override val caller: CallerIdentity = caller

            override suspend fun close(reason: String) {
                if (closing.compareAndSet(false, true)) protocol.close()
                closed.complete(Unit)
            }

            override suspend fun awaitClosed() = closed.await()
        }
    }
}

/** 一条连接上的 AgentSupport：initialize、session/new（含自动选会话扩展）。 */
internal class AcpAgentSupport(
    private val engine: RuntimeEngine,
    private val caller: CallerIdentity,
    private val gate: OutboundGate,
    private val config: AcpConfig,
    private val runtimeVersion: String,
) : AgentSupport {
    @Volatile private var extensions: Set<String> = emptySet()

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        extensions = ProfileExtensions.clientExtensions(clientInfo._meta)
        return AgentInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = AgentCapabilities(
                loadSession = false, // session/load：M2（W10）
                promptCapabilities = PromptCapabilities(audio = false, image = config.promptImages, embeddedContext = true),
                mcpCapabilities = McpCapabilities(http = false, sse = false),
            ),
            implementation = Implementation(name = "agentos", version = runtimeVersion, title = "AgentOS"),
            _meta = ProfileExtensions.initializeMeta(runtimeVersion),
        )
    }

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        if (sessionParameters.mcpServers.isNotEmpty()) {
            // Profile：非空的 MCP 配置明确返回不支持，不静默忽略
            throw rpcError(ErrorCode.UNSUPPORTED.info("mcpServers are not supported; tools come from AgentOS plugins"))
        }
        val cwd = sessionParameters.cwd.takeIf { it.isNotBlank() }
        val query = ProfileExtensions.autoSelectQuery(sessionParameters._meta)
        if (query != null) {
            if (ProfileExtensions.SESSION_AUTO_SELECT !in extensions) {
                throw rpcError(ErrorCode.INVALID_PARAMS.info("the ${ProfileExtensions.SESSION_AUTO_SELECT} extension was not negotiated in initialize"))
            }
            if (query.isEmpty()) throw rpcError(ErrorCode.INVALID_PARAMS.info("autoSelect.query must be a non-empty string"))
            val result = guarded { engine.autoSelect(caller, query, cwd) }
            return AcpSession(result.session.id, engine, caller, gate, config, result)
        }
        val session = guarded { engine.createSession(caller, cwd) }
        return AcpSession(session.id, engine, caller, gate, config, null)
    }

    // ---- 不启用的方法：明确返回“方法不存在”，而不是 SDK 默认的内部错误 ----

    override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession =
        notSupported("session/load")

    override suspend fun resumeSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession =
        notSupported("session/resume")

    override suspend fun forkSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession =
        notSupported("session/fork")

    override suspend fun deleteSession(sessionId: SessionId, _meta: JsonElement?): DeleteSessionResponse = notSupported("session/delete")

    override suspend fun listSessions(cwd: String?, additionalDirectories: List<String>?, _meta: JsonElement?): Sequence<SessionInfo> =
        notSupported("session/list")

    private fun notSupported(method: String): Nothing =
        throw JsonRpcException(JsonRpcErrorCode.METHOD_NOT_FOUND.code, "$method is not enabled in AgentOS Profile v1")
}

/**
 * 一个 ACP 会话。prompt：持久化提交 → 从事件日志按顺序读这一轮的已提交事件 → 映射成 session/update →
 * 等到任务结束返回 stopReason（ACP v1：一轮结束才返回）。
 */
internal class AcpSession(
    id: String,
    private val engine: RuntimeEngine,
    private val caller: CallerIdentity,
    private val gate: OutboundGate,
    private val config: AcpConfig,
    private val selection: SessionRouter.Result?,
) : AgentSession {
    override val sessionId: SessionId = SessionId(id)
    private val id: String = id

    private companion object {
        /** [awaitCancelHandoff] 的兜底：cancel 最多等 cancelWaitMillis，再留这么多余量。 */
        const val CANCEL_HANDOFF_MARGIN_MILLIS = 5_000L
    }

    override suspend fun postInitialize() {
        val s = selection ?: return
        // 自动选会话的结果：只对协商了扩展的客户端有意义；普通字段为空，标准客户端看到的是一条无内容的会话信息更新
        runCatching {
            currentCoroutineContext().client.notify(SessionUpdate.SessionInfoUpdate(_meta = ProfileExtensions.selectionMeta(s)), null)
        }
    }

    /**
     * 本连接上这个会话正在进行的一轮（SDK 同一会话同时只允许一轮）。`cancelRequested`：这一轮进行期间收到了本连接的
     * session/cancel——SDK 会在 [cancel] 返回之后取消这一轮的协程（A8，见 [cancel]）。
     */
    private class PromptTurn {
        val cancelRequested = AtomicBoolean(false)
    }

    private val active = AtomicReference<PromptTurn?>(null)

    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> {
        val turn = PromptTurn()
        active.set(turn)
        try {
            val blocks = validate(content)
            // 从收到起就计入 runState（恢复期间收到的也一样），提交持久化后才开始读这一轮的事件。
            // 提交不可中断：SDK 在提交途中取消这一轮（session/cancel）时，要拿到任务 ID 一并取消，不能留下没人等的任务
            val (after, task) = withContext(NonCancellable) { guarded { engine.submitWithCursor(caller, id, blocks) } }
            if (turn.cancelRequested.get() || !currentCoroutineContext().isActive) {
                // session/cancel 在任务提交完成之前到达，那次 engine.cancel 可能没看到这个任务
                withContext(NonCancellable) { runCatching { engine.cancel(caller, id) } }
            }
            currentCoroutineContext().ensureActive()
            return turnEvents(turn, after, task.id)
        } catch (e: Throwable) {
            active.compareAndSet(turn, null)
            throw e
        }
    }

    private fun turnEvents(turn: PromptTurn, after: Long, taskId: String): Flow<Event> {
        val mapper = UpdateMapper()
        return flow {
            try {
                var result: PromptResponse? = null
                var failure: JsonRpcException? = null
                engine.events(id, after)
                    .filter { it.taskId == taskId }
                    .transformWhile { e -> emit(e); !isTerminal(e) }
                    .collect { e ->
                        when (e.eventType) {
                            EventTypes.TASK_COMPLETED -> result = PromptResponse(stopReason(e), _meta = ProfileExtensions.promptResponseMeta(taskId))
                            EventTypes.TASK_CANCELLED -> result = PromptResponse(StopReason.CANCELLED, _meta = ProfileExtensions.promptResponseMeta(taskId))
                            EventTypes.TASK_FAILED -> failure = rpcError(e.error ?: ErrorCode.INTERNAL.info("task failed"), taskId)
                            EventTypes.TASK_RECOVERY_REQUIRED -> {
                                val err = engine.task(taskId)?.error ?: ErrorCode.AGENT_CORE_FAILED.info("the task was interrupted")
                                failure = rpcError(err, taskId)
                            }
                            else -> for (update in mapper.map(e)) {
                                // 背压：Binder 通道的本地积压降下来再发（binder-channel-v1 第 5 节）
                                gate.awaitWritable()
                                emit(Event.SessionUpdateEvent(update))
                            }
                        }
                    }
                // SDK 记下这个响应，这一轮的协程结束（或被取消）时才交给客户端
                if (failure == null) emit(Event.PromptResponseEvent(result ?: PromptResponse(StopReason.END_TURN)))
                if (turn.cancelRequested.get()) awaitCancelHandoff()
                failure?.let { throw it }
            } finally {
                active.compareAndSet(turn, null)
            }
        }
    }

    /**
     * 这一轮收到了本连接的 session/cancel：不结束，等 SDK 在 [cancel] 返回之后取消这一轮的协程——SDK 随后把上面记下的
     * 响应交给客户端（失败的一轮则回 cancelled）。这样客户端收到响应、发出下一轮时，SDK 的取消一定已经做完，
     * 不会落到下一轮上（A8）。兜底：等不到就照常结束。
     */
    private suspend fun awaitCancelHandoff() {
        withTimeoutOrNull(config.cancelWaitMillis + CANCEL_HANDOFF_MARGIN_MILLIS) { awaitCancellation() }
    }

    /**
     * session/cancel：取消会话里未结束的任务，并等运行中的停下（随后的 prompt 不会撞上“取消中”），最多 [AcpConfig.cancelWaitMillis]。
     *
     * A8：SDK 0.30.1 在这个方法**返回之后**才取消它眼里的“当前 prompt”（`Agent.SessionWrapper.cancel`：
     * `agentSession.cancel()` → `_activePrompt.getAndSet(null)?.promptJob.cancel()`）。所以被取消的这一轮不能在那之前把响应
     * 交给客户端（[awaitCancelHandoff]），否则客户端马上发的下一轮会成为“当前 prompt”，被这次 cancel 取消。
     * 本连接上没有进行中的一轮时什么都不做、也不挂起：ACP 的 session/cancel 只针对进行中的 prompt。
     */
    override suspend fun cancel() {
        val turn = active.get() ?: return
        turn.cancelRequested.set(true)
        val ids = runCatching { engine.cancel(caller, id) }.getOrDefault(emptyList())
        withTimeoutOrNull(config.cancelWaitMillis) { ids.forEach { engine.awaitTask(it) } }
    }

    private fun isTerminal(e: EventEnvelope) = e.eventType in EventTypes.TASK_TERMINAL || e.eventType == EventTypes.TASK_RECOVERY_REQUIRED

    private fun stopReason(e: EventEnvelope): StopReason = when ((e.payload["stopReason"] as? JsonPrimitive)?.contentOrNull) {
        "max_tokens" -> StopReason.MAX_TOKENS
        "max_turn_requests" -> StopReason.MAX_TURN_REQUESTS
        "refusal" -> StopReason.REFUSAL
        else -> StopReason.END_TURN
    }

    /** 只接受 Profile 声明过的内容类型，转成原样保存的 JSON。 */
    private fun validate(content: List<ContentBlock>): JsonArray {
        if (content.isEmpty()) throw rpcError(ErrorCode.INVALID_PARAMS.info("prompt is empty"))
        var chars = 0
        for (b in content) {
            when (b) {
                is ContentBlock.Text -> chars += b.text.length
                is ContentBlock.ResourceLink -> Unit
                is ContentBlock.Resource -> when (val r = b.resource) {
                    is EmbeddedResourceResource.TextResourceContents -> chars += r.text.length
                    else -> throw rpcError(ErrorCode.UNSUPPORTED.info("binary embedded resources are not supported"))
                }
                is ContentBlock.Image -> if (!config.promptImages) throw rpcError(ErrorCode.UNSUPPORTED.info("image prompts are not enabled"))
                else -> throw rpcError(ErrorCode.UNSUPPORTED.info("unsupported content block"))
            }
        }
        if (chars > config.maxPromptChars) throw rpcError(ErrorCode.PAYLOAD_TOO_LARGE.info("prompt exceeds ${config.maxPromptChars} characters"))
        return ACPJson.encodeToJsonElement(ListSerializer(ContentBlock.serializer()), content) as JsonArray
    }
}

/** 宿主层的错误 → ACP 的 JSON-RPC 错误（errors.md 第 5 节）。 */
internal fun rpcError(error: ErrorInfo, taskId: String? = null, sessionId: String? = null): JsonRpcException =
    JsonRpcException(
        code = error.code.rpcCode,
        message = "${error.code.wire}: ${error.message}",
        data = RuntimeJson.encodeToJsonElement(
            RpcErrorData.serializer(),
            RpcErrorData(error.code, error.retryable, sessionId, taskId, error.details),
        ),
    )

/** 把宿主层抛出的 [AgentOsException] 转成 JSON-RPC 错误。 */
internal inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (e: AgentOsException) {
    throw rpcError(e.info)
}
