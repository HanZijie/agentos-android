package org.agentos.runtime.acp

import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.warn
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * JSON-RPC 消息与一行文字的互转。
 *
 * - Android（电脑端网关，W9）：sdk:acp-android 的 `org.agentos.acp.JsonRpcCodec`（与 Binder 通道共用）；
 * - 电脑上的测试：testFixtures 的 `TestLineCodec`（同样的实现：按具体类型的 serializer 编码）。
 *
 * 编码结果必须是一行、没有多余字段：SDK 0.30.1 的 `StdioTransport` 按接口类型编码，每条多一个 `"type"` 类鉴别字段
 * （S3 问题 3），所以这里不用它。
 */
interface LineCodec {
    fun encode(message: JsonRpcMessage): String

    /** 解析一条 JSON-RPC 消息；不是合法的 JSON-RPC 对象时抛异常。 */
    fun decode(line: String): JsonRpcMessage
}

/** 一行超过了上限（不含换行符，按 `String.length` 计）。 */
class LineTooLongException(val limit: Int) : IOException("line exceeds $limit characters")

/**
 * 从字节流按行读 UTF-8 文字，每行有长度上限：超过上限时抛 [LineTooLongException]，不会把整行读进内存。
 * 行尾的 `\r` 去掉。同一时刻只能有一个读者；电脑端网关先用它读握手行，再把同一个实例交给 [LineTransport]，
 * 已经读进缓冲区的后续字节不会丢。
 */
class BoundedLineReader(input: InputStream, bufferChars: Int = 8_192) {
    private val reader = InputStreamReader(input, Charsets.UTF_8)
    private val buf = CharArray(bufferChars)
    private var pos = 0
    private var end = 0
    private var eof = false

    /** 读下一行（不含换行符）；流结束时返回 null。最后一行没有换行符时照常返回。 */
    fun readLine(maxChars: Int): String? {
        val sb = StringBuilder()
        while (true) {
            if (pos == end) {
                if (eof) return if (sb.isEmpty()) null else finish(sb, maxChars)
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) {
                    eof = true
                    continue
                }
                pos = 0
                end = n
            }
            var i = pos
            while (i < end && buf[i] != '\n') i++
            // 多留 1 个字符给行尾的 \r
            if (sb.length + (i - pos) > maxChars + 1) throw LineTooLongException(maxChars)
            sb.appendRange(buf, pos, i)
            if (i < end) {
                pos = i + 1
                return finish(sb, maxChars)
            }
            pos = end
        }
    }

    private fun finish(sb: StringBuilder, maxChars: Int): String {
        if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
        if (sb.length > maxChars) throw LineTooLongException(maxChars)
        return sb.toString()
    }
}

/**
 * 按行分隔的 JSON-RPC 传输（ACP SDK 0.30.1 的 [Transport]）：一行一条消息，UTF-8，`\n` 结尾。
 * 电脑端网关（抽象 socket `agentos-acp`，W9）和电脑上的 stdio Agent（`AcpStdioAgent`）用它。
 *
 * - **单行上限** [maxLineChars]（默认 65,536 字符，与 binder-channel-v1 的单条消息上限相同，按 `String.length` 计）。
 *   入站超长：连接关闭（原因 `line_too_long`），不尝试重新同步。出站超长的处理与 Binder 通道一致：
 *   请求在本地合成一个错误响应，响应改发错误响应，通知丢弃并计数。
 * - 入站解码失败的行跳过并计数，不触发 onError（与 SDK 的 StdioTransport、Binder 通道一致）。
 * - [send] 不阻塞：编码后入队，写协程在 [ioDispatcher] 上写出；[awaitWritable] 给流式输出做背压。
 * - 对端关闭（读到 EOF）、读写出错、本端 [close] / [abort]，都会让传输进入 CLOSED 并触发 onClose。
 *   对端先关闭写方向时，已经入队的出站消息最多再写 [drainMillis]。
 *
 * @param closeStreams 关闭底层连接（socket）；必须让阻塞中的读返回。只会调用一次。
 */
class LineTransport(
    private val reader: BoundedLineReader,
    output: OutputStream,
    parentScope: CoroutineScope,
    private val codec: LineCodec,
    private val name: String = "line-transport",
    private val maxLineChars: Int = MAX_LINE_CHARS,
    private val closeStreams: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: RuntimeLog = RuntimeLog.NONE,
    private val drainMillis: Long = DRAIN_MILLIS,
) : BaseTransport() {

    private val out = BufferedOutputStream(output, 16_384)
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + CoroutineName(name),
    )
    private val outbound = Channel<String>(Channel.UNLIMITED)
    private val queuedChars = MutableStateFlow(0L)
    private val streamsClosed = AtomicBoolean(false)
    private val reason = AtomicReference<String?>(null)
    private var writer: Job? = null

    private val linesIn = AtomicLong()
    private val linesOut = AtomicLong()
    private val decodeErrors = AtomicLong()
    private val droppedTooLarge = AtomicLong()
    private val syntheticErrors = AtomicLong()
    private val droppedClosed = AtomicLong()

    /** 关闭原因（第一个生效的）：`peer_closed`、`line_too_long`、`closed`、`aborted: …`、`read_error: …`、`write_error: …`。 */
    val closeReason: String? get() = reason.get()

    override fun start() {
        check(_state.compareAndSet(Transport.State.CREATED, Transport.State.STARTING)) { "Transport is not in CREATED state" }
        writer = scope.launch(ioDispatcher + CoroutineName("$name.write")) { writeLoop() }
        scope.launch(ioDispatcher + CoroutineName("$name.read")) {
            _state.compareAndSet(Transport.State.STARTING, Transport.State.STARTED)
            try {
                readLoop()
            } finally {
                withContext(NonCancellable) { finish() }
            }
        }
    }

    override fun send(message: JsonRpcMessage) {
        val encoded = codec.encode(message)
        if (encoded.length > maxLineChars) onTooLarge(message, encoded.length) else enqueue(encoded)
    }

    /** 正常关闭：不再接受新消息，已入队的消息写完（最多 [drainMillis]）后关闭连接。 */
    override fun close() {
        when (_state.value) {
            Transport.State.CLOSING, Transport.State.CLOSED -> return
            Transport.State.CREATED -> {
                _state.value = Transport.State.CLOSED
                reason.compareAndSet(null, "closed before start")
                closeStreamsOnce()
                fireClose()
            }
            else -> {
                _state.value = Transport.State.CLOSING
                reason.compareAndSet(null, "closed")
                outbound.close()
                scope.launch {
                    delay(drainMillis)
                    closeStreamsOnce()
                }
            }
        }
    }

    /** 立即断开（开关关闭、配对被撤销）：不等出站队列写完。 */
    fun abort(why: String) {
        reason.compareAndSet(null, "aborted: $why")
        outbound.close()
        closeStreamsOnce()
        if (_state.value == Transport.State.CREATED) {
            _state.value = Transport.State.CLOSED
            fireClose()
        }
    }

    /**
     * 挂起直到本地出站积压不超过 [maxQueuedChars]（字符）；传输关闭后立即返回。
     * [send] 不能挂起，所以流式输出的生产者每发一条之前调用它（OutboundGate）。
     */
    suspend fun awaitWritable(maxQueuedChars: Long) {
        queuedChars.first { it <= maxQueuedChars }
    }

    fun stats(): LineTransportStats = LineTransportStats(
        state = _state.value.name,
        linesIn = linesIn.get(),
        linesOut = linesOut.get(),
        decodeErrors = decodeErrors.get(),
        droppedTooLarge = droppedTooLarge.get(),
        syntheticErrors = syntheticErrors.get(),
        droppedClosed = droppedClosed.get(),
        queuedChars = queuedChars.value,
        closeReason = reason.get(),
    )

    // ------------------------------------------------------------------ 读

    private fun readLoop() {
        while (true) {
            val line = try {
                reader.readLine(maxLineChars)
            } catch (e: LineTooLongException) {
                reason.compareAndSet(null, "line_too_long")
                log.warn(TAG, "$name: inbound line exceeds $maxLineChars characters; closing")
                return
            } catch (e: IOException) {
                reason.compareAndSet(null, "read_error: ${e.javaClass.simpleName}")
                return
            }
            if (line == null) {
                reason.compareAndSet(null, "peer_closed")
                return
            }
            if (line.isBlank()) continue
            linesIn.incrementAndGet()
            val message = try {
                codec.decode(line)
            } catch (e: Exception) {
                decodeErrors.incrementAndGet()
                log.warn(TAG, "$name: undecodable line (${line.length} chars) skipped: ${e.javaClass.simpleName}")
                null
            }
            if (message != null) fireMessage(message)
        }
    }

    private suspend fun finish() {
        if (_state.value != Transport.State.CLOSED) _state.value = Transport.State.CLOSING
        outbound.close()
        // 对端只关了写方向时，把已经入队的响应写完
        withTimeoutOrNull(drainMillis) { writer?.join() }
        closeStreamsOnce()
        writer?.cancel()
        queuedChars.value = 0
        _state.value = Transport.State.CLOSED
        fireClose()
        scope.cancel()
    }

    // ------------------------------------------------------------------ 写

    private fun enqueue(line: String): Boolean {
        queuedChars.update { it + line.length }
        if (outbound.trySend(line).isFailure) {
            queuedChars.update { it - line.length }
            droppedClosed.incrementAndGet()
            return false
        }
        return true
    }

    private suspend fun writeLoop() {
        try {
            for (first in outbound) {
                var next: String? = first
                while (next != null) {
                    val line: String = next
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                    linesOut.incrementAndGet()
                    queuedChars.update { it - line.length }
                    next = outbound.tryReceive().getOrNull()
                }
                out.flush()
            }
            out.flush()
        } catch (e: IOException) {
            reason.compareAndSet(null, "write_error: ${e.javaClass.simpleName}")
        } finally {
            queuedChars.value = 0
            // 正常关闭（close）写完之后，或写出错时：关掉连接，读协程随之结束
            closeStreamsOnce()
        }
    }

    private fun closeStreamsOnce() {
        if (streamsClosed.compareAndSet(false, true)) {
            runCatching { out.flush() }
            runCatching { closeStreams() }
        }
    }

    private fun onTooLarge(message: JsonRpcMessage, length: Int) {
        val detail = "$TOO_LARGE_PREFIX message of $length chars exceeds $maxLineChars"
        log.warn(TAG, "$name: $detail (${message::class.simpleName})")
        when (message) {
            is JsonRpcRequest -> {
                // 让本端的 sendRequest 立即失败，而不是永远等不到响应
                syntheticErrors.incrementAndGet()
                val error = JsonRpcResponse(id = message.id, error = JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR.code, detail))
                scope.launch { fireMessage(error) }
            }
            is JsonRpcResponse -> {
                syntheticErrors.incrementAndGet()
                val error = JsonRpcResponse(id = message.id, error = JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR.code, detail))
                enqueue(codec.encode(error))
            }
            is JsonRpcNotification -> droppedTooLarge.incrementAndGet()
        }
    }

    companion object {
        private const val TAG = "LineTransport"

        /** 单行上限（字符），与 binder-channel-v1 的单条消息上限相同。 */
        const val MAX_LINE_CHARS = 65_536

        /** 超长出站消息的错误响应的 message 前缀（acp-mapping.md 第 10 节）。 */
        const val TOO_LARGE_PREFIX = "acp-line:"

        /** 关闭时把已入队的出站消息写完的上限。 */
        const val DRAIN_MILLIS = 2_000L
    }
}

/** [LineTransport] 的诊断统计（不含消息内容）。 */
data class LineTransportStats(
    val state: String,
    val linesIn: Long,
    val linesOut: Long,
    val decodeErrors: Long,
    val droppedTooLarge: Long,
    val syntheticErrors: Long,
    val droppedClosed: Long,
    val queuedChars: Long,
    val closeReason: String?,
)
