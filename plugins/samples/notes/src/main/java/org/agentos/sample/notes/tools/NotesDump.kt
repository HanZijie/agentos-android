package org.agentos.sample.notes.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.agentos.sample.notes.data.Note
import org.agentos.sample.notes.data.NoteQueries

/**
 * debug 的 `cmd=dump`（`DebugCallReceiver`）用的状态快照：**只读**，包含全部备忘录（含归档和回收站）和标签统计。
 * 字段是 snake_case，每条备忘录的字段与 MCP 的 `note_get` 一致（再加 `revision`），这样设备验收时可以直接拿 MCP 的返回对照。
 * 只在 debug 包里被引用，release 里会被 R8 去掉。纯函数，JVM 可测。
 *
 * 广播的 result data 约 1 MB 上限（Parcel 里字符串占两个字节），所以按字符预算分页：到预算就停，`next_offset` 指向下一条，
 * 没有更多时 `next_offset` 为 null；也可以用 `limit` 主动限制每页条数。单条正文超过 [MAX_CONTENT_CHARS] 时截断并标 `content_truncated`。
 */
object NotesDump {
    const val PAGE_BUDGET_CHARS = 200_000
    const val MAX_CONTENT_CHARS = 100_000
    const val MAX_LIMIT = 10_000

    /**
     * @param notes 仓库里的全部备忘录
     * @param offset 从第几条开始（0 起，按创建时间、id 排序，分页稳定）
     * @param limit 本页最多几条；null = 只受字符预算限制
     */
    fun build(tools: NotesTools, notes: List<Note>, offset: Int = 0, limit: Int? = null): JsonObject {
        val ordered = notes.sortedWith(compareBy<Note>({ it.createdAt }, { it.id }))
        val start = offset.coerceIn(0, ordered.size)
        val maxItems = (limit ?: MAX_LIMIT).coerceIn(1, MAX_LIMIT)
        val page = ArrayList<JsonObject>()
        var chars = 0
        var next = start
        while (next < ordered.size && page.size < maxItems) {
            val item = noteItem(tools, ordered[next])
            val cost = item.toString().length
            if (page.isNotEmpty() && chars + cost > PAGE_BUDGET_CHARS) break
            page += item
            chars += cost
            next++
        }
        val counts = NoteQueries.tagCounts(notes)
        return buildJsonObject {
            put("notes", JsonArray(page))
            putJsonArray("tags") {
                counts.forEach { add(buildJsonObject { put("name", it.name); put("count", it.count) }) }
            }
            put("total", ordered.size)
            put("offset", start)
            put("count", page.size)
            put("next_offset", if (next < ordered.size) JsonPrimitive(next) else JsonNull)
        }
    }

    private fun noteItem(tools: NotesTools, note: Note): JsonObject {
        val base = tools.noteJson(note)
        val truncated = note.content.length > MAX_CONTENT_CHARS
        val content = if (truncated) note.content.substring(0, safeEnd(note.content, MAX_CONTENT_CHARS)) else note.content
        return buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            put("content", content)
            put("revision", note.revision)
            if (truncated) put("content_truncated", true)
        }
    }

    private fun safeEnd(text: String, max: Int): Int =
        if (max > 0 && Character.isHighSurrogate(text[max - 1])) max - 1 else max
}
