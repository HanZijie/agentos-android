package org.agentos.app.ui.consent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import org.agentos.app.R
import org.agentos.app.ui.Ui

/**
 * 点确认通知进来的页面（D5.2）：本身只是一块底，对话框由 [ConsentHost] 画在它上面（它是前台 Activity）。
 * 没有待确认了（已超时、已答复）就说一句并关闭；队列里有的话对话框答复完、队列空了自动关闭。
 */
class ConsentActivity : Activity(), QueueEmptyAware {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.ui_background))
            addView(Ui.text(context, 15f, R.color.ui_text_secondary).apply { text = "工具确认" })
        }
        setContentView(root)
        Ui.applyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        // 快照到达后（ConsentHost）队列有内容就显示对话框；2 秒后还是空的，说明请求已经结案，关掉
        handler.postDelayed({ if (!isFinishing && !ConsentHost.hasPending()) finish() }, 2_000)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    /** 对话框答复完、队列空了就回到原来的地方。 */
    override fun onQueueEmpty() {
        if (!isFinishing) finish()
    }

    companion object {
        fun intent(context: Context, requestId: String): Intent =
            Intent(context, ConsentActivity::class.java).putExtra("requestId", requestId)
    }
}
