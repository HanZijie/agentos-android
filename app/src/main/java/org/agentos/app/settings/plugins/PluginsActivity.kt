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
        val (root, col) = Ui.page(this, "插件管理")
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
                    val list = Plugins.parsePlugins(if (rescan) it.rescan() else it.listPlugins())
                    val policy = runCatching { Plugins.parsePolicy(it.policyStatus) }.getOrNull()
                    Snapshot(list, policy, null)
                }
            } catch (e: Exception) {
                Snapshot(emptyList(), null, Plugins.errorText(e.message))
            }
            locked = snap.policy?.let { it.corrupt && it.failClosed } == true
            render(snap)
        }
    }

    private fun render(s: Snapshot) {
        column.removeAllViews()
        s.error?.let { column.addView(Ui.card(this).apply { addView(Ui.line(context, "插件服务", it, warn = true)) }) }
        column.addView(Ui.paragraph(this, "插件是别的 App 提供给 AgentOS 模型调用的工具。第三方插件默认关闭；启用后，写操作每次都会先向你确认。"))

        s.policy?.let { p ->
            Plugins.policyText(p)?.let { text ->
                column.addView(Ui.sectionTitle(this, "策略文件"))
                column.addView(Ui.card(this).apply {
                    addView(Ui.line(context, "状态", text, warn = true))
                    p.reason?.takeIf { it.isNotEmpty() }?.let { addView(Ui.line(context, "原因", it)) }
                    addView(Ui.buttons(context, "重置策略" to { confirmReset() }))
                })
            }
        }

        column.addView(Ui.sectionTitle(this, "已发现的插件"))
        if (s.plugins.isEmpty() && s.error == null) {
            column.addView(Ui.card(this).apply {
                addView(Ui.paragraph(context, "还没有发现插件。安装了支持 AgentOS 插件的 App 后点“重新扫描”。"))
            })
        }
        s.plugins.forEach { column.addView(card(it), Ui.matchWrap().apply { bottomMargin = dp(10) }) }
        column.addView(Ui.buttons(this, "重新扫描" to { reload(rescan = true) }))
    }

    private fun card(p: Plugins.Plugin): LinearLayout = Ui.card(this).apply {
        addView(header(p) { on -> toggle(p, on) { reload() } })
        identity(this, p)
        Plugins.toolSummary(p).takeIf { it.isNotEmpty() }?.let { addView(Ui.paragraph(context, it)) }
        val issues = Plugins.issues(p)
        issues.take(Plugins.LIST_ISSUES).forEach { addView(Ui.paragraph(context, it, R.color.ui_warn)) }
        if (issues.size > Plugins.LIST_ISSUES) addView(Ui.paragraph(context, "另有 ${issues.size - Plugins.LIST_ISSUES} 项，点开查看"))
        addView(Ui.buttons(context, "详情" to { openDetail(p) }))
        clickable(this, "${p.displayName}，查看详情") { openDetail(p) }
    }

    private fun openDetail(p: Plugins.Plugin) =
        startActivity(Intent(this, PluginDetailActivity::class.java).putExtra(PluginDetailActivity.EXTRA_ID, p.id))

    private fun confirmReset() {
        show(
            AlertDialog.Builder(this)
                .setTitle("重置插件策略？")
                .setMessage("会丢弃现在的用户策略文件，全部恢复默认：第三方插件都会停用，所有“始终允许”都会取消。之后需要重新启用你要用的插件。")
                .setPositiveButton("重置") { _, _ -> call({ reload() }) { it.resetPolicy() } }
                .setNegativeButton("取消", null),
        )
    }
}
