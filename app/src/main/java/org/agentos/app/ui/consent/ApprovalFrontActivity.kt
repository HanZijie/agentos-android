package org.agentos.app.ui.consent

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import org.agentos.app.R
import org.agentos.app.ui.Ui

/**
 * 第三方 App 把用户带到待决对话框的入口（docs/third-party-acp.md 4.2；SDK：`AgentOs.bringApprovalToFront`）。
 * **导出，但只做这一件事**，所以尽量小：
 *
 * - **不读 Intent**：不看 extras、data、action、flags——调用方传什么都没有效果，也没法借它让 AgentOS 做别的事。
 * - 没有任何界面内容：透明窗口。AgentOS 的前台对话框宿主（[ConsentHost]）在登记监听者后收到 `:agent` 的快照：
 *   队列里有待决（授权提示或工具确认）就在这个 Activity 上弹出对话框（和别处完全一样的对话框和规矩），答复完队列空了就关闭，
 *   用户回到调用它的 App；快照说没有待决就**立刻**关闭，什么也不显示。
 * - 兜底：2 秒内没连上 `:agent`（没有快照）也关闭。
 * - 不进最近任务；对话框里的任何答复仍然由 `:agent` 里的协调器 / 注册表校验，这里没有答复的权力。
 */
class ApprovalFrontActivity : Activity(), QueueEmptyAware {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 有内容待显示时才铺底色，这样“没有待决”时不会闪一下
        val root = FrameLayout(this)
        setContentView(root)
        Ui.applyInsets(root)
        rootView = root
    }

    private var rootView: FrameLayout? = null

    override fun onResume() {
        super.onResume()
        refresh()
        handler.postDelayed({ if (!isFinishing) finish() }, FALLBACK_MS)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    /** 宿主在队列变化后调：有待决就铺底色、让对话框显示，没有就关。 */
    private fun refresh() {
        if (ConsentHost.hasPending()) {
            rootView?.setBackgroundColor(getColor(R.color.ui_background))
        } else if (ConsentHost.snapshotReady) {
            finish()
        }
    }

    override fun onQueueEmpty() {
        if (!isFinishing) finish()
    }

    override fun onQueueChanged() = refresh()

    companion object {
        /** 没有快照时的最长停留。 */
        const val FALLBACK_MS = 2_000L
    }
}
