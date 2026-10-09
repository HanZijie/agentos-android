package org.agentos.sample.todo.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.agentos.sample.todo.data.Todo
import org.agentos.sample.todo.data.TodoStatus

/**
 * debug 的 `cmd=dump`（`DebugToolReceiver`）用的状态快照：**只读**，包含全部待办（含子任务、已完成）。
 * 每条的字段与 MCP 的 `todo_get` 完全一致（同一个序列化函数，不含 `subtasks` 数组——子任务是 `todos` 里 `parent_id` 指向父任务的普通一行），
 * 设备验收时可以直接拿 MCP 的返回对照。只在 debug 包里被引用，release 里会被 R8 去掉。纯函数，JVM 可测。
 *
 * 广播的 result data 约 1 MB 上限，所以按字符预算分页：到预算就停，`next_offset` 指向下一条，没有更多时 `next_offset` 为 null；
 * 也可以用 `limit` 主动限制每页条数。按创建时间、id 排序，翻页稳定。
 */
object TodoDump {
    const val PAGE_BUDGET_CHARS = 200_000
    const val MAX_LIMIT = 10_000

    fun build(tools: TodoTools, todos: List<Todo>, nowMillis: Long, offset: Int = 0, limit: Int? = null): JsonObject {
        val ordered = todos.sortedWith(compareBy<Todo>({ it.createdAt }, { it.id }))
        val start = offset.coerceIn(0, ordered.size)
        val maxItems = (limit ?: MAX_LIMIT).coerceIn(1, MAX_LIMIT)
        val page = ArrayList<JsonObject>()
        var chars = 0
        var next = start
        while (next < ordered.size && page.size < maxItems) {
            val item = tools.detailJson(ordered[next], emptyList(), nowMillis)
            val cost = item.toString().length
            if (page.isNotEmpty() && chars + cost > PAGE_BUDGET_CHARS) break
            page += item
            chars += cost
            next++
        }
        return buildJsonObject {
            put("todos", JsonArray(page))
            putJsonObject("counts") { TodoStatus.entries.forEach { s -> put(s.key, todos.count { it.status == s }) } }
            put("total", ordered.size)
            put("offset", start)
            put("count", page.size)
            put("next_offset", if (next < ordered.size) JsonPrimitive(next) else JsonNull)
        }
    }
}
