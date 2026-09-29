package org.agentos.app.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.agentos.app.R

/**
 * The App's own conversation screen (W8): streaming output, cancel, thinking (collapsible), tool calls.
 * Renders [ChatController.state]; one view per [ChatItem], rebound only when the item changed, so a
 * streamed chunk touches a single TextView.
 */
class ConversationActivity : Activity() {
    private lateinit var controller: ChatController
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var action: TextView
    private lateinit var newChat: TextView

    private val views = HashMap<Long, View>()
    private val bound = HashMap<Long, ChatItem>()
    private var order: List<Long> = emptyList()
    private var uiScope: CoroutineScope? = null
    private var lastState: ChatState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ChatController.get(this)
        setContentView(buildLayout())
        if (savedInstanceState == null && !org.agentos.app.onboarding.OnboardingActivity.isDone(this)) {
            startActivity(android.content.Intent(this, org.agentos.app.onboarding.OnboardingActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        uiScope = MainScope().also { s -> s.launch { controller.state.collect { render(it) } } }
    }

    override fun onStop() {
        uiScope?.cancel()
        uiScope = null
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) controller.onScreenFinished()
        super.onDestroy()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.ui_background))
        }

        // top bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.text(this, 20f, R.color.ui_text, bold = true).apply { setText(R.string.ui_title) })
        status = Ui.text(this, 12f, R.color.ui_text_secondary)
        titles.addView(status)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        newChat = Ui.textButton(this, getString(R.string.ui_new_chat), R.color.ui_accent) { controller.newConversation() }
        bar.addView(newChat)
        bar.addView(Ui.textButton(this, getString(R.string.ui_settings), R.color.ui_accent) {
            startActivity(android.content.Intent(this, org.agentos.app.settings.SettingsActivity::class.java))
        })
        root.addView(bar, Ui.matchWrap())
        root.addView(View(this).apply { setBackgroundColor(getColor(R.color.ui_divider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        // messages
        val frame = FrameLayout(this)
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty = Ui.text(this, 15f, R.color.ui_text_secondary).apply {
            setText(R.string.ui_empty)
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
        }
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // composer
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setBackgroundColor(getColor(R.color.ui_surface))
        }
        input = EditText(this).apply {
            setHint(R.string.ui_hint_input)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
            imeOptions = EditorInfo.IME_ACTION_SEND
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(getColor(R.color.ui_text))
            setHintTextColor(getColor(R.color.ui_text_secondary))
            background = Ui.rounded(getColor(R.color.ui_background), dp(20).toFloat())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { onAction(); true } else false }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        action = Ui.textButton(this, getString(R.string.ui_send), R.color.ui_accent) { onAction() }
        composer.addView(action)
        root.addView(composer, Ui.matchWrap())

        Ui.applyInsets(root)
        return root
    }

    private fun onAction() {
        val s = controller.state.value
        if (s.busy) {
            controller.cancel()
        } else if (controller.send(input.text.toString())) {
            input.text.clear()
        }
    }

    private fun render(s: ChatState) {
        val prev = lastState
        lastState = s
        status.text = when {
            s.turn == ChatState.Turn.CANCELLING -> getString(R.string.ui_status_cancelling)
            s.turn == ChatState.Turn.RUNNING -> getString(R.string.ui_status_running)
            s.connection == ChatState.Connection.CONNECTING -> getString(R.string.ui_status_connecting)
            s.turn == ChatState.Turn.SENDING -> getString(R.string.ui_status_sending)
            s.connection == ChatState.Connection.CONNECTED -> getString(R.string.ui_status_connected)
            else -> ""
        }
        action.text = getString(if (s.busy) R.string.ui_stop else R.string.ui_send)
        action.isEnabled = s.turn != ChatState.Turn.CANCELLING
        action.alpha = if (action.isEnabled) 1f else 0.4f
        newChat.isEnabled = !s.busy
        newChat.alpha = if (newChat.isEnabled) 1f else 0.4f
        empty.visibility = if (s.items.isEmpty()) View.VISIBLE else View.GONE

        val atBottom = scroll.scrollY + scroll.height >= list.height - dp(48)
        val ids = s.items.map { it.id }
        if (ids.size < order.size || ids.subList(0, order.size) != order) {
            list.removeAllViews()
            views.clear()
            bound.clear()
        }
        for (item in s.items) {
            val view = views[item.id] ?: create(item).also {
                views[item.id] = it
                list.addView(it)
            }
            if (bound[item.id] != item) {
                bind(view, item)
                bound[item.id] = item
            }
        }
        order = ids
        val userJustSent = s.items.lastOrNull() is ChatItem.User && prev?.items?.lastOrNull()?.id != s.items.last().id
        if (atBottom || userJustSent) scroll.post { scroll.scrollTo(0, list.height) }
    }

    // ---------------------------------------------------------------- item views

    private fun create(item: ChatItem): View = when (item) {
        is ChatItem.User -> bubble(end = true)
        is ChatItem.Agent -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = spaced(Gravity.START)
            val thoughtHeader = Ui.text(context, 13f, R.color.ui_thought).apply {
                tag = "thoughtHeader"
                setPadding(dp(4), dp(2), dp(4), dp(2))
                setOnClickListener { controller.toggleThought(item.id) }
            }
            addView(thoughtHeader)
            addView(Ui.text(context, 13f, R.color.ui_thought).apply {
                tag = "thought"
                setPadding(dp(12), dp(4), dp(12), dp(6))
                setTextIsSelectable(true)
            })
            addView(bubble(end = false).apply { tag = "text" })
        }
        is ChatItem.Tool -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = spaced(Gravity.START)
            background = Ui.rounded(getColor(R.color.ui_tool_bg), dp(10).toFloat())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { controller.toggleTool(item.id) }
            addView(Ui.text(context, 14f, R.color.ui_text).apply { tag = "header" })
            addView(Ui.text(context, 13f, R.color.ui_text_secondary).apply { tag = "detail"; setTextIsSelectable(true) })
        }
        is ChatItem.Notice -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = spaced(Gravity.CENTER_HORIZONTAL)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(Ui.text(context, 13f, R.color.ui_text_secondary, bold = true).apply { tag = "title"; gravity = Gravity.CENTER })
            addView(Ui.text(context, 12f, R.color.ui_text_secondary).apply { tag = "hint"; gravity = Gravity.CENTER })
        }
    }

    private fun bind(view: View, item: ChatItem) {
        when (item) {
            is ChatItem.User -> (view as TextView).text = item.text
            is ChatItem.Agent -> {
                val header = view.findViewWithTag<TextView>("thoughtHeader")
                val thought = view.findViewWithTag<TextView>("thought")
                val text = view.findViewWithTag<TextView>("text")
                val hasThought = item.thought.isNotBlank()
                header.visibility = if (hasThought) View.VISIBLE else View.GONE
                header.setText(if (item.thoughtExpanded) R.string.ui_thought_expanded else R.string.ui_thought_collapsed)
                thought.visibility = if (hasThought && item.thoughtExpanded) View.VISIBLE else View.GONE
                thought.text = item.thought.trim()
                // trailing newlines are kept while streaming (the next chunk may continue the line), trimmed once done
                val body = if (item.streaming) item.text.trimStart() else item.text.trim()
                text.visibility = if (body.isEmpty() && !item.streaming) View.GONE else View.VISIBLE
                text.text = if (item.streaming) "$body▍" else body
            }
            is ChatItem.Tool -> {
                val status = getString(
                    when (item.status) {
                        ToolStatus.PENDING -> R.string.ui_tool_pending
                        ToolStatus.IN_PROGRESS -> R.string.ui_tool_running
                        ToolStatus.COMPLETED -> R.string.ui_tool_completed
                        ToolStatus.FAILED -> R.string.ui_tool_failed
                    }
                )
                val arrow = if (item.detail.isNullOrBlank()) "" else if (item.expanded) " ▾" else " ▸"
                view.findViewWithTag<TextView>("header").text = "🔧 ${item.title} · $status$arrow"
                view.findViewWithTag<TextView>("detail").apply {
                    text = item.detail.orEmpty()
                    visibility = if (item.expanded && !item.detail.isNullOrBlank()) View.VISIBLE else View.GONE
                }
            }
            is ChatItem.Notice -> {
                val error = item.kind == ChatItem.Notice.Kind.ERROR
                view.background = if (error) Ui.rounded(getColor(R.color.ui_error_bg), dp(10).toFloat()) else null
                view.findViewWithTag<TextView>("title").apply {
                    text = item.title
                    setTextColor(getColor(if (error) R.color.ui_error else R.color.ui_text_secondary))
                }
                view.findViewWithTag<TextView>("hint").apply {
                    text = item.hint.orEmpty()
                    visibility = if (item.hint.isNullOrBlank()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun bubble(end: Boolean): TextView = Ui.text(this, 16f, if (end) R.color.ui_user_text else R.color.ui_text).apply {
        background = Ui.rounded(getColor(if (end) R.color.ui_user_bubble else R.color.ui_agent_bubble), dp(16).toFloat())
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setTextIsSelectable(true)
        layoutParams = spaced(if (end) Gravity.END else Gravity.START)
        maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
    }

    private fun spaced(gravity: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        this.gravity = gravity
        topMargin = dp(6)
    }
}
