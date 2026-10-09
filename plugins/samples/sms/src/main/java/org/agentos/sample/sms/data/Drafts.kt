package org.agentos.sample.sms.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `sms_compose` 交出去的草稿。**为什么要记**：Android 不允许后台 App 随意打开界面（Background activity launch blocked，
 * V1 在 API 36 模拟器上实测：MCP 服务在 AgentOS 绑定下没有可见窗口，`startActivity` 静默失败、也不抛异常），
 * 所以工具不能保证短信界面真的弹出。草稿记在这里，Agent 发送页上有“打开”按钮，由用户在本 App 可见时再打开系统短信界面。
 */
data class DraftEntry(val id: String, val to: String, val text: String?, val createdAt: Long)

interface DraftStore {
    fun load(): List<DraftEntry>

    fun save(entries: List<DraftEntry>)
}

class InMemoryDraftStore : DraftStore {
    private var entries: List<DraftEntry> = emptyList()

    override fun load() = entries

    override fun save(entries: List<DraftEntry>) {
        this.entries = entries
    }
}

/** SharedPreferences 里存一段 JSON（草稿最多 [Drafts.MAX] 条，很小）。 */
class PrefsDraftStore(context: Context) : DraftStore {
    private val prefs = context.applicationContext.getSharedPreferences("sms_drafts", Context.MODE_PRIVATE)

    override fun load(): List<DraftEntry> = runCatching {
        val array = Json.parseToJsonElement(prefs.getString(KEY, null) ?: return emptyList()) as JsonArray
        array.map {
            val o = it.jsonObject
            DraftEntry(
                id = o.getValue("id").jsonPrimitive.content,
                to = o.getValue("to").jsonPrimitive.content,
                text = (o["text"] as? JsonPrimitive)?.contentOrNull,
                createdAt = o.getValue("created_at").jsonPrimitive.content.toLong(),
            )
        }
    }.getOrDefault(emptyList())

    override fun save(entries: List<DraftEntry>) {
        val json = JsonArray(
            entries.map {
                buildJsonObject {
                    put("id", it.id)
                    put("to", it.to)
                    put("text", it.text?.let { t -> JsonPrimitive(t) } ?: JsonNull)
                    put("created_at", it.createdAt)
                }
            },
        )
        prefs.edit { putString(KEY, json.toString()) }
    }

    private companion object {
        const val KEY = "drafts"
    }
}

class Drafts(private val store: DraftStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val flow = MutableStateFlow(store.load())

    /** 新到旧。 */
    val recent: StateFlow<List<DraftEntry>> = flow.asStateFlow()

    fun add(to: String, text: String?): DraftEntry = synchronized(lock) {
        val now = clock()
        val next = (flow.value.maxOfOrNull { it.id.toLongOrNull() ?: 0L } ?: 0L) + 1
        val entry = DraftEntry(next.toString(), to, text, now)
        // 同一个收件人 + 同一段文字只留最新的一条
        val kept = flow.value.filterNot { it.to == to && it.text == text }
        persist((listOf(entry) + kept).take(MAX))
        entry
    }

    fun remove(id: String) = synchronized(lock) { persist(flow.value.filterNot { it.id == id }) }

    fun clear(): Int = synchronized(lock) { flow.value.size.also { persist(emptyList()) } }

    fun count(): Int = flow.value.size

    private fun persist(entries: List<DraftEntry>) {
        store.save(entries)
        flow.value = entries
    }

    companion object {
        const val MAX = 5
    }
}
