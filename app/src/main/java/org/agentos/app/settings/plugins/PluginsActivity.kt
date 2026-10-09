package org.agentos.app.settings.plugins

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import kotlinx.coroutines.launch
import org.agentos.app.R
import org.agentos.app.ui.Ui

/**
 * 插件管理（D5.3）：列出 :ext 发现的插件（IExtensionHost.listPlugins）。每个插件一张卡片：名称和开关、版本、来源 App、签名摘要、状态、
 * 工具数量、不支持/被忽略/有问题的项；点卡片进详情页。策略文件损坏时顶部显示原因和“重置策略”（需确认）。
 * 第三方插件默认关闭；打开开关会弹对话框写明风险（签名变了的还要在同一个对话框重新确认）。
 */
class PluginsActivity : PluginPage() {
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (root, col) = Ui.page(this, strings.get(R.string.plugins_title))
        column = col
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private data class Snapshot(val plugins: List<Plugins.Plugin>, val policy: Plugins.PolicyStatus?, val error: String?)

    private fun reload(rescan: Boolean = false) {
        scope?.launch {
            val snap = try {
                host.use {
                    val list = Plugins.withAppLabels(Plugins.parsePlugins(if (rescan) it.rescan() else it.listPlugins()), ::appLabelOf)
                    val policy = runCatching { Plugins.parsePolicy(it.policyStatus) }.getOrNull()
                    Snapshot(list, policy, null)
                }
            } catch (e: Exception) {
                Snapshot(emptyList(), null, Plugins.errorText(e.message, strings))
            }
            locked = snap.policy?.let { it.corrupt && it.failClosed } == true
            render(snap)
        }
    }

    private fun render(s: Snapshot) {
        column.removeAllViews()
        s.error?.let { column.addView(Ui.card(this).apply { addView(Ui.line(context, strings.get(R.string.plugins_service_label), it, warn = true)) }) }
        column.addView(Ui.paragraph(this, strings.get(R.string.plugins_intro)))

        s.policy?.let { p ->
            Plugins.policyText(p, strings)?.let { text ->
                column.addView(Ui.sectionTitle(this, strings.get(R.string.plugins_policy_section)))
                column.addView(Ui.card(this).apply {
                    addView(Ui.line(context, strings.get(R.string.plugins_state_label), text, warn = true))
                    p.reason?.takeIf { it.isNotEmpty() }?.let { addView(Ui.line(context, strings.get(R.string.plugins_reason_label), it)) }
                    addView(Ui.buttons(context, strings.get(R.string.plugins_reset_policy) to { confirmReset() }))
                })
            }
        }

        column.addView(Ui.sectionTitle(this, strings.get(R.string.plugins_discovered_section)))
        if (s.plugins.isEmpty() && s.error == null) {
            column.addView(Ui.card(this).apply {
                addView(Ui.paragraph(context, strings.get(R.string.plugins_empty)))
            })
        }
        s.plugins.forEach { column.addView(card(it), Ui.matchWrap().apply { bottomMargin = dp(10) }) }
        column.addView(Ui.buttons(this, strings.get(R.string.plugins_rescan) to { reload(rescan = true) }))
    }

    private fun card(p: Plugins.Plugin): LinearLayout = Ui.card(this).apply {
        addView(header(p) { on -> toggle(p, on) { reload() } })
        identity(this, p)
        Plugins.toolSummary(p, strings).takeIf { it.isNotEmpty() }?.let { addView(Ui.paragraph(context, it)) }
        val issues = Plugins.issues(p, strings)
        issues.take(Plugins.LIST_ISSUES).forEach { addView(Ui.paragraph(context, it, R.color.ui_warn)) }
        if (issues.size > Plugins.LIST_ISSUES) addView(Ui.paragraph(context, (issues.size - Plugins.LIST_ISSUES).let { strings.plural(R.plurals.plugins_more_issues, it, it) }))
        addView(Ui.buttons(context, strings.get(R.string.plugins_details) to { openDetail(p) }))
        clickable(this, strings.get(R.string.plugins_card_cd, p.title)) { openDetail(p) }
    }

    private fun openDetail(p: Plugins.Plugin) =
        startActivity(Intent(this, PluginDetailActivity::class.java).putExtra(PluginDetailActivity.EXTRA_ID, p.id))

    private fun confirmReset() {
        show(
            AlertDialog.Builder(this)
                .setTitle(strings.get(R.string.plugins_reset_title))
                .setMessage(strings.get(R.string.plugins_reset_message))
                .setPositiveButton(strings.get(R.string.plugins_reset_confirm)) { _, _ -> call({ reload() }) { it.resetPolicy() } }
                .setNegativeButton(strings.get(R.string.plugins_cancel), null),
        )
    }
}
