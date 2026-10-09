package org.agentos.app.ui

import org.agentos.app.R
import org.agentos.app.i18n.Strings
import org.agentos.channel.CloseCause

/**
 * A failure shown to the user: a short title, what to do about it, and whether sending again may help.
 * Built from AgentOS error codes (core/contracts/errors.md: JSON-RPC -32040…-32059 with
 * `data.agentosCode`), from the Binder channel's close cause, or from connection failures.
 * Never contains a key, a prompt, or tool arguments.
 *
 * [title] and [hint] are already text in the language of the screen at the moment of the failure (an error notice is
 * transient, it is not re-rendered when the language changes): every function here takes the [Strings] to render with
 * (`AndroidStrings(context)` at run time, `ResStrings.zh / .en` in tests). The wording lives in `values/strings_p1.xml`
 * and `values-en/strings_p1.xml` as `error_<code>_title` / `error_<code>_hint`.
 */
data class AgentError(
    val title: String,
    val hint: String?,
    val retryable: Boolean,
    /** AgentOS error code, or a local code (`channel_*`, `connect_*`, `rpc_<n>`), for diagnostics. */
    val code: String,
)

object AgentErrors {
    private class Text(val title: Int, val hint: Int? = null)

    private val byCode: Map<String, Text> = mapOf(
        "invalid_params" to Text(R.string.error_invalid_params_title),
        "unsupported" to Text(R.string.error_unsupported_title),
        "auth_required" to Text(R.string.error_auth_required_title),
        "not_open" to Text(R.string.error_not_open_title),
        "forbidden" to Text(R.string.error_forbidden_title),
        "session_not_found" to Text(R.string.error_session_not_found_title, R.string.error_session_not_found_hint),
        "task_not_found" to Text(R.string.error_task_not_found_title),
        "invalid_state" to Text(R.string.error_invalid_state_title, R.string.error_invalid_state_hint),
        "request_conflict" to Text(R.string.error_request_conflict_title),
        "session_terminal" to Text(R.string.error_session_terminal_title, R.string.error_session_terminal_hint),
        "cursor_too_old" to Text(R.string.error_cursor_too_old_title),
        "payload_too_large" to Text(R.string.error_payload_too_large_title, R.string.error_payload_too_large_hint),
        "busy" to Text(R.string.error_busy_title, R.string.error_busy_hint),
        "quota_exceeded" to Text(R.string.error_quota_exceeded_title, R.string.error_quota_exceeded_hint),
        "recovery_required" to Text(R.string.error_recovery_required_title, R.string.error_recovery_required_hint),
        "safe_mode" to Text(R.string.error_safe_mode_title, R.string.error_safe_mode_hint),
        "model_not_configured" to Text(R.string.error_model_not_configured_title, R.string.error_model_not_configured_hint),
        "model_auth_failed" to Text(R.string.error_model_auth_failed_title, R.string.error_model_auth_failed_hint),
        "model_quota_exhausted" to Text(R.string.error_model_quota_exhausted_title, R.string.error_model_quota_exhausted_hint),
        "model_bad_request" to Text(R.string.error_model_bad_request_title, R.string.error_model_bad_request_hint),
        "model_request_too_large" to Text(R.string.error_model_request_too_large_title, R.string.error_model_request_too_large_hint),
        "model_rate_limited" to Text(R.string.error_model_rate_limited_title, R.string.error_model_rate_limited_hint),
        "model_unavailable" to Text(R.string.error_model_unavailable_title, R.string.error_model_unavailable_hint),
        "model_network" to Text(R.string.error_model_network_title, R.string.error_model_network_hint),
        "model_timeout" to Text(R.string.error_model_timeout_title, R.string.error_model_timeout_hint),
        "model_stream_interrupted" to Text(R.string.error_model_stream_interrupted_title, R.string.error_model_stream_interrupted_hint),
        "model_tls_failed" to Text(R.string.error_model_tls_failed_title, R.string.error_model_tls_failed_hint),
        "model_protocol" to Text(R.string.error_model_protocol_title, R.string.error_model_protocol_hint),
        "tool_not_in_catalog" to Text(R.string.error_tool_not_in_catalog_title),
        "tool_denied" to Text(R.string.error_tool_denied_title),
        "tool_blocked" to Text(R.string.error_tool_blocked_title),
        "tool_failed" to Text(R.string.error_tool_failed_title),
        "tool_timeout" to Text(R.string.error_tool_timeout_title),
        "tool_unavailable" to Text(R.string.error_tool_unavailable_title),
        "tool_result_unknown" to Text(R.string.error_tool_result_unknown_title),
        "tool_result_too_large" to Text(R.string.error_tool_result_too_large_title),
        "queue_timeout" to Text(R.string.error_queue_timeout_title, R.string.error_queue_timeout_hint),
        "execution_timeout" to Text(R.string.error_execution_timeout_title),
        "agent_core_failed" to Text(R.string.error_agent_core_failed_title),
        "abandoned" to Text(R.string.error_abandoned_title),
        "store_failed" to Text(R.string.error_store_failed_title, R.string.error_store_failed_hint),
        "internal" to Text(R.string.error_internal_title),
    )

    /** The AgentOS error codes this screen has wording for (tests check each one has both languages). */
    val knownCodes: Set<String> get() = byCode.keys

    /**
     * From a JSON-RPC error of `session/prompt` or another request. [reason] is `data.details.reason`
     * (architecture F9: `model_not_configured` with `key_revoked` = the key was cleared during this turn).
     * [attempts] is `data.details.attempts`: the network exit retried the model request before giving up
     * (errors.md, B6); more than one attempt is added to the hint so "try again later" is not a surprise.
     */
    fun fromRpc(
        strings: Strings,
        rpcCode: Int,
        message: String?,
        agentosCode: String?,
        retryable: Boolean?,
        retryAfterSeconds: Long? = null,
        reason: String? = null,
        attempts: Int? = null,
    ): AgentError {
        val e = fromRpcCode(strings, rpcCode, message, agentosCode, retryable, retryAfterSeconds, reason)
        if (attempts == null || attempts <= 1) return e
        val tried = strings.get(R.string.error_attempts, attempts)
        return e.copy(hint = e.hint?.let { strings.get(R.string.error_hint_with_attempts, it, tried) } ?: tried)
    }

    private fun fromRpcCode(
        strings: Strings,
        rpcCode: Int,
        message: String?,
        agentosCode: String?,
        retryable: Boolean?,
        retryAfterSeconds: Long?,
        reason: String?,
    ): AgentError {
        if (agentosCode == "model_not_configured" && reason == "key_revoked") {
            return AgentError(strings.get(R.string.error_key_revoked_title), strings.get(R.string.error_key_revoked_hint), false, agentosCode)
        }
        val known = agentosCode?.let { byCode[it] }
        if (known != null) {
            val hint = if (agentosCode == "model_rate_limited" && retryAfterSeconds != null && retryAfterSeconds > 0) {
                strings.plural(R.plurals.error_retry_after, retryAfterSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), retryAfterSeconds)
            } else {
                known.hint?.let { strings.get(it) }
            }
            return AgentError(strings.get(known.title), hint, retryable ?: false, agentosCode)
        }
        val title = strings.get(
            when (rpcCode) {
                -32601 -> R.string.error_rpc_unsupported_title
                -32602 -> R.string.error_invalid_params_title
                -32700, -32600 -> R.string.error_rpc_protocol_title
                -32000 -> R.string.error_auth_required_title
                -32002 -> R.string.error_rpc_not_found_title
                -32800 -> R.string.error_rpc_cancelled_title
                else -> R.string.error_rpc_failed_title
            },
        )
        // the detail is the runtime's own English diagnostic line (never translated, never a secret): first line, cut
        val detail = message?.substringBefore('\n')?.take(120)?.takeIf { it.isNotBlank() }
        return AgentError(title, detail, retryable ?: false, agentosCode ?: "rpc_$rpcCode")
    }

    /** The Binder channel closed while a request was in flight (prompt ends with "Protocol closed"). */
    fun fromClose(strings: Strings, cause: CloseCause?): AgentError {
        val reconnect = strings.get(R.string.error_close_reconnect_hint)
        return when (cause) {
            is CloseCause.PeerDied -> AgentError(
                strings.get(R.string.error_close_peer_died_title), strings.get(R.string.error_close_peer_died_hint), true, "channel_peer_died",
            )
            is CloseCause.Remote -> AgentError(strings.get(R.string.error_close_remote_title), reconnect, true, "channel_remote")
            is CloseCause.Violation -> AgentError(strings.get(R.string.error_close_violation_title, cause.reason), reconnect, true, "channel_violation")
            is CloseCause.Failure -> AgentError(strings.get(R.string.error_close_failure_title), reconnect, true, "channel_failure")
            is CloseCause.Local -> AgentError(strings.get(R.string.error_close_local_title), null, true, "channel_local")
            null -> AgentError(strings.get(R.string.error_close_unknown_title), reconnect, true, "channel_unknown")
        }
    }

    fun connectFailed(strings: Strings, reason: String?): AgentError = when (reason) {
        "agentos.acp.not_open" -> AgentError(
            strings.get(R.string.error_connect_not_open_title), strings.get(R.string.error_connect_not_open_hint), false, "connect_not_open",
        )
        null -> AgentError(
            strings.get(R.string.error_connect_failed_title), strings.get(R.string.error_connect_failed_hint), true, "connect_failed",
        )
        else -> AgentError(strings.get(R.string.error_connect_rejected_title, reason), null, false, "connect_rejected")
    }

    fun serviceMissing(strings: Strings): AgentError =
        AgentError(strings.get(R.string.error_no_service_title), strings.get(R.string.error_no_service_hint), false, "connect_no_service")

    fun unexpected(strings: Strings, t: Throwable): AgentError =
        AgentError(strings.get(R.string.error_unexpected_title, t.javaClass.simpleName), null, true, "client_exception")
}
