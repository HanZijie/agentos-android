package org.agentos.runtime.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.AgentEvent
import org.agentos.runtime.events.AssistantUpdate
import org.agentos.runtime.net.HostFetchException
import org.agentos.runtime.net.NetErrorKind
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiEventMapperTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun update(kind: String, extra: String = "") =
        obj("""{"type":"message_update","role":"assistant","update":{"type":"$kind","contentIndex":0$extra}}""")

    @Test
    fun forwardsLoggedUpdateKinds() {
        val text = PiEventMapper.map(update("text_delta", ""","delta":"hi""""))
        assertIs<AgentEvent.MessageUpdate>(text)
        assertEquals(AssistantUpdate.TEXT_DELTA, text.update.kind)
        assertEquals("hi", text.update.delta)

        for (kind in listOf("thinking_delta", "done", "error")) {
            assertIs<AgentEvent.MessageUpdate>(PiEventMapper.map(update(kind)), kind)
        }
        val end = PiEventMapper.map(
            update("toolcall_end", ""","toolCall":{"type":"toolCall","id":"c1","name":"echo","arguments":{}}"""),
        )
        assertIs<AgentEvent.MessageUpdate>(end)
        assertEquals(AssistantUpdate.TOOLCALL_END, end.update.kind)
    }

    @Test
    fun dropsUpdateKindsThatAreNotLogged() {
        // pi-agent.js no longer sends these; the mapper still drops them should an older bundle do so.
        for (kind in listOf("start", "text_start", "text_end", "thinking_start", "thinking_end", "toolcall_start", "toolcall_delta")) {
            assertNull(PiEventMapper.map(update(kind)), kind)
        }
    }

    @Test
    fun unknownAndMalformedEventsBecomeOther() {
        val unknown = PiEventMapper.map(obj("""{"type":"compaction_start","x":1}"""))
        assertIs<AgentEvent.Other>(unknown)
        assertEquals("compaction_start", unknown.type)

        // A known type with a missing required field must not throw out of the pump.
        val malformed = PiEventMapper.map(obj("""{"type":"message_update","role":"assistant"}"""))
        assertIs<AgentEvent.Other>(malformed)
        assertEquals("message_update", malformed.type)
    }

    @Test
    fun passesLifecycleEventsThrough() {
        assertIs<AgentEvent.MessageStart>(PiEventMapper.map(obj("""{"type":"message_start","role":"assistant"}""")))
        val turnEnd = PiEventMapper.map(obj("""{"type":"turn_end","stopReason":"toolUse","toolResults":1}"""))
        assertIs<AgentEvent.TurnEnd>(turnEnd)
        assertEquals("toolUse", turnEnd.stopReason)
    }

    private fun messageEnd(role: String, stopReason: String, content: String) = AgentEvent.MessageEnd(
        obj("""{"role":"$role","stopReason":"$stopReason","content":$content}"""),
    )

    private val withToolCall = """[{"type":"text","text":"x"},{"type":"toolCall","id":"c1","name":"echo","arguments":{}}]"""

    @Test
    fun toolRoundIsAnAssistantMessageEndWithToolCallsThatWillRun() {
        assertTrue(PiEventMapper.isToolRound(messageEnd("assistant", "toolUse", withToolCall)))

        assertFalse(PiEventMapper.isToolRound(messageEnd("assistant", "stop", """[{"type":"text","text":"x"}]""")))
        // Tool calls in a failed, aborted or truncated message are not executed by Pi.
        for (stop in listOf("error", "aborted", "length")) {
            assertFalse(PiEventMapper.isToolRound(messageEnd("assistant", stop, withToolCall)), stop)
        }
        assertFalse(PiEventMapper.isToolRound(messageEnd("user", "toolUse", withToolCall)))
        assertFalse(PiEventMapper.isToolRound(AgentEvent.MessageStart("assistant")))
    }
}

class PiErrorClassifierTest {

    private val url = "https://api.example.test/v1/messages"

    private fun responded(status: Int, retryAfter: Long? = null) =
        ModelRequestOutcome.Responded("s1", url, status, attempts = 1, retryAfterSeconds = retryAfter)

    private fun failed(kind: NetErrorKind, cause: Throwable? = null) =
        ModelRequestOutcome.Failed("s1", url, HostFetchException(kind, "x", cause))

    private fun bodyFailed(kind: NetErrorKind, cause: Throwable? = null) =
        ModelRequestOutcome.BodyFailed("s1", url, HostFetchException(kind, "x", cause))

    @Test
    fun httpStatusDecidesAndCarriesPublicDetails() {
        val limited = PiErrorClassifier.classify(listOf(responded(429, retryAfter = 7)), "429 rate_limit_error")
        assertEquals(ErrorCode.MODEL_RATE_LIMITED, limited.code)
        assertTrue(limited.retryable)
        assertEquals(429, limited.details!!["status"]!!.jsonPrimitive.int)
        assertEquals(7L, limited.details!!["retryAfterSeconds"]!!.jsonPrimitive.long)

        val cases = mapOf(
            401 to ErrorCode.MODEL_AUTH_FAILED,
            403 to ErrorCode.MODEL_AUTH_FAILED,
            402 to ErrorCode.MODEL_QUOTA_EXHAUSTED,
            408 to ErrorCode.MODEL_TIMEOUT,
            413 to ErrorCode.MODEL_REQUEST_TOO_LARGE,
            400 to ErrorCode.MODEL_BAD_REQUEST,
            503 to ErrorCode.MODEL_UNAVAILABLE,
            529 to ErrorCode.MODEL_UNAVAILABLE,
        )
        for ((status, code) in cases) {
            // The provider's text is ignored when the status is known.
            val info = PiErrorClassifier.classify(listOf(responded(status)), "overloaded_error")
            assertEquals(code, info.code, "status $status")
            assertNull(info.details!!["retryAfterSeconds"], "status $status")
        }
    }

    @Test
    fun onlyTheLastRequestOfTheTurnCounts() {
        // An earlier failed request that Pi recovered from does not decide the classification.
        val info = PiErrorClassifier.classify(listOf(responded(503), responded(200)), "Anthropic: overloaded_error")
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, info.code)
        assertNull(info.details)
    }

    @Test
    fun failuresBeforeAResponseHead() {
        val cases = mapOf(
            NetErrorKind.NO_CREDENTIAL to ErrorCode.MODEL_NOT_CONFIGURED,
            NetErrorKind.REJECTED to ErrorCode.MODEL_NOT_CONFIGURED,
            NetErrorKind.TIMEOUT to ErrorCode.MODEL_TIMEOUT,
            NetErrorKind.TLS to ErrorCode.MODEL_TLS_FAILED,
            NetErrorKind.CONNECT to ErrorCode.MODEL_NETWORK,
            NetErrorKind.DNS to ErrorCode.MODEL_NETWORK,
            NetErrorKind.NETWORK to ErrorCode.MODEL_NETWORK,
        )
        for ((kind, code) in cases) {
            val info = PiErrorClassifier.classify(listOf(failed(kind)), "Connection error.")
            assertEquals(code, info.code, kind.name)
            assertEquals(code.retryable, info.retryable, kind.name)
        }
    }

    @Test
    fun retriedFailuresReportTheirAttempts() {
        val spent = PiErrorClassifier.classify(listOf(ModelRequestOutcome.Responded("s1", url, 529, attempts = 5)), null)
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, spent.code)
        assertEquals(5, spent.details!!["attempts"]!!.jsonPrimitive.int)

        val network = failed(NetErrorKind.NETWORK).also { it.error.attempts = 3 }
        assertEquals(3, PiErrorClassifier.classify(listOf(network), null).details!!["attempts"]!!.jsonPrimitive.int)

        // A single attempt adds nothing.
        assertNull(PiErrorClassifier.classify(listOf(responded(503)), null).details!!["attempts"])
        assertNull(PiErrorClassifier.classify(listOf(failed(NetErrorKind.NETWORK)), null).details)
    }

    @Test
    fun revokedKeyIsModelNotConfiguredWithReason() {
        // F9: clearing the model source mid-turn; the integrator's wording, never auth_failed.
        for (outcome in listOf(failed(NetErrorKind.KEY_REVOKED), bodyFailed(NetErrorKind.KEY_REVOKED))) {
            val info = PiErrorClassifier.classify(listOf(responded(200), outcome), "Connection error.")
            assertEquals(ErrorCode.MODEL_NOT_CONFIGURED, info.code)
            assertFalse(info.retryable)
            assertEquals("The model key was removed in AgentOS settings; this turn was stopped.", info.message)
            assertEquals("key_revoked", info.details!!["reason"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun failuresAfterTheHead() {
        assertEquals(
            ErrorCode.MODEL_STREAM_INTERRUPTED,
            PiErrorClassifier.classify(listOf(bodyFailed(NetErrorKind.NETWORK)), null).code,
        )
        assertEquals(
            ErrorCode.MODEL_TIMEOUT,
            PiErrorClassifier.classify(listOf(bodyFailed(NetErrorKind.TIMEOUT, SocketTimeoutException("read"))), null).code,
        )
    }

    @Test
    fun providerTextIsOnlyAFallback() {
        val cases = mapOf(
            "Unsupported model API: foo" to ErrorCode.MODEL_NOT_CONFIGURED,
            "rate_limit_error: slow down" to ErrorCode.MODEL_RATE_LIMITED,
            "Rate limit reached for requests" to ErrorCode.MODEL_RATE_LIMITED,
            "overloaded_error" to ErrorCode.MODEL_UNAVAILABLE,
            "The server had an error while processing your request (server_error)" to ErrorCode.MODEL_UNAVAILABLE,
            "authentication_error: invalid x-api-key" to ErrorCode.MODEL_AUTH_FAILED,
            "Incorrect API key provided" to ErrorCode.MODEL_AUTH_FAILED,
            "insufficient_quota" to ErrorCode.MODEL_QUOTA_EXHAUSTED,
            "invalid_request_error: messages: empty" to ErrorCode.MODEL_BAD_REQUEST,
            "Unexpected token < in JSON" to ErrorCode.MODEL_PROTOCOL,
        )
        for ((text, code) in cases) {
            assertEquals(code, PiErrorClassifier.classify(listOf(responded(200)), text).code, text)
            assertEquals(code, PiErrorClassifier.classify(emptyList(), text).code, text)
        }
        assertEquals(ErrorCode.MODEL_PROTOCOL, PiErrorClassifier.classify(emptyList(), null).code)
    }

    @Test
    fun messagesNeverEchoProviderText() {
        // Provider error text can quote request content; errors.md forbids it in ErrorInfo.message.
        val secretish = "authentication_error: key sk-live-SECRET is invalid; prompt was 'my password'"
        for (outcomes in listOf(emptyList(), listOf(responded(200)), listOf(responded(401)))) {
            val info = PiErrorClassifier.classify(outcomes, secretish)
            assertFalse("SECRET" in info.message || "password" in info.message, info.message)
        }
        val unknown = PiErrorClassifier.classify(emptyList(), "weird: sk-live-SECRET")
        assertEquals("unexpected model response", unknown.message)
    }
}
