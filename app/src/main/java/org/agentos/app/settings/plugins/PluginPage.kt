package org.agentos.app.settings.plugins

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.agentos.app.R
import org.agentos.app.i18n.AndroidStrings
import org.agentos.app.i18n.Strings
import org.agentos.app.ui.Ui

/**
 * 插件列表页和详情页共用的部分：到 :ext 的客户端、启用对话框、统一的错误提示、插件卡片的公共行。
 * 来自插件的文字只经 `setText(String)` 和 AlertDialog.setMessage(String) 显示（纯文本，不解析）。
 */
abstract class PluginPage : Activity() {
    protected val host by lazy { ExtensionHostClient(this) }

    /** 界面文字（跟随系统语言或“应用语言”）。 */
    protected val strings: Strings by lazy { AndroidStrings(this) }
    protected var scope: CoroutineScope? = null

    /** 策略文件读不出来且没有副本（fail closed）：:ext 拒绝一切修改，界面不给开关，免得点了没反应。 */
    protected var locked = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        scope = MainScope()
    }

    override fun onDestroy() {
        scope?.cancel()
        super.onDestroy()
    }

    protected fun dp(v: Int) = Ui.dp(this, v)

    protected fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** App 的标签（随系统语言）：给 [Plugins.withAppLabels]。取不到抛异常，调用方当“没有标签”处理。在后台线程用。 */
    protected fun appLabelOf(packageName: String): CharSequence? = packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager)

    /** 对话框按钮在被别的窗口遮挡时不响应（防止 tapjacking 骗到“确认新签名”）。 */
    protected fun show(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        dialog.setOnShowListener { dialog.window?.decorView?.filterTouchesWhenObscured = true }
        dialog.show()
    }

    /** 做一次对 :ext 的调用；失败显示统一文案；结束后（无论成败）调 [then]。 */
    protected fun <T> call(then: () -> Unit, block: (org.agentos.internal.IExtensionHost) -> T) {
        scope?.launch {
            try {
                host.use { block(it) }
            } catch (e: Exception) {
                toast(Plugins.errorText(e.message, strings))
            }
            then()
        }
    }

    /**
     * 开关被拨动。关：直接停用。开：先弹对话框（[Plugins.enablePlan]：第三方写明风险；签名变了或未确认的在同一个对话框里重新确认），
     * 点确认才调用；取消则 [then] 把开关刷回去。
     */
    protected fun toggle(p: Plugins.Plugin, on: Boolean, then: () -> Unit) {
        if (!on) {
            call(then) { it.setPluginEnabled(p.id, false) }
            return
        }
        val plan = Plugins.enablePlan(p, strings)
        show(
            AlertDialog.Builder(this)
                .setTitle(plan.title)
                .setMessage(plan.message)
                .setPositiveButton(plan.confirmLabel) { _, _ ->
                    call(then) {
                        if (plan.confirmSignatureFirst) it.confirmSignature(p.id)
                        it.setPluginEnabled(p.id, true)
                    }
                }
                .setNegativeButton(strings.get(R.string.plugins_cancel)) { _, _ -> then() }
                .setOnCancelListener { then() },
        )
    }

    /** 标题行：插件名 + 开关。开关不直接生效，由 [toggle] 决定。 */
    protected fun header(p: Plugins.Plugin, onToggle: (Boolean) -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(Ui.text(context, 17f, R.color.ui_text, bold = true).apply { text = p.title }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Switch(context).apply {
            contentDescription = strings.get(R.string.plugins_switch_cd, p.title)
            isChecked = p.switchOn
            isEnabled = p.switchEnabled && !locked
            // 用点击而不是 OnCheckedChange：程序里 setChecked 不会触发；点击后先把开关拨回，等调用结果再刷新
            setOnClickListener {
                val wanted = isChecked
                isChecked = !wanted
                onToggle(wanted)
            }
        })
    }

    /** 版本、来源 App、签名、状态：列表卡片和详情页相同。 */
    protected fun identity(card: LinearLayout, p: Plugins.Plugin) {
        card.addView(Ui.paragraph(this, listOf(p.versionName.takeIf { it.isNotEmpty() }?.let { strings.get(R.string.plugins_version, it) }, p.packageName.takeIf { it.isNotEmpty() }?.let { strings.get(R.string.plugins_from_app, it) })
            .filterNotNull().joinToString(" · ")))
        if (p.builtin) card.addView(Ui.paragraph(this, strings.get(R.string.plugins_builtin)))
        else card.addView(Ui.paragraph(this, strings.get(R.string.plugins_signature_line, Plugins.digestShown(p.signingDigest, strings))))
        val (status, warn) = Plugins.statusLine(p, strings)
        card.addView(Ui.paragraph(this, status, if (warn) R.color.ui_error else R.color.ui_text_secondary))
    }

    protected fun clickable(v: View, desc: String, onClick: () -> Unit) {
        v.contentDescription = desc
        v.isClickable = true
        v.setOnClickListener { onClick() }
    }

    protected fun label(s: String): TextView = Ui.text(this, 12f, R.color.ui_text_secondary).apply { text = s }
}
