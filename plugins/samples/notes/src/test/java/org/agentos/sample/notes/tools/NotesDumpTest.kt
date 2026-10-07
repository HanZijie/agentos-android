package org.agentos.sample.notes.tools

import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.sample.notes.TestEnv
import org.agentos.sample.notes.data.NoteDraft
import org.agentos.sample.notes.data.NotePatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** debug 的 dump / reset 背后的纯逻辑：只读、含回收站和归档、字段与 MCP 一致、分页、clearAll。 */
class NotesDumpTest {
    private val env = TestEnv()
    private val tools = NotesTools(env.repo, zone = { ZoneOffset.ofHours(8) })

    private suspend fun seed() {
        env.repo.create(NoteDraft(title = "active", content = "# a\n- [ ] x", tags = listOf("work", "Home"), pinned = true))
        val archived = env.repo.create(NoteDraft(title = "archived", content = "b", tags = listOf("work")))
        env.repo.update(archived.id, NotePatch(archived = true))
        val trashed = env.repo.create(NoteDraft(title = "trashed", content = "c\nwith \"quotes\" and 中文"))
        env.repo.trash(trashed.id)
    }

    private fun dump(offset: Int = 0, limit: Int? = null) = NotesDump.build(tools, env.repo.notes.value, offset, limit)

    @Test fun `dump includes archived and trashed notes with full content`() = runTest {
        seed()
        val d = dump()
        assertEquals(3, d["total"]!!.jsonPrimitive.int)
        assertEquals(3, d["count"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, d["next_offset"])
        val byTitle = d["notes"]!!.jsonArray.map { it.jsonObject }.associateBy { it["title"]!!.jsonPrimitive.content }
        assertEquals(setOf("active", "archived", "trashed"), byTitle.keys)
        assertEquals(true, byTitle.getValue("archived")["archived"]!!.jsonPrimitive.boolean)
        assertEquals(false, byTitle.getValue("archived")["trashed"]!!.jsonPrimitive.boolean)
        assertEquals(true, byTitle.getValue("trashed")["trashed"]!!.jsonPrimitive.boolean)
        assertNotNull(byTitle.getValue("trashed")["trashed_at"])
        assertEquals("c\nwith \"quotes\" and 中文", byTitle.getValue("trashed")["content"]!!.jsonPrimitive.content)
        assertEquals("# a\n- [ ] x", byTitle.getValue("active")["content"]!!.jsonPrimitive.content)
    }

    @Test fun `every note carries the same fields as note_get plus revision`() = runTest {
        seed()
        for (item in dump()["notes"]!!.jsonArray.map { it.jsonObject }) {
            val got = (tools.call("note_get", buildJsonObject { put("id", item["id"]!!.jsonPrimitive.content) }) as ToolOutput.Ok).value.jsonObject
            // note_get 的字段（去掉分页用的）在 dump 里都有，值相同
            val shared = got.keys - setOf("content_offset", "truncated", "next_offset")
            for (k in shared) assertEquals("field $k", got[k], item[k])
            assertTrue(item.containsKey("revision"))
        }
        // 约定的字段名都在，且是 snake_case
        val keys = dump()["notes"]!!.jsonArray[0].jsonObject.keys
        assertTrue(keys.toString(), keys.containsAll(listOf("id", "title", "content", "tags", "color", "pinned", "archived", "trashed", "created_at", "updated_at")))
        assertTrue(keys.all { it == it.lowercase() })
    }

    @Test fun `tags are counted over notes that are not in the trash`() = runTest {
        seed()
        val tags = dump()["tags"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["count"]!!.jsonPrimitive.int }
        assertEquals(listOf("work" to 2, "Home" to 1), tags)
    }

    @Test fun `dump is read-only`() = runTest {
        seed()
        val before = env.repo.notes.value
        val rows = env.store.rows.toMap()
        repeat(3) { dump() }
        assertEquals(before, env.repo.notes.value)
        assertEquals(rows, env.store.rows)
    }

    @Test fun `empty repository dumps an empty list`() = runTest {
        val d = dump()
        assertEquals(0, d["total"]!!.jsonPrimitive.int)
        assertEquals(0, d["notes"]!!.jsonArray.size)
        assertEquals(0, d["tags"]!!.jsonArray.size)
        assertEquals(JsonNull, d["next_offset"])
    }

    @Test fun `paging by limit walks every note exactly once`() = runTest {
        repeat(7) { env.repo.create(NoteDraft(title = "n$it", content = "body $it")) }
        val seen = ArrayList<String>()
        var offset = 0
        var pages = 0
        while (true) {
            val d = dump(offset, limit = 3)
            assertEquals(7, d["total"]!!.jsonPrimitive.int)
            assertEquals(offset, d["offset"]!!.jsonPrimitive.int)
            d["notes"]!!.jsonArray.forEach { seen += it.jsonObject["title"]!!.jsonPrimitive.content }
            pages++
            val next = d["next_offset"]
            if (next == null || next is JsonNull) break
            offset = next.jsonPrimitive.int
        }
        assertEquals(3, pages)
        assertEquals((0 until 7).map { "n$it" }, seen)
    }

    @Test fun `character budget splits big data into pages`() = runTest {
        repeat(12) { env.repo.create(NoteDraft(title = "big$it", content = "字".repeat(30_000))) }
        val first = dump()
        val count = first["count"]!!.jsonPrimitive.int
        assertTrue("page should be cut by the budget, got $count", count in 1..11)
        assertTrue(first.toString().length < NotesDump.PAGE_BUDGET_CHARS + 60_000)
        assertEquals(count, first["next_offset"]!!.jsonPrimitive.int)
        var total = count
        var next = first["next_offset"]
        while (next != null && next !is JsonNull) {
            val page = dump(next.jsonPrimitive.int)
            total += page["count"]!!.jsonPrimitive.int
            next = page["next_offset"]
        }
        assertEquals(12, total)
    }

    @Test fun `offset beyond the end and odd limits are safe`() = runTest {
        seed()
        assertEquals(0, dump(offset = 99)["count"]!!.jsonPrimitive.int)
        assertEquals(0, dump(offset = -5)["offset"]!!.jsonPrimitive.int)
        assertEquals(1, dump(limit = 0)["count"]!!.jsonPrimitive.int)
        assertEquals(3, dump(limit = 1_000_000)["count"]!!.jsonPrimitive.int)
    }

    @Test fun `a huge body is truncated and flagged`() = runTest {
        env.repo.create(NoteDraft(title = "huge", content = "x".repeat(150_000)))
        val item = dump()["notes"]!!.jsonArray[0].jsonObject
        assertEquals(NotesDump.MAX_CONTENT_CHARS, item["content"]!!.jsonPrimitive.content.length)
        assertEquals(true, item["content_truncated"]!!.jsonPrimitive.boolean)
        assertEquals(150_000, item["content_length"]!!.jsonPrimitive.int)
    }

    @Test fun `clearAll removes everything including archive and trash and persists it`() = runTest {
        seed()
        assertEquals(3, env.repo.clearAll())
        assertTrue(env.repo.notes.value.isEmpty())
        assertTrue(env.store.rows.isEmpty())
        assertEquals(0, env.repo.clearAll())
        assertFalse(dump()["notes"]!!.jsonArray.isNotEmpty())
        // 清完之后仓库照常可用
        assertEquals("n4", env.repo.create(NoteDraft(title = "again", content = "x")).id)
    }

    @Test fun `dump output is plain json an adb result can carry`() = runTest {
        seed()
        val text = dump().toString()
        assertFalse(text.contains('\u0000'))
        val reparsed = kotlinx.serialization.json.Json.parseToJsonElement(text)
        assertTrue(reparsed is JsonObject)
        assertTrue(text.length * 2 < 1_000_000)
        assertTrue(reparsed.jsonObject["notes"] is JsonArray)
    }
}
