package org.agentos.app.agent.consent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import org.agentos.app.R
import org.agentos.app.i18n.AndroidStrings
import org.agentos.app.i18n.Strings
import org.agentos.app.ui.consent.AuthorizationLabels
import org.agentos.app.ui.consent.ConsentActivity
import org.agentos.internal.IConsentListener
import org.agentos.internal.IConsentService
import org.agentos.runtime.consent.ConsentChoice
import org.agentos.runtime.consent.ConsentCoordinator
import org.agentos.runtime.consent.ConsentResolution
import org.agentos.runtime.consent.ConsentSurface
import org.agentos.runtime.consent.ConsentView
import java.util.concurrent.ConcurrentHashMap

/**
 * `:agent` 里的确认界面接缝（D5.2）：把 [ConsentCoordinator] 的待确认交给主进程的界面，没有界面时自己发通知。
 *
 * - **事实在这里**：待确认的请求和各自的 60 秒超时都在协调器（`:agent`）里。主进程只显示它、回传选择；主进程被杀时请求照常超时后拒绝，
 *   不会挂住任务。
 * - 前台：主进程的 [ConsentActivity] bind [Service]、登记 [IConsentListener]；新请求推给它，**不发通知**（对话框已经在眼前）。
 * - 后台（没有登记的监听者）：为每条待确认发一条通知，“允许一次 / 拒绝”两个按钮 + 点通知打开 [ConsentActivity]
 *   （在那里看全文、可选“始终允许”）。登记监听者时、请求结案时，撤回通知。
 * - 通知文字都是协调器已清理过的纯文本，用 setContentTitle / setContentText 传（不当格式化字符串、不用 Html）。
 *
 * 回调由协调器在单独的协程里按顺序调用（不阻塞任务），这里不做耗时操作。
 */
class ConsentBridge(private val context: Context, private val log: (String) -> Unit = { Log.w(TAG, it) }) : ConsentSurface, AuthorizationSurface {
    @Volatile private var coordinator: ConsentCoordinator? = null
    /** 通知文字按当前界面语言（`:agent` 进程的 Resources 跟随应用语言）；每次现取，不缓存文字。 */
    private val strings: Strings = AndroidStrings(context)
    /** 监听者所在的主进程死了（被杀、崩溃）时，还没答复的请求转成通知：用户不会因为界面没了就收不到确认。 */
    private val listeners = object : RemoteCallbackList<IConsentListener>() {
        override fun onCallbackDied(callback: IConsentListener?) {
            if (!hasListener()) notifyAllPending()
        }
    }
    private val notified = ConcurrentHashMap<String, Int>()

    /** 协调器要先有界面才能构造，所以晚一步接上。 */
    fun attach(coordinator: ConsentCoordinator) {
        this.coordinator = coordinator
    }

    /** 给 [ConsentCoordinator.pending] 的当前值用（登记监听者、点通知时）。 */
    private fun pending(): List<ConsentView> = coordinator?.pending?.value.orEmpty()

    // ---------------------------------------------------------------- 第三方 App 授权提示（AuthorizationSurface）

    /** 待决的授权提示（事实在 CallerRegistry；这里只为登记监听者时给快照、界面走后给通知用）。到达先后。 */
    private val authPending = java.util.LinkedHashMap<String, ConsentWire.AuthRequest>()

    private fun authList(): List<ConsentWire.AuthRequest> = synchronized(authPending) { authPending.values.toList() }

    /**
     * 通知上“拒绝”的出口：由 CallerRegistry 的接线者（C）设成 `{ requestId, allow -> registry.answer(...) }`。没接线时拒绝也不会生效
     * （请求照常超时按拒绝），通知上的按钮仍会撤回。通知上**不给“允许”**：授权是一个持久的信任决定，要在对话框里看清包名和签名。
     */
    @Volatile var authorizationAnswer: ((requestId: String, allow: Boolean) -> Boolean)? = null

    override fun authorizationRequested(request: ConsentWire.AuthRequest) {
        synchronized(authPending) { authPending[request.requestId] = request }
        if (hasListener()) broadcast { it.onRequested(ConsentWire.encodeAuthString(request)) } else notifyAuth(request)
    }

    override fun authorizationResolved(requestId: String, resolution: ConsentResolution) {
        synchronized(authPending) { authPending.remove(requestId) }
        broadcast { it.onResolved(requestId, ConsentWire.encodeResolution(resolution)) }
        cancelNotification(requestId)
    }

    /** 通知上的“拒绝”（运行在 `:agent` 的 [ConsentActionReceiver]）。 */
    fun denyAuthorizationFromNotification(requestId: String) {
        runCatching { authorizationAnswer?.invoke(requestId, false) }.onFailure { log("authorization deny failed: ${it.javaClass.simpleName}") }
        cancelNotification(requestId)
    }

    private fun notifyAllPending() {
        pending().forEach { notify(it) }
        authList().forEach { notifyAuth(it) }
    }

    // ---------------------------------------------------------------- ConsentSurface

    override fun requested(view: ConsentView) {
        if (hasListener()) {
            broadcast { it.onRequested(ConsentWire.encodeViewString(view)) }
        } else {
            notify(view)
        }
    }

    override fun resolved(requestId: String, resolution: ConsentResolution) {
        broadcast { it.onResolved(requestId, ConsentWire.encodeResolution(resolution)) }
        cancelNotification(requestId)
    }

    // ---------------------------------------------------------------- IConsentService（主进程调用）

    val service: IConsentService.Stub = object : IConsentService.Stub() {
        override fun registerListener(listener: IConsentListener?) {
            enforceSelf()
            if (listener == null) return
            listeners.register(listener)
            // 界面接手：之前发的通知全部撤回，当前全部待确认以快照交给它
            notified.keys.toList().forEach { cancelNotification(it) }
            runCatching { listener.onSnapshot(ConsentWire.encodePending(pending(), authList())) }
        }

        override fun unregisterListener(listener: IConsentListener?) {
            enforceSelf()
            if (listener == null) return
            listeners.unregister(listener)
            // 界面走了：还没答复的转成通知
            if (!hasListener()) notifyAllPending()
        }

        override fun respond(requestId: String?, choice: String?): Boolean {
            enforceSelf()
            val c = ConsentWire.parseChoice(choice) ?: return false
            val ok = requestId != null && coordinator?.respond(requestId, c) == true
            return ok
        }
    }

    fun binder(): IBinder = service

    private fun hasListener(): Boolean = synchronized(listeners) {
        val n = listeners.beginBroadcast()
        listeners.finishBroadcast()
        n > 0
    }

    private inline fun broadcast(call: (IConsentListener) -> Unit) = synchronized(listeners) {
        val n = listeners.beginBroadcast()
        try {
            for (i in 0 until n) runCatching { call(listeners.getBroadcastItem(i)) }
        } finally {
            listeners.finishBroadcast()
        }
    }

    private fun enforceSelf() {
        val uid = android.os.Binder.getCallingUid()
        if (uid != android.os.Process.myUid()) throw SecurityException("IConsentService only accepts the AgentOS app itself (uid $uid)")
    }

    // ---------------------------------------------------------------- 通知

    private fun nm() = context.getSystemService(NotificationManager::class.java)

    private fun notify(view: ConsentView) {
        val card = ConsentWire.parseCard(ConsentWire.encodeViewString(view)) ?: return
        val id = notified.computeIfAbsent(view.requestId) { NEXT_ID + (nextSeq.getAndIncrement() and 0xffff) }
        try {
            val channel = if (card.severity == org.agentos.runtime.consent.ConsentSeverity.CRITICAL) CHANNEL_HIGH else CHANNEL
            ensureChannels()
            val open = PendingIntent.getActivity(
                context, id, ConsentActivity.intent(context, view.requestId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val deny = action(id, view.requestId, ConsentChoice.DENY)
            val b = Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(strings.get(card.title))
                .setContentText(listOfNotNull(strings.get(card.initiatorLine), card.sourceLine?.let { strings.get(it) }).joinToString(" · "))
                .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(strings.get(card.initiatorLine), card.sourceLine?.let { strings.get(it) }, strings.get(card.riskDescription).ifEmpty { null }, card.argumentsPreview.ifEmpty { null }).joinToString("\n")))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(open)
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setWhen(card.deadlineMillis - card.timeoutMillis)
                .setTimeoutAfter((card.deadlineMillis - System.currentTimeMillis()).coerceAtLeast(1_000)) // 到点系统也撤回
            // 拒绝放第一个（默认动作更安全）；“允许一次”只在这条请求允许时给，高风险的也给，但要打开界面确认（见下）
            b.addAction(deny)
            if (card.allowsOnce() && card.severity != org.agentos.runtime.consent.ConsentSeverity.CRITICAL) {
                b.addAction(action(id, view.requestId, ConsentChoice.ALLOW_ONCE))
            }
            nm().notify(TAG, id, b.build())
        } catch (e: Exception) {
            log("notification failed: ${e.javaClass.simpleName}")
        }
    }

    private fun notifyAuth(req: ConsentWire.AuthRequest) {
        val id = notified.computeIfAbsent(req.requestId) { NEXT_ID + (nextSeq.getAndIncrement() and 0xffff) }
        try {
            ensureChannels()
            val label = runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(req.packageName, 0)).toString()
            }.getOrNull()
            val open = PendingIntent.getActivity(
                context, id, ConsentActivity.intent(context, req.requestId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val deny = Intent(context, ConsentActionReceiver::class.java)
                .putExtra(ConsentActionReceiver.EXTRA_REQUEST, req.requestId)
                .putExtra(ConsentActionReceiver.EXTRA_AUTH_DENY, true)
                .setPackage(context.packageName)
            val denyPi = PendingIntent.getBroadcast(context, id * 4 + 3, deny, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = AuthorizationLabels.notificationText(req, strings)
            val big = listOfNotNull(text, AuthorizationLabels.signatureChangedLine(req, strings), AuthorizationLabels.explanation(strings)).joinToString("\n")
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(AuthorizationLabels.title(req, label, strings))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(big))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(open)
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setWhen(req.arrivalMillis)
                .setTimeoutAfter((req.deadlineMillis - System.currentTimeMillis()).coerceAtLeast(1_000))
            // 只有“拒绝”；允许要点开通知，在对话框里看清包名和签名
            b.addAction(Notification.Action.Builder(null as Icon?, strings.get(R.string.auth_deny), denyPi).build())
            nm().notify(TAG, id, b.build())
        } catch (e: Exception) {
            log("authorization notification failed: ${e.javaClass.simpleName}")
        }
    }

    private fun action(id: Int, requestId: String, choice: ConsentChoice): Notification.Action {
        val intent = Intent(context, ConsentActionReceiver::class.java)
            .putExtra(ConsentActionReceiver.EXTRA_REQUEST, requestId)
            .putExtra(ConsentActionReceiver.EXTRA_CHOICE, choice.name)
            .setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(
            context, id * 4 + choice.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = strings.get(if (choice == ConsentChoice.DENY) R.string.consent_option_deny else R.string.consent_option_allow_once)
        return Notification.Action.Builder(null as Icon?, label, pi).build()
    }

    private fun cancelNotification(requestId: String) {
        val id = notified.remove(requestId) ?: return
        runCatching { nm().cancel(TAG, id) }
    }

    private fun ensureChannels() {
        val m = nm()
        if (m.getNotificationChannel(CHANNEL) == null) {
            m.createNotificationChannel(NotificationChannel(CHANNEL, strings.get(R.string.consent_channel_normal), NotificationManager.IMPORTANCE_HIGH))
        }
        if (m.getNotificationChannel(CHANNEL_HIGH) == null) {
            m.createNotificationChannel(NotificationChannel(CHANNEL_HIGH, strings.get(R.string.consent_channel_high), NotificationManager.IMPORTANCE_HIGH))
        }
    }

    /** 通知上的按钮：回答 :agent 里的协调器（不导出的 receiver，运行在 :agent）。 */
    fun respondFromNotification(requestId: String, choice: ConsentChoice): Boolean =
        coordinator?.respond(requestId, choice) == true

    companion object {
        private const val TAG = "AgentOS.Consent"
        private const val CHANNEL = "consent"
        private const val CHANNEL_HIGH = "consent_high"
        private const val NEXT_ID = 0x4000
        private val nextSeq = java.util.concurrent.atomic.AtomicInteger()
    }
}
