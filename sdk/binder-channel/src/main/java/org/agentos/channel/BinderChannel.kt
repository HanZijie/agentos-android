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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 通道为什么关闭。 */
sealed class CloseCause(val kind: String, val reason: String) {
    /** 本端调用了 [BinderChannel.close]，或外层作用域被取消。 */
    class Local(reason: String) : CloseCause(KIND_LOCAL, reason)
    /** 对端发来了 close。 */
    class Remote(reason: String) : CloseCause(KIND_REMOTE, reason)
    /** 对端进程死亡（linkToDeath）。 */
    object PeerDied : CloseCause(KIND_PEER_DIED, "peer died")
    /** 对端违反通道规则：UID 不符、超长、超窗口、非法 ack。 */
    class Violation(reason: String) : CloseCause(KIND_VIOLATION, reason)
    /** 本端发送失败、ack 超时、积压超限。 */
    class Failure(reason: String, val error: Throwable? = null) : CloseCause(KIND_FAILURE, reason)

    override fun toString(): String = "$kind($reason)"

    companion object {
        const val KIND_LOCAL = "local"
        const val KIND_REMOTE = "remote"
        const val KIND_PEER_DIED = "peer_died"
        const val KIND_VIOLATION = "violation"
        const val KIND_FAILURE = "failure"
    }
}

/**
 * binder-channel-v1 的一端：一条双向、有序、带流控的 JSON-RPC 消息通道（core/protocol/binder-channel-v1.md）。
 *
 * - 出站：[enqueue] 只入队，不阻塞；写协程在 [binderDispatcher] 上按顺序调用对端的 `IChannel.send`，
 *   在途（已发出未 ack）超过窗口就挂起等待 ack。Binder 调用不在主线程。
 * - 入站：对端的 `send` 在 Binder 线程上只做检查和入队；使用方从 [incoming] 逐条取出，处理完调用
 *   [markConsumed]，由本类按阈值回 ack。
 * - 每个入站调用都校验 `Binder.getCallingUid()` 等于 [expectedPeerUid]；对端进程死亡经 linkToDeath 感知。
 * - 关闭后 [incoming] 先交付已经收到的消息再结束；[closeCause] 给出原因，并且在 [incoming] 结束之前就已确定。
 * - 外层作用域被取消时，本类同样走关闭流程并通知对端。
 *
 * 用法：先创建（此时 [binder] 已能接收），把 [binder] 交给对端（`open` 的参数或返回值），拿到对端的接收端后
 * 调用 [attachPeer]。
 */
class BinderChannel(
    val name: String,
    val expectedPeerUid: Int,
    val config: ChannelConfig = ChannelConfig.DEFAULT,
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
    private val sendWindow = SendWindow(config)
    private val ackSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var writerJob: Job? = null

    // ---------- 入站 ----------
    private val inbox = Channel<String>(Channel.UNLIMITED)
    private val receiveWindow = ReceiveWindow(config)
    @Volatile private var latestAck = 0L

    @Volatile private var peer: IChannel? = null
    private val deathRecipient = IBinder.DeathRecipient { abort(CloseCause.PeerDied, notifyPeer = false) }

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
        scope.coroutineContext[Job]?.invokeOnCompletion {
            if (!finished.get()) abort(CloseCause.Local("scope cancelled"), notifyPeer = true)
        }
    }

    /** 本端的接收端，交给对端（`open` 的参数或返回值）。 */
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

    /** 关闭原因；通道关闭时完成。 */
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
        val ackValue = synchronized(receiveWindow) { receiveWindow.onConsumed(length) }
        if (ackValue >= 0) sendAck(ackValue)
    }

    /** 优雅关闭：先把积压发完（最多 closeFlushTimeoutMs），再通知对端。可以重复调用。 */
    fun close(reason: String) {
        if (!state.compareAndSet(State.OPEN, State.CLOSING)) return
        outbox.close()
        scope.launch(binderDispatcher + CoroutineName("$name.close")) {
            writerJob?.let { w -> withTimeoutOrNull(config.closeFlushTimeoutMs) { w.join() } }
            writerJob?.cancel()
            peer?.let { p -> notifyPeerClose(p, reason) }
            finish(CloseCause.Local(reason))
        }
    }

    /** 诊断用的统计（不含消息内容）。 */
    fun stats(): JSONObject {
        val out = synchronized(sendWindow) {
            JSONObject().put("sent", sendWindow.sent).put("acked", sendWindow.acked)
                .put("inFlightMessages", sendWindow.inFlightMessages).put("inFlightChars", sendWindow.inFlightChars)
        }
        val inb = synchronized(receiveWindow) {
            JSONObject().put("received", receiveWindow.received).put("consumed", receiveWindow.consumed)
                .put("unackedMessages", receiveWindow.unackedMessages).put("unackedChars", receiveWindow.unackedChars)
        }
        return JSONObject()
            .put("name", name)
            .put("peerUid", expectedPeerUid)
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

    /**
     * 只给测试用：对端的接收端。用来绕过流控直接往对端塞消息，验证对端的违规检查。
     * 正式代码不要用。
     */
    val peerForTesting: IChannel? get() = peer

    // ------------------------------------------------------------------
    // 入站（Binder 线程）

    private fun onRemoteSend(message: String?) {
        if (!callerOk("send")) return
        if (finished.get()) return
        if (message == null) {
            abort(CloseCause.Violation("null message"), notifyPeer = true)
            return
        }
        val violation = synchronized(receiveWindow) {
            val v = receiveWindow.onReceived(message.length)
            st.maxUnackedMessages.accumulateAndGet(receiveWindow.unackedMessages.toLong(), ::maxOf)
            st.maxUnackedChars.accumulateAndGet(receiveWindow.unackedChars, ::maxOf)
            v
        }
        if (violation != null && config.enforceInboundLimits) {
            abort(CloseCause.Violation(violation), notifyPeer = true)
            return
        }
        inbox.trySend(message)
    }

    private fun onRemoteAck(consumed: Long) {
        if (!callerOk("ack")) return
        st.acksReceived.incrementAndGet()
        val result = synchronized(sendWindow) { sendWindow.onAck(consumed) }
        if (result == SendWindow.AckResult.BEYOND_SENT && config.enforceInboundLimits) {
            abort(CloseCause.Violation("ack $consumed beyond sent ${sendWindow.sent}"), notifyPeer = true)
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
                synchronized(sendWindow) {
                    sendWindow.onSent(len)
                    st.maxInFlightMessages.accumulateAndGet(sendWindow.inFlightMessages.toLong(), ::maxOf)
                    st.maxInFlightChars.accumulateAndGet(sendWindow.inFlightChars, ::maxOf)
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
            // oneway 事务因接收方异步缓冲不足而失败时，系统也会抛 DeadObjectException，
            // 所以只能以 isBinderAlive 判断对端是否真的死亡。
            if (!isAlive(p)) {
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
            if (synchronized(sendWindow) { sendWindow.canSend(len) }) {
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
        latestAck = maxOf(latestAck, value)
        if (tryAck(p, latestAck)) return
        if (!isAlive(p)) {
            abort(CloseCause.PeerDied, notifyPeer = false)
            return
        }
        // 对端异步缓冲暂时满（ENOSPC）：ack 是累计值，稍后补发最新值即可，不关闭通道。
        ACK_RETRIES.incrementAndGet()
        fun schedule(attempt: Int) {
            if (attempt >= RETRY_DELAYS_MS.size || finished.get()) return
            RETRY_EXECUTOR.schedule({
                if (!finished.get() && !tryAck(p, latestAck)) schedule(attempt + 1)
            }, RETRY_DELAYS_MS[attempt], TimeUnit.MILLISECONDS)
        }
        schedule(0)
    }

    private fun tryAck(p: IChannel, value: Long): Boolean = try {
        binderCall { p.ack(value) }
        st.acksSent.incrementAndGet()
        true
    } catch (e: Exception) {
        false
    }

    private inline fun binderCall(block: () -> Unit) {
        if (Looper.getMainLooper().isCurrentThread) MAIN_THREAD_BINDER_CALLS.incrementAndGet()
        block()
    }

    private fun isAlive(p: IChannel): Boolean = runCatching { p.asBinder().isBinderAlive }.getOrDefault(false)

    // ------------------------------------------------------------------
    // 关闭

    private fun abort(cause: CloseCause, notifyPeer: Boolean) {
        if (finished.get()) return
        state.set(State.CLOSED)
        outbox.close()
        writerJob?.cancel()
        if (notifyPeer) peer?.let { p -> RETRY_EXECUTOR.execute { notifyPeerClose(p, cause.reason) } }
        finish(cause)
    }

    /**
     * 通知对端关闭。对端的异步缓冲满时 oneway 调用也会失败（内核返回 ENOSPC，Java 层是 DeadObjectException），
     * 这时 close 丢了，对端会永远等下去（S3 问题 1），所以按退避重试到成功或对端死亡。
     */
    private fun notifyPeerClose(p: IChannel, reason: String) {
        if (tryClose(p, reason)) return
        CLOSE_RETRIES.incrementAndGet()
        fun schedule(attempt: Int) {
            if (attempt >= RETRY_DELAYS_MS.size) {
                Log.w(TAG, "$name: giving up notifying peer of close")
                return
            }
            RETRY_EXECUTOR.schedule({
                if (!isAlive(p)) return@schedule
                if (!tryClose(p, reason)) schedule(attempt + 1)
            }, RETRY_DELAYS_MS[attempt], TimeUnit.MILLISECONDS)
        }
        schedule(0)
    }

    private fun tryClose(p: IChannel, reason: String): Boolean = try {
        binderCall { p.close(reason) }
        true
    } catch (e: Exception) {
        false
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

    companion object {
        private const val TAG = "BinderChannel"
        private val LIVE = AtomicInteger()
        private val MAIN_THREAD_BINDER_CALLS = AtomicLong()
        private val CLOSE_RETRIES = AtomicLong()
        private val ACK_RETRIES = AtomicLong()

        /** close / ack 失败而对端仍活着时的重试间隔（binder-channel-v1 第 5、6 节）。 */
        private val RETRY_DELAYS_MS = longArrayOf(10, 50, 200, 1_000, 3_000)
        private val RETRY_EXECUTOR = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "binder-channel-retry").apply { isDaemon = true }
        }

        /** 本进程里还没关闭的通道数，用来查泄漏。 */
        val liveChannels: Int get() = LIVE.get()

        /** 本进程里在主线程上发生的 Binder 调用次数，期望为 0。 */
        val mainThreadBinderCalls: Long get() = MAIN_THREAD_BINDER_CALLS.get()

        /** 第一次通知对端关闭失败、转入重试的次数。 */
        val closeNotifyRetries: Long get() = CLOSE_RETRIES.get()

        /** ack 因对端缓冲满发送失败、转入补发的次数。 */
        val ackRetries: Long get() = ACK_RETRIES.get()
    }
}
