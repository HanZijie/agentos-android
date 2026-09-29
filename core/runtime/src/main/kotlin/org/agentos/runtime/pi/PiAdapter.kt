package org.agentos.runtime.pi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.events.RuntimeJson
import org.agentos.runtime.net.HostFetch
import org.agentos.runtime.net.RetryPolicy
import org.agentos.runtime.ports.AgentCore
import org.agentos.runtime.ports.AgentCoreFactory
import org.agentos.runtime.ports.AgentCoreSession
import org.agentos.runtime.ports.AgentCoreState
import org.agentos.runtime.ports.AgentCoreUnavailableException
import org.agentos.runtime.ports.AgentSessionConfig
import org.agentos.runtime.ports.ContentPart
import org.agentos.runtime.ports.FinishReason
import org.agentos.runtime.ports.ModelSpec
import org.agentos.runtime.ports.PiMessages
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.SecretPort
import org.agentos.runtime.ports.ToolCall
import org.agentos.runtime.ports.ToolCallDecision
import org.agentos.runtime.ports.ToolDeclaration
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.TurnHost
import org.agentos.runtime.ports.TurnInput
import org.agentos.runtime.ports.TurnOutcome
import org.agentos.runtime.ports.info
import org.agentos.runtime.ports.warn
import java.util.concurrent.ConcurrentHashMap

/**
 * [AgentCore] on the upstream Pi Agent core: one [PiRuntime] (QuickJS + pump, docs/spikes/S8.md b)
 * hosts every session's Pi `Agent`. Implements the contract in ports/AgentCore.kt:
 *
 * - `runTurn` returns after Pi's `agent_end`; events go to [TurnHost.onEvent] in order, on the JS
 *   thread, mapped by [PiEventMapper];
 * - tool calls, `beforeToolCall` and `afterToolCall` come back to the [TurnHost]; a blocked tool is
 *   not executed and Pi, like Claude Code's PostToolUse, does not call `afterToolCall` for it;
 * - `abort` ends the turn as [TurnOutcome.Aborted] through Pi's own abort (no interrupt of the JS
 *   evaluation); a running `executeTool` coroutine is cancelled; Pi's `shouldStopAfterTurn` ends
 *   the run after the tool batch, so no further model request is started;
 * - the tool round limit is counted here (assistant messages with tool calls); the round over the
 *   limit is blocked with Pi's `terminate` hint, so every tool call still gets an (error) result
 *   and the transcript stays valid for the next turn;
 * - model failures are classified from what HostFetch observed ([PiErrorClassifier]);
 * - a pump failure turns [state] into Failed, running turns return [TurnOutcome.CoreLost] and
 *   every later call throws [AgentCoreUnavailableException].
 *
 * Keys never reach JS: requests go through [HostFetch] with [secrets].
 */
class PiAdapter(
    private val bundle: suspend () -> PiBundle,
    private val engineFactory: JsEngineFactory,
    secrets: SecretPort,
    private val bytecodeCache: BytecodeCache? = null,
    retry: RetryPolicy = RetryPolicy.NONE,
    httpClient: OkHttpClient = HostFetch.defaultClient(),
    private val log: RuntimeLog = RuntimeLog.NONE,
) : AgentCore {

    private val fetch = HostFetch(secrets, httpClient, retry)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateFlow = MutableStateFlow<AgentCoreState>(AgentCoreState.Idle)
    override val state: StateFlow<AgentCoreState> = stateFlow.asStateFlow()

    private val startLock = Mutex()
    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var runtime: PiRuntime? = null
    @Volatile private var closing = false

    /** For diagnostics (startup timings, engine). */
    @Volatile var startup: PiStartup? = null
        private set

    // ------------------------------------------------------------------ AgentCore

    override suspend fun start() {
        startLock.withLock {
            when (val s = stateFlow.value) {
                AgentCoreState.Ready -> return
                is AgentCoreState.Failed, AgentCoreState.Closed -> throw AgentCoreUnavailableException("Pi agent core is $s")
                else -> Unit
            }
            stateFlow.value = AgentCoreState.Starting
            val rt = PiRuntime(engineFactory, fetch, bridge, bridge, bytecodeCache)
            runtime = rt
            try {
                startup = rt.start(bundle())
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    stateFlow.value = AgentCoreState.Closed
                    throw e
                }
                fail(ErrorCode.AGENT_CORE_FAILED.info("Pi runtime failed to start: ${e.javaClass.simpleName}"))
                throw AgentCoreUnavailableException("Pi runtime failed to start", e)
            }
            if (stateFlow.value == AgentCoreState.Starting) stateFlow.value = AgentCoreState.Ready
            startup?.let { log.info(TAG, "Pi runtime started: $it") }
        }
    }

    override suspend fun openSession(sessionId: String, config: AgentSessionConfig, restore: PiMessages?): AgentCoreSession {
        val rt = readyRuntime()
        val session = Session(sessionId, config)
        check(sessions.putIfAbsent(sessionId, session) == null) { "session $sessionId is already open" }
        try {
            rt.createSession(
                sid = sessionId,
                model = config.model.model,
                systemPrompt = config.systemPrompt,
                tools = toolsJson(config.tools),
                messages = restore?.json,
                thinkingLevel = config.model.thinkingLevel,
            )
        } catch (e: Throwable) {
            sessions.remove(sessionId, session)
            throw translate(e)
        }
        return session
    }

    override suspend fun close() {
        if (stateFlow.value == AgentCoreState.Closed) return
        closing = true
        withContext(NonCancellable) {
            sessions.values.forEach { it.onClosing() }
            runCatching { runtime?.close() }
        }
        fetch.close() // ends the key-revocation subscription
        sessions.clear()
        stateFlow.value = AgentCoreState.Closed
        scope.cancel()
    }

    // ------------------------------------------------------------------ internals

    private fun readyRuntime(): PiRuntime {
        when (val s = stateFlow.value) {
            AgentCoreState.Ready -> return runtime ?: throw AgentCoreUnavailableException("Pi agent core has no runtime")
            is AgentCoreState.Failed, AgentCoreState.Closed -> throw AgentCoreUnavailableException("Pi agent core is $s")
            else -> throw IllegalStateException("Pi agent core is not started: $s")
        }
    }

    private fun fail(error: ErrorInfo) {
        synchronized(this) {
            val s = stateFlow.value
            if (s is AgentCoreState.Failed || s == AgentCoreState.Closed) return
            stateFlow.value = AgentCoreState.Failed(error)
        }
        sessions.values.forEach { it.onLost(error) }
    }

    private fun lostError(): ErrorInfo =
        (stateFlow.value as? AgentCoreState.Failed)?.error ?: ErrorCode.AGENT_CORE_FAILED.info("Pi runtime stopped")

    private fun translate(e: Throwable): Throwable = when (e) {
        is PiRuntimeStoppedException -> AgentCoreUnavailableException("Pi agent core is unavailable", e)
        is JsException -> IllegalStateException("Pi runtime rejected the command: ${e.message}", e)
        else -> e
    }

    /** Callbacks from the runtime, routed to the session they belong to. */
    private val bridge = object : PiRuntimeListener, PiHost {
        override fun onEvent(sid: String, event: JsonObject) {
            sessions[sid]?.onEvent(event)
        }

        override fun onModelRequest(outcome: ModelRequestOutcome) {
            outcome.sessionId?.let { sessions[it]?.onModelRequest(outcome) }
        }

        override fun onLog(level: String, message: String) {
            if (level == "error" || level == "warn") log.warn(TAG, "js: ${message.take(500)}")
        }

        override fun onStopped(cause: Throwable?) {
            if (cause == null || closing) return
            log.warn(TAG, "Pi runtime stopped: ${cause.javaClass.simpleName}")
            fail(ErrorCode.AGENT_CORE_FAILED.info("Pi runtime stopped: ${cause.javaClass.simpleName}"))
        }

        override suspend fun executeTool(sid: String, toolCallId: String, name: String, args: JsonElement): JsonObject =
            sessions[sid]?.executeTool(toolCallId, name, args) ?: toolResultJson(ToolResult.text(ABORTED_TOOL, isError = true))

        override suspend fun beforeToolCall(sid: String, toolCallId: String, name: String, args: JsonElement): JsonObject? =
            sessions[sid]?.beforeToolCall(toolCallId, name, args)

        override suspend fun afterToolCall(
            sid: String,
            toolCallId: String,
            name: String,
            args: JsonElement,
            result: JsonElement,
            isError: Boolean,
        ): JsonObject? = sessions[sid]?.afterToolCall(toolCallId, name, args, result, isError)

        override fun onToolCancelled(sid: String, toolCallId: String) {
            sessions[sid]?.cancelTool(toolCallId)
        }
    }

    /** State of one running turn. */
    private class Turn(val host: TurnHost, val limit: Int) {
        @Volatile var rounds = 0
        @Volatile var limitHit = false
        @Volatile var abortRequested = false
        /** The caller of runTurn is gone: no more TurnHost callbacks. */
        @Volatile var detached = false
        @Volatile var lost: ErrorInfo? = null
        val outcomes: MutableList<ModelRequestOutcome> = java.util.Collections.synchronizedList(mutableListOf())
        val toolJobs = ConcurrentHashMap<String, Job>()
        val lostSignal = CompletableDeferred<ErrorInfo>()
        val silent: Boolean get() = detached || lost != null
    }

    private inner class Session(
        override val sessionId: String,
        @Volatile private var config: AgentSessionConfig,
    ) : AgentCoreSession {
        @Volatile private var turn: Turn? = null
        /** The JS prompt of the current (or a detached, still finishing) turn. */
        @Volatile private var inFlight: Deferred<PromptResult>? = null
        @Volatile private var disposed = false

        private fun checkUsable(): PiRuntime {
            check(!disposed) { "session $sessionId is disposed" }
            return readyRuntime()
        }

        override suspend fun reconfigure(config: AgentSessionConfig) {
            val rt = checkUsable()
            check(inFlight == null) { "cannot reconfigure session $sessionId while a turn is running" }
            val old = this.config
            try {
                if (old.model != config.model) rt.setModel(sessionId, config.model.model, config.model.thinkingLevel)
                if (old.tools != config.tools) rt.setTools(sessionId, toolsJson(config.tools))
                if (old.systemPrompt != config.systemPrompt) rt.setSystemPrompt(sessionId, config.systemPrompt)
            } catch (e: Throwable) {
                throw translate(e)
            }
            this.config = config
        }

        override suspend fun runTurn(input: TurnInput, host: TurnHost): TurnOutcome {
            val rt = checkUsable()
            val t = Turn(host, config.maxToolRounds)
            synchronized(this) {
                check(inFlight == null) { "a turn is already running in session $sessionId" }
                turn = t
                inFlight = scope.async { rt.prompt(sessionId, input.text, imagesJson(input.images)) }
            }
            val reply = inFlight!!
            try {
                val result = try {
                    kotlinx.coroutines.selects.select {
                        reply.onAwait { Result.success(it) }
                        t.lostSignal.onAwait { Result.failure(PiRuntimeStoppedException("Pi runtime stopped")) }
                    }.getOrThrow()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PiRuntimeStoppedException) {
                    if (closing) return TurnOutcome.Aborted
                    return TurnOutcome.CoreLost(t.lost ?: lostError().also { fail(it) })
                } catch (e: Throwable) {
                    if (stateFlow.value is AgentCoreState.Failed) return TurnOutcome.CoreLost(lostError())
                    if (closing) return TurnOutcome.Aborted
                    throw translate(e)
                }
                t.lost?.let { return TurnOutcome.CoreLost(it) }
                return outcomeOf(t, result)
            } catch (e: CancellationException) {
                // The caller went away: abort, stop calling the host, keep the session busy until
                // Pi has finished so the next runTurn does not collide with this one.
                t.detached = true
                t.abortRequested = true
                t.toolJobs.values.forEach { it.cancel() }
                scope.launch {
                    runCatching { rt.abort(sessionId) }
                    withTimeoutOrNull(30_000) { reply.join() }
                    finishTurn(t, reply)
                }
                throw e
            } finally {
                if (!t.detached) finishTurn(t, reply)
            }
        }

        private fun finishTurn(t: Turn, reply: Deferred<PromptResult>) {
            synchronized(this) {
                if (turn === t) turn = null
                if (inFlight === reply) inFlight = null
            }
        }

        private fun outcomeOf(t: Turn, r: PromptResult): TurnOutcome {
            val usage = r.appended.lastOrNull { (it as? JsonObject)?.get("role")?.let { role -> (role as? JsonPrimitive)?.contentOrNull } == "assistant" }
                ?.let { (it as JsonObject)["usage"] as? JsonObject }
            return when {
                t.limitHit -> TurnOutcome.ToolRoundLimit(t.rounds, t.limit)
                r.stopReason == "aborted" -> TurnOutcome.Aborted
                t.abortRequested && r.stopReason == "toolUse" -> TurnOutcome.Aborted
                r.stopReason == "error" -> TurnOutcome.Failed(PiErrorClassifier.classify(t.outcomes.toList(), r.errorMessage))
                r.stopReason == "length" -> TurnOutcome.Finished(FinishReason.MAX_TOKENS, usage)
                else -> TurnOutcome.Finished(FinishReason.END_TURN, usage)
            }
        }

        override suspend fun abort() {
            val rt = checkUsable()
            val t = turn ?: return
            t.abortRequested = true
            t.toolJobs.values.forEach { it.cancel() }
            try {
                rt.abort(sessionId)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (e is PiRuntimeStoppedException) throw translate(e)
                // The session may have finished between our check and the command: nothing to abort.
            }
        }

        override suspend fun messages(): PiMessages {
            val rt = checkUsable()
            return try {
                PiMessages(rt.history(sessionId))
            } catch (e: Throwable) {
                throw translate(e)
            }
        }

        override suspend fun dispose() {
            if (disposed) return
            disposed = true
            turn?.let { t ->
                t.abortRequested = true
                t.toolJobs.values.forEach { it.cancel() }
            }
            val rt = runtime
            if (rt != null && stateFlow.value == AgentCoreState.Ready) {
                runCatching { rt.dispose(sessionId) }
            }
            sessions.remove(sessionId, this)
        }

        // ---- called from the runtime

        fun onEvent(json: JsonObject) {
            val t = turn ?: return
            if (t.silent) return
            val event = PiEventMapper.map(json) ?: return
            if (PiEventMapper.isToolRound(event)) t.rounds++
            t.host.onEvent(event)
        }

        fun onModelRequest(outcome: ModelRequestOutcome) {
            turn?.outcomes?.add(outcome)
        }

        fun onLost(error: ErrorInfo) {
            val t = turn ?: return
            t.lost = error
            t.toolJobs.values.forEach { it.cancel() }
            t.lostSignal.complete(error)
        }

        fun onClosing() {
            val t = turn ?: return
            t.abortRequested = true
            t.detached = true
            t.toolJobs.values.forEach { it.cancel() }
        }

        suspend fun beforeToolCall(toolCallId: String, name: String, args: JsonElement): JsonObject? {
            val t = turn ?: return block(ABORTED_TOOL, terminate = true)
            if (t.rounds > t.limit) {
                // One round over the limit: execute nothing, give every call an error result and
                // let Pi end the run (terminate) without another model request.
                t.limitHit = true
                return block(LIMIT_REACHED, terminate = true)
            }
            if (t.silent || t.abortRequested) return block(ABORTED_TOOL, terminate = true)
            return when (val d = t.host.beforeToolCall(ToolCall(toolCallId, name, argsObject(args)))) {
                ToolCallDecision.Allow -> null
                is ToolCallDecision.Block -> block(d.reason, d.terminate)
            }
        }

        suspend fun executeTool(toolCallId: String, name: String, args: JsonElement): JsonObject {
            val t = turn
            if (t == null || t.silent || t.abortRequested) return toolResultJson(ToolResult.text(ABORTED_TOOL, isError = true))
            val result = coroutineScope {
                val job = async { t.host.executeTool(ToolCall(toolCallId, name, argsObject(args))) }
                t.toolJobs[toolCallId] = job
                try {
                    job.await()
                } catch (e: CancellationException) {
                    if (job.isCancelled) null else throw e
                } finally {
                    t.toolJobs.remove(toolCallId, job)
                }
            } ?: return toolResultJson(ToolResult.text(ABORTED_TOOL, isError = true))
            return toolResultJson(result)
        }

        suspend fun afterToolCall(toolCallId: String, name: String, args: JsonElement, result: JsonElement, isError: Boolean): JsonObject? {
            val t = turn ?: return null
            // Pi calls afterToolCall also for a call whose execution was aborted; the contract says no.
            if (t.silent || t.abortRequested || t.limitHit) return null
            val current = toolResultOf(result, isError)
            val changed = t.host.afterToolCall(ToolCall(toolCallId, name, argsObject(args)), current) ?: return null
            return toolResultJson(changed)
        }

        fun cancelTool(toolCallId: String) {
            turn?.toolJobs?.get(toolCallId)?.cancel()
        }
    }

    companion object {
        private const val TAG = "PiAdapter"
        const val ABORTED_TOOL = "Tool call aborted"
        const val LIMIT_REACHED = "Tool round limit reached"

        /** Factory for the host (CoreSessions creates a new instance after a pump failure). */
        fun factory(
            bundle: suspend () -> PiBundle,
            engineFactory: JsEngineFactory,
            secrets: SecretPort,
            bytecodeCache: BytecodeCache? = null,
            retry: RetryPolicy = RetryPolicy.NONE,
            httpClient: OkHttpClient = HostFetch.defaultClient(),
            log: RuntimeLog = RuntimeLog.NONE,
        ): AgentCoreFactory = AgentCoreFactory { PiAdapter(bundle, engineFactory, secrets, bytecodeCache, retry, httpClient, log) }

        internal fun toolsJson(tools: List<ToolDeclaration>): JsonArray = buildJsonArray {
            for (t in tools) add(buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("parameters", t.inputSchema)
                t.label?.let { put("label", it) }
            })
        }

        internal fun imagesJson(images: List<ContentPart.Image>): JsonArray? =
            if (images.isEmpty()) null else JsonArray(images.map { RuntimeJson.encodeToJsonElement(ContentPart.serializer(), it) })

        private fun argsObject(args: JsonElement): JsonObject = args as? JsonObject ?: JsonObject(emptyMap())

        private fun block(reason: String, terminate: Boolean): JsonObject = buildJsonObject {
            put("block", true)
            put("reason", reason)
            if (terminate) put("terminate", true)
        }

        internal fun toolResultJson(r: ToolResult): JsonObject = buildJsonObject {
            put("content", JsonArray(r.content.map { RuntimeJson.encodeToJsonElement(ContentPart.serializer(), it) }))
            r.details?.let { put("details", it) }
            if (r.isError) put("isError", true)
        }

        internal fun toolResultOf(result: JsonElement, isError: Boolean): ToolResult {
            val o = result as? JsonObject ?: return ToolResult(emptyList(), isError)
            val content = (o["content"] as? JsonArray).orEmpty().mapNotNull { el ->
                runCatching { RuntimeJson.decodeFromJsonElement(ContentPart.serializer(), el) }.getOrNull()
            }
            val details = o["details"]?.takeUnless { it is JsonNull || (it is JsonObject && it.isEmpty()) }
            return ToolResult(content, isError, details)
        }
    }
}
