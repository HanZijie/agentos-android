package org.agentos.runtime.consent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.agentos.runtime.i18n.MessageRef
import org.agentos.runtime.ports.Clock
import org.agentos.runtime.ports.ToolRisk

/**
 * **调试用**的自动应答（adb 无人值守测试）：包住真正的 [ConsentSurface]（或什么都不包），按 [mode] 自动回答每一条新的确认请求，
 * 并记下最近的请求摘要（[recent]）。D 的 `ConsentDebugReceiver`（debug 源集）设置 [mode]、读取 [recent]。
 *
 * **只在 debug 构建里接线**：它在 core:runtime 里，但 release 的代码不引用它（R8 会把没有引用的类删掉）；它也没有任何能在 release 里被外部触发的入口。
 * 自动应答**从不**选 [ConsentChoice.ALWAYS_ALLOW]（测试不能改用户策略）。
 *
 * 记录里**不含参数全文**：只有清理过、截断到 [SUMMARY_CHARS] 的摘要，没有密钥和完整的第三方文本。
 *
 * @param delegate 真正的界面；自动应答之前先转给它（调试时仍然能看到对话框和通知）
 */
class AutoConsentResponder(
    private val delegate: ConsentSurface = ConsentSurface.NONE,
    private val clock: Clock = Clock.SYSTEM,
    private val maxEntries: Int = 50,
) : ConsentSurface {

    enum class Mode {
        /** 不自动回答（照常交给界面）。 */
        OFF,

        /** 允许；能选“本会话内不再询问”就选它，否则允许一次。 */
        ALLOW,

        /** 只允许这一次。 */
        ALLOW_ONCE,

        /** 拒绝。 */
        DENY,
    }

    /** 一条请求的摘要。[answeredWith] 是自动应答选的（没有自动应答为 null）；[end] 在请求结案后填上。 */
    data class Entry(
        val requestId: String,
        val toolName: String,
        val risk: ToolRisk,
        val sourceLine: MessageRef?,
        val argumentsSummary: String,
        val options: List<ConsentChoice>,
        val atMillis: Long,
        val answeredWith: ConsentChoice? = null,
        val end: ConsentEnd? = null,
        val notice: String? = null,
    )

    @Volatile var mode: Mode = Mode.OFF

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private val recentFlow = MutableStateFlow<List<Entry>>(emptyList())

    /** 最近 [maxEntries] 条请求的摘要，最新的在最后。 */
    val recent: StateFlow<List<Entry>> = recentFlow.asStateFlow()

    private var coordinator: ConsentCoordinator? = null

    /** 接上协调器（协调器要先有界面才能构造，所以应答器晚一步接上）。 */
    fun attach(coordinator: ConsentCoordinator) {
        this.coordinator = coordinator
    }

    override fun requested(view: ConsentView) {
        val auto = when (mode) {
            Mode.OFF -> null
            Mode.DENY -> ConsentChoice.DENY
            Mode.ALLOW_ONCE -> ConsentChoice.ALLOW_ONCE
            Mode.ALLOW -> if (view.options.any { it.choice == ConsentChoice.ALLOW_FOR_SESSION }) ConsentChoice.ALLOW_FOR_SESSION else ConsentChoice.ALLOW_ONCE
        }
        synchronized(lock) {
            entries += Entry(
                requestId = view.requestId,
                toolName = view.toolName,
                risk = view.risk,
                sourceLine = view.sourceLine,
                argumentsSummary = view.argumentsPreview.replace('\n', ' ').take(SUMMARY_CHARS),
                options = view.options.map { it.choice },
                atMillis = clock.nowMillis(),
                answeredWith = auto,
            )
            while (entries.size > maxEntries) entries.removeAt(0)
            recentFlow.value = entries.toList()
        }
        delegate.requested(view)
        if (auto != null) coordinator?.respond(view.requestId, auto)
    }

    override fun resolved(requestId: String, resolution: ConsentResolution) {
        synchronized(lock) {
            val i = entries.indexOfLast { it.requestId == requestId }
            if (i >= 0) {
                entries[i] = entries[i].copy(end = resolution.end, notice = resolution.notice)
                recentFlow.value = entries.toList()
            }
        }
        delegate.resolved(requestId, resolution)
    }

    companion object {
        const val SUMMARY_CHARS = 120
    }
}
