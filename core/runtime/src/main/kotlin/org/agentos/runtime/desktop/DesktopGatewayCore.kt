package org.agentos.runtime.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import org.agentos.runtime.AgentRuntime
import org.agentos.runtime.acp.BoundedLineReader
import org.agentos.runtime.acp.LineCodec
import org.agentos.runtime.acp.LineTooLongException
import org.agentos.runtime.acp.LineTransport
import org.agentos.runtime.acp.LineTransportStats
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.OutboundGate
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.info
import org.agentos.runtime.ports.warn
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** 一条已接受的电脑端连接。Android 上是抽象 socket 的 LocalSocket；电脑上的测试是 TCP socket。 */
interface DesktopEndpoint {
    val input: InputStream
    val output: OutputStream

    /** 对端 UID（LocalSocket 的 SO_PEERCRED；adb forward 时是 adbd 的 shell 2000）；取不到时 -1。 */
    val peerUid: Int

    /** 关闭连接；必须让阻塞中的读返回。可以重复调用。 */
    fun close()
}

/** 监听端。[close] 必须让阻塞中的 [accept] 以 IOException 返回。 */
interface DesktopListener : Closeable {
    fun accept(): DesktopEndpoint
}

/** 打开监听端；地址被占用等情况抛 IOException。 */
fun interface DesktopListenerFactory {
    fun open(): DesktopListener
}

data class DesktopGatewayConfig(
    /** 同时服务的电脑端连接上限（已完成握手的）。 */
    val maxConnections: Int = 8,
    /** 同时进行中的握手上限：每个握手占一个阻塞读，最多 [handshakeTimeoutMillis]。 */
    val maxPendingHandshakes: Int = 4,
    val handshakeTimeoutMillis: Long = DesktopHandshake.TIMEOUT_MILLIS,
    val maxLineChars: Int = LineTransport.MAX_LINE_CHARS,
)

/**
 * 电脑端接入（architecture F11，W9）与平台无关的部分；Android 外壳是 app 的 `DesktopGateway`。
 *
 * - 开关打开时监听（[DesktopListenerFactory]），关闭时停止监听并断开所有电脑端连接、清掉全部配对（[DesktopPairing]）。
 * - 每条连接：先检查对端 UID（[peerAllowed]，Android 上只接受 adbd 的 shell 和 root），不通过的**不读任何数据**直接关闭；
 *   然后在 [DesktopGatewayConfig.handshakeTimeoutMillis] 内读配对握手（[DesktopHandshake]），失败回 `auth_required` 并关闭。
 *   **握手成功之前不解析、不处理任何 ACP 消息。**
 * - 握手成功后，同一条连接变成 [LineTransport]，以 [CallerKind.DESKTOP]（ownerKey `desktop`）交给 [AgentRuntime.serveAcp]。
 *   电脑端和其他调用方一样走宿主层的授权与确认（HostPort.consent，手机上弹确认），ACP 里没有任何能替用户确认的入口。
 */
class DesktopGatewayCore(
    private val runtime: AgentRuntime,
    val pairing: DesktopPairing,
    private val codec: LineCodec,
    private val listenerFactory: DesktopListenerFactory,
    parentScope: CoroutineScope,
    private val config: DesktopGatewayConfig = DesktopGatewayConfig(),
    private val peerAllowed: (Int) -> Boolean = { true },
    private val log: RuntimeLog = RuntimeLog.NONE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + CoroutineName("desktop-gateway"),
    )
    private val lock = Any()

    @Volatile private var listener: DesktopListener? = null

    /** 最近一次打开监听失败的原因（例如抽象 socket 名被占用）；监听正常时为 null。 */
    @Volatile var listenError: String? = null
        private set

    private val nextId = AtomicInteger()
    private val conns = ConcurrentHashMap<Int, Conn>()
    private val pending = AtomicInteger()
    private val accepted = AtomicLong()
    private val served = AtomicLong()
    private val closed = AtomicLong()
    private val rejectedPeer = AtomicLong()
    private val rejectedBusy = AtomicLong()
    private val handshakeTimeouts = AtomicLong()
    private val handshakeFailures = ConcurrentHashMap<String, AtomicLong>()
    private val recentCloses = ConcurrentLinkedDeque<DesktopCloseRecord>()

    private class Conn(
        val id: Int,
        val pairingId: String,
        val label: String,
        val peerUid: Int,
        val transport: LineTransport,
        val openedAtMillis: Long,
    )

    val enabled: Boolean get() = pairing.enabled
    val listening: Boolean get() = listener != null

    /** 进程启动时调用：开关是打开的就开始监听。 */
    fun restore() {
        synchronized(lock) { if (pairing.enabled) startListening() }
    }

    /** 打开或关闭电脑端接入。关闭：停止监听、断开全部电脑端连接、清掉配对码和全部配对。 */
    fun setEnabled(on: Boolean) {
        synchronized(lock) {
            pairing.setEnabled(on)
            if (on) {
                startListening()
            } else {
                stopListening()
                abortAll("desktop access turned off")
            }
        }
        log.info(TAG, "desktop access ${if (on) "on" else "off"} (listening=$listening)")
    }

    /** 生成新的一次性配对码（替换旧的）。开关关闭时抛 [IllegalStateException]。 */
    fun newPairingCode(ttlMillis: Long = pairing.config.codeTtlMillis): PairingCode = pairing.newCode(ttlMillis)

    /** 撤销一个配对，并断开它的连接。 */
    fun revoke(pairingId: String): Boolean = synchronized(lock) {
        val removed = pairing.revoke(pairingId)
        conns.values.filter { it.pairingId == pairingId }.forEach { it.transport.abort("pairing revoked") }
        removed
    }

    /** 撤销全部配对，并断开全部电脑端连接。 */
    fun revokeAll(): Int = synchronized(lock) {
        val n = pairing.revokeAll()
        abortAll("all pairings revoked")
        n
    }

    fun connections(): List<DesktopConnectionInfo> = conns.values.snapshot().sortedBy { it.id }.map { it.info() }

    fun stats(): DesktopGatewayStats = DesktopGatewayStats(
        enabled = pairing.enabled,
        listening = listening,
        listenError = listenError,
        code = pairing.codeStatus(),
        pairings = pairing.pairings().size,
        connections = connections(),
        accepted = accepted.get(),
        served = served.get(),
        closed = closed.get(),
        rejectedPeer = rejectedPeer.get(),
        rejectedBusy = rejectedBusy.get(),
        pendingHandshakes = pending.get(),
        handshakeTimeouts = handshakeTimeouts.get(),
        handshakeFailures = handshakeFailures.mapValues { it.value.get() }.toSortedMap(),
        recentCloses = recentCloses.snapshot(),
    )

    /** 进程退出前：停止监听、断开连接（不改开关和配对）。 */
    fun shutdown() {
        synchronized(lock) {
            stopListening()
            abortAll("shutdown")
        }
        scope.cancel()
    }

    // ------------------------------------------------------------------ 监听

    private fun startListening() {
        if (listener != null) return
        val l = try {
            listenerFactory.open()
        } catch (e: IOException) {
            listenError = e.message ?: e.javaClass.simpleName
            log.warn(TAG, "cannot listen: $listenError")
            return
        }
        listenError = null
        listener = l
        thread(name = "desktop-gateway-accept", isDaemon = true) { acceptLoop(l) }
    }

    private fun stopListening() {
        val l = listener ?: return
        listener = null
        runCatching { l.close() }
    }

    private fun acceptLoop(l: DesktopListener) {
        while (true) {
            val ep = try {
                l.accept()
            } catch (e: IOException) {
                break
            }
            if (listener !== l) {
                // 监听已经关掉（close 之后才返回的连接，或为了唤醒 accept 的自连）
                ep.close()
                break
            }
            onAccepted(ep)
        }
        synchronized(lock) {
            if (listener === l) {
                // 不是我们关的：监听端出错
                listener = null
                listenError = "accept loop ended unexpectedly"
                runCatching { l.close() }
            }
        }
    }

    private fun onAccepted(ep: DesktopEndpoint) {
        accepted.incrementAndGet()
        if (!peerAllowed(ep.peerUid)) {
            // 不读任何数据：只有 adb forward（shell）和 root 能连上来
            rejectedPeer.incrementAndGet()
            log.warn(TAG, "rejected a desktop connection from uid ${ep.peerUid}")
            ep.close()
            return
        }
        if (conns.size >= config.maxConnections || pending.get() >= config.maxPendingHandshakes) {
            rejectedBusy.incrementAndGet()
            scope.launch(ioDispatcher) {
                writeLine(ep, DesktopHandshake.busy())
                ep.close()
            }
            return
        }
        pending.incrementAndGet()
        scope.launch(ioDispatcher + CoroutineName("desktop-handshake")) {
            try {
                handshake(ep)
            } catch (e: Exception) {
                log.warn(TAG, "desktop handshake failed: ${e.javaClass.simpleName}")
                ep.close()
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    // ------------------------------------------------------------------ 握手

    private fun handshake(ep: DesktopEndpoint) {
        val reader = BoundedLineReader(ep.input)
        val done = AtomicBoolean(false)
        val watchdog = scope.launch {
            delay(config.handshakeTimeoutMillis)
            if (done.compareAndSet(false, true)) {
                handshakeTimeouts.incrementAndGet()
                ep.close()
            }
        }
        val line = try {
            reader.readLine(DesktopHandshake.MAX_LINE_CHARS)
        } catch (e: LineTooLongException) {
            if (done.compareAndSet(false, true)) fail("line_too_long")
            watchdog.cancel()
            ep.close()
            return
        } catch (e: IOException) {
            null
        }
        if (!done.compareAndSet(false, true)) return // 已超时，连接已关
        watchdog.cancel()
        if (line == null) {
            fail("no_request")
            ep.close()
            return
        }
        val request = DesktopHandshake.parse(line)
        val result = when (request) {
            is DesktopHandshake.Request.Code -> pairing.pairWithCode(request.code, request.label)
            is DesktopHandshake.Request.Token -> pairing.verifyToken(request.token)
            is DesktopHandshake.Request.Invalid -> PairingResult.Rejected(PairingFailure.PAIRING_REQUIRED)
        }
        when (result) {
            is PairingResult.Rejected -> reject(ep, request.id, result.failure)
            is PairingResult.Accepted -> serve(ep, reader, request.id, result)
        }
    }

    private fun reject(ep: DesktopEndpoint, id: JsonElement, failure: PairingFailure) {
        fail(failure.wire)
        log.info(TAG, "desktop handshake rejected: ${failure.wire}")
        writeLine(ep, DesktopHandshake.failure(id, failure))
        ep.close()
    }

    private fun serve(ep: DesktopEndpoint, reader: BoundedLineReader, id: JsonElement, ok: PairingResult.Accepted) {
        val connId = nextId.incrementAndGet()
        val transport = LineTransport(
            reader = reader,
            output = ep.output,
            parentScope = scope,
            codec = codec,
            name = "desktop-$connId",
            maxLineChars = config.maxLineChars,
            closeStreams = { ep.close() },
            ioDispatcher = ioDispatcher,
            log = log,
        )
        val conn = Conn(connId, ok.pairing.id, ok.pairing.label, ep.peerUid, transport, wallClock())
        transport.onClose { onClosed(conn) }
        synchronized(lock) {
            // 验证之后、登记之前开关被关掉或配对被撤销
            if (!pairing.isPaired(ok.pairing.id)) {
                reject(ep, id, if (pairing.enabled) PairingFailure.INVALID_TOKEN else PairingFailure.DISABLED)
                return
            }
            if (!writeLine(ep, DesktopHandshake.success(id, ok, config.maxLineChars))) {
                // 令牌没送到：这次新建的配对作废
                if (ok.token != null) pairing.revoke(ok.pairing.id)
                ep.close()
                return
            }
            conns[connId] = conn
        }
        served.incrementAndGet()
        log.info(TAG, "desktop connection $connId opened (pairing ${ok.pairing.id}, uid ${ep.peerUid})")
        val caller = CallerIdentity(uid = ep.peerUid, kind = CallerKind.DESKTOP, label = CALLER_LABEL)
        try {
            runtime.serveAcp(transport, caller, OutboundGate { transport.awaitWritable(OutboundGate.BINDER_HIGH_WATER_CHARS) })
        } catch (e: Exception) {
            // 登记之后、启动之前被 abort（开关关闭）时，transport 已是 CLOSED，start 会失败
            log.warn(TAG, "desktop connection $connId could not be served: ${e.javaClass.simpleName}")
            transport.abort("serve failed")
            onClosed(conn)
        }
    }

    private fun onClosed(c: Conn) {
        if (conns.remove(c.id) == null) return
        closed.incrementAndGet()
        recentCloses.addLast(
            DesktopCloseRecord(c.id, c.pairingId, wallClock() - c.openedAtMillis, c.transport.closeReason ?: "unknown"),
        )
        while (recentCloses.size > 20) recentCloses.pollFirst()
        log.info(TAG, "desktop connection ${c.id} closed: ${c.transport.closeReason}")
    }

    private fun abortAll(why: String) {
        conns.values.forEach { it.transport.abort(why) }
    }

    private fun fail(reason: String) {
        handshakeFailures.getOrPut(reason) { AtomicLong() }.incrementAndGet()
    }

    private fun writeLine(ep: DesktopEndpoint, line: String): Boolean = try {
        ep.output.write((line + "\n").toByteArray(Charsets.UTF_8))
        ep.output.flush()
        true
    } catch (e: IOException) {
        false
    }

    private fun Conn.info() = DesktopConnectionInfo(id, pairingId, label, peerUid, openedAtMillis, transport.stats())

    companion object {
        private const val TAG = "DesktopGateway"

        /** 电脑端调用方的 label（给人看；不来自客户端）。 */
        const val CALLER_LABEL = "desktop"
    }
}

data class DesktopConnectionInfo(
    val id: Int,
    val pairingId: String,
    /** 电脑端自报的名字（配对时提交），只作区分。 */
    val label: String,
    val peerUid: Int,
    val openedAtMillis: Long,
    val transport: LineTransportStats,
)

data class DesktopCloseRecord(val id: Int, val pairingId: String, val aliveMillis: Long, val reason: String)

/**
 * 并发集合（ConcurrentHashMap 的视图、ConcurrentLinkedDeque）的一份快照，之后再排序、转换。
 *
 * 不要直接对它们用 `toList()`、`sortedBy`、`first()` 这类扩展：Kotlin 对大小 ≤ 1 的集合先读 `size`、再 `iterator().next()`，
 * 两步之间另一个线程移走了唯一的元素就抛 NoSuchElementException（A9：关开关时最后一条连接在 IO 线程上关闭、移出连接表，
 * 同时 setEnabled(false) 之后的 status() 在读它）。`ArrayList(collection)` 走 `toArray()`，并发修改时也不会抛。
 */
internal fun <T> Collection<T>.snapshot(): List<T> = ArrayList(this)

/** 诊断信息：不含配对码、令牌和消息内容。 */
data class DesktopGatewayStats(
    val enabled: Boolean,
    val listening: Boolean,
    val listenError: String?,
    val code: CodeStatus?,
    val pairings: Int,
    val connections: List<DesktopConnectionInfo>,
    val accepted: Long,
    val served: Long,
    val closed: Long,
    val rejectedPeer: Long,
    val rejectedBusy: Long,
    val pendingHandshakes: Int,
    val handshakeTimeouts: Long,
    val handshakeFailures: Map<String, Long>,
    val recentCloses: List<DesktopCloseRecord>,
)
