package org.agentos.channel

import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 通道为什么关闭。 */
sealed class CloseCause(val kind: String, val reason: String) {
    /** 本端调用了 [BinderChannel.close]。 */
    class Local(reason: String) : CloseCause("local", reason)
    /** 对端发来了 close。 */
    class Remote(reason: String) : CloseCause("remote", reason)
    /** 对端进程死亡（linkToDeath）。 */
    object PeerDied : CloseCause("peer_died", "peer died")
    /** 对端违反通道规则：UID 不符、超长、超窗口、非法 ack。 */
    class Violation(reason: String) : CloseCause("violation", reason)
    /** 本端发送失败、ack 超时、积压超限。 */
    class Failure(reason: String, val error: Throwable? = null) : CloseCause("failure", reason)

    override fun toString(): String = "$kind($reason)"
}

/**
 * binder-channel-v1 的一端：一条双向、有序、带流控的 JSON-RPC 消息通道。
 *
 * - 出站：[enqueue] 只入队，不阻塞；写协程在 [binderDispatcher] 上按顺序调用对端的 `IChannel.send`，
 *   在途（已发出未 ack）超过窗口就挂起等待 ack。
 * - 入站：对端的 `send` 在 Binder 线程上只做检查和入队；使用方从 [incoming] 逐条取出，处理完调用
 *   [markConsumed]，由本类按阈值回 ack。
 * - 每个入站调用都校验 `Binder.getCallingUid()`；对端进程死亡经 linkToDeath 感知。
 * - 关闭后 [incoming] 会先交付已经收到的消息再结束，[closeCause] 给出原因。
 */
class BinderChannel(
    val name: String,
    val expectedPeerUid: Int,
    val config: ChannelConfig,
    parentScope: CoroutineScope,
    private val binderDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    enum class EnqueueResult { OK, TOO_LARGE, CLOSED, BACKLOG_EXCEEDED }

    private enum class State { OPEN, CLOSING, CLOSED }

    private class AckTimeout : Exception()

    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + CoroutineName(name)
    )
    private val state = AtomicReference(State.OPEN)
    private val finished = AtomicBoolean(false)
    private val closed = CompletableDeferred<CloseCause>()
    @Volatile private var finalCause: CloseCause? = null

    // ---------- 出站 ----------
    private val outbox = Channel<String>(Channel.UNLIMITED)
    private val queued = MutableStateFlow(0L)
    private val outLock = Any()
    private val inFlightSizes = ArrayDeque<Int>()
    private var inFlightChars = 0L
    private var sentCount = 0L
    private var ackedCount = 0L
    private val ackSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var writerJob: Job? = null

    // ---------- 入站 ----------
    private val inbox = Channel<String>(Channel.UNLIMITED)
    private val inLock = Any()
    private val unackedSizes = ArrayDeque<Int>()
    private var unackedChars = 0L
    private var receivedCount = 0L
    private var consumedCount = 0L
    private var lastAckSent = 0L
    private var consumedCharsSinceAck = 0L

    @Volatile private var peer: IChannel? = null
    private val deathRecipient = IBinder.DeathRecipient { abort(CloseCause.PeerDied, notifyPeer = false) }

    // ---------- 统计 ----------
    private val st = Counters()

    private class Counters {
        val enqueued = AtomicLong()
        val sent = AtomicLong()
        val acksSent = AtomicLong()
        val acksReceived = AtomicLong()
        val windowWaits = AtomicLong()
        val windowWaitNanos = AtomicLong()
        val maxWindowWaitNanos = AtomicLong()
        val maxInFlightMessages = AtomicLong()
        val maxInFlightChars = AtomicLong()
        val maxQueuedChars = AtomicLong()
        val maxUnackedMessages = AtomicLong()
        val maxUnackedChars = AtomicLong()
        val rejectedTooLarge = AtomicLong()
        val uidRejects = AtomicLong()
        val sendNanosTotal = AtomicLong()
        val sendNanosMax = AtomicLong()
    }

    init {
        LIVE.incrementAndGet()
        // 外层作用域被取消（例如 Service 销毁）时也要走完关闭流程，否则 LIVE 计数和 linkToDeath 会泄漏。
        scope.coroutineContext[Job]?.invokeOnCompletion {
            if (!finished.get()) abort(CloseCause.Local("scope cancelled"), notifyPeer = true)
        }
    }

    /** 本端的接收端，交给对端（open 的参数或返回值）。 */
    val binder: IChannel.Stub = object : IChannel.Stub() {
        override fun send(message: String?) = onRemoteSend(message)
        override fun close(reason: String?) {
            if (!callerOk("close")) return
            finish(CloseCause.Remote(reason ?: ""))
        }
        override fun ack(consumed: Long) = onRemoteAck(consumed)
    }

    /** 入站消息。通道关闭后，已收到的消息仍会交付，然后结束。 */
    val incoming: ReceiveChannel<String> get() = inbox

    val closeCause: Deferred<CloseCause> get() = closed

    /** 已关闭时返回关闭原因，否则为 null。 */
    val closeCauseOrNull: CloseCause? get() = finalCause

    val isOpen: Boolean get() = state.get() == State.OPEN

    /** 本地积压（还没交给 Binder）的字符数。 */
    val queuedChars: Long get() = queued.value

    /**
     * 绑定对端的接收端并开始发送。只能调用一次。
     * 对端已经死亡时，通道立即以 [CloseCause.PeerDied] 关闭。
     */
    fun attachPeer(p: IChannel) {
        check(peer == null) { "peer already attached" }
        peer = p
        try {
            p.asBinder().linkToDeath(deathRecipient, 0)
        } catch (e: RemoteException) {
            abort(CloseCause.PeerDied, notifyPeer = false)
            return
        }
        if (finished.get()) {
            runCatching { p.asBinder().unlinkToDeath(deathRecipient, 0) }
            return
        }
        writerJob = scope.launch(binderDispatcher + CoroutineName("$name.writer")) { writerLoop(p) }
    }

    /** 入队一条出站消息，不阻塞，可以在任何线程调用。 */
    fun enqueue(message: String): EnqueueResult {
        if (state.get() != State.OPEN) return EnqueueResult.CLOSED
        val len = message.length
        if (len > config.maxMessageChars) {
            st.rejectedTooLarge.incrementAndGet()
            return EnqueueResult.TOO_LARGE
        }
        val q = queued.updateAndGet { it + len }
        if (q > config.maxQueuedChars) {
            queued.update { it - len }
            abort(CloseCause.Failure("send backlog $q chars exceeds ${config.maxQueuedChars}"), notifyPeer = true)
            return EnqueueResult.BACKLOG_EXCEEDED
        }
        if (outbox.trySend(message).isFailure) {
            queued.update { it - len }
            return EnqueueResult.CLOSED
        }
        st.enqueued.incrementAndGet()
        st.maxQueuedChars.accumulateAndGet(q, ::maxOf)
        return EnqueueResult.OK
    }

    /** 挂起直到本地积压不超过 [maxQueuedChars]，或通道已关闭。给生产者（如流式输出）做背压用。 */
    suspend fun awaitWritable(maxQueuedChars: Long) {
        if (queued.value <= maxQueuedChars || state.get() != State.OPEN) return
        queued.first { it <= maxQueuedChars || state.get() != State.OPEN }
    }

    /** 使用方处理完 [incoming] 里的一条消息后调用（按取出顺序）。 */
    fun markConsumed(length: Int) {
        var ackValue = -1L
        synchronized(inLock) {
            consumedCount++
            consumedCharsSinceAck += length
            val pending = consumedCount - lastAckSent
            val idle = consumedCount == receivedCount
            if (pending > 0 && (pending >= config.ackEveryMessages ||
                        consumedCharsSinceAck >= config.ackEveryChars || idle)
            ) {
                var n = pending
                while (n-- > 0 && unackedSizes.isNotEmpty()) unackedChars -= unackedSizes.removeFirst()
                lastAckSent = consumedCount
                consumedCharsSinceAck = 0
                ackValue = consumedCount
            }
        }
        if (ackValue >= 0) sendAck(ackValue)
    }

    /** 优雅关闭：先把积压发完（最多 closeFlushTimeoutMs），再通知对端。 */
    fun close(reason: String) {
        if (!state.compareAndSet(State.OPEN, State.CLOSING)) return
        outbox.close()
        scope.launch(binderDispatcher + CoroutineName("$name.close")) {
            writerJob?.let { w -> withTimeoutOrNull(config.closeFlushTimeoutMs) { w.join() } }
            writerJob?.cancel()
            peer?.let { p -> runCatching { binderCall { p.close(reason) } } }
            finish(CloseCause.Local(reason))
        }
    }

    fun stats(): JSONObject {
        val out = synchronized(outLock) {
            JSONObject().put("sent", sentCount).put("acked", ackedCount)
                .put("inFlightMessages", inFlightSizes.size).put("inFlightChars", inFlightChars)
        }
        val inb = synchronized(inLock) {
            JSONObject().put("received", receivedCount).put("consumed", consumedCount)
                .put("unackedMessages", unackedSizes.size).put("unackedChars", unackedChars)
        }
        return JSONObject()
            .put("name", name)
            .put("state", state.get().name)
            .put("closeCause", finalCause?.toString() ?: JSONObject.NULL)
            .put("out", out)
            .put("in", inb)
            .put("enqueued", st.enqueued.get())
            .put("acksSent", st.acksSent.get())
            .put("acksReceived", st.acksReceived.get())
            .put("windowWaits", st.windowWaits.get())
            .put("windowWaitMsTotal", st.windowWaitNanos.get() / 1e6)
            .put("windowWaitMsMax", st.maxWindowWaitNanos.get() / 1e6)
            .put("maxInFlightMessages", st.maxInFlightMessages.get())
            .put("maxInFlightChars", st.maxInFlightChars.get())
            .put("maxQueuedChars", st.maxQueuedChars.get())
            .put("maxUnackedMessages", st.maxUnackedMessages.get())
            .put("maxUnackedChars", st.maxUnackedChars.get())
            .put("rejectedTooLarge", st.rejectedTooLarge.get())
            .put("uidRejects", st.uidRejects.get())
            .put("binderSendUsAvg", if (st.sent.get() > 0) st.sendNanosTotal.get() / st.sent.get() / 1000.0 else 0.0)
            .put("binderSendUsMax", st.sendNanosMax.get() / 1000.0)
    }

    // ------------------------------------------------------------------
    // 入站（Binder 线程）

    private fun onRemoteSend(message: String?) {
        if (!callerOk("send")) return
        if (finished.get()) return
        if (message == null) {
            abort(CloseCause.Violation("null message"), notifyPeer = true)
            return
        }
        val len = message.length
        if (config.enforceInboundLimits && len > config.maxMessageChars) {
            abort(CloseCause.Violation("message of $len chars exceeds ${config.maxMessageChars}"), notifyPeer = true)
            return
        }
        var violation: String? = null
        synchronized(inLock) {
            receivedCount++
            unackedSizes.addLast(len)
            unackedChars += len
            val n = unackedSizes.size
            st.maxUnackedMessages.accumulateAndGet(n.toLong(), ::maxOf)
            st.maxUnackedChars.accumulateAndGet(unackedChars, ::maxOf)
            if (config.enforceInboundLimits &&
                (n > config.windowMessages || (n > 1 && unackedChars > config.windowChars))
            ) {
                violation = "window exceeded: $n messages / $unackedChars chars unacked"
            }
        }
        violation?.let {
            abort(CloseCause.Violation(it), notifyPeer = true)
            return
        }
        inbox.trySend(message)
    }

    private fun onRemoteAck(consumed: Long) {
        if (!callerOk("ack")) return
        st.acksReceived.incrementAndGet()
        var bad: String? = null
        synchronized(outLock) {
            if (consumed > sentCount) {
                if (config.enforceInboundLimits) bad = "ack $consumed beyond sent $sentCount"
            } else if (consumed > ackedCount) {
                var n = consumed - ackedCount
                while (n-- > 0) inFlightChars -= inFlightSizes.removeFirst()
                ackedCount = consumed
            }
        }
        bad?.let {
            abort(CloseCause.Violation(it), notifyPeer = true)
            return
        }
        ackSignal.trySend(Unit)
    }

    private fun callerOk(method: String): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == expectedPeerUid) return true
        st.uidRejects.incrementAndGet()
        Log.w(TAG, "$name: $method from uid $uid, expected $expectedPeerUid; closing")
        abort(CloseCause.Violation("caller uid $uid != expected $expectedPeerUid"), notifyPeer = true)
        return false
    }

    // ------------------------------------------------------------------
    // 出站

    private suspend fun writerLoop(p: IChannel) {
        try {
            for (msg in outbox) {
                val len = msg.length
                queued.update { it - len }
                if (config.flowControl) awaitWindow(len)
                synchronized(outLock) {
                    sentCount++
                    inFlightSizes.addLast(len)
                    inFlightChars += len
                    st.maxInFlightMessages.accumulateAndGet(inFlightSizes.size.toLong(), ::maxOf)
                    st.maxInFlightChars.accumulateAndGet(inFlightChars, ::maxOf)
                }
                val t0 = SystemClock.elapsedRealtimeNanos()
                binderCall { p.send(msg) }
                val dt = SystemClock.elapsedRealtimeNanos() - t0
                st.sent.incrementAndGet()
                st.sendNanosTotal.addAndGet(dt)
                st.sendNanosMax.accumulateAndGet(dt, ::maxOf)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AckTimeout) {
            abort(CloseCause.Failure("no ack for ${config.ackTimeoutMs} ms with a full window"), notifyPeer = true)
        } catch (e: Exception) {
            // 注意：oneway 事务因接收方异步缓冲不足而失败时，系统也会抛 DeadObjectException，
            // 所以只能以 isBinderAlive 判断对端是否真的死亡。
            val alive = runCatching { p.asBinder().isBinderAlive }.getOrDefault(false)
            if (!alive) {
                abort(CloseCause.PeerDied, notifyPeer = false)
            } else {
                Log.w(TAG, "$name: send failed", e)
                abort(CloseCause.Failure("send failed: $e", e), notifyPeer = true)
            }
        }
    }

    private suspend fun awaitWindow(len: Int) {
        var waitStart = 0L
        while (true) {
            val ok = synchronized(outLock) {
                val n = inFlightSizes.size
                n == 0 || (n < config.windowMessages && inFlightChars + len <= config.windowChars)
            }
            if (ok) {
                if (waitStart != 0L) {
                    val waited = SystemClock.elapsedRealtimeNanos() - waitStart
                    st.windowWaitNanos.addAndGet(waited)
                    st.maxWindowWaitNanos.accumulateAndGet(waited, ::maxOf)
                }
                return
            }
            if (waitStart == 0L) {
                waitStart = SystemClock.elapsedRealtimeNanos()
                st.windowWaits.incrementAndGet()
            }
            withTimeoutOrNull(config.ackTimeoutMs) { ackSignal.receive() } ?: throw AckTimeout()
        }
    }

    private fun sendAck(value: Long) {
        val p = peer ?: return
        if (finished.get()) return
        try {
            binderCall { p.ack(value) }
            st.acksSent.incrementAndGet()
        } catch (e: Exception) {
            val alive = runCatching { p.asBinder().isBinderAlive }.getOrDefault(false)
            abort(if (alive) CloseCause.Failure("ack failed: $e", e) else CloseCause.PeerDied, notifyPeer = alive)
        }
    }

    private inline fun binderCall(block: () -> Unit) {
        if (Looper.getMainLooper().isCurrentThread) MAIN_THREAD_BINDER_CALLS.incrementAndGet()
        block()
    }

    // ------------------------------------------------------------------
    // 关闭

    private fun abort(cause: CloseCause, notifyPeer: Boolean) {
        if (finished.get()) return
        state.set(State.CLOSED)
        outbox.close()
        writerJob?.cancel()
        if (notifyPeer) peer?.let { p -> runCatching { binderCall { p.close(cause.reason) } } }
        finish(cause)
    }

    private fun finish(cause: CloseCause) {
        if (!finished.compareAndSet(false, true)) return
        // 先给出关闭原因，再结束 incoming：使用方在 incoming 结束时总能读到 closeCause。
        finalCause = cause
        closed.complete(cause)
        state.set(State.CLOSED)
        outbox.close()
        writerJob?.cancel()
        peer?.let { p -> runCatching { p.asBinder().unlinkToDeath(deathRecipient, 0) } }
        inbox.close()
        queued.value = 0
        LIVE.decrementAndGet()
        Log.i(TAG, "$name closed: $cause")
        scope.cancel()
    }

    /** 只给 spike 的违规注入测试用：拿到对端的接收端，绕过本类直接发消息。 */
    val peerForTesting: IChannel? get() = peer

    companion object {
        private const val TAG = "BinderChannel"
        private val LIVE = AtomicInteger()
        private val MAIN_THREAD_BINDER_CALLS = AtomicLong()

        /** 本进程里还没关闭的通道数，用来查泄漏。 */
        val liveChannels: Int get() = LIVE.get()

        /** 本进程里在主线程上发生的 Binder 调用次数，期望为 0。 */
        val mainThreadBinderCalls: Long get() = MAIN_THREAD_BINDER_CALLS.get()
    }
}
