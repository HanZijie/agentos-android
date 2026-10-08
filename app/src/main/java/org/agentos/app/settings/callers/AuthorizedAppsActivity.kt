package org.agentos.app.settings.callers

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.app.R
import org.agentos.app.ui.Ui
import org.agentos.app.ui.consent.AuthorizationLabels

/**
 * 已授权的应用（docs/third-party-acp.md 4.2）：哪些第三方 App 被允许 / 拒绝使用 AgentOS，最近使用和用量；可以撤销授权，
 * 把“已拒绝”改成允许。数据来自 `:agent` 里的调用方注册表（[CallerStore]）。
 *
 * App 名和包名是第三方给的：名字清理成单行，包名总是同时显示，签名摘要前 12 位也显示，只用 `setText(String)`。
 * 改成允许、撤销都先弹确认；对话框开了 filterTouchesWhenObscured。
 */
class AuthorizedAppsActivity : Activity() {
    private val store by lazy { CallerStore.default(this) }
    private var scope: CoroutineScope? = null
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope = MainScope()
        val (root, col) = Ui.page(this, "已授权的应用")
        column = col
        setContentView(root)
    }

    override fun onDestroy() {
        scope?.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun reload() {
        scope?.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.list() } }
            render(result.getOrNull()?.let { AcpCallers.parse(it) }, failed = result.isFailure)
        }
    }

    private fun render(callers: List<AcpCallers.Caller>?, failed: Boolean) {
        column.removeAllViews()
        column.addView(Ui.paragraph(this, "第三方 App 经 AgentOS 提问之前需要你允许。它用到的工具，每次都会再问你。"))
        if (failed || callers == null) {
            column.addView(Ui.card(this).apply { addView(Ui.line(context, "运行时", "读取失败，或运行时版本过旧不支持这一项。", warn = true)) })
            return
        }
        column.addView(Ui.sectionTitle(this, "应用"))
        if (callers.isEmpty()) {
            column.addView(Ui.card(this).apply { addView(Ui.paragraph(context, "还没有第三方 App 请求过。")) })
        }
        val now = System.currentTimeMillis()
        callers.forEach { column.addView(card(it, now), Ui.matchWrap().apply { bottomMargin = dp(10) }) }
        column.addView(Ui.buttons(this, "刷新" to { reload() }))
    }

    private fun card(c: AcpCallers.Caller, now: Long): LinearLayout = Ui.card(this).apply {
        addView(Ui.text(context, 17f, R.color.ui_text, bold = true).apply { text = c.displayName })
        addView(Ui.paragraph(context, "包名：${AuthorizationLabels.safePackage(c.packageName)}"))
        addView(Ui.paragraph(context, AuthorizationLabels.digestLine(c.signingDigest)))
        addView(Ui.paragraph(context, AcpCallers.stateText(c, now), if (c.state == AcpCallers.State.DENIED) R.color.ui_error else R.color.ui_text_secondary))
        addView(Ui.paragraph(context, AcpCallers.lastUsedText(c, now)))
        addView(Ui.paragraph(context, AcpCallers.usageText(c)))
        val actions = AcpCallers.actions(c).map { a -> (AcpCallers.actionLabel(a) as CharSequence) to { confirm(c, a) } }
        addView(Ui.buttons(context, *actions.toTypedArray()))
    }

    private fun confirm(c: AcpCallers.Caller, a: AcpCallers.Action) {
        val plan = AcpCallers.confirm(c, a)
        val dialog = AlertDialog.Builder(this)
            .setTitle(plan.title)
            .setMessage(plan.message)
            .setPositiveButton(plan.confirmLabel) { _, _ -> apply(c, a) }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener { dialog.window?.decorView?.filterTouchesWhenObscured = true }
        dialog.show()
    }

    private fun apply(c: AcpCallers.Caller, a: AcpCallers.Action) {
        scope?.launch {
            val r = withContext(Dispatchers.IO) { runCatching { store.set(c.packageName, AcpCallers.wireState(a)) } }
            if (r.isFailure) Toast.makeText(this@AuthorizedAppsActivity, AcpCallers.errorText(r.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
            reload()
        }
    }
}
