@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * SDK 里不依赖 Android 的部分：重试、错误映射、事件映射、toolScope 的 `_meta`。公开类型不暴露 ACP 的任何类。
 * 这些函数都是 internal，有 JVM 单测。
 */
internal object AgentOsMapping {

    // ------------------------------------------------------------------ 授权重试（docs/third-party-acp.md 4.1 第 3 点）

    /**
     * 反复调用 [attempt]（一次 `IAcpService.open`），只要它抛 `agentos.acp.authorization_pending` 就每 [intervalMillis] 重试，
     * 最多 [timeoutMillis]；每次待决回调 [onWaiting]。其他异常原样抛出。超时抛 [AgentOsError.AUTHORIZATION_PENDING_TIMEOUT]。
     * [now] 是单调毫秒时钟（测试用虚拟时间）。
     */
    suspend fun <T> retryWhilePending(
        timeoutMillis: Long,
        intervalMillis: Long,
        now: () -> Long,
        onWaiting: (Waiting) -> Unit,
        attempt: suspend () -> T,
    ): T {
        val start = now()
        while (true) {
            try {
                return attempt()
            } catch (e: SecurityException) {
                if (AcpServiceContract.reasonOf(e) != AcpServiceContract.REASON_AUTHORIZATION_PENDING) throw e
            }
            val elapsed = now() - start
            if (elapsed >= timeoutMillis) {
                throw AgentOsException(AgentOsError.AUTHORIZATION_PENDING_TIMEOUT, "the user did not decide within ${timeoutMillis / 1000} s")
            }
            onWaiting(Waiting(elapsed, timeoutMillis))
            delay(minOf(intervalMillis, timeoutMillis - elapsed))
        }
    }

    // ------------------------------------------------------------------ 错误映射

    /** `IAcpService.open` 的拒绝（SecurityException，message 形如 `agentos.acp.<code>: …`）→ [AgentOsException]。 */
    fun fromOpen(e: SecurityException): AgentOsException = when (AcpServiceContract.reasonOf(e)) {
        AcpServiceContract.REASON_DENIED -> AgentOsException(AgentOsError.DENIED, "the user denied this app")
        AcpServiceContract.REASON_NOT_OPEN -> AgentOsException(AgentOsError.NOT_INSTALLED, "this AgentOS does not accept this app")
        else -> AgentOsException(AgentOsError.FAILED, "open was refused (${AcpServiceContract.reasonOf(e) ?: "security"})")
    }

    /** `session/new`、`session/prompt` 的 JSON-RPC 错误 → [AgentOsException]（errors.md：`data.agentosCode`、`data.details.reason`）。 */
    fun fromRpc(e: JsonRpcException): AgentOsException {
        val data = e.data as? JsonObject
        val code = (data?.get("agentosCode") as? JsonPrimitive)?.contentOrNull
        val reason = ((data?.get("details") as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
        val error = when {
            code == "model_not_configured" -> AgentOsError.NO_MODEL
            code == "rate_limited" && reason == "busy" -> AgentOsError.BUSY
            code == "rate_limited" -> AgentOsError.RATE_LIMITED
            e.code == RPC_BUSY -> AgentOsError.BUSY
            e.code == RPC_QUOTA_EXCEEDED -> AgentOsError.RATE_LIMITED
            code == "invalid_params" && reason == "too_large" -> AgentOsError.TOO_LARGE
            e.code == RPC_PAYLOAD_TOO_LARGE -> AgentOsError.TOO_LARGE
            else -> AgentOsError.FAILED
        }
        // 不把服务端的 message 原样带出去（可能含用户文字）：只留代码
        return AgentOsException(error, "rpc ${e.code}${code?.let { " $it" }.orEmpty()}${reason?.let { " ($it)" }.orEmpty()}")
    }

    /** 协议关闭 / 对端死亡 / 取消导致的请求失败。[callerCancelled] 为 true 时不应该走到这里（调用方自己取消，直接重抛）。 */
    fun closed(cause: Throwable?): AgentOsException =
        AgentOsException(AgentOsError.DISCONNECTED, "the connection to AgentOS closed", cause)

    // ------------------------------------------------------------------ toolScope（4.5）

    const val MAX_SCOPE_ITEMS = 32
    const val MAX_SCOPE_STRING_CHARS = 128

    /** `session/new` 的 `_meta`：`{"org.agentos": {"toolScope": [{"plugin","tool"}, …]}}`。形状不合法是调用方的编程错误。 */
    fun scopeMeta(scope: List<ToolRef>): JsonObject {
        require(scope.size <= MAX_SCOPE_ITEMS) { "toolScope has at most $MAX_SCOPE_ITEMS items" }
        for (r in scope) {
            require(r.plugin.isNotEmpty() && r.tool.isNotEmpty()) { "toolScope items need a plugin and a tool" }
            require(r.plugin.length <= MAX_SCOPE_STRING_CHARS && r.tool.length <= MAX_SCOPE_STRING_CHARS) { "toolScope names are at most $MAX_SCOPE_STRING_CHARS chars" }
        }
        return buildJsonObject {
            put("org.agentos", buildJsonObject {
                put("toolScope", buildJsonArray {
                    scope.distinct().forEach { add(buildJsonObject { put("plugin", it.plugin); put("tool", it.tool) }) }
                })
            })
        }
    }

    // ------------------------------------------------------------------ 事件映射（4.7）

    /**
     * 一轮 prompt 里的事件映射。同一个工具调用的几次更新共用一份记录（工具名、对应的 toolScope 项、参数、最近的状态），
     * 所以 `tool_call_update` 这种只带变化字段的补丁也能给出完整的 [AgentOsEvent.ToolCall]。
     */
    class PromptMapper(private val scope: List<ToolRef>) {
        private class Known(val tool: String, val ref: ToolRef?, val args: String?, var status: ToolStatus)

        private val calls = HashMap<String, Known>()

        fun map(update: SessionUpdate): AgentOsEvent? = when (update) {
            is SessionUpdate.AgentMessageChunk -> (update.content as? ContentBlock.Text)?.text?.takeIf { it.isNotEmpty() }?.let { AgentOsEvent.Text(it) }
            is SessionUpdate.ToolCall -> {
                val id = update.toolCallId.value
                val ref = refFor(update.title, scope)
                val result = text(update.content)
                val known = Known(ref?.tool ?: update.title, ref, update.rawInput?.toString(), status(update.status, result) ?: ToolStatus.PENDING_APPROVAL)
                calls[id] = known
                AgentOsEvent.ToolCall(id, known.tool, known.status, result, known.args, known.ref)
            }
            is SessionUpdate.ToolCallUpdate -> {
                val id = update.toolCallId.value
                val known = calls[id] ?: Known(update.title ?: "tool", null, null, ToolStatus.RUNNING).also { calls[id] = it }
                val result = text(update.content)
                status(update.status, result)?.let { known.status = it }
                AgentOsEvent.ToolCall(id, known.tool, known.status, result, known.args, known.ref)
            }
            else -> null // 思考、计划、用量…不对外
        }

        private fun text(content: List<ToolCallContent>?): String? =
            content?.mapNotNull { ((it as? ToolCallContent.Content)?.content as? ContentBlock.Text)?.text }?.joinToString("")?.takeIf { it.isNotEmpty() }

        private fun status(s: ToolCallStatus?, result: String?): ToolStatus? = when (s) {
            null -> null
            ToolCallStatus.PENDING -> ToolStatus.PENDING_APPROVAL
            ToolCallStatus.IN_PROGRESS -> ToolStatus.RUNNING
            ToolCallStatus.COMPLETED -> ToolStatus.COMPLETED
            ToolCallStatus.FAILED -> if (result?.startsWith(DENIED_PREFIX) == true) ToolStatus.DENIED else ToolStatus.FAILED
        }
    }

    /** 用户拒绝（或确认超时）时交回模型、也出现在 `tool_call_update` 里的文字前缀（CapabilityBroker 的 modelText）。 */
    const val DENIED_PREFIX = "[agentos:tool_denied]"

    /**
     * AgentOS 给模型的最终工具名 `mcp__<插件名>__<服务器>__<工具>`（字符集 `[A-Za-z0-9_-]`，其他换成 `_`）反推 toolScope 里的哪一项。
     * 总长超过 64 被截断并加哈希的名字对不上，返回 null（上层仍拿到最终名字）。
     */
    fun refFor(finalName: String, scope: List<ToolRef>): ToolRef? {
        if (!finalName.startsWith("mcp__")) return scope.firstOrNull { it.tool == finalName }
        return scope.firstOrNull { r ->
            finalName.startsWith("mcp__${sanitize(r.plugin)}__") && finalName.endsWith("__${sanitize(r.tool)}")
        }
    }

    private fun sanitize(s: String): String = s.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-') it else '_' }.joinToString("")

    fun stopReasonWire(r: StopReason): String = when (r) {
        StopReason.END_TURN -> "end_turn"
        StopReason.MAX_TOKENS -> "max_tokens"
        StopReason.MAX_TURN_REQUESTS -> "max_turn_requests"
        StopReason.REFUSAL -> "refusal"
        StopReason.CANCELLED -> "cancelled"
    }

    /** 文字上限（docs/third-party-acp.md 第 3 节）；服务端 CallerQuota 是权威，这里只是少走一趟 Binder。 */
    const val MAX_PROMPT_CHARS = 16_000

    // errors.md 的 JSON-RPC 码（core/runtime 的 RpcCodes）
    private const val RPC_PAYLOAD_TOO_LARGE = -32046
    private const val RPC_BUSY = -32047
    private const val RPC_QUOTA_EXCEEDED = -32048
}
