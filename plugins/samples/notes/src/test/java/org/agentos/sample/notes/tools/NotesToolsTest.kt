package org.agentos.sample.notes.tools

import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.sample.notes.TestEnv
import org.agentos.sample.notes.data.NoteDraft
import org.agentos.sample.notes.data.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesToolsTest {
    private val env = TestEnv()
    private val tools = NotesTools(env.repo, zone = { ZoneOffset.ofHours(8) })

    private suspend fun ok(name: String, args: JsonObject = JsonObject(emptyMap())): JsonObject {
        val out = tools.call(name, args)
        check(out is ToolOutput.Ok) { "$name failed: ${(out as ToolOutput.Error).message}" }
        return out.value.jsonObject
    }

    private suspend fun err(name: String, args: JsonObject = JsonObject(emptyMap())): String {
        val out = tools.call(name, args)
        check(out is ToolOutput.Error) { "$name unexpectedly succeeded: $out" }
        assertFalse("error must be one line", out.message.contains('\n'))
        assertTrue("error must say something", out.message.isNotBlank())
        return out.message
    }

    private fun args(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)

    private suspend fun seed(title: String, content: String = "body of $title", tags: List<String> = emptyList()) =
        env.repo.create(NoteDraft(title = title, content = content, tags = tags))

    // ---------------------------------------------------------------- 目录

    @Test fun `exposes exactly the contracted tools with accurate annotations`() {
        val byName = tools.all.associateBy { it.name }
        assertEquals(
            setOf("note_list", "note_get", "note_create", "note_update", "note_append", "note_search", "note_trash", "note_restore", "note_delete", "tag_list"),
            byName.keys,
        )
        for (name in listOf("note_list", "note_get", "note_search", "tag_list")) {
            assertEquals(name, true, byName.getValue(name).annotations.readOnlyHint)
        }
        for (name in listOf("note_create", "note_update", "note_append", "note_trash", "note_restore", "note_delete")) {
            assertEquals(name, false, byName.getValue(name).annotations.readOnlyHint)
        }
        assertEquals(true, byName.getValue("note_delete").annotations.destructiveHint)
        for (name in byName.keys - "note_delete") {
            assertTrue(name, byName.getValue(name).annotations.destructiveHint != true)
        }
        assertEquals(true, byName.getValue("note_update").annotations.idempotentHint)
    }

    @Test fun `required parameters match the contract and schemas are objects`() {
        val required = mapOf(
            "note_list" to emptySet(), "note_get" to setOf("id"), "note_create" to setOf("content"),
            "note_update" to setOf("id"), "note_append" to setOf("id", "text"), "note_search" to setOf("query"),
            "note_trash" to setOf("id"), "note_restore" to setOf("id"), "note_delete" to setOf("id"), "tag_list" to emptySet<String>(),
        )
        for (tool in tools.all) {
            assertEquals(tool.name, "object", tool.inputSchema["type"]!!.jsonPrimitive.content)
            val req = tool.inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
            assertEquals(tool.name, required.getValue(tool.name), req)
            // 契约里列出的可选参数都在 properties 里
            assertTrue(tool.name, tool.description.length in 40..900)
            assertTrue(tool.name, tool.name.matches(Regex("[a-z]+(_[a-z]+)+")))
        }
        val listProps = tools.find("note_list")!!.inputSchema["properties"]!!.jsonObject.keys
        assertTrue(listProps.containsAll(listOf("tag", "pinned", "archived", "trashed", "limit", "offset")))
        assertTrue(tools.find("note_create")!!.inputSchema["properties"]!!.jsonObject.keys.containsAll(listOf("content", "title", "tags", "color", "pinned")))
        assertTrue(tools.find("note_update")!!.inputSchema["properties"]!!.jsonObject.keys.containsAll(listOf("id", "title", "content", "tags", "color", "pinned", "archived")))
        assertTrue(tools.find("note_append")!!.inputSchema["properties"]!!.jsonObject.keys.containsAll(listOf("id", "text", "separator")))
        assertTrue(tools.find("note_search")!!.inputSchema["properties"]!!.jsonObject.keys.containsAll(listOf("query", "tag", "limit")))
    }

    @Test fun `unknown tool is an error`() = runTest {
        assertEquals("Unknown tool: nope", err("nope"))
    }

    // ---------------------------------------------------------------- note_create

    @Test fun `note_create makes a note with derived title and iso timestamps`() = runTest {
        val r = ok("note_create", args { put("content", "# Shopping\n- milk\n- eggs"); put("pinned", true) })
        assertEquals("Shopping", r["title"]!!.jsonPrimitive.content)
        assertEquals(true, r["pinned"]!!.jsonPrimitive.boolean)
        assertEquals("default", r["color"]!!.jsonPrimitive.content)
        assertEquals("# Shopping - milk - eggs", r["summary"]!!.jsonPrimitive.content)
        // 1_800_000_000_s = 2027-01-15T08:00:00Z，加 +08:00 偏移，秒级精度
        assertTrue(r["created_at"]!!.jsonPrimitive.content, r["created_at"]!!.jsonPrimitive.content.matches(Regex("""2027-01-15T16:00:0\d\+08:00""")))
        assertEquals(1, env.repo.notes.value.size)
        assertEquals("# Shopping\n- milk\n- eggs", env.repo.notes.value.single().content)
    }

    @Test fun `note_create with all options`() = runTest {
        val r = ok(
            "note_create",
            args {
                put("content", "text"); put("title", "Mine"); put("color", "Teal")
                put("tags", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("a")); add(kotlinx.serialization.json.JsonPrimitive("#b")) })
            },
        )
        assertEquals("Mine", r["title"]!!.jsonPrimitive.content)
        assertEquals("teal", r["color"]!!.jsonPrimitive.content)
        assertEquals(listOf("a", "b"), r["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `note_create validation`() = runTest {
        assertTrue(err("note_create").contains("content"))
        assertTrue(err("note_create", args { put("content", "   ") }).contains("title or some content"))
        assertTrue(err("note_create", args { put("content", "x"); put("color", "magenta") }).contains("color must be one of"))
        assertTrue(err("note_create", args { put("content", "x"); put("tags", "not-an-array") }).contains("tags"))
        assertTrue(err("note_create", args { put("content", "x"); put("tags", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(5)) }) }).contains("tags"))
        assertTrue(err("note_create", args { put("content", "x"); put("pinned", "maybe") }).contains("pinned"))
        assertTrue(err("note_create", args { put("content", "x"); put("title", "t".repeat(500)) }).contains("title"))
        assertTrue(env.repo.notes.value.isEmpty())
    }

    // ---------------------------------------------------------------- note_get

    @Test fun `note_get returns the full body`() = runTest {
        val n = seed("A", "full **markdown** body", listOf("t"))
        val r = ok("note_get", args { put("id", n.id) })
        assertEquals("full **markdown** body", r["content"]!!.jsonPrimitive.content)
        assertEquals(false, r["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(n.content.length, r["content_length"]!!.jsonPrimitive.int)
        assertEquals(false, r["archived"]!!.jsonPrimitive.boolean)
        assertEquals(false, r["trashed"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("t"), r["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `note_get pages long bodies`() = runTest {
        val n = seed("Long", "x".repeat(50_000))
        val first = ok("note_get", args { put("id", n.id); put("max_chars", 30_000) })
        assertEquals(30_000, first["content"]!!.jsonPrimitive.content.length)
        assertEquals(true, first["truncated"]!!.jsonPrimitive.boolean)
        val next = first["next_offset"]!!.jsonPrimitive.int
        val second = ok("note_get", args { put("id", n.id); put("offset", next) })
        assertEquals(20_000, second["content"]!!.jsonPrimitive.content.length)
        assertEquals(false, second["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun `note_get validation`() = runTest {
        assertTrue(err("note_get").contains("Missing required argument: id"))
        assertTrue(err("note_get", args { put("id", "missing") }).contains("No note with id"))
        val n = seed("A")
        assertTrue(err("note_get", args { put("id", n.id); put("offset", -1) }).contains("offset"))
        assertTrue(err("note_get", args { put("id", n.id); put("max_chars", "lots") }).contains("max_chars"))
    }

    // ---------------------------------------------------------------- note_list

    @Test fun `note_list returns summaries ordered with has_more`() = runTest {
        val long = "字".repeat(500)
        val a = seed("a", long)
        val b = seed("b")
        val c = seed("c")
        env.repo.update(b.id, org.agentos.sample.notes.data.NotePatch(pinned = true))
        val r = ok("note_list", args { put("limit", 2) })
        val ids = r["notes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf(b.id, c.id), ids)
        assertEquals(true, r["has_more"]!!.jsonPrimitive.boolean)
        assertEquals(3, r["total"]!!.jsonPrimitive.int)
        assertEquals(2, r["next_offset"]!!.jsonPrimitive.int)
        val page2 = ok("note_list", args { put("limit", 2); put("offset", 2) })
        assertEquals(listOf(a.id), page2["notes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(false, page2["has_more"]!!.jsonPrimitive.boolean)
        val summary = page2["notes"]!!.jsonArray[0].jsonObject["summary"]!!.jsonPrimitive.content
        assertEquals(200, summary.length)
        assertTrue(page2["notes"]!!.jsonArray[0].jsonObject["content"] == null)
    }

    @Test fun `note_list filters by tag pinned archived and trashed`() = runTest {
        val a = seed("a", tags = listOf("work"))
        val b = seed("b")
        val c = seed("c")
        env.repo.update(b.id, org.agentos.sample.notes.data.NotePatch(archived = true))
        env.repo.trash(c.id)
        fun idsOf(o: JsonObject) = o["notes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf(a.id), idsOf(ok("note_list")))
        assertEquals(listOf(a.id), idsOf(ok("note_list", args { put("tag", "WORK") })))
        assertEquals(emptyList<String>(), idsOf(ok("note_list", args { put("tag", "none") })))
        assertEquals(listOf(b.id), idsOf(ok("note_list", args { put("archived", true) })))
        assertEquals(listOf(c.id), idsOf(ok("note_list", args { put("trashed", true) })))
        assertEquals(emptyList<String>(), idsOf(ok("note_list", args { put("pinned", true) })))
    }

    @Test fun `note_list limit is capped and validated`() = runTest {
        repeat(3) { seed("n$it") }
        assertEquals(200, ok("note_list", args { put("limit", 5000) })["limit"]!!.jsonPrimitive.int)
        assertEquals(50, ok("note_list")["limit"]!!.jsonPrimitive.int)
        assertTrue(err("note_list", args { put("limit", 0) }).contains("limit"))
        assertTrue(err("note_list", args { put("limit", "many") }).contains("limit"))
        assertTrue(err("note_list", args { put("offset", -3) }).contains("offset"))
        assertTrue(err("note_list", args { put("archived", true); put("trashed", true) }).contains("both"))
        assertTrue(err("note_list", args { put("archived", "perhaps") }).contains("archived"))
    }

    @Test fun `note_list stays under the binder message budget`() = runTest {
        repeat(200) { seed("note $it", "字".repeat(1000)) }
        val out = tools.call("note_list", args { put("limit", 200) }) as ToolOutput.Ok
        val bytes = out.value.toString().toByteArray().size
        assertTrue("size $bytes", bytes < 110_000)
        val r = out.value.jsonObject
        assertTrue(r["notes"]!!.jsonArray.size < 200)
        assertEquals(true, r["has_more"]!!.jsonPrimitive.boolean)
        // next_offset 接着读，最终能读完全部
        var offset = 0
        var seen = 0
        while (true) {
            val page = ok("note_list", args { put("limit", 200); put("offset", offset) })
            seen += page["notes"]!!.jsonArray.size
            if (!page["has_more"]!!.jsonPrimitive.boolean) break
            offset = page["next_offset"]!!.jsonPrimitive.int
        }
        assertEquals(200, seen)
    }

    // ---------------------------------------------------------------- note_update

    @Test fun `note_update changes only given fields`() = runTest {
        val n = seed("A", "one", listOf("x"))
        val r = ok("note_update", args { put("id", n.id); put("content", "two"); put("color", "red") })
        assertEquals("A", r["title"]!!.jsonPrimitive.content)
        assertEquals("red", r["color"]!!.jsonPrimitive.content)
        assertEquals(listOf("x"), r["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        val cleared = ok("note_update", args { put("id", n.id); put("tags", JsonArray(emptyList())) })
        assertEquals(0, cleared["tags"]!!.jsonArray.size)
        assertEquals("two", env.repo.get(n.id).content)
    }

    @Test fun `note_update archives and unarchives`() = runTest {
        val n = seed("A")
        assertEquals(true, ok("note_update", args { put("id", n.id); put("archived", true) })["archived"]!!.jsonPrimitive.boolean)
        assertEquals(NoteStatus.ARCHIVED, env.repo.get(n.id).status)
        assertEquals(false, ok("note_update", args { put("id", n.id); put("archived", false) })["archived"]!!.jsonPrimitive.boolean)
    }

    @Test fun `note_update is idempotent`() = runTest {
        val n = seed("A", "one")
        val a = ok("note_update", args { put("id", n.id); put("content", "two") })
        val b = ok("note_update", args { put("id", n.id); put("content", "two") })
        assertEquals(a, b)
    }

    @Test fun `note_update validation`() = runTest {
        val n = seed("A")
        assertTrue(err("note_update").contains("Missing required argument: id"))
        assertTrue(err("note_update", args { put("id", n.id) }).contains("Nothing to update"))
        assertTrue(err("note_update", args { put("id", "missing"); put("title", "x") }).contains("No note with id"))
        assertTrue(err("note_update", args { put("id", n.id); put("color", "plaid") }).contains("color"))
        assertTrue(err("note_update", args { put("id", n.id); put("tags", "x") }).contains("tags"))
        assertTrue(err("note_update", args { put("id", n.id); put("tags", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("a,b")) }) }).contains("Tag"))
        assertTrue(err("note_update", args { put("id", n.id); put("content", "x".repeat(200_001)) }).contains("content"))
        env.repo.trash(n.id)
        assertTrue(err("note_update", args { put("id", n.id); put("title", "x") }).contains("note_restore"))
    }

    // ---------------------------------------------------------------- note_append

    @Test fun `note_append adds text and reports the size`() = runTest {
        val n = seed("Log", "first")
        val r = ok("note_append", args { put("id", n.id); put("text", "second") })
        assertEquals(5 + 1 + 6, r["content_length"]!!.jsonPrimitive.int)
        assertEquals(6, r["appended_chars"]!!.jsonPrimitive.int)
        assertEquals("first\nsecond", env.repo.get(n.id).content)
        ok("note_append", args { put("id", n.id); put("text", "- [ ] todo"); put("separator", "\n\n") })
        assertEquals("first\nsecond\n\n- [ ] todo", env.repo.get(n.id).content)
    }

    @Test fun `note_append validation`() = runTest {
        val n = seed("Log")
        assertTrue(err("note_append", args { put("text", "x") }).contains("Missing required argument: id"))
        assertTrue(err("note_append", args { put("id", n.id) }).contains("Missing required argument: text"))
        assertTrue(err("note_append", args { put("id", n.id); put("text", "") }).contains("text"))
        assertTrue(err("note_append", args { put("id", "missing"); put("text", "x") }).contains("No note with id"))
        env.repo.trash(n.id)
        assertTrue(err("note_append", args { put("id", n.id); put("text", "x") }).contains("note_restore"))
    }

    // ---------------------------------------------------------------- note_search

    @Test fun `note_search returns snippets and match fields`() = runTest {
        seed("Trip", "book the flight to Lisbon for March", listOf("travel"))
        seed("Other", "nothing relevant")
        val r = ok("note_search", args { put("query", "lisbon") })
        val hit = r["results"]!!.jsonArray.single().jsonObject
        assertEquals("Trip", hit["title"]!!.jsonPrimitive.content)
        assertTrue(hit["snippet"]!!.jsonPrimitive.content.contains("Lisbon"))
        assertEquals(listOf("content"), hit["matched_in"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, r["total"]!!.jsonPrimitive.int)
        val byTag = ok("note_search", args { put("query", "travel") })["results"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("tag"), byTag["matched_in"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `note_search respects tag limit and trash`() = runTest {
        val a = seed("a needle", tags = listOf("x"))
        seed("b needle")
        val gone = seed("c needle")
        env.repo.trash(gone.id)
        assertEquals(2, ok("note_search", args { put("query", "needle") })["total"]!!.jsonPrimitive.int)
        assertEquals(3, ok("note_search", args { put("query", "needle"); put("include_trashed", true) })["total"]!!.jsonPrimitive.int)
        val tagged = ok("note_search", args { put("query", "needle"); put("tag", "x") })["results"]!!.jsonArray
        assertEquals(listOf(a.id), tagged.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val limited = ok("note_search", args { put("query", "needle"); put("limit", 1) })
        assertEquals(1, limited["count"]!!.jsonPrimitive.int)
        assertEquals(true, limited["has_more"]!!.jsonPrimitive.boolean)
    }

    @Test fun `note_search validation`() = runTest {
        assertTrue(err("note_search").contains("Missing required argument: query"))
        assertTrue(err("note_search", args { put("query", "  ") }).contains("blank"))
        assertTrue(err("note_search", args { put("query", "x"); put("limit", 0) }).contains("limit"))
        assertEquals(0, ok("note_search", args { put("query", "zzz") })["total"]!!.jsonPrimitive.int)
    }

    // ---------------------------------------------------------------- trash / restore / delete

    @Test fun `trash restore and delete go through the safe path`() = runTest {
        val n = seed("A")
        // 不在回收站的不能直接永久删除
        assertTrue(err("note_delete", args { put("id", n.id) }).contains("note_trash"))
        assertNotNull(env.store.rows[n.id])

        val trashed = ok("note_trash", args { put("id", n.id) })
        assertEquals(true, trashed["trashed"]!!.jsonPrimitive.boolean)
        assertNotNull(trashed["trashed_at"])
        assertTrue(err("note_trash", args { put("id", n.id) }).contains("already in the trash"))

        val restored = ok("note_restore", args { put("id", n.id) })
        assertEquals(false, restored["trashed"]!!.jsonPrimitive.boolean)
        assertTrue(err("note_restore", args { put("id", n.id) }).contains("nothing to restore"))

        ok("note_trash", args { put("id", n.id) })
        val deleted = ok("note_delete", args { put("id", n.id) })
        assertEquals(true, deleted["deleted"]!!.jsonPrimitive.boolean)
        assertEquals(null, env.store.rows[n.id])
        assertTrue(err("note_get", args { put("id", n.id) }).contains("No note with id"))
    }

    @Test fun `restore also works from the archive`() = runTest {
        val n = seed("A")
        ok("note_update", args { put("id", n.id); put("archived", true) })
        assertEquals(false, ok("note_restore", args { put("id", n.id) })["archived"]!!.jsonPrimitive.boolean)
    }

    @Test fun `trash restore delete validation`() = runTest {
        for (name in listOf("note_trash", "note_restore", "note_delete")) {
            assertTrue(name, err(name).contains("Missing required argument: id"))
            assertTrue(name, err(name, args { put("id", "missing") }).contains("No note with id"))
            assertTrue(name, err(name, args { put("id", JsonArray(emptyList())) }).contains("id"))
        }
    }

    // ---------------------------------------------------------------- tag_list

    @Test fun `tag_list counts notes per tag`() = runTest {
        seed("1", tags = listOf("a", "b"))
        seed("2", tags = listOf("a"))
        val gone = seed("3", tags = listOf("c"))
        env.repo.trash(gone.id)
        val r = ok("tag_list")
        val tags = r["tags"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["count"]!!.jsonPrimitive.int }
        assertEquals(listOf("a" to 2, "b" to 1), tags)
        assertEquals(2, r["total"]!!.jsonPrimitive.int)
    }

    @Test fun `tool errors never throw for odd argument shapes`() = runTest {
        // 对每个工具传各种怪参数：只能得到 Ok 或 Error，不能抛异常
        val weird = listOf(
            JsonObject(emptyMap()),
            args { put("id", 42) },
            args { put("id", "x"); put("limit", 1.5) },
            args { put("query", "x"); put("tag", JsonArray(emptyList())) },
            args { put("content", JsonArray(emptyList())) },
        )
        for (tool in tools.all) for (a in weird) {
            val out = tools.call(tool.name, a)
            assertTrue(tool.name, out is ToolOutput.Ok || out is ToolOutput.Error)
        }
    }
}
