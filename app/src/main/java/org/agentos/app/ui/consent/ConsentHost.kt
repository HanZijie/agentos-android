package org.agentos.app.ui.consent

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.agentos.app.agent.consent.ConsentService
import org.agentos.app.agent.consent.ConsentWire
import org.agentos.app.agent.consent.ConsentWire.AuthRequest
import org.agentos.app.agent.consent.ConsentWire.Card
import org.agentos.app.i18n.AndroidStrings
import org.agentos.internal.IConsentListener
import org.agentos.internal.IConsentService
import org.agentos.runtime.consent.ConsentChoice
import org.agentos.runtime.consent.ConsentEnd

/**
 * 主进程里的确认界面宿主（D5.2）：AgentOS 自己的任何 Activity 在前台期间，bind `:agent` 的 [IConsentService]、登记监听者，
 * 把待确认弹成对话框（排队：先进先出，一次一条）；没有 Activity 在前台时解除登记，`:agent` 自己发通知。
 *
 * - 对话框画在**当前前台的 AgentOS Activity** 上（不由后台服务弹出）。切换 Activity 时对话框重画在新的上面。
 * - 事实在 `:agent`：这里只是镜像；`:agent` 重启（连接断开）时清空队列并重新登记，重启前的请求已经由协调器按拒绝结案。
 * - 登记监听者时会收到全部待确认的快照（用户在后台点通知、之后打开 App 看到的是同一份）。
 */
object ConsentHost {
    private const val TAG = "AgentOS.ConsentHost"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Application
    private val queue = ConsentQueue()
    private var current: Activity? = null
    private var started = 0
    private var service: IConsentService? = null
    private var bound = false
    private var dialog: ConsentDialog? = null
    private var authDialog: AuthorizationDialog? = null
    private var shownFor: String? = null

    /** 授权提示的回答出口（C 的 IAgentControl.answerAuthorization）；测试里可以换成假的。 */
    @Volatile var authorizationAnswerer: AuthorizationAnswerer = AuthorizationAnswerer.Control

    /** 登记监听者后收到过 `:agent` 的快照：之前队列是空的不代表没有待决，之后才是。 */
    var snapshotReady = false
        private set

    /** 每条请求最近一次的 App 标签缓存（PackageManager 解析，不可信文字：清理后再显示）。 */
    private val labels = HashMap<String, String?>()

    private val listener = object : IConsentListener.Stub() {
        override fun onSnapshot(pendingJson: String?) {
            val items = ConsentWire.parsePendings(pendingJson)
            main.post { snapshotReady = true; queue.replaceAll(items); render() }
        }

        override fun onRequested(viewJson: String?) {
            val item = ConsentWire.parsePending(viewJson) ?: return
            main.post { queue.add(item); render() }
        }

        override fun onResolved(requestId: String?, resolutionJson: String?) {
            val id = requestId ?: return
            val r = ConsentWire.parseResolution(resolutionJson)
            main.post {
                queue.remove(id)
                if (r?.notice != null && r.end == ConsentEnd.ANSWERED) {
                    runCatching { Toast.makeText(app, AndroidStrings(app).get(r.notice), Toast.LENGTH_LONG).show() }
                }
                if (shownFor == id) closeDialog()
                render()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = IConsentService.Stub.asInterface(binder)
            service = s
            runCatching { s.registerListener(listener) }.onFailure { Log.w(TAG, "register failed: ${it.javaClass.simpleName}") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // :agent 重启：之前的请求已按拒绝结案；清空，等它回来（BIND_AUTO_CREATE 会重连并重新登记）
            service = null
            main.post { snapshotReady = false; queue.clear(); closeDialog() }
        }
    }

    fun install(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                current = activity
                connect()
                render()
            }

            override fun onActivityResumed(activity: Activity) {
                current = activity
                render()
            }

            override fun onActivityPaused(activity: Activity) {
                if (current === activity) closeDialog()
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (current === activity) current = null
                if (started <= 0) {
                    started = 0
                    disconnect()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun connect() {
        if (bound) return
        bound = app.bindService(Intent(app, ConsentService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) Log.w(TAG, "cannot bind ConsentService")
    }

    private fun disconnect() {
        if (!bound) return
        runCatching { service?.unregisterListener(listener) }
        runCatching { app.unbindService(connection) }
        bound = false
        service = null
        snapshotReady = false
        queue.clear()
        closeDialog()
    }

    fun hasPending(): Boolean = queue.size > 0

    private fun closeDialog() {
        dialog?.dismiss()
        dialog = null
        authDialog?.dismiss()
        authDialog = null
        shownFor = null
    }

    /** 队首对应的对话框已经在当前 Activity 上就不动；否则（新的队首、换了 Activity）重画。 */
    private fun render() {
        val activity = current
        val head = queue.head
        if (activity == null || activity.isFinishing || head == null) {
            if (head == null) {
                closeDialog()
                // 快照到达之前队列是空的，不能当“没有待决”
                if (snapshotReady) (activity as? QueueEmptyAware)?.onQueueEmpty()
            }
            return
        }
        (activity as? QueueEmptyAware)?.onQueueChanged()
        if (shownFor == head.requestId && (dialog != null || authDialog != null)) return
        closeDialog()
        shownFor = head.requestId
        when (head) {
            is Card -> dialog = ConsentDialog.show(activity, head, appLabel(head.callerPackage), queue.size) { choice -> answer(head.requestId, choice) }
            is AuthRequest -> authDialog = AuthorizationDialog.show(activity, head, appLabel(head.packageName), queue.size) { allow -> answerAuthorization(head.requestId, allow) }
        }
    }

    private fun answerAuthorization(requestId: String, allow: Boolean) {
        val answerer = authorizationAnswerer
        Thread {
            runCatching { answerer.answer(app, requestId, allow) }.onFailure { Log.w(TAG, "answerAuthorization failed: ${it.javaClass.simpleName}") }
        }.start()
    }

    private fun answer(requestId: String, choice: ConsentChoice) {
        val s = service
        Thread {
            runCatching { s?.respond(requestId, choice.name) }.onFailure { Log.w(TAG, "respond failed: ${it.javaClass.simpleName}") }
        }.start()
    }

    private fun appLabel(pkg: String?): String? {
        if (pkg == null) return null
        return labels.getOrPut(pkg) {
            runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }
    }
}
