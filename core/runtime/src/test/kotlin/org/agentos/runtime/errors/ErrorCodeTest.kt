package org.agentos.runtime.errors

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorCodeTest {

    /** core/contracts/errors.md 第 3 节的表：| `code` | 是/否 | rpc | 含义 |。 */
    private fun documentedCodes(): Map<String, Pair<Boolean, Int>> {
        val doc = File("../contracts/errors.md").readText()
        val section = doc.substringAfter("## 3. 错误码").substringBefore("## 4.")
        val row = Regex("""^\|\s*`([a-z_]+)`\s*\|\s*(是|否)\s*\|\s*(-?\d+)\s*\|""", RegexOption.MULTILINE)
        return row.findAll(section).associate { m -> m.groupValues[1] to ((m.groupValues[2] == "是") to m.groupValues[3].toInt()) }
    }

    @Test
    fun `errors_md and ErrorCode agree on every code, retryable flag and rpc code`() {
        val documented = documentedCodes()
        val inCode = ErrorCode.entries.associate { it.wire to (it.retryable to it.rpcCode) }
        assertEquals(inCode.keys.sorted(), documented.keys.sorted(), "same set of error codes")
        inCode.forEach { (wire, v) -> assertEquals(v, documented[wire], "row for `$wire`") }
    }

    @Test
    fun `wire names are unique snake case and agentos rpc codes stay in the reserved range`() {
        assertEquals(ErrorCode.entries.size, ErrorCode.entries.map { it.wire }.toSet().size)
        ErrorCode.entries.forEach { assertTrue(Regex("[a-z][a-z_]*").matches(it.wire), it.wire) }
        val standard = setOf(RpcCodes.INVALID_PARAMS, RpcCodes.INTERNAL_ERROR, RpcCodes.AUTH_REQUIRED, RpcCodes.RESOURCE_NOT_FOUND)
        ErrorCode.entries.map { it.rpcCode }.filter { it !in standard }.forEach { assertTrue(it in -32059..-32040, "rpc code $it") }
        ErrorCode.entries.forEach { assertEquals(it, ErrorCode.fromWire(it.wire)) }
    }

    @Test
    fun `model HTTP status classification`() {
        assertEquals(ErrorCode.MODEL_AUTH_FAILED, ModelFailures.forStatus(401))
        assertEquals(ErrorCode.MODEL_AUTH_FAILED, ModelFailures.forStatus(403))
        assertEquals(ErrorCode.MODEL_QUOTA_EXHAUSTED, ModelFailures.forStatus(402))
        assertEquals(ErrorCode.MODEL_BAD_REQUEST, ModelFailures.forStatus(400))
        assertEquals(ErrorCode.MODEL_BAD_REQUEST, ModelFailures.forStatus(404))
        assertEquals(ErrorCode.MODEL_REQUEST_TOO_LARGE, ModelFailures.forStatus(413))
        assertEquals(ErrorCode.MODEL_RATE_LIMITED, ModelFailures.forStatus(429))
        assertEquals(ErrorCode.MODEL_TIMEOUT, ModelFailures.forStatus(408))
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, ModelFailures.forStatus(500))
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, ModelFailures.forStatus(529))
        assertEquals(ErrorCode.MODEL_PROTOCOL, ModelFailures.forStatus(302))
        // 可重试的只有暂时性的
        val retryable = (400..599).map { ModelFailures.forStatus(it) }.filter { it.retryable }.toSet()
        assertEquals(setOf(ErrorCode.MODEL_TIMEOUT, ErrorCode.MODEL_RATE_LIMITED, ErrorCode.MODEL_UNAVAILABLE), retryable)
    }

    @Test
    fun `model transport exception classification`() {
        assertEquals(ErrorCode.MODEL_NETWORK, ModelFailures.forException(UnknownHostException("api.example"), responseStarted = false))
        assertEquals(ErrorCode.MODEL_NETWORK, ModelFailures.forException(ConnectException("refused"), responseStarted = false))
        assertEquals(ErrorCode.MODEL_TIMEOUT, ModelFailures.forException(SocketTimeoutException("read timed out"), responseStarted = true))
        assertEquals(ErrorCode.MODEL_TLS_FAILED, ModelFailures.forException(SSLHandshakeException("bad cert"), responseStarted = false))
        assertEquals(ErrorCode.MODEL_STREAM_INTERRUPTED, ModelFailures.forException(IOException("unexpected end of stream"), responseStarted = true))
        assertEquals(
            ErrorCode.MODEL_TLS_FAILED,
            ModelFailures.forException(IOException("wrapped", SSLHandshakeException("x")), responseStarted = false),
        )
        assertEquals(ErrorCode.MODEL_PROTOCOL, ModelFailures.forException(IllegalArgumentException("bad json"), responseStarted = true))
    }

    @Test
    fun `ErrorInfo serializes with wire code`() {
        val info = ErrorCode.MODEL_RATE_LIMITED.info("provider returned 429")
        val json = org.agentos.runtime.events.RuntimeJson.encodeToString(ErrorInfo.serializer(), info)
        assertEquals("""{"code":"model_rate_limited","message":"provider returned 429","retryable":true}""", json)
        assertEquals(info, org.agentos.runtime.events.RuntimeJson.decodeFromString(ErrorInfo.serializer(), json))
    }
}
