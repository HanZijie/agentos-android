package org.agentos.sample.notes.tools

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.agentos.sample.notes.data.MatchField
import org.agentos.sample.notes.data.Note
import org.agentos.sample.notes.data.NoteColor
import org.agentos.sample.notes.data.NoteDraft
import org.agentos.sample.notes.data.NoteException
import org.agentos.sample.notes.data.NotePatch
import org.agentos.sample.notes.data.NoteRepository
import org.agentos.sample.notes.data.NoteStatus
import org.agentos.sample.notes.data.NoteText

/**
 * 备忘录的 MCP 工具（docs/sample-apps.md 4.3）。只依赖仓库和 kotlinx-serialization-json，
 * 不依赖 plugin-sdk，所以 JVM 测试里可以直接调 [ToolDefinition.handler]。
 * 描述写给模型看，用英文；错误一律是一句话原因。时间是带时区偏移的 ISO-8601。
 */
class NotesTools(
    private val repository: NoteRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    val all: List<ToolDefinition> by lazy {
        listOf(noteList, noteGet, noteCreate, noteUpdate, noteAppend, noteSearch, noteTrash, noteRestore, noteDelete, tagList)
    }

    fun find(name: String): ToolDefinition? = all.firstOrNull { it.name == name }

    /** 直接按名字调用（测试和 debug 自测用）；未知工具返回错误。 */
    suspend fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): ToolOutput =
        find(name)?.handler?.invoke(arguments) ?: ToolOutput.Error("Unknown tool: $name")

    // ---------------------------------------------------------------- 工具

    private val noteList = tool(
        name = "note_list",
        title = "List notes",
        description = "List notes, pinned first and then most recently updated first. By default only active notes are returned; " +
            "set archived=true for the archive or trashed=true for the trash (not both). Each item carries a summary (the first 200 characters " +
            "of the body) instead of the full text; use note_get to read a whole note. Page with limit/offset; has_more says whether more notes follow.",
        schema = schema(
            "tag" to str("Only notes carrying this tag (case-insensitive)."),
            "pinned" to bool("Only pinned (true) or only unpinned (false) notes."),
            "archived" to bool("List archived notes instead of active ones. Default false."),
            "trashed" to bool("List notes in the trash instead of active ones. Default false."),
            "limit" to int("Maximum number of notes to return. Default 50, maximum 200.", min = 1, max = MAX_LIST_LIMIT),
            "offset" to int("Number of notes to skip, for paging. Default 0.", min = 0),
        ),
        annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { args ->
        val tag = args.optString("tag")
        val pinned = args.optBoolean("pinned")
        val archived = args.optBoolean("archived") ?: false
        val trashed = args.optBoolean("trashed") ?: false
        val limit = args.optInt("limit", 1, MAX_LIST_LIMIT) ?: DEFAULT_LIST_LIMIT
        val offset = args.optInt("offset", 0, Int.MAX_VALUE) ?: 0
        if (archived && trashed) throw ToolArgumentException("archived and trashed cannot both be true.")
        val status = if (trashed) NoteStatus.TRASHED else if (archived) NoteStatus.ARCHIVED else NoteStatus.ACTIVE
        val matches = repository.list(status, tag, pinned)
        val page = matches.drop(offset).take(limit)
        val (items, cutBySize) = fitToBudget(page.map { summaryJson(it) })
        val returned = items.size
        ok(
            buildJsonObject {
                put("notes", JsonArray(items))
                put("total", matches.size)
                put("offset", offset)
                put("limit", limit)
                put("count", returned)
                val hasMore = cutBySize || offset + page.size < matches.size
                put("has_more", hasMore)
                if (hasMore) put("next_offset", offset + returned)
            },
        )
    }

    private val noteGet = tool(
        name = "note_get",
        title = "Get a note",
        description = "Get one note with its full Markdown body. Very long bodies are returned in slices: when truncated is true, " +
            "call again with offset set to next_offset to read on.",
        schema = schema(
            "id" to str("Id of the note."),
            "offset" to int("Character offset to start the body from. Default 0.", min = 0),
            "max_chars" to int("Maximum number of body characters to return. Default $DEFAULT_GET_CHARS, maximum $MAX_GET_CHARS.", min = 1, max = MAX_GET_CHARS),
            required = listOf("id"),
        ),
        annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { args ->
        val note = repository.get(args.string("id"))
        val offset = (args.optInt("offset", 0, Int.MAX_VALUE) ?: 0).coerceAtMost(note.content.length)
        // 结果会在 content 和 structuredContent 里各出现一次，且正文里的换行 / 引号会被转义：
        // 按真实的线上成本把切片缩到装得下为止，剩下的由 next_offset 接着读
        fun slice(maxChars: Int): JsonObject {
            var end = (offset + maxChars).coerceAtMost(note.content.length)
            if (end < note.content.length && end > offset && Character.isHighSurrogate(note.content[end - 1])) end--
            val truncated = end < note.content.length
            return buildJsonObject {
                putNoteFields(note)
                put("content", note.content.substring(offset, end))
                put("content_offset", offset)
                put("truncated", truncated)
                if (truncated) put("next_offset", end)
            }
        }
        var maxChars = args.optInt("max_chars", 1, MAX_GET_CHARS) ?: DEFAULT_GET_CHARS
        var result = slice(maxChars)
        while (WireSize.cost(result) > WireSize.LIMIT && maxChars > MIN_GET_CHARS) {
            maxChars = maxOf(MIN_GET_CHARS, maxChars * 3 / 4)
            result = slice(maxChars)
        }
        ok(result)
    }

    private val noteCreate = tool(
        name = "note_create",
        title = "Create a note",
        description = "Create a note from Markdown text. Before creating, consider note_search: if a note on the same topic exists, " +
            "prefer note_append or note_update instead of making a duplicate. title defaults to the first line of content. " +
            "tags is a list of short labels (no '#'); color is one of: ${NoteColor.keys.joinToString(", ")}. Returns the created note (summary form).",
        schema = schema(
            "content" to str("The note body in Markdown."),
            "title" to str("Note title. Defaults to the first line of content."),
            "tags" to strArray("Tags for the note, e.g. [\"work\", \"ideas\"]. At most ${org.agentos.sample.notes.data.NoteLimits.MAX_TAGS}."),
            "color" to enum("Card color.", NoteColor.keys),
            "pinned" to bool("Pin the note to the top of the list. Default false."),
            required = listOf("content"),
        ),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
    ) { args ->
        val content = args.string("content")
        val title = args.optString("title")?.takeIf { it.isNotBlank() } ?: NoteText.deriveTitle(content)
        val note = repository.create(
            NoteDraft(
                title = title,
                content = content,
                tags = args.optStringList("tags") ?: emptyList(),
                color = args.color() ?: NoteColor.DEFAULT,
                pinned = args.optBoolean("pinned") ?: false,
            ),
        )
        ok(summaryJson(note))
    }

    private val noteUpdate = tool(
        name = "note_update",
        title = "Update a note",
        description = "Change fields of an existing note; only the fields you pass are changed. content replaces the whole body " +
            "(use note_append to add text at the end instead). tags replaces the whole tag list. archived=true moves the note to the archive, " +
            "archived=false brings it back. Notes in the trash cannot be updated; call note_restore first. Returns the updated note (summary form).",
        schema = schema(
            "id" to str("Id of the note."),
            "title" to str("New title."),
            "content" to str("New Markdown body (replaces the existing body)."),
            "tags" to strArray("New complete tag list (replaces the existing tags; [] clears them)."),
            "color" to enum("New card color.", NoteColor.keys),
            "pinned" to bool("Pin or unpin the note."),
            "archived" to bool("true = move to the archive, false = move back to the active list."),
            required = listOf("id"),
        ),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    ) { args ->
        val id = args.string("id")
        val patch = NotePatch(
            title = args.optString("title"),
            content = args.optString("content"),
            tags = args.optStringList("tags"),
            color = args.color(),
            pinned = args.optBoolean("pinned"),
            archived = args.optBoolean("archived"),
        )
        if (patch.isEmpty) {
            throw ToolArgumentException("Nothing to update: pass at least one of title, content, tags, color, pinned, archived.")
        }
        ok(summaryJson(repository.update(id, patch)))
    }

    private val noteAppend = tool(
        name = "note_append",
        title = "Append text to a note",
        description = "Append text to the end of a note's body, joined with separator (default a single newline). " +
            "Use this to add to an existing note (for example a running log or a checklist item) instead of creating a duplicate. " +
            "Returns the note (summary form) and appended_chars.",
        schema = schema(
            "id" to str("Id of the note."),
            "text" to str("Markdown text to append."),
            "separator" to str("Text inserted between the existing body and the new text. Default \"\\n\"."),
            required = listOf("id", "text"),
        ),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
    ) { args ->
        val id = args.string("id")
        val text = args.string("text")
        val note = repository.append(id, text, args.optString("separator") ?: "\n")
        ok(
            buildJsonObject {
                putNoteFields(note, summary = true)
                put("appended_chars", text.length)
            },
        )
    }

    private val noteSearch = tool(
        name = "note_search",
        title = "Search notes",
        description = "Case-insensitive search over note titles, bodies and tags. Several words must all match (each may match anywhere). " +
            "Archived notes are included; the trash is excluded unless include_trashed is true. Each result has a snippet of the matching body text " +
            "and matched_in (title / tag / content); best matches first. Use this before creating a note to find an existing one.",
        schema = schema(
            "query" to str("Text to look for."),
            "tag" to str("Only search notes with this tag."),
            "limit" to int("Maximum number of results. Default $DEFAULT_SEARCH_LIMIT, maximum $MAX_SEARCH_LIMIT.", min = 1, max = MAX_SEARCH_LIMIT),
            "include_trashed" to bool("Also search notes in the trash. Default false."),
            required = listOf("query"),
        ),
        annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { args ->
        val query = args.string("query")
        if (query.isBlank()) throw ToolArgumentException("query must not be blank.")
        val limit = args.optInt("limit", 1, MAX_SEARCH_LIMIT) ?: DEFAULT_SEARCH_LIMIT
        val hits = repository.search(query, args.optString("tag"), args.optBoolean("include_trashed") ?: false)
        val page = hits.take(limit)
        val (items, cutBySize) = fitToBudget(
            page.map { hit ->
                buildJsonObject {
                    put("id", hit.note.id)
                    put("title", hit.note.displayTitle)
                    putJsonArray("tags") { hit.note.tags.forEach { add(JsonPrimitive(it)) } }
                    put("pinned", hit.note.pinned)
                    put("archived", hit.note.isArchived)
                    put("trashed", hit.note.isTrashed)
                    put("snippet", hit.snippet.text)
                    putJsonArray("matched_in") {
                        hit.matchedIn.forEach { add(JsonPrimitive(it.name.lowercase())) }
                    }
                    put("updated_at", iso(hit.note.updatedAt))
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

    private val noteTrash = tool(
        name = "note_trash",
        title = "Move a note to the trash",
        description = "Move a note to the trash. This is reversible: use note_restore to bring it back. " +
            "The note is only removed for good by note_delete, which works on notes in the trash only. Returns the note (summary form).",
        schema = schema("id" to str("Id of the note."), required = listOf("id")),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
    ) { args ->
        ok(summaryJson(repository.trash(args.string("id"))))
    }

    private val noteRestore = tool(
        name = "note_restore",
        title = "Restore a note",
        description = "Bring a note back to the active list from the trash or from the archive. " +
            "Fails if the note is already active. Returns the note (summary form).",
        schema = schema("id" to str("Id of the note."), required = listOf("id")),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
    ) { args ->
        ok(summaryJson(repository.restore(args.string("id"))))
    }

    private val noteDelete = tool(
        name = "note_delete",
        title = "Delete a note permanently",
        description = "Permanently delete a note. IRREVERSIBLE. Only notes that are already in the trash can be deleted this way; " +
            "for any other note call note_trash first (and only delete when the user really wants the note gone for good).",
        schema = schema("id" to str("Id of a note that is in the trash."), required = listOf("id")),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false),
    ) { args ->
        val note = repository.deletePermanently(args.string("id"))
        ok(
            buildJsonObject {
                put("deleted", true)
                put("id", note.id)
                put("title", note.displayTitle)
            },
        )
    }

    private val tagList = tool(
        name = "tag_list",
        title = "List tags",
        description = "List all tags in use with the number of notes carrying each (notes in the trash are not counted), most used first. " +
            "Reuse existing tags when tagging notes.",
        schema = schema(),
        annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { _ ->
        val tags = repository.tags()
        ok(
            buildJsonObject {
                putJsonArray("tags") {
                    tags.forEach { tag ->
                        add(buildJsonObject { put("name", tag.name); put("count", tag.count) })
                    }
                }
                put("total", tags.size)
            },
        )
    }

    // ---------------------------------------------------------------- JSON 输出

    private fun iso(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone()).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun summaryJson(note: Note): JsonObject = noteJson(note, summary = true)

    /** 一条备忘录的 JSON（字段与 note_get / note_list 一致，不含正文；[summary] 时带 200 字摘要）。debug 的 dump 也用它，保证两边字段相同。 */
    fun noteJson(note: Note, summary: Boolean = false): JsonObject = buildJsonObject { putNoteFields(note, summary) }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNoteFields(note: Note, summary: Boolean = false) {
        put("id", note.id)
        put("title", note.displayTitle)
        if (summary) put("summary", NoteText.summary(note.content, SUMMARY_CHARS))
        putJsonArray("tags") { note.tags.forEach { add(JsonPrimitive(it)) } }
        put("color", note.color.key)
        put("pinned", note.pinned)
        put("archived", note.isArchived)
        put("trashed", note.isTrashed)
        put("created_at", iso(note.createdAt))
        put("updated_at", iso(note.updatedAt))
        if (note.trashedAt != null) put("trashed_at", iso(note.trashedAt))
        put("content_length", note.content.length)
    }

    /**
     * 总大小控制在 Binder 通道的单条消息上限（65,536 字符）之内：按真实的线上成本（见 [WireSize]）累加，
     * 超了就少返回几条，并让 has_more 为 true，调用方用 next_offset 接着取。
     */
    private fun fitToBudget(items: List<JsonObject>): Pair<List<JsonObject>, Boolean> {
        var cost = ENVELOPE_COST
        val kept = ArrayList<JsonObject>(items.size)
        for (item in items) {
            cost += WireSize.cost(item) + 2
            if (cost > WireSize.LIMIT && kept.isNotEmpty()) return kept to true
            kept += item
        }
        return kept to false
    }

    private fun ok(value: JsonElement): ToolOutput = ToolOutput.Ok(value)

    private fun Args.color(): NoteColor? {
        val key = optString("color") ?: return null
        return NoteColor.fromKey(key)
            ?: throw ToolArgumentException("color must be one of: ${NoteColor.keys.joinToString(", ")}.")
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
        } catch (e: NoteException) {
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
        const val DEFAULT_GET_CHARS = 12_000
        const val MAX_GET_CHARS = 20_000
        const val MIN_GET_CHARS = 200
        const val SUMMARY_CHARS = 200

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

    fun cost(value: JsonObject): Int {
        val text = value.toString()
        var escapes = 0
        for (c in text) if (c == '"' || c == '\\' || c < ' ') escapes++
        return text.length * 2 + escapes + ENVELOPE
    }
}
