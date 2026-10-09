package org.agentos.sample.sms.agentos

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 已经让 AgentOS 处理过的短信 id（系统短信库的 `_id`）的存放处。 */
interface ProcessedStore {
    fun load(): List<String>

    fun save(ids: List<String>)
}

class InMemoryProcessedStore : ProcessedStore {
    private var ids: List<String> = emptyList()

    override fun load() = ids

    override fun save(ids: List<String>) {
        this.ids = ids
    }
}

/** SharedPreferences 里存一行一个 id。id 只是数字，不含短信内容。 */
class PrefsProcessedStore(context: Context) : ProcessedStore {
    private val prefs = context.applicationContext.getSharedPreferences("sms_agentos", Context.MODE_PRIVATE)

    override fun load(): List<String> = prefs.getString(KEY, null)?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()

    override fun save(ids: List<String>) {
        prefs.edit { putString(KEY, ids.joinToString("\n")) }
    }

    private companion object {
        const val KEY = "processed_ids"
    }
}

/**
 * 哪些短信已经处理过：让 AgentOS 正常处理完一轮（没有被停止、没有出错）之后，把那一轮发出去的短信记下来，下次默认不再带上，
 * 免得同一条短信反复建出重复的待办和日程。只记 id，最多 [max] 个，满了丢最早的。
 */
class ProcessedLedger(private val store: ProcessedStore, private val max: Int = DEFAULT_MAX) {
    private val lock = Any()
    private val flow = MutableStateFlow(store.load().toCollection(LinkedHashSet()).toSet())

    /** 已处理的 id（界面据此给气泡打“已处理”标记）。 */
    val ids: StateFlow<Set<String>> = flow.asStateFlow()

    fun isProcessed(id: String): Boolean = id in flow.value

    fun mark(newIds: Collection<String>) = synchronized(lock) {
        if (newIds.isEmpty()) return
        val merged = LinkedHashSet(flow.value).also { set ->
            newIds.forEach { set.remove(it); set.add(it) }
        }
        val kept = merged.toList().takeLast(max)
        store.save(kept)
        flow.value = kept.toSet()
    }

    /** 清空（debug 的 reset 用）。返回清掉了多少个。 */
    fun clear(): Int = synchronized(lock) {
        val n = flow.value.size
        store.save(emptyList())
        flow.value = emptySet()
        n
    }

    companion object {
        const val DEFAULT_MAX = 5_000
    }
}

/** 用户改过的任务说明（见 [SmsSchedulePrompt]）；没改过是 null，用资源里的默认值。 */
interface InstructionStore {
    fun load(): String?

    fun save(text: String?)
}

class InMemoryInstructionStore(private var value: String? = null) : InstructionStore {
    override fun load() = value

    override fun save(text: String?) {
        value = text
    }
}

class PrefsInstructionStore(context: Context) : InstructionStore {
    private val prefs = context.applicationContext.getSharedPreferences("sms_agentos", Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    override fun save(text: String?) {
        prefs.edit { if (text.isNullOrBlank()) remove(KEY) else putString(KEY, text) }
    }

    private companion object {
        const val KEY = "custom_instructions"
    }
}

/**
 * 任务说明的当前值：用户改过就用用户的，否则用 [default]（资源里的，跟界面语言走）。保存时和默认值一样（忽略首尾空白）就等于没改，
 * 清掉存储，这样以后默认说明更新了，没改过的用户自动用上新的。
 */
class PromptSettings(private val store: InstructionStore, private val default: () -> String) {
    val defaultText: String get() = default()

    val effective: String get() = store.load() ?: default()

    val isCustom: Boolean get() = store.load() != null

    /** 保存用户的编辑；返回实际生效的文字（超长截断；空白或与默认相同 = 恢复默认）。 */
    fun save(text: String): String {
        val clamped = SmsSchedulePrompt.clampInstructions(text)
        if (clamped.isBlank() || clamped.trim() == default().trim()) {
            store.save(null)
            return default()
        }
        store.save(clamped)
        return clamped
    }

    fun reset(): String {
        store.save(null)
        return default()
    }
}
