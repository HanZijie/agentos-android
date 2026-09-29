package org.agentos.runtime.errors

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject

/**
 * AgentOS 的稳定错误码。权威定义在 core/contracts/errors.md，这里与它逐条对应（ErrorCodeTest 守住）。
 *
 * - [wire]：写进事件日志、`task.failed`、JSON-RPC `error.data.agentosCode` 的字符串，冻结后不改名。
 * - [retryable]：条件是否是暂时的——同样的操作过一会儿再做可能成功，且重做不会重复外部副作用。
 *   它**不**表示宿主层会自动重试：自动重试的范围见 errors.md 第 4 节（只重试还没产生输出的模型请求，工具调用永不自动重做）。
 * - [rpcCode]：这个错误直接作为 JSON-RPC 错误返回给 ACP 客户端时用的 code。
 * - [kind]：错误出现的位置，决定它怎么呈现（见 [Kind]）。
 */
@Serializable(with = ErrorCode.WireSerializer::class)
enum class ErrorCode(
    val wire: String,
    val retryable: Boolean,
    val rpcCode: Int,
    val kind: Kind,
) {
    // ---- 请求错误：请求本身被拒绝，立即作为 JSON-RPC 错误返回，不产生任务 ----
    INVALID_PARAMS("invalid_params", false, RpcCodes.INVALID_PARAMS, Kind.REQUEST),
    UNSUPPORTED("unsupported", false, RpcCodes.INVALID_PARAMS, Kind.REQUEST),
    AUTH_REQUIRED("auth_required", false, RpcCodes.AUTH_REQUIRED, Kind.REQUEST),
    NOT_OPEN("not_open", false, RpcCodes.NOT_OPEN, Kind.REQUEST),
    FORBIDDEN("forbidden", false, RpcCodes.FORBIDDEN, Kind.REQUEST),
    SESSION_NOT_FOUND("session_not_found", false, RpcCodes.RESOURCE_NOT_FOUND, Kind.REQUEST),
    TASK_NOT_FOUND("task_not_found", false, RpcCodes.RESOURCE_NOT_FOUND, Kind.REQUEST),
    INVALID_STATE("invalid_state", false, RpcCodes.INVALID_STATE, Kind.REQUEST),
    REQUEST_CONFLICT("request_conflict", false, RpcCodes.REQUEST_CONFLICT, Kind.REQUEST),
    SESSION_TERMINAL("session_terminal", false, RpcCodes.SESSION_TERMINAL, Kind.REQUEST),
    CURSOR_TOO_OLD("cursor_too_old", false, RpcCodes.CURSOR_TOO_OLD, Kind.REQUEST),
    PAYLOAD_TOO_LARGE("payload_too_large", false, RpcCodes.PAYLOAD_TOO_LARGE, Kind.REQUEST),
    BUSY("busy", true, RpcCodes.BUSY, Kind.REQUEST),
    QUOTA_EXCEEDED("quota_exceeded", true, RpcCodes.QUOTA_EXCEEDED, Kind.REQUEST),
    RECOVERY_REQUIRED("recovery_required", false, RpcCodes.RECOVERY_REQUIRED, Kind.REQUEST),
    SAFE_MODE("safe_mode", false, RpcCodes.SAFE_MODE, Kind.REQUEST),

    // ---- 模型错误：一轮里模型调用失败，任务以 task.failed 结束，session/prompt 返回 JSON-RPC 错误 ----
    MODEL_NOT_CONFIGURED("model_not_configured", false, RpcCodes.TASK_FAILED, Kind.MODEL),
    MODEL_AUTH_FAILED("model_auth_failed", false, RpcCodes.TASK_FAILED, Kind.MODEL),
    MODEL_QUOTA_EXHAUSTED("model_quota_exhausted", false, RpcCodes.TASK_FAILED, Kind.MODEL),
    MODEL_BAD_REQUEST("model_bad_request", false, RpcCodes.TASK_FAILED, Kind.MODEL),
    MODEL_REQUEST_TOO_LARGE("model_request_too_large", false, RpcCodes.TASK_FAILED, Kind.MODEL),
    MODEL_RATE_LIMITED("model_rate_limited", true, RpcCodes.TASK_FAILED, Kind.MODEL),
    MODEL_UNAVAILABLE("model_unavailable", true, RpcCodes.TASK_FAILED, Kind.MODEL),
    MODEL_NETWORK("model_network", true, RpcCodes.TASK_FAILED, Kind.MODEL),
    MODEL_TIMEOUT("model_timeout", true, RpcCodes.TASK_FAILED, Kind.MODEL),
    MODEL_STREAM_INTERRUPTED("model_stream_interrupted", true, RpcCodes.TASK_FAILED, Kind.MODEL),
    MODEL_TLS_FAILED("model_tls_failed", false, RpcCodes.TASK_FAILED, Kind.MODEL),
    MODEL_PROTOCOL("model_protocol", false, RpcCodes.TASK_FAILED, Kind.MODEL),

    // ---- 工具错误：不让任务失败，作为 isError 的 tool_result 交回模型；同时写进 tool.settled ----
    TOOL_NOT_IN_CATALOG("tool_not_in_catalog", false, RpcCodes.TASK_FAILED, Kind.TOOL),
    TOOL_DENIED("tool_denied", false, RpcCodes.TASK_FAILED, Kind.TOOL),
    TOOL_BLOCKED("tool_blocked", false, RpcCodes.TASK_FAILED, Kind.TOOL),
    TOOL_FAILED("tool_failed", false, RpcCodes.TASK_FAILED, Kind.TOOL),
    TOOL_TIMEOUT("tool_timeout", false, RpcCodes.TASK_FAILED, Kind.TOOL),
    TOOL_UNAVAILABLE("tool_unavailable", true, RpcCodes.TASK_FAILED, Kind.TOOL),
    TOOL_RESULT_UNKNOWN("tool_result_unknown", false, RpcCodes.TASK_FAILED, Kind.TOOL),
    TOOL_RESULT_TOO_LARGE("tool_result_too_large", false, RpcCodes.TASK_FAILED, Kind.TOOL),

    // ---- 任务错误：调度、恢复与运行时本身 ----
    QUEUE_TIMEOUT("queue_timeout", true, RpcCodes.TASK_FAILED, Kind.TASK),
    EXECUTION_TIMEOUT("execution_timeout", false, RpcCodes.TASK_FAILED, Kind.TASK),
    AGENT_CORE_FAILED("agent_core_failed", false, RpcCodes.TASK_FAILED, Kind.TASK),
    ABANDONED("abandoned", false, RpcCodes.TASK_FAILED, Kind.TASK),
    /** architecture F8 过渡期限：需要恢复的任务满 24 小时、或超过保留上限，启动时由运行时放弃。 */
    RECOVERY_EXPIRED("recovery_expired", false, RpcCodes.TASK_FAILED, Kind.TASK),
    STORE_FAILED("store_failed", false, RpcCodes.INTERNAL_ERROR, Kind.TASK),
    INTERNAL("internal", false, RpcCodes.INTERNAL_ERROR, Kind.TASK),
    ;

    /** 错误出现的位置。 */
    enum class Kind {
        /** 请求被拒绝，没有产生任务。 */
        REQUEST,

        /** 模型调用失败；任务失败。 */
        MODEL,

        /** 工具调用失败；作为 isError 的 tool_result 交回模型，本轮继续。 */
        TOOL,

        /** 调度、恢复、运行时故障；任务失败或进入恢复。 */
        TASK,
    }

    fun info(message: String, details: JsonObject? = null): ErrorInfo = ErrorInfo(this, message, retryable, details)

    companion object {
        private val byWire: Map<String, ErrorCode> = entries.associateBy { it.wire }

        fun fromWire(wire: String): ErrorCode? = byWire[wire]
    }

    internal object WireSerializer : KSerializer<ErrorCode> {
        override val descriptor = PrimitiveSerialDescriptor("org.agentos.runtime.errors.ErrorCode", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: ErrorCode) = encoder.encodeString(value.wire)

        override fun deserialize(decoder: Decoder): ErrorCode {
            val wire = decoder.decodeString()
            return fromWire(wire) ?: INTERNAL
        }
    }
}

/** JSON-RPC 错误码。标准码与 ACP 定义的码沿用 SDK 的值；AgentOS 自己的码占用 -32040..-32059。 */
object RpcCodes {
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    /** ACP：需要认证（电脑端未配对，W9）。 */
    const val AUTH_REQUIRED = -32000

    /** ACP：资源不存在（会话、任务）。 */
    const val RESOURCE_NOT_FOUND = -32002

    /** ACP：请求被取消（`$/cancel_request`，SDK 内部使用）。 */
    const val REQUEST_CANCELLED = -32800

    const val NOT_OPEN = -32040
    const val FORBIDDEN = -32041
    const val INVALID_STATE = -32042
    const val REQUEST_CONFLICT = -32043
    const val SESSION_TERMINAL = -32044
    const val CURSOR_TOO_OLD = -32045
    const val PAYLOAD_TOO_LARGE = -32046
    const val BUSY = -32047
    const val QUOTA_EXCEEDED = -32048
    const val RECOVERY_REQUIRED = -32049
    const val SAFE_MODE = -32050

    /** 本轮任务失败（模型、任务类错误）：`session/prompt` 返回这个 code，具体原因在 `data.agentosCode`。 */
    const val TASK_FAILED = -32051
}

/**
 * 一个具体的错误。写进 `task.failed` 的 `error`、`tool.settled`，以及 JSON-RPC 错误的 `data`。
 *
 * [message] 给人看，可以写进诊断页；**不得**包含 key、请求头、完整 prompt 或工具参数原文。
 * [details] 只放可以公开的结构化信息（HTTP 状态码、retry-after 秒数、工具名等）。
 */
@Serializable
data class ErrorInfo(
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean = code.retryable,
    val details: JsonObject? = null,
)

/** 携带 [ErrorInfo] 的异常，宿主层内部用它把错误原样传到 ACP 边界。 */
class AgentOsException(val info: ErrorInfo, cause: Throwable? = null) : Exception("${info.code.wire}: ${info.message}", cause) {
    constructor(code: ErrorCode, message: String, cause: Throwable? = null) : this(code.info(message), cause)
}

/**
 * 模型请求失败的分类：给宿主层的网络出口（B lane 的 net/HostFetch.kt）和 Pi 适配层用，
 * 保证“可重试 / 不可重试”与 errors.md 第 3 节一致。
 */
object ModelFailures {

    /** HTTP 响应状态码（非 2xx）→ 错误码。 */
    fun forStatus(status: Int): ErrorCode = when (status) {
        401, 403 -> ErrorCode.MODEL_AUTH_FAILED
        402 -> ErrorCode.MODEL_QUOTA_EXHAUSTED
        408 -> ErrorCode.MODEL_TIMEOUT
        413 -> ErrorCode.MODEL_REQUEST_TOO_LARGE
        425, 429 -> ErrorCode.MODEL_RATE_LIMITED
        in 500..599 -> ErrorCode.MODEL_UNAVAILABLE // 含 Anthropic 的 529 overloaded
        in 400..499 -> ErrorCode.MODEL_BAD_REQUEST
        else -> ErrorCode.MODEL_PROTOCOL // 1xx / 3xx 走到这里说明重定向或协议不对
    }

    /**
     * 传输层异常 → 错误码。用户取消（abort）不是错误，调用方不要拿取消产生的异常来分类。
     *
     * @param responseStarted 已经收到响应头（开始读响应体）之后才失败。
     */
    fun forException(error: Throwable, responseStarted: Boolean): ErrorCode {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        fun has(simpleName: String) =
            chain.any { e -> generateSequence<Class<*>>(e.javaClass) { it.superclass }.any { it.simpleName == simpleName } }
        return when {
            has("SSLHandshakeException") || has("SSLPeerUnverifiedException") || has("CertificateException") -> ErrorCode.MODEL_TLS_FAILED
            // SocketTimeoutException 是 InterruptedIOException 的子类：连接、读、写超时都算
            has("InterruptedIOException") -> ErrorCode.MODEL_TIMEOUT
            has("IOException") && responseStarted -> ErrorCode.MODEL_STREAM_INTERRUPTED
            has("IOException") -> ErrorCode.MODEL_NETWORK // UnknownHost、Connect、NoRouteToHost、连接被重置等
            else -> ErrorCode.MODEL_PROTOCOL
        }
    }
}

/** JSON-RPC 错误的 `data`（errors.md 第 5 节）。 */
@Serializable
data class RpcErrorData(
    @SerialName("agentosCode") val agentosCode: ErrorCode,
    val retryable: Boolean,
    val sessionId: String? = null,
    val taskId: String? = null,
    val details: JsonObject? = null,
)
