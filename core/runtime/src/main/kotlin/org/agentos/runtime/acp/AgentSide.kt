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
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.DeleteSessionResponse
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.McpCapabilities
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.PromptCapabilities
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.model.SessionCloseCapabilities
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionCategory
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionConfigSelectOption
import com.agentclientprotocol.model.SessionConfigSelectOptions
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.model.SessionDeleteCapabilities
import com.agentclientprotocol.model.SessionForkCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionInfo
import com.agentclientprotocol.model.SessionListCapabilities
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionNotification
import com.agentclientprotocol.model.SessionResumeCapabilities
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.SetSessionConfigOptionResponse
import com.agentclientprotocol.model.SetSessionModeResponse
import com.agentclientprotocol.model.SetSessionModelResponse
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.protocol.invoke
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
import kotlinx.serialization.json.put
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
import org.agentos.runtime.ports.SessionMcpRejected
import org.agentos.runtime.ports.SessionMcpResult
import org.agentos.runtime.ports.SessionMcpServer
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.router.SessionRouter
import org.agentos.runtime.store.SessionRecord
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.agentclientprotocol.model.SessionMode as AcpSessionMode
import org.agentos.runtime.store.SessionMode as AgentOsMode

/** ACP Agent 端的参数（acp-mapping.md）。 */
data class AcpConfig(
    /** 是否接受图片输入（promptCapabilities.image）。Profile：验证后再声明，M1 关闭。 */
    val promptImages: Boolean = false,
    /** 一轮 prompt 的文字上限（字符），超过返回 payload_too_large。 */
    val maxPromptChars: Int = 200_000,
    /** session/cancel 等运行中的任务停下的上限；超过不再等（任务由调度器按宽限期处理）。 */
    val cancelWaitMillis: Long = 12_000,
    /** `session/load` 重放历史时最多重放最近的多少轮（一轮 = 一次 prompt）；更早的不重放，重放量有上限。 */
    val maxReplayTurns: Int = 200,
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
        Agent(protocol, AcpAgentSupport(engine, caller, gate, config, runtimeVersion, protocol))
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

/** 一条连接上的 AgentSupport：initialize、session/new | load | resume | fork | list | delete（含自动选会话扩展与会话级 MCP 服务器）。 */
internal class AcpAgentSupport(
    private val engine: RuntimeEngine,
    private val caller: CallerIdentity,
    private val gate: OutboundGate,
    private val config: AcpConfig,
    private val runtimeVersion: String,
    /** `session/load` 在返回会话之前要把历史发给客户端；SDK 的会话上下文在那之前还没有，所以直接用连接发通知。 */
    private val protocol: Protocol,
) : AgentSupport {
    @Volatile private var extensions: Set<String> = emptySet()

    /** 客户端协商了会话建立收尾标记（[ProfileExtensions.SESSION_SETUP]）。 */
    private fun setupNegotiated(): Boolean = ProfileExtensions.SESSION_SETUP in extensions

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        extensions = ProfileExtensions.clientExtensions(clientInfo._meta)
        return AgentInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = AgentCapabilities(
                loadSession = true,
                promptCapabilities = PromptCapabilities(audio = false, image = config.promptImages, embeddedContext = true),
                // 只有 Streamable HTTP（"http"）；stdio 在 Android 上没有意义，SSE 是 MCP 已经弃用的传输。构建没接会话级工具时一个都不声明
                mcpCapabilities = McpCapabilities(http = engine.supportsSessionTools, sse = false),
                sessionCapabilities = SessionCapabilities(
                    fork = SessionForkCapabilities(),
                    list = SessionListCapabilities(),
                    resume = SessionResumeCapabilities(),
                    delete = SessionDeleteCapabilities(),
                    close = SessionCloseCapabilities(),
                ),
            ),
            implementation = Implementation(name = "agentos", version = runtimeVersion, title = "AgentOS"),
            _meta = ProfileExtensions.initializeMeta(runtimeVersion),
        )
    }

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        // 先校验形状：不对就不创建会话（mcpServers、toolScope、autoSelect 都一样）
        val servers = mcpServers(sessionParameters.mcpServers)
        val cwd = sessionParameters.cwd.takeIf { it.isNotBlank() }
        // 工具范围：先于任何会话操作校验，形状不对不创建会话（docs/third-party-acp.md 4.5）
        val toolScope: List<ToolRef>? = when (val parsed = ProfileExtensions.toolScope(sessionParameters._meta)) {
            ProfileExtensions.ToolScopeParse.Absent -> null
            is ProfileExtensions.ToolScopeParse.Scope -> parsed.refs
            is ProfileExtensions.ToolScopeParse.Invalid -> throw rpcError(ErrorCode.INVALID_PARAMS.info(parsed.reason))
        }
        val query = ProfileExtensions.autoSelectQuery(sessionParameters._meta)
        if (query != null) {
            if (ProfileExtensions.SESSION_AUTO_SELECT !in extensions) {
                throw rpcError(ErrorCode.INVALID_PARAMS.info("the ${ProfileExtensions.SESSION_AUTO_SELECT} extension was not negotiated in initialize"))
            }
            if (query.isEmpty()) throw rpcError(ErrorCode.INVALID_PARAMS.info("autoSelect.query must be a non-empty string"))
            val result = guarded { engine.autoSelect(caller, query, cwd, toolScope) }
            // 自动选到的可能是已有的会话：它的会话级服务器按这次带来的替换；新建的会话被拒绝时删掉
            val mcp = attachServers(result.session.id, servers, discardOnReject = result.created, always = !result.created)
            return AcpSession(result.session, engine, caller, gate, config, selection = result, mcp = mcp, setupMarker = setupNegotiated())
        }
        val session = guarded { engine.createSession(caller, cwd, toolScope) }
        val mcp = attachServers(session.id, servers, discardOnReject = true, always = false)
        return AcpSession(session, engine, caller, gate, config, selection = null, mcp = mcp, setupMarker = setupNegotiated())
    }

    // ---- 取回会话：session/load（重放历史）、session/resume（不重放） ----

    override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession =
        reopen(sessionId.value, sessionParameters, replay = true)

    override suspend fun resumeSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession =
        reopen(sessionId.value, sessionParameters, replay = false)

    /**
     * 取回一个已有的会话。归属检查在引擎里（不是自己的会话一律 session_not_found）。
     * 请求里的 `_meta.toolScope` **忽略**：范围属于会话，创建时定下，加载不能改（docs/third-party-acp.md 4.5）。
     * 请求里的 `mcpServers` 是这次连接要用的那批，**替换**会话原来挂的（它们只在内存里，进程重启后要客户端重新带上来）。
     */
    private suspend fun reopen(id: String, sessionParameters: SessionCreationParameters, replay: Boolean): AgentSession {
        val servers = mcpServers(sessionParameters.mcpServers)
        val session = guarded { engine.session(caller, id) }
        val mcp = attachServers(id, servers, discardOnReject = false, always = true)
        // ACP：load 在响应之前把整段历史重放完。SDK 的 postInitialize 在响应之后才跑，所以重放在这里做
        val replayed = if (replay) replayHistory(id) else 0
        val active = guarded { engine.activeTask(caller, id) }?.let { it.id to it.state.wire }
        // 会话信息（挂了哪些服务器、还有没有一轮在跑、收尾标记）同样在响应之前发，并且是最后一条
        ProfileExtensions.sessionMeta(null, mcp, active, replayed = if (setupNegotiated()) replayed else null)?.let { meta ->
            AcpMethod.ClientMethods.V1.SessionUpdate.invoke(protocol, SessionNotification(SessionId(id), SessionUpdate.SessionInfoUpdate(_meta = meta)))
        }
        return AcpSession(session, engine, caller, gate, config, selection = null)
    }

    /**
     * `session/load`：把这个会话已经提交的历史按顺序重放成 `session/update`（用户消息、助手文字、思考、工具调用），重放完 load 才返回。
     * 会话很长时只重放最近 [AcpConfig.maxReplayTurns] 轮。每发一条之前等传输层的积压降下来（背压）；连接断开时随协程取消结束。
     * 重放的是事件日志，所以 load 时还在跑的那一轮已经提交的部分也在里面；剩下的部分客户端等任务结束后再取（`activeTask`）。
     */
    private suspend fun replayHistory(id: String): Int {
        val replayer = HistoryReplayer(guarded { engine.replayTaskIds(caller, id, config.maxReplayTurns) })
        var cursor = 0L
        var sent = 0
        while (true) {
            val batch = engine.readEvents(id, cursor, REPLAY_BATCH)
            if (batch.isEmpty()) break
            for (e in batch) {
                cursor = e.sequence
                for (update in replayer.map(e)) {
                    gate.awaitWritable()
                    AcpMethod.ClientMethods.V1.SessionUpdate.invoke(protocol, SessionNotification(SessionId(id), update))
                    sent++
                }
            }
        }
        return sent
    }

    // ---- 分叉、列表、删除 ----

    override suspend fun forkSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        val servers = mcpServers(sessionParameters.mcpServers)
        // 分叉请求自己的范围只能在原会话的范围之上收窄（引擎取交集）
        val narrower: List<ToolRef>? = when (val parsed = ProfileExtensions.toolScope(sessionParameters._meta)) {
            ProfileExtensions.ToolScopeParse.Absent -> null
            is ProfileExtensions.ToolScopeParse.Scope -> parsed.refs
            is ProfileExtensions.ToolScopeParse.Invalid -> throw rpcError(ErrorCode.INVALID_PARAMS.info(parsed.reason))
        }
        val session = guarded { engine.forkSession(caller, sessionId.value, narrower) }
        val mcp = attachServers(session.id, servers, discardOnReject = true, always = false)
        return AcpSession(session, engine, caller, gate, config, selection = null, mcp = mcp, setupMarker = setupNegotiated())
    }

    override suspend fun listSessions(cwd: String?, additionalDirectories: List<String>?, _meta: JsonElement?): Sequence<SessionInfo> =
        guarded { engine.listSessions(caller, cwd) }.map { it.toInfo() }.asSequence()

    override suspend fun deleteSession(sessionId: SessionId, _meta: JsonElement?): DeleteSessionResponse {
        guarded { engine.deleteSession(caller, sessionId.value) }
        return DeleteSessionResponse()
    }

    // ---- 会话级 MCP 服务器（ACP mcpServers） ----

    /**
     * ACP 的 `mcpServers` → 会话级工具端口的 [SessionMcpServer]。只收 Streamable HTTP（`type: "http"`）：
     * stdio 要在手机上起任意进程，SSE 是 MCP 已经弃用的传输，都明确拒绝（`unsupported`），不静默忽略。
     * 错误信息只说是第几个、什么类型，**不回显**名字、URL、头（可能含凭据）。构建没接会话级工具时，非空的列表一律拒绝。
     */
    private fun mcpServers(list: List<McpServer>): List<SessionMcpServer> {
        if (list.isEmpty()) return emptyList()
        if (!engine.supportsSessionTools) throw rpcError(ErrorCode.UNSUPPORTED.info("mcpServers are not supported on this build"))
        return list.mapIndexed { i, server ->
            when (server) {
                is McpServer.Http -> SessionMcpServer(server.name, server.url, server.headers.map { it.name to it.value })
                is McpServer.Sse -> throw rpcError(ErrorCode.UNSUPPORTED.info("mcpServers[$i]: sse servers are not supported; use an http (Streamable HTTP) server"))
                is McpServer.Stdio -> throw rpcError(ErrorCode.UNSUPPORTED.info("mcpServers[$i]: stdio servers are not supported; use an http (Streamable HTTP) server"))
            }
        }
    }

    /**
     * 把 [servers] 挂到会话上，返回各服务器的结局（连不上的不让会话建立失败，结局里 `connected = false`，经 `session_info_update` 告知客户端）。
     * 被拒绝（形状或限制不对）：[discardOnReject] 时删掉刚建的空会话，然后报 invalid_params（超出配额报 quota_exceeded）。
     * [always] 为 false 且 [servers] 为空时什么也不做；`session/load | resume` 传 true：空列表也要执行，让会话原来挂的那批被替换掉。
     */
    private suspend fun attachServers(sessionId: String, servers: List<SessionMcpServer>, discardOnReject: Boolean, always: Boolean): List<SessionMcpResult>? {
        if (servers.isEmpty() && !always) return null
        if (servers.isEmpty() && !engine.supportsSessionTools) return null
        return try {
            engine.attachSessionTools(caller, sessionId, servers)
        } catch (e: SessionMcpRejected) {
            if (discardOnReject) runCatching { engine.deleteSession(caller, sessionId) }
            val message = "mcpServers rejected (${e.reason}): ${e.message}"
            // 配额超限带 details.reason = mcp_servers，和“每小时 prompt 次数”(hourly) 区分开
            throw rpcError(
                if (e.reason == "quota") ErrorCode.QUOTA_EXCEEDED.info(message, kotlinx.serialization.json.buildJsonObject { put("reason", "mcp_servers") })
                else ErrorCode.INVALID_PARAMS.info(message),
            )
        } catch (e: AgentOsException) {
            if (discardOnReject) runCatching { engine.deleteSession(caller, sessionId) }
            throw rpcError(e.info)
        }
    }

    private fun SessionRecord.toInfo(): SessionInfo = SessionInfo(
        sessionId = SessionId(id),
        cwd = cwd.orEmpty(),
        title = selection.firstQuery?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_CHARS),
        updatedAt = java.time.Instant.ofEpochMilli(lastActivityAt).toString(),
    )

    private companion object {
        const val TITLE_CHARS = 80
        const val REPLAY_BATCH = 500
    }
}

/**
 * 一个 ACP 会话。prompt：持久化提交 → 从事件日志按顺序读这一轮的已提交事件 → 映射成 session/update →
 * 等到任务结束返回 stopReason（ACP v1：一轮结束才返回）。
 *
 * 会话的模式和模型（`session/set_mode | set_model | set_config_option`）存在会话上、下一个任务起生效；这里缓存当前值，
 * 因为 SDK 在 `session/new | load | resume | fork` 的响应里同步读 [defaultMode]、[defaultModel]、[configOptions]。
 */
internal class AcpSession(
    initial: SessionRecord,
    private val engine: RuntimeEngine,
    private val caller: CallerIdentity,
    private val gate: OutboundGate,
    private val config: AcpConfig,
    private val selection: SessionRouter.Result?,
    /** 这次挂的会话级 MCP 服务器的结局（没挂为 null）。 */
    private val mcp: List<SessionMcpResult>? = null,
    /** `session/load | resume` 时会话里还没结束的任务：`(taskId, state)`。 */
    private val activeTask: Pair<String, String>? = null,
    /** 客户端协商了 [ProfileExtensions.SESSION_SETUP]：建立的最后发收尾标记（新建 / 分叉没有重放，`replayed = 0`）。 */
    private val setupMarker: Boolean = false,
) : AgentSession {
    private val id: String = initial.id
    override val sessionId: SessionId = SessionId(id)

    @Volatile private var mode: AgentOsMode = initial.mode

    @Volatile private var modelId: String? = engine.currentModelId(initial)

    private companion object {
        /** [awaitCancelHandoff] 的兜底：cancel 最多等 cancelWaitMillis，再留这么多余量。 */
        const val CANCEL_HANDOFF_MARGIN_MILLIS = 5_000L

        const val CONFIG_MODE = "mode"
        const val CONFIG_MODEL = "model"
    }

    // ---- 模式、模型、配置项 ----

    override val availableModes: List<AcpSessionMode> =
        AgentOsMode.entries.map { AcpSessionMode(SessionModeId(it.wire), it.title, it.description) }

    override val defaultMode: SessionModeId get() = SessionModeId(mode.wire)

    override val availableModels: List<ModelInfo> get() = engine.availableModels().map { ModelInfo(ModelId(it.id), it.name) }

    // SDK 的 asModelState 在 availableModels 为空时直接不发 models 状态，不会读 defaultModel
    override val defaultModel: ModelId get() = ModelId(modelId ?: engine.availableModels().firstOrNull()?.id.orEmpty())

    override val configOptions: List<SessionConfigOption> get() = buildList {
        add(
            SessionConfigOption.Select(
                id = SessionConfigId(CONFIG_MODE),
                name = "Mode",
                description = "What the agent may do in this session. It can only narrow the tools the session was created with.",
                category = SessionConfigOptionCategory.MODE,
                currentValue = SessionConfigValueId(mode.wire),
                options = SessionConfigSelectOptions.Flat(AgentOsMode.entries.map { SessionConfigSelectOption(SessionConfigValueId(it.wire), it.title, it.description) }),
            ),
        )
        val models = engine.availableModels()
        if (models.isNotEmpty()) {
            add(
                SessionConfigOption.Select(
                    id = SessionConfigId(CONFIG_MODEL),
                    name = "Model",
                    description = "The model for this session's next turns. Only models that share the key the user configured in AgentOS.",
                    category = SessionConfigOptionCategory.MODEL,
                    currentValue = SessionConfigValueId(modelId ?: models.first().id),
                    options = SessionConfigSelectOptions.Flat(models.map { SessionConfigSelectOption(SessionConfigValueId(it.id), it.name, null) }),
                ),
            )
        }
    }

    override suspend fun setMode(modeId: SessionModeId, _meta: JsonElement?): SetSessionModeResponse {
        applyMode(modeId.value)
        announceConfig(modeChanged = true)
        return SetSessionModeResponse()
    }

    override suspend fun setModel(modelId: ModelId, _meta: JsonElement?): SetSessionModelResponse {
        applyModel(modelId.value)
        announceConfig(modeChanged = false)
        return SetSessionModelResponse()
    }

    override suspend fun setConfigOption(configId: SessionConfigId, value: SessionConfigOptionValue, _meta: JsonElement?): SetSessionConfigOptionResponse {
        val text = (value as? SessionConfigOptionValue.StringValue)?.value
            ?: throw rpcError(ErrorCode.INVALID_PARAMS.info("the value of this config option must be a string"))
        when (configId.value) {
            CONFIG_MODE -> applyMode(text)
            CONFIG_MODEL -> applyModel(text)
            else -> throw rpcError(ErrorCode.INVALID_PARAMS.info("unknown config option"))
        }
        return SetSessionConfigOptionResponse(configOptions)
    }

    private suspend fun applyMode(wire: String) {
        // 空串和未知的名字都拒绝（SessionMode.parse 会把空白当 default，这里不接受：调用方要明确说）
        val m = AgentOsMode.entries.firstOrNull { it.wire == wire } ?: throw rpcError(ErrorCode.INVALID_PARAMS.info("unknown mode"))
        guarded { engine.setSessionMode(caller, id, m) }
        mode = m
    }

    private suspend fun applyModel(wire: String) {
        if (wire.isBlank()) throw rpcError(ErrorCode.INVALID_PARAMS.info("unknown model for this session"))
        guarded { engine.setSessionModel(caller, id, wire) }
        modelId = wire
    }

    /** 客户端自己改的，响应已经告诉它了；再发一条更新是为了同一会话的其他观察者（尽力而为，失败不影响设置）。 */
    private suspend fun announceConfig(modeChanged: Boolean) {
        runCatching {
            val client = currentCoroutineContext().client
            if (modeChanged) client.notify(SessionUpdate.CurrentModeUpdate(SessionModeId(mode.wire)), null)
            client.notify(SessionUpdate.ConfigOptionUpdate(configOptions), null)
        }
    }

    // ---- close ----

    /** `session/close`：取消没结束的任务、释放会话占的内存；会话和历史保留，以后可以 load | resume 回来。 */
    override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
        guarded { engine.closeSession(caller, id) }
        return CloseSessionResponse()
    }

    // ---- 建立之后：历史重放、通知 ----

    override suspend fun postInitialize() {
        // 自动选会话的结果、会话级服务器的结局、还在跑的那一轮：只对读 _meta 的客户端有意义；普通字段为空，标准客户端看到的是一条无内容的会话信息更新
        val meta = ProfileExtensions.sessionMeta(selection, mcp, activeTask, replayed = if (setupMarker) 0 else null) ?: return
        runCatching { currentCoroutineContext().client.notify(SessionUpdate.SessionInfoUpdate(_meta = meta), null) }
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
        // 第三方 App 先按自己的文字上限判（invalid_params / too_large，docs/third-party-acp.md 4.6），再看协议的总上限
        engine.quota.checkSize(caller, chars)?.let { throw rpcError(it.toError(), sessionId = id) }
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
