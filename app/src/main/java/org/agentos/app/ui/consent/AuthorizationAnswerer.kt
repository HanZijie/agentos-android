package org.agentos.app.ui.consent

import android.content.Context

/** 前台界面上有队列且清空时要关掉自己的 Activity（[ConsentActivity]、[ApprovalFrontActivity]）。 */
interface QueueEmptyAware {
    fun onQueueEmpty()

    /** 队列有变化（新请求到达、快照到达）。 */
    fun onQueueChanged() = Unit
}

/** 授权提示的回答出口：主进程把用户点的“允许 / 拒绝”交给 `:agent`（C 的 `IAgentControl.answerAuthorization(requestId, allow)`）。在后台线程调用。 */
fun interface AuthorizationAnswerer {
    fun answer(context: Context, requestId: String, allow: Boolean)

    object Unwired : AuthorizationAnswerer {
        override fun answer(context: Context, requestId: String, allow: Boolean) {
            android.util.Log.w("AgentOS.ConsentHost", "no authorization answerer wired")
        }
    }
}
