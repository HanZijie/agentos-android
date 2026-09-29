package org.agentos.app.settings

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.agentos.app.R
import org.agentos.app.ui.Ui

/**
 * Choose the model source and enter the key (F9, IAgentControl v2): a vendor preset from
 * model-catalog.json (MiniMax international / China first, as ordered by the catalog), or a custom
 * compatible endpoint (URL, protocol, model name, key).
 *
 * The key goes from the password field straight into setModelSource and the field is cleared
 * afterwards; it is never logged, shown, or kept in this Activity's state. FLAG_SECURE keeps it out
 * of screenshots and the recents thumbnail. Saving is a hot reload: the next model request uses it.
 */
class ModelSourceActivity : Activity() {
    private val control by lazy { AgentControlClient(this) }
    private var scope: CoroutineScope? = null

    private var catalog: Byok.Catalog? = null
    private var current: Byok.Source? = null
    private var models: List<Byok.Model> = emptyList()
    private var mode = MODE_PRESET

    private lateinit var presetTab: TextView
    private lateinit var customTab: TextView
    private lateinit var presetPanel: LinearLayout
    private lateinit var customPanel: LinearLayout
    private lateinit var providerSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var thinkingRow: LinearLayout
    private lateinit var thinkingSpinner: Spinner
    private lateinit var apiSpinner: Spinner
    private lateinit var baseUrl: EditText
    private lateinit var customModel: EditText
    private lateinit var key: EditText
    private lateinit var keyHint: TextView
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val (root, column) = Ui.page(this, "模型与 key")
        build(column)
        setContentView(root)
        scope = MainScope().also { it.launch { load() } }
    }

    override fun onDestroy() {
        scope?.cancel()
        key.text.clear()
        super.onDestroy()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun build(column: LinearLayout) {
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presetTab = Ui.textButton(this, "厂商预设", R.color.ui_accent) { setMode(MODE_PRESET) }
        customTab = Ui.textButton(this, "自定义兼容端点", R.color.ui_accent) { setMode(MODE_CUSTOM) }
        tabs.addView(presetTab)
        tabs.addView(customTab)
        column.addView(tabs)

        presetPanel = Ui.card(this).apply {
            addView(label("模型厂商"))
            providerSpinner = Spinner(context)
            addView(providerSpinner)
            addView(label("模型"))
            modelSpinner = Spinner(context)
            addView(modelSpinner)
            thinkingRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("思考强度"))
                thinkingSpinner = Spinner(context)
                addView(thinkingSpinner)
            }
            addView(thinkingRow)
        }
        column.addView(presetPanel)

        customPanel = Ui.card(this).apply {
            addView(label("协议"))
            apiSpinner = Spinner(context).apply {
                adapter = adapter(listOf(Byok.apiText(Byok.ANTHROPIC), Byok.apiText(Byok.OPENAI)))
            }
            addView(apiSpinner)
            addView(label("API 地址"))
            baseUrl = field("https://api.example.com/v1", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            addView(baseUrl)
            addView(Ui.paragraph(context,
                "必须是 https://；唯一的例外是本机地址（127.0.0.1、localhost、::1）可以用 http://，给手机上运行的模型服务用。" +
                    "OpenAI Chat Completions 填到 /v1 这一级，Anthropic Messages 填 API 根地址。"))
            addView(label("模型名"))
            customModel = field("例如 deepseek-chat", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            addView(customModel)
            visibility = View.GONE
        }
        column.addView(customPanel)

        column.addView(Ui.card(this).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12)
            addView(label("key"))
            key = field("粘贴 key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            addView(key)
            keyHint = Ui.paragraph(context, "")
            addView(keyHint)
        }, Ui.matchWrap().apply { topMargin = dp(12) })

        message = Ui.text(this, 14f, R.color.ui_error).apply { setPadding(dp(4), dp(8), dp(4), 0) }
        column.addView(message)
        column.addView(Ui.buttons(this, "保存" to { save() }))
        column.addView(Ui.paragraph(this, "保存后从下一次模型请求起生效，不打断正在进行的这一轮。key 用 Android Keystore 加密保存，界面上只显示首尾各 4 位。"))

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                catalog?.providers?.getOrNull(position)?.let { p -> scope?.launch { loadModels(p.id, keep = null) } }
                updateKeyHint()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateThinking()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun label(text: String) = Ui.text(this, 12f, R.color.ui_text_secondary).apply {
        this.text = text
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setSingleLine(true)
        setTextColor(getColor(R.color.ui_text))
        setHintTextColor(getColor(R.color.ui_text_secondary))
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun setMode(m: Int) {
        mode = m
        presetPanel.visibility = if (m == MODE_PRESET) View.VISIBLE else View.GONE
        customPanel.visibility = if (m == MODE_CUSTOM) View.VISIBLE else View.GONE
        presetTab.alpha = if (m == MODE_PRESET) 1f else 0.5f
        customTab.alpha = if (m == MODE_CUSTOM) 1f else 0.5f
        updateKeyHint()
    }

    private suspend fun load() {
        try {
            val (cat, src) = control.use { Byok.parseCatalog(it.getModelPresets(null)) to Byok.parseSource(it.modelSource) }
            catalog = cat
            current = src
            providerSpinner.adapter = adapter(cat.providers.map { it.name })
            thinkingSpinner.adapter = adapter(cat.thinkingLevels.map { Byok.thinkingText(it) })
            if (src.configured && src.kind == "custom") {
                setMode(MODE_CUSTOM)
                apiSpinner.setSelection(if (src.api == Byok.OPENAI) 1 else 0)
                baseUrl.setText(src.baseUrl.orEmpty())
                customModel.setText(src.model.orEmpty())
            } else {
                setMode(MODE_PRESET)
            }
            val providerIndex = cat.providers.indexOfFirst { it.id == src.provider }.takeIf { it >= 0 } ?: 0
            providerSpinner.setSelection(providerIndex)
            cat.providers.getOrNull(providerIndex)?.let { loadModels(it.id, keep = src.model) }
            cat.thinkingLevels.indexOf(src.thinkingLevel ?: "off").takeIf { it >= 0 }?.let { thinkingSpinner.setSelection(it) }
        } catch (e: Exception) {
            message.text = "读取厂商目录失败：${Byok.errorText(e.message)}"
        }
        updateKeyHint()
    }

    private suspend fun loadModels(providerId: String, keep: String?) {
        try {
            val list = control.use { Byok.parseModels(it.getModelPresets(providerId)) }
            if (catalog?.providers?.getOrNull(providerSpinner.selectedItemPosition)?.id != providerId) return
            models = list
            modelSpinner.adapter = adapter(list.map { if (it.name == it.id) it.id else "${it.name}（${it.id}）" })
            val want = keep ?: current?.model.takeIf { current?.provider == providerId }
            list.indexOfFirst { it.id == want }.takeIf { it >= 0 }?.let { modelSpinner.setSelection(it) }
            updateThinking()
        } catch (e: Exception) {
            message.text = Byok.errorText(e.message)
        }
    }

    private fun updateThinking() {
        val m = models.getOrNull(modelSpinner.selectedItemPosition)
        thinkingRow.visibility = if (m?.reasoning == true) View.VISIBLE else View.GONE
    }

    private fun form(): Byok.Form? {
        val cat = catalog ?: return null
        val thinking = cat.thinkingLevels.getOrNull(thinkingSpinner.selectedItemPosition) ?: "off"
        return when (mode) {
            MODE_PRESET -> {
                val p = cat.providers.getOrNull(providerSpinner.selectedItemPosition) ?: return null
                val m = models.getOrNull(modelSpinner.selectedItemPosition)
                Byok.Form.Preset(p.id, m?.id.orEmpty(), if (m?.reasoning == true) thinking else "off")
            }
            else -> Byok.Form.Custom(
                api = if (apiSpinner.selectedItemPosition == 1) Byok.OPENAI else Byok.ANTHROPIC,
                baseUrl = baseUrl.text.toString(),
                model = customModel.text.toString().trim(),
            )
        }
    }

    private fun updateKeyHint() {
        val f = form()
        val src = current
        keyHint.text = when {
            f != null && Byok.sameEndpoint(f, src) -> "留空则沿用已保存的 key（${src?.keyMasked}）"
            src?.configured == true -> "换了厂商或地址，需要重新填写 key；key 不会被带到别的地址"
            else -> catalog?.providers?.getOrNull(providerSpinner.selectedItemPosition)?.keyLabel?.takeIf { mode == MODE_PRESET }.orEmpty()
        }
    }

    private fun save() {
        val f = form() ?: return
        val keyText = key.text.toString().trim()
        Byok.validate(f, keyText.isNotEmpty(), current)?.let { bad ->
            message.text = bad.message
            return
        }
        message.text = ""
        scope?.launch {
            try {
                val after = Byok.parseSource(control.use { it.setModelSource(Byok.sourceJson(f), keyText.ifEmpty { null }) })
                key.text.clear()
                current = after
                Toast.makeText(this@ModelSourceActivity, if (after.usable) "已保存，下一次请求起生效" else "已保存，但暂时不可用", Toast.LENGTH_SHORT).show()
                if (after.usable) finish() else {
                    message.text = after.problems.joinToString("\n") { Byok.problemText(it) }.ifEmpty { "已保存，但暂时不可用" }
                }
            } catch (e: Exception) {
                message.text = Byok.errorText(e.message)
            }
        }
    }

    companion object {
        private const val MODE_PRESET = 0
        private const val MODE_CUSTOM = 1
    }
}
