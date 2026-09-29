package org.agentos.runtime.pi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.errors.ModelFailures
import org.agentos.runtime.events.AgentEvent
import org.agentos.runtime.events.AssistantUpdate
import org.agentos.runtime.net.NetErrorKind

/**
 * Pi lifecycle events (compacted by pi-agent.js) -> internal events (core/contracts/events.md, section 3).
 *
 * pi-agent.js already sends the events.md shape: `message_update` carries only the delta, and
 * update kinds that are not written to the event log (start, text_start / text_end,
 * thinking_start / thinking_end, toolcall_start, toolcall_delta) are not sent at all. The mapper
 * decodes with [AgentEvent.decode], drops those kinds defensively should an older bundle send
 * them, and passes unknown Pi events through as [AgentEvent.Other].
 */
object PiEventMapper {

    /** Update kinds that go to the event log (events.md, section 3). */
    val LOGGED_UPDATES: Set<String> = setOf(
        AssistantUpdate.TEXT_DELTA,
        AssistantUpdate.THINKING_DELTA,
        "toolcall_end",
        AssistantUpdate.DONE,
        AssistantUpdate.ERROR,
    )

    /** Returns the internal event, or null when the event is not forwarded. */
    fun map(event: JsonObject): AgentEvent? {
        val decoded = runCatching { AgentEvent.decode(event) }.getOrElse {
            val type = (event["type"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
            return AgentEvent.Other(type, event)
        }
        if (decoded is AgentEvent.MessageUpdate && decoded.update.kind !in LOGGED_UPDATES) return null
        return decoded
    }

    /** True for an assistant `message_end` whose tool calls Pi is about to execute (one tool round). */
    fun isToolRound(event: AgentEvent): Boolean {
        if (event !is AgentEvent.MessageEnd || event.role != "assistant") return false
        val stop = (event.message["stopReason"] as? JsonPrimitive)?.contentOrNull
        if (stop == "error" || stop == "aborted" || stop == "length") return false
        val content = event.message["content"] as? JsonArray ?: return false
        return content.any { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "toolCall" }
    }
}

/**
 * Classifies a failed model call (Pi stopReason "error") per core/contracts/errors.md, section 3.2.
 *
 * The network egress knows what really happened (status code, transport failure), so the
 * classification comes from the [ModelRequestOutcome]s HostFetch reported for this turn; Pi's
 * error text is only a fallback. Messages never contain keys or request headers: they are built
 * from status codes, error kinds and the provider's error type.
 */
object PiErrorClassifier {

    fun classify(outcomes: List<ModelRequestOutcome>, piErrorMessage: String?): ErrorInfo {
        when (val last = outcomes.lastOrNull()) {
            is ModelRequestOutcome.Failed -> return forHeadFailure(last)
            is ModelRequestOutcome.BodyFailed -> {
                val code = ModelFailures.forException(last.error, responseStarted = true)
                return code.info("model response interrupted: ${last.error.kind.name.lowercase()}")
            }
            is ModelRequestOutcome.Responded -> if (last.status !in 200..299) {
                val code = ModelFailures.forStatus(last.status)
                val details = buildJsonObject {
                    put("status", last.status)
                    last.retryAfterSeconds?.let { put("retryAfterSeconds", it) }
                }
                return ErrorInfo(code, "provider returned ${last.status}", code.retryable, details)
            }
            null -> Unit
        }
        return forMessage(piErrorMessage)
    }

    private fun forHeadFailure(f: ModelRequestOutcome.Failed): ErrorInfo {
        val code = when (f.error.kind) {
            NetErrorKind.NO_CREDENTIAL, NetErrorKind.REJECTED, NetErrorKind.KEY_REVOKED -> ErrorCode.MODEL_NOT_CONFIGURED
            NetErrorKind.TIMEOUT -> ErrorCode.MODEL_TIMEOUT
            NetErrorKind.TLS -> ErrorCode.MODEL_TLS_FAILED
            NetErrorKind.CONNECT, NetErrorKind.DNS, NetErrorKind.NETWORK -> ErrorCode.MODEL_NETWORK
            NetErrorKind.CANCELED -> ErrorCode.MODEL_NETWORK
        }
        val message = when (f.error.kind) {
            NetErrorKind.NO_CREDENTIAL -> "no API key configured for this endpoint"
            NetErrorKind.REJECTED -> "endpoint not allowed: ${f.error.message}"
            else -> "request failed before a response: ${f.error.kind.name.lowercase()}"
        }
        return code.info(message)
    }

    /**
     * The request itself succeeded (2xx) or never reached the network: an error inside the stream,
     * a response that could not be parsed, or a configuration problem found in JS.
     */
    private fun forMessage(message: String?): ErrorInfo {
        val raw = message.orEmpty()
        val m = raw.lowercase()
        // Providers report in-stream errors with an error type (Anthropic: rate_limit_error,
        // overloaded_error, ...) or, through the OpenAI SDK, only with the error's message text.
        val code = when {
            raw.startsWith("Unsupported model API") -> ErrorCode.MODEL_NOT_CONFIGURED
            "rate_limit" in m || "rate limit" in m || "too many requests" in m -> ErrorCode.MODEL_RATE_LIMITED
            "overloaded" in m || "api_error" in m || "server_error" in m || "internal server error" in m ||
                "service unavailable" in m || "temporarily unavailable" in m -> ErrorCode.MODEL_UNAVAILABLE
            "authentication" in m || "permission_error" in m || "invalid api key" in m || "incorrect api key" in m -> ErrorCode.MODEL_AUTH_FAILED
            "insufficient_quota" in m || "insufficient balance" in m -> ErrorCode.MODEL_QUOTA_EXHAUSTED
            "invalid_request" in m -> ErrorCode.MODEL_BAD_REQUEST
            else -> ErrorCode.MODEL_PROTOCOL
        }
        return code.info(if (code == ErrorCode.MODEL_PROTOCOL) "unexpected model response" else "provider reported ${code.wire.removePrefix("model_")}")
    }
}
