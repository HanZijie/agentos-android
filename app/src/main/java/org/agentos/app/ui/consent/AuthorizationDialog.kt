package org.agentos.app.ui.consent

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.agentos.app.R
import org.agentos.app.agent.consent.ConsentWire.AuthRequest
import org.agentos.app.ui.Ui

/**
 * 授权提示卡片：“允许「X」使用 AgentOS 吗？”（docs/third-party-acp.md 4.2）。和工具确认框 [ConsentDialog] 一套规矩：
 *
 * - 全部纯文本（只用 `TextView.setText(String)`）：App 名、包名、签名摘要都是第三方给的，不解析、不当格式化字符串。
 *   App 名后面总跟着包名和签名摘要前 12 位，名字冒充不了。
 * - 选项只有“允许”和“拒绝”；默认焦点在“拒绝”，“拒绝”是主按钮，“允许”是次要样式。
 * - 防点击劫持：弹出后 [ConsentDialog.TAP_GUARD_MS] 内按钮不可点；窗口被别的窗口遮挡时丢弃触摸（filterTouchesWhenObscured）。
 * - 不可取消（返回键、点外面都不关）：不回答就到点按拒绝（注册表计时）。Activity 暂停时撤下，回来由宿主重新弹。
 */
class AuthorizationDialog private constructor(
    private val activity: Activity,
    private val request: AuthRequest,
    private val resolvedLabel: String?,
    private val queueSize: Int,
    private val onAnswer: (allow: Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private lateinit var countdown: TextView
    private val buttons = ArrayList<Button>()
    @Volatile private var answered = false

    private fun dp(v: Int) = Ui.dp(activity, v)

    private fun show() {
        val view = build()
        val d = AlertDialog.Builder(activity).setView(view).setCancelable(false).create()
        d.setCanceledOnTouchOutside(false)
        dialog = d
        d.show()
        d.window?.decorView?.filterTouchesWhenObscured = true
        view.filterTouchesWhenObscured = true
        buttons.forEach { it.filterTouchesWhenObscured = true }
        handler.postDelayed({ if (!answered) buttons.forEach { it.isEnabled = true; it.alpha = 1f } }, ConsentDialog.TAP_GUARD_MS)
        tick()
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        runCatching { dialog?.dismiss() }
        dialog = null
    }

    /** 同工具确认框：只在文字真的变了时更新，并标成不重要（不向无障碍服务每秒发事件）。 */
    private var lastCountdown = ""

    private fun tick() {
        val text = AuthorizationLabels.countdown(request.deadlineMillis, System.currentTimeMillis())
        if (text != lastCountdown) {
            lastCountdown = text
            countdown.text = text
        }
        handler.postDelayed(::tick, 500)
    }

    private fun build(): View {
        val accent = activity.getColor(R.color.ui_accent)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        column.addView(
            Ui.text(activity, 12f, R.color.ui_user_text, bold = true).apply {
                text = "第三方 App 请求授权"
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = Ui.rounded(accent, dp(10).toFloat())
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        column.addView(Ui.text(activity, 19f, R.color.ui_text, bold = true).apply {
            text = AuthorizationLabels.title(request, resolvedLabel)
            setPadding(0, dp(10), 0, dp(6))
        })
        column.addView(line(AuthorizationLabels.packageLine(request), R.color.ui_text))
        column.addView(line(AuthorizationLabels.digestLine(request.signingDigest), R.color.ui_text))
        AuthorizationLabels.signatureChangedLine(request)?.let {
            column.addView(Ui.text(activity, 14f, R.color.ui_error, bold = true).apply {
                text = it
                setPadding(0, dp(8), 0, dp(2))
            })
        }
        column.addView(Ui.text(activity, 14f, R.color.ui_text_secondary).apply {
            text = AuthorizationLabels.EXPLANATION
            setPadding(0, dp(12), 0, dp(2))
        })
        ConsentLabels.queueNote(queueSize)?.let { column.addView(line(it, R.color.ui_text_secondary)) }
        countdown = Ui.text(activity, 12f, R.color.ui_text_secondary).apply {
            setPadding(0, dp(10), 0, dp(6))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        column.addView(countdown)

        // “允许”在上，“拒绝”在下并且是主按钮、默认焦点
        var denyButton: Button? = null
        for (allow in listOf(true, false)) {
            val b = Button(activity).apply {
                text = if (allow) AuthorizationLabels.ALLOW else AuthorizationLabels.DENY
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(activity.getColor(if (allow) R.color.ui_text else R.color.ui_user_text))
                background = Ui.rounded(if (allow) activity.getColor(R.color.ui_tool_bg) else accent, dp(10).toFloat())
                minHeight = dp(46)
                isEnabled = false
                alpha = 0.5f
                // 不要 isFocusableInTouchMode：触摸模式下第一次点击只拿焦点（同 ConsentDialog）
                setOnClickListener { choose(allow) }
            }
            buttons += b
            if (!allow) denyButton = b
            column.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        // 默认焦点在“拒绝”：触摸模式下 requestFocus 不生效（按钮不是 focusableInTouchMode，见上），
        // 所以同时标成“默认获得焦点”——切到键盘 / 方向键 / 读屏导航时系统先落在它上面，而不是第一个按钮
        denyButton?.isFocusedByDefault = true
        denyButton?.requestFocus()
        return ScrollView(activity).apply { addView(column) }
    }

    private fun line(text: String, color: Int) = Ui.text(activity, 14f, color).apply {
        this.text = text
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun choose(allow: Boolean) {
        if (answered) return
        answered = true
        buttons.forEach { it.isEnabled = false; it.alpha = 0.5f }
        countdown.text = "已提交…"
        onAnswer(allow)
    }

    companion object {
        fun show(activity: Activity, request: AuthRequest, resolvedLabel: String?, queueSize: Int, onAnswer: (Boolean) -> Unit): AuthorizationDialog =
            AuthorizationDialog(activity, request, resolvedLabel, queueSize, onAnswer).also { it.show() }
    }
}
