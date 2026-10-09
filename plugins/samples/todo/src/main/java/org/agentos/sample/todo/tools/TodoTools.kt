package org.agentos.sample.todo.tools

import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.agentos.sample.todo.data.Change
import org.agentos.sample.todo.data.Due
import org.agentos.sample.todo.data.DueTime
import org.agentos.sample.todo.data.Priority
import org.agentos.sample.todo.data.Todo
import org.agentos.sample.todo.data.TodoDraft
import org.agentos.sample.todo.data.TodoException
import org.agentos.sample.todo.data.TodoFilter
import org.agentos.sample.todo.data.TodoLimits
import org.agentos.sample.todo.data.TodoPatch
import org.agentos.sample.todo.data.TodoQueries
import org.agentos.sample.todo.data.TodoRepository
import org.agentos.sample.todo.data.TodoStatus

/**
 * 待办的 MCP 工具（docs/next-apps-plan.md 第 3 节）。只依赖仓库和 kotlinx-serialization-json，不依赖 plugin-sdk，
 * 所以 JVM 测试里可以直接调 [ToolDefinition.handler]。描述写给模型看，用英文；错误一律是一句话原因；
 * 时间是带设备本地偏移的 ISO-8601（全天的待办只有日期）；id 是字符串。
 *
 * @param zone 设备本地时区：逾期、今天、本周、`due_before` / `due_after` 对全天待办的比较都按它。
 * @param week 一周从周几开始（`todo_summary` 的“本周”）。默认 `WeekFields.of(Locale.getDefault())`，即系统 / 区域的一周首日；测试里显式传。
 */
class TodoTools(
    private val repository: TodoRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val week: () -> WeekFields = { WeekFields.of(Locale.getDefault()) },
) {
    val all: List<ToolDefinition> by lazy {
        listOf(todoList, todoGet, todoCreate, todoUpdate, todoSetStatus, todoDelete, todoSearch, todoSummary)
    }

    fun find(name: String): ToolDefinition? = all.firstOrNull { it.name == name }

    /** 直接按名字调用（测试和 debug 自测用）；未知工具返回错误。 */
    suspend fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): ToolOutput =
        find(name)?.handler?.invoke(arguments) ?: ToolOutput.Error("Unknown tool: $name")

    // ---------------------------------------------------------------- 工具

    private val todoList = tool(
        name = "todo_list",
        title = "List todos",
        description = "List todos ordered by priority (high first), then due date (soonest first, undated last). " +
            "Done todos are hidden unless include_done=true or status=done is given. Without parent_id the result mixes top-level todos and subtasks " +
            "(a subtask carries parent_id; a todo with subtasks carries subtask_total and subtask_done); parent_id lists only the subtasks of that todo. " +
            "due_before and due_after are inclusive bounds, each a date (YYYY-MM-DD) or an ISO-8601 date-time with UTC offset; " +
            "a date bound and all-day todos compare by the phone's local calendar date. overdue_only returns open (todo/doing) items whose due time has passed. " +
            "Items are compact: fields that are empty are left out (no due, tags, parent_id or completed_at means none) and notes shows a 200-character preview; use todo_get for everything. " +
            "Page with limit/offset; has_more says whether more follow.",
        schema = schema(
            "status" to enum("Only todos in this status.", TodoStatus.keys),
            "priority" to enum("Only todos with this priority.", Priority.keys),
            "tag" to str("Only todos carrying this tag (case-insensitive)."),
            "due_before" to str("Only todos due on or before this date (YYYY-MM-DD) or moment (ISO-8601 with offset)."),
            "due_after" to str("Only todos due on or after this date (YYYY-MM-DD) or moment (ISO-8601 with offset)."),
            "overdue_only" to bool("Only open (todo/doing) items that are past their due time. Default false."),
            "parent_id" to str("Only the subtasks of this todo."),
            "include_done" to bool("Also return done todos. Default false. Ignored when status is given."),
            "limit" to int("Maximum number of todos to return. Default $DEFAULT_LIST_LIMIT, maximum $MAX_LIST_LIMIT.", min = 1, max = MAX_LIST_LIMIT),
            "offset" to int("Number of todos to skip, for paging. Default 0.", min = 0),
        ),
        annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { args ->
        val z = zone()
        val now = clock()
        val filter = TodoFilter(
            status = args.status("status"),
            priority = args.priority("priority"),
            tag = args.optString("tag")?.takeIf { it.isNotBlank() },
            dueBefore = args.optString("due_before")?.takeIf { it.isNotBlank() }?.let { DueTime.parse(it) },
            dueAfter = args.optString("due_after")?.takeIf { it.isNotBlank() }?.let { DueTime.parse(it) },
            overdueOnly = args.optBoolean("overdue_only") ?: false,
            parentId = args.optString("parent_id")?.takeIf { it.isNotBlank() },
            includeDone = args.optBoolean("include_done") ?: false,
        )
        val limit = args.optInt("limit", 1, MAX_LIST_LIMIT) ?: DEFAULT_LIST_LIMIT
        val offset = args.optInt("offset", 0, Int.MAX_VALUE) ?: 0
        val everything = repository.all()
        val matches = TodoQueries.filter(everything, filter, now, z)
        val children = everything.filter { it.parentId != null }.groupBy { it.parentId }
        val page = matches.drop(offset).take(limit)
        val (items, cutBySize) = fitToBudget(page.map { summaryJson(it, children[it.id].orEmpty(), now, z) })
        ok(
            buildJsonObject {
                put("todos", JsonArray(items))
                put("total", matches.size)
                put("offset", offset)
                put("limit", limit)
                put("count", items.size)
                val hasMore = cutBySize || offset + page.size < matches.size
                put("has_more", hasMore)
                if (hasMore) put("next_offset", offset + items.size)
            },
        )
    }

    private val todoGet = tool(
        name = "todo_get",
        title = "Get a todo",
        description = "Get one todo with its full notes and its subtasks (in the order they were added). " +
            "Subtasks are compact items; if there are too many for one response, subtasks_truncated is true and todo_list with parent_id pages through them.",
        schema = schema("id" to str("Id of the todo."), required = listOf("id")),
        annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { args ->
        val z = zone()
        val now = clock()
        val todo = repository.get(args.string("id"))
        val subtasks = repository.subtasksOf(todo.id)
        val head = detailJson(todo, subtasks, now, z)
        var kept = emptyList<JsonObject>()
        var truncated = false
        if (subtasks.isNotEmpty()) {
            val budget = WireSize.LIMIT - WireSize.cost(head) - ENVELOPE_COST
            var cost = 0
            val items = ArrayList<JsonObject>(subtasks.size)
            for (s in subtasks) {
                val item = summaryJson(s, emptyList(), now, z, withNotes = false)
                cost += WireSize.itemCost(item) + 4
                if (cost > budget && items.isNotEmpty()) { truncated = true; break }
                items += item
            }
            kept = items
        }
        ok(
            buildJsonObject {
                head.forEach { (k, v) -> put(k, v) }
                put("subtasks", JsonArray(kept))
                if (truncated) put("subtasks_truncated", true)
            },
        )
    }

    private val todoCreate = tool(
        name = "todo_create",
        title = "Create a todo",
        description = "Create a todo: one thing that has a clear done state. Before creating, consider todo_search so you do not add a duplicate. " +
            "due is a date (YYYY-MM-DD, an all-day due) or an ISO-8601 date-time with a UTC offset (2026-10-12T17:00:00+08:00); " +
            "due_all_day=true turns a date-time into an all-day due on that date. Setting a due date does not notify or ring anything. " +
            "priority defaults to medium, status to todo. parent_id makes it a subtask of an existing top-level todo " +
            "(only one level: a subtask cannot have subtasks). Returns the created todo.",
        schema = schema(
            "title" to str("What to do, at most ${TodoLimits.MAX_TITLE_CHARS} characters."),
            "notes" to str("Details, at most ${TodoLimits.MAX_NOTES_CHARS} characters."),
            "priority" to enum("Priority. Default medium.", Priority.keys),
            "due" to str("Due date (YYYY-MM-DD) or ISO-8601 date-time with UTC offset."),
            "due_all_day" to bool("true = all-day due on the date of due; false = due must include a time."),
            "tags" to strArray("Short labels without '#', e.g. [\"work\"]. At most ${TodoLimits.MAX_TAGS}."),
            "parent_id" to str("Id of the top-level todo this becomes a subtask of."),
            "status" to enum("Initial status. Default todo.", TodoStatus.keys),
            required = listOf("title"),
        ),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
    ) { args ->
        val z = zone()
        val now = clock()
        val dueText = args.optString("due")?.takeIf { it.isNotBlank() }
        val allDay = args.optBoolean("due_all_day")
        if (dueText == null && allDay != null) throw ToolArgumentException("due_all_day only applies together with due.")
        val todo = repository.create(
            TodoDraft(
                title = args.string("title"),
                notes = args.optString("notes") ?: "",
                status = args.status("status") ?: TodoStatus.TODO,
                priority = args.priority("priority") ?: Priority.MEDIUM,
                due = dueText?.let { DueTime.parse(it, allDay) },
                tags = args.optStringList("tags") ?: emptyList(),
                parentId = args.optString("parent_id")?.takeIf { it.isNotBlank() },
            ),
        )
        ok(detailJson(todo, emptyList(), now, z))
    }

    private val todoUpdate = tool(
        name = "todo_update",
        title = "Update a todo",
        description = "Change fields of a todo; only the fields you pass are changed. notes replaces the whole text and tags replaces the whole list. " +
            "due takes a date or an ISO-8601 date-time with offset (see todo_create); due=\"\" removes the due date; " +
            "due_all_day=true on its own makes the current due an all-day due on its local date. " +
            "parent_id moves the todo under another top-level todo; parent_id=\"\" makes a subtask top-level (a todo that has subtasks cannot become a subtask). " +
            "status also works here, but todo_set_status is the direct way. Returns the updated todo.",
        schema = schema(
            "id" to str("Id of the todo."),
            "title" to str("New title."),
            "notes" to str("New notes (replaces the old text; \"\" clears them)."),
            "priority" to enum("New priority.", Priority.keys),
            "due" to str("New due: date (YYYY-MM-DD) or ISO-8601 date-time with offset; \"\" removes the due date."),
            "due_all_day" to bool("true = all-day due on the date of due (or of the current due if due is omitted); false = due must include a time."),
            "tags" to strArray("New complete tag list (replaces the old tags; [] clears them)."),
            "parent_id" to str("Id of the top-level todo to move under; \"\" detaches a subtask."),
            "status" to enum("New status.", TodoStatus.keys),
            required = listOf("id"),
        ),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    ) { args ->
        val z = zone()
        val now = clock()
        val id = args.string("id")
        val dueArg = args.optString("due")
        val allDay = args.optBoolean("due_all_day")
        val due: Change<Due> = when {
            dueArg != null && dueArg.isBlank() -> {
                if (allDay != null) throw ToolArgumentException("due_all_day cannot be combined with an empty due.")
                Change.Clear
            }
            dueArg != null -> Change.Set(DueTime.parse(dueArg, allDay))
            allDay == true -> {
                val cur = repository.get(id).due ?: throw ToolArgumentException("This todo has no due date, so due_all_day has nothing to apply to; pass due as well.")
                Change.Set(Due.Day(DueTime.localDate(cur, z)))
            }
            allDay == false -> {
                val cur = repository.get(id).due
                    ?: throw ToolArgumentException("This todo has no due date, so due_all_day has nothing to apply to; pass due as well.")
                if (cur.isAllDay) throw ToolArgumentException("due_all_day=false needs a time: pass due as an ISO-8601 date-time with UTC offset.")
                Change.Keep
            }
            else -> Change.Keep
        }
        val parentArg = args.optString("parent_id")
        val patch = TodoPatch(
            title = args.optString("title"),
            notes = args.optString("notes"),
            status = args.status("status"),
            priority = args.priority("priority"),
            due = due,
            tags = args.optStringList("tags"),
            parentId = when {
                parentArg == null -> Change.Keep
                parentArg.isBlank() -> Change.Clear
                else -> Change.Set(parentArg)
            },
        )
        if (patch.isEmpty && allDay == null) {
            throw ToolArgumentException("Nothing to update: pass at least one of title, notes, priority, due, tags, parent_id, status.")
        }
        val todo = repository.update(id, patch)
        ok(detailJson(todo, repository.subtasksOf(todo.id), now, z))
    }

    private val todoSetStatus = tool(
        name = "todo_set_status",
        title = "Set a todo's status",
        description = "Set the status of a todo: todo (not started), doing (in progress), done, or shelved (on hold). " +
            "Marking done records completed_at; moving a done todo to any other status clears it. Setting the status it already has changes nothing. " +
            "Completing a todo does not complete its subtasks. Returns the todo.",
        schema = schema(
            "id" to str("Id of the todo."),
            "status" to enum("New status.", TodoStatus.keys),
            required = listOf("id", "status"),
        ),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    ) { args ->
        val id = args.string("id")
        val status = args.status("status") ?: throw ToolArgumentException("Missing required argument: status")
        val todo = repository.setStatus(id, status)
        ok(detailJson(todo, repository.subtasksOf(todo.id), clock(), zone()))
    }

    private val todoDelete = tool(
        name = "todo_delete",
        title = "Delete a todo",
        description = "Permanently delete a todo. IRREVERSIBLE. If it has subtasks they are deleted with it; the result says how many todos were deleted in total. " +
            "To keep the record but stop working on it, use todo_set_status with done or shelved instead.",
        schema = schema("id" to str("Id of the todo."), required = listOf("id")),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false),
    ) { args ->
        val gone = repository.delete(args.string("id"))
        ok(
            buildJsonObject {
                put("deleted", gone.size)
                put("id", gone.first().id)
                put("title", gone.first().title)
                put("subtasks_deleted", gone.size - 1)
            },
        )
    }

    private val todoSearch = tool(
        name = "todo_search",
        title = "Search todos",
        description = "Case-insensitive search over todo titles, notes and tags; several words must all match (each may match anywhere). " +
            "Searches all statuses unless status is given (done todos included). Title matches come first; matched_in says where each todo matched (title / notes / tags). " +
            "Use it before todo_create to find an existing todo.",
        schema = schema(
            "query" to str("Text to look for."),
            "status" to enum("Only search todos in this status.", TodoStatus.keys),
            "limit" to int("Maximum number of results. Default $DEFAULT_SEARCH_LIMIT, maximum $MAX_SEARCH_LIMIT.", min = 1, max = MAX_SEARCH_LIMIT),
            required = listOf("query"),
        ),
        annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { args ->
        val z = zone()
        val now = clock()
        val query = args.string("query")
        if (query.isBlank()) throw ToolArgumentException("query must not be blank.")
        val limit = args.optInt("limit", 1, MAX_SEARCH_LIMIT) ?: DEFAULT_SEARCH_LIMIT
        val everything = repository.all()
        val hits = TodoQueries.search(everything, query, args.status("status"), z)
        val children = everything.filter { it.parentId != null }.groupBy { it.parentId }
        val page = hits.take(limit)
        val (items, cutBySize) = fitToBudget(
            page.map { hit ->
                buildJsonObject {
                    summaryJson(hit.todo, children[hit.todo.id].orEmpty(), now, z).forEach { (k, v) -> put(k, v) }
                    putJsonArray("matched_in") { hit.matchedIn.forEach { add(JsonPrimitive(it)) } }
                }
            },
        )
        ok(
            buildJsonObject {
                put("query", query.trim())
                put("results", JsonArray(items))
                put("total", hits.size)
                put("count", items.size)
                put("has_more", cutBySize || hits.size > page.size)
            },
        )
    }

    private val todoSummary = tool(
        name = "todo_summary",
        title = "Summarize todos",
        description = "A cheap overview of all todos (subtasks included): counts per status, and for open todos (status todo or doing) " +
            "overdue (its due time has passed; an all-day due is overdue from the next local day), due_today (due today and not yet overdue) and " +
            "due_this_week (due from today to the end of the current calendar week and not yet overdue, so it includes due_today). " +
            "Dates and the week use the phone's local time zone and its first day of the week; today, week_start, week_end and time_zone are returned so you can state the range. " +
            "Use todo_list with overdue_only, due_after or due_before for the items behind a number.",
        schema = schema(),
        annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { _ ->
        val z = zone()
        val s = TodoQueries.summary(repository.all(), clock(), z, week())
        ok(
            buildJsonObject {
                put("total", s.total)
                putJsonObject("counts") { TodoStatus.entries.forEach { put(it.key, s.counts.getValue(it)) } }
                put("overdue", s.overdue)
                put("due_today", s.dueToday)
                put("due_this_week", s.dueThisWeek)
                put("today", s.today.toString())
                put("week_start", s.weekStart.toString())
                put("week_end", s.weekEnd.toString())
                put("time_zone", z.id)
            },
        )
    }

    // ---------------------------------------------------------------- JSON 输出

    /** 列表 / 搜索里的一项（紧凑形式）：空字段不写，不含全文备注（只有前 200 字的预览），带子任务计数。 */
    private fun summaryJson(todo: Todo, children: List<Todo>, now: Long, z: ZoneId, withNotes: Boolean = true): JsonObject =
        buildJsonObject { putFields(todo, children, now, z, detail = false, withNotes = withNotes) }

    /** 一条待办的完整字段（todo_get / create / update / set_status / dump 共用，所以设备验收时 dump 和 MCP 返回可以直接对照）。 */
    fun detailJson(todo: Todo, children: List<Todo>, now: Long = clock(), z: ZoneId = zone()): JsonObject =
        buildJsonObject { putFields(todo, children, now, z, detail = true, withNotes = true) }

    private fun JsonObjectBuilder.putFields(todo: Todo, children: List<Todo>, now: Long, z: ZoneId, detail: Boolean, withNotes: Boolean) {
        put("id", todo.id)
        put("title", todo.title)
        put("status", todo.status.key)
        put("priority", todo.priority.key)
        val overdue = todo.status.isOpen && todo.due != null && DueTime.isOverdue(todo.due, now, z)
        if (detail) {
            // 完整形式：字段一个不少，空的写成 null / false / []（get / create / update / set_status / dump 共用）
            put("due", todo.due?.let { JsonPrimitive(DueTime.iso(it, z)) } ?: JsonNull)
            put("due_all_day", todo.due?.isAllDay ?: false)
            putJsonArray("tags") { todo.tags.forEach { add(JsonPrimitive(it)) } }
            put("parent_id", todo.parentId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("completed_at", todo.completedAt?.let { JsonPrimitive(DueTime.iso(it, z)) } ?: JsonNull)
            put("overdue", overdue)
        } else {
            // 列表 / 搜索里的紧凑形式：空的字段不写（没有 = 没有），一页能装更多条
            if (todo.due != null) {
                put("due", DueTime.iso(todo.due, z))
                put("due_all_day", todo.due.isAllDay)
            }
            if (todo.tags.isNotEmpty()) putJsonArray("tags") { todo.tags.forEach { add(JsonPrimitive(it)) } }
            if (todo.parentId != null) put("parent_id", todo.parentId)
            if (todo.completedAt != null) put("completed_at", DueTime.iso(todo.completedAt, z))
            if (overdue) put("overdue", true)
        }
        if (children.isNotEmpty()) {
            put("subtask_total", children.size)
            put("subtask_done", children.count { it.status == TodoStatus.DONE })
        }
        if (detail) {
            put("created_at", DueTime.iso(todo.createdAt, z))
            put("updated_at", DueTime.iso(todo.updatedAt, z))
            put("notes", todo.notes)
        } else if (withNotes && todo.notes.isNotEmpty()) {
            put("notes", preview(todo.notes))
            if (todo.notes.length > PREVIEW_CHARS) put("notes_truncated", true)
        }
    }

    private fun preview(text: String): String {
        if (text.length <= PREVIEW_CHARS) return text
        var end = PREVIEW_CHARS
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    /**
     * 总大小控制在 Binder 通道的单条消息上限（65,536 字符）之内：按真实的线上成本（见 [WireSize]）累加，
     * 超了就少返回几条，并让 has_more 为 true，调用方用 next_offset 接着取。
     */
    private fun fitToBudget(items: List<JsonObject>): Pair<List<JsonObject>, Boolean> {
        var cost = ENVELOPE_COST
        val kept = ArrayList<JsonObject>(items.size)
        for (item in items) {
            cost += WireSize.itemCost(item) + 4
            if (cost > WireSize.LIMIT && kept.isNotEmpty()) return kept to true
            kept += item
        }
        return kept to false
    }

    private fun ok(value: JsonElement): ToolOutput = ToolOutput.Ok(value)

    private fun Args.status(name: String): TodoStatus? {
        val key = optString(name)?.takeIf { it.isNotBlank() } ?: return null
        return TodoStatus.fromKey(key) ?: throw ToolArgumentException("$name must be one of: ${TodoStatus.keys.joinToString(", ")}.")
    }

    private fun Args.priority(name: String): Priority? {
        val key = optString(name)?.takeIf { it.isNotBlank() } ?: return null
        return Priority.fromKey(key) ?: throw ToolArgumentException("$name must be one of: ${Priority.keys.joinToString(", ")}.")
    }

    // ---------------------------------------------------------------- 定义工具的小工具

    private fun tool(
        name: String,
        title: String,
        description: String,
        schema: JsonObject,
        annotations: ToolAnnotations,
        body: suspend (Args) -> ToolOutput,
    ) = ToolDefinition(name, title, description, schema, annotations) { arguments ->
        try {
            body(Args(arguments))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolArgumentException) {
            ToolOutput.Error(e.message ?: "Invalid arguments.")
        } catch (e: TodoException) {
            ToolOutput.Error(e.message ?: "Operation failed.")
        } catch (e: Exception) {
            ToolOutput.Error("Unexpected error: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}")
        }
    }

    private fun schema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (name, def) -> put(name, def) } }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }

    private fun str(description: String) = buildJsonObject { put("type", "string"); put("description", description) }

    private fun bool(description: String) = buildJsonObject { put("type", "boolean"); put("description", description) }

    private fun int(description: String, min: Int? = null, max: Int? = null) = buildJsonObject {
        put("type", "integer")
        put("description", description)
        if (min != null) put("minimum", min)
        if (max != null) put("maximum", max)
    }

    private fun strArray(description: String) = buildJsonObject {
        put("type", "array")
        putJsonObject("items") { put("type", "string") }
        put("description", description)
    }

    private fun enum(description: String, values: List<String>) = buildJsonObject {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
        put("description", description)
    }

    companion object {
        const val DEFAULT_LIST_LIMIT = 50
        const val MAX_LIST_LIMIT = 200
        const val DEFAULT_SEARCH_LIMIT = 20
        const val MAX_SEARCH_LIMIT = 100
        const val PREVIEW_CHARS = 200

        /** 列表 / 搜索结果外层字段（total、offset、has_more……）占的成本。 */
        private const val ENVELOPE_COST = 1_500
    }
}

/**
 * 结果在 MCP 线上的真实成本（docs/sample-apps.md，C7a 的实际行为）：`McpToolResult.json(对象)` 把同一段紧凑 JSON
 * 在 `content`（作为被转义的字符串）和 `structuredContent` 里各放一份，单条消息上限 65,536 字符。
 * 成本 ≈ 2 × 长度 + 转义增量（引号、反斜杠、控制字符各多一个字符）+ 外层信封。
 */
internal object WireSize {
    const val CHANNEL_LIMIT = 65_536

    /** 留出余量后的上限。 */
    const val LIMIT = 60_000

    private const val ENVELOPE = 160

    /** 一项（数组里的一个元素）的成本，不含外层信封。 */
    fun itemCost(value: JsonObject): Int {
        val text = value.toString()
        var escapes = 0
        for (c in text) if (c == '"' || c == '\\' || c < ' ') escapes++
        return text.length * 2 + escapes
    }

    /** 整个结果对象的成本。 */
    fun cost(value: JsonObject): Int = itemCost(value) + ENVELOPE
}
