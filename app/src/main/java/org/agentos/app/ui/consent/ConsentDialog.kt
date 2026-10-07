package org.agentos.app.ui.consent

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.agentos.app.R
import org.agentos.app.agent.consent.ConsentWire.Card
import org.agentos.app.ui.Ui
import org.agentos.runtime.consent.ConsentChoice
import org.agentos.runtime.consent.ConsentSeverity

/**
 * 工具确认对话框（D5.2，architecture F5）：按 [Card] 画，**所有文字都是纯文本**——只用 `TextView.setText(String)`，
 * 不用 Html.fromHtml、不用 Spannable 解析、不当格式化字符串的参数；第三方文字（工具名、参数、插件名）协调器已经清理过。
 *
 * - 醒目程度：只读 / 写 / 高风险三档，用不同的色条、标签和说明；高风险的“允许”按钮是灰色次要样式，“拒绝”是主按钮，
 *   没有“始终允许”（协调器不给这个选项）。
 * - 默认焦点在“拒绝”上。
 * - 防点击劫持：刚弹出的 [TAP_GUARD_MS] 内按钮不可点（忽略触摸），窗口被别的窗口遮挡时触摸也被丢弃（filterTouchesWhenObscured）。
 * - 不可取消（返回键、点外面都不关）：必须点一个选项，否则到点按拒绝；Activity 暂停时对话框撤下，回来时由宿主重新弹。
 * - 倒计时来自协调器给的 deadline（排队时间也计入）。
 */
class ConsentDialog private constructor(
    private val activity: Activity,
    private val card: Card,
    private val appLabel: String?,
    private val queueSize: Int,
    private val onChoice: (ConsentChoice) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private lateinit var countdown: TextView
    private val buttons = ArrayList<Button>()
    @Volatile private var answered = false

    val requestId: String get() = card.requestId

    private fun dp(v: Int) = Ui.dp(activity, v)

    private fun show() {
        val view = build()
        val d = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()
        d.setCanceledOnTouchOutside(false)
        dialog = d
        d.show()
        // 被别的窗口盖住时丢弃触摸（点击劫持）
        d.window?.decorView?.filterTouchesWhenObscured = true
        view.filterTouchesWhenObscured = true
        buttons.forEach { it.filterTouchesWhenObscured = true }
        // 刚弹出的一小段时间内忽略触摸
        handler.postDelayed({ if (!answered) buttons.forEach { it.isEnabled = true; it.alpha = 1f } }, TAP_GUARD_MS)
        tick()
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        runCatching { dialog?.dismiss() }
        dialog = null
    }

    /**
     * 倒计时每秒只在文字真的变了时才更新：每次 setText 都会发无障碍事件，UI Automator 的 dump 要等界面连续 1 秒没有事件，
     * 每 250 ms 刷一次的话 adb 驱动的测试永远等不到“空闲”，点不到按钮。同时标成不重要（读屏用户不会每秒被打断；剩余时间
     * 在对话框弹出时已经读过一次，超时按拒绝）。
     */
    private var lastCountdown = ""

    private fun tick() {
        val text = ConsentLabels.countdown(card.deadlineMillis, System.currentTimeMillis())
        if (text != lastCountdown) {
            lastCountdown = text
            countdown.text = text
        }
        handler.postDelayed(::tick, 500)
    }

    private fun build(): View {
        val critical = card.severity == ConsentSeverity.CRITICAL
        val elevated = card.severity == ConsentSeverity.ELEVATED
        val accent = activity.getColor(
            when {
                critical -> R.color.ui_error
                elevated -> R.color.ui_warn
                else -> R.color.ui_accent
            },
        )
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        // 风险标签：色块 + 文字（不只靠颜色）
        column.addView(
            Ui.text(activity, 12f, R.color.ui_user_text, bold = true).apply {
                text = card.riskLabel.ifEmpty { card.risk }
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = Ui.rounded(accent, dp(10).toFloat())
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        column.addView(Ui.text(activity, 19f, R.color.ui_text, bold = true).apply {
            text = card.title
            setPadding(0, dp(10), 0, dp(4))
        })
        column.addView(line(ConsentLabels.initiator(card, appLabel), R.color.ui_text))
        card.sourceLine?.let { column.addView(line(it, R.color.ui_text_secondary)) }
        ConsentLabels.queueNote(queueSize)?.let { column.addView(line(it, R.color.ui_text_secondary)) }

        if (card.argumentsPreview.isNotEmpty()) {
            column.addView(Ui.text(activity, 12f, R.color.ui_text_secondary).apply {
                text = "参数"
                setPadding(0, dp(12), 0, dp(4))
            })
            val scroll = ScrollView(activity).apply {
                background = Ui.rounded(activity.getColor(R.color.ui_code_bg), dp(8).toFloat())
                isVerticalScrollBarEnabled = true
            }
            scroll.addView(
                Ui.text(activity, 13f, R.color.ui_text).apply {
                    typeface = Typeface.MONOSPACE
                    text = card.argumentsPreview
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    movementMethod = ScrollingMovementMethod.getInstance()
                },
            )
            column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // 参数很长时限高滚动，不把按钮挤出屏幕
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            })
            scroll.post { if (scroll.height > dp(160)) scroll.layoutParams = scroll.layoutParams.apply { height = dp(160) } }
            if (card.argumentsTruncated) column.addView(line("参数过长，已截断显示", R.color.ui_text_secondary))
        }
        if (card.riskDescription.isNotEmpty()) {
            column.addView(Ui.text(activity, 14f, if (critical) R.color.ui_error else R.color.ui_text_secondary, bold = critical).apply {
                text = card.riskDescription
                setPadding(0, dp(12), 0, dp(2))
            })
        }
        countdown = Ui.text(activity, 12f, R.color.ui_text_secondary).apply {
            setPadding(0, dp(10), 0, dp(6))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // 每秒变化，不向无障碍服务发事件
        }
        column.addView(countdown)

        // 选项：允许类在上，拒绝在最下面；高风险的“允许”是灰色次要样式，拒绝是主按钮
        val deny = card.options.first { it.choice == ConsentChoice.DENY }
        var denyButton: Button? = null
        for (o in card.options.filter { it.choice != ConsentChoice.DENY } + deny) {
            val isDeny = o.choice == ConsentChoice.DENY
            val primary = if (critical) isDeny else !isDeny && o.choice == ConsentChoice.ALLOW_ONCE
            val b = Button(activity).apply {
                text = o.label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(activity.getColor(if (primary) R.color.ui_user_text else R.color.ui_text))
                background = Ui.rounded(
                    if (primary) accent else activity.getColor(R.color.ui_tool_bg),
                    dp(10).toFloat(),
                )
                minHeight = dp(46)
                isEnabled = false // 刚弹出时忽略触摸
                alpha = 0.5f
                // 不要 isFocusableInTouchMode：触摸模式下它会让第一次点击只拿焦点、不触发 onClick（两次才生效）
                setOnClickListener { choose(o.choice) }
            }
            buttons += b
            if (isDeny) denyButton = b
            column.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        denyButton?.requestFocus() // 默认焦点在“拒绝”（键盘 / 方向键 / 读屏导航时落在它上面）
        return ScrollView(activity).apply { addView(column) }
    }

    private fun line(text: String, color: Int) = Ui.text(activity, 14f, color).apply {
        this.text = text
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun choose(choice: ConsentChoice) {
        if (answered) return
        answered = true
        buttons.forEach { it.isEnabled = false; it.alpha = 0.5f }
        countdown.text = "已提交…"
        onChoice(choice)
    }

    companion object {
        /** 弹出后这段时间内按钮不可点（防点击劫持）。 */
        const val TAP_GUARD_MS = 400L

        fun show(activity: Activity, card: Card, appLabel: String?, queueSize: Int, onChoice: (ConsentChoice) -> Unit): ConsentDialog =
            ConsentDialog(activity, card, appLabel, queueSize, onChoice).also { it.show() }
    }
}
