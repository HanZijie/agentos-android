package org.agentos.sample.todo.tools

import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.sample.todo.TestEnv
import org.agentos.sample.todo.data.TodoDraft
import org.agentos.sample.todo.millis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoToolsTest {
    private val env = TestEnv()
    private val tools = env.tools()

    private suspend fun ok(name: String, args: JsonObject = JsonObject(emptyMap())): JsonObject {
        val out = tools.call(name, args)
        check(out is ToolOutput.Ok) { "$name failed: ${(out as ToolOutput.Error).message}" }
        return out.value.jsonObject
    }

    private suspend fun err(name: String, args: JsonObject = JsonObject(emptyMap())): String {
        val out = tools.call(name, args)
        check(out is ToolOutput.Error) { "$name unexpectedly succeeded: $out" }
        assertFalse("error must be one line: ${out.message}", out.message.contains('\n'))
        assertTrue("error must say something", out.message.isNotBlank())
        return out.message
    }

    private fun args(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)

    private suspend fun create(title: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        ok("todo_create", args { put("title", title); extra() })

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.ids(key: String) = this[key]!!.jsonArray.map { it.jsonObject.str("id") }
    private fun JsonObject.isNull(k: String) = this[k] is JsonNull

    // ---------------------------------------------------------------- 目录

    @Test fun `exposes exactly the 8 contracted tools with accurate annotations`() {
        val byName = tools.all.associateBy { it.name }
        assertEquals(
            listOf("todo_list", "todo_get", "todo_create", "todo_update", "todo_set_status", "todo_delete", "todo_search", "todo_summary"),
            tools.all.map { it.name },
        )
        for (name in listOf("todo_list", "todo_get", "todo_search", "todo_summary")) {
            assertEquals(name, true, byName.getValue(name).annotations.readOnlyHint)
        }
        for (name in listOf("todo_create", "todo_update", "todo_set_status", "todo_delete")) {
            assertEquals(name, false, byName.getValue(name).annotations.readOnlyHint)
        }
        assertEquals(true, byName.getValue("todo_delete").annotations.destructiveHint)
        for (name in byName.keys - "todo_delete") assertTrue(name, byName.getValue(name).annotations.destructiveHint != true)
        assertEquals(true, byName.getValue("todo_update").annotations.idempotentHint)
        assertEquals(true, byName.getValue("todo_set_status").annotations.idempotentHint)
        assertTrue(byName.getValue("todo_create").annotations.idempotentHint != true)
    }

    @Test fun `required and optional parameters match the contract and schemas are objects`() {
        val required = mapOf(
            "todo_list" to emptySet(), "todo_get" to setOf("id"), "todo_create" to setOf("title"),
            "todo_update" to setOf("id"), "todo_set_status" to setOf("id", "status"), "todo_delete" to setOf("id"),
            "todo_search" to setOf("query"), "todo_summary" to emptySet<String>(),
        )
        val optional = mapOf(
            "todo_list" to setOf("status", "priority", "tag", "due_before", "due_after", "overdue_only", "parent_id", "include_done", "limit", "offset"),
            "todo_create" to setOf("notes", "priority", "due", "due_all_day", "tags", "parent_id", "status"),
            "todo_update" to setOf("title", "notes", "priority", "due", "due_all_day", "tags", "parent_id", "status"),
            "todo_search" to setOf("status", "limit"),
        )
        for (tool in tools.all) {
            assertEquals(tool.name, "object", tool.inputSchema["type"]!!.jsonPrimitive.content)
            val req = tool.inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
            assertEquals(tool.name, required.getValue(tool.name), req)
            val props = tool.inputSchema["properties"]!!.jsonObject.keys
            assertTrue("${tool.name} lists its required parameters", props.containsAll(req))
            assertTrue("${tool.name} lists its optional parameters", props.containsAll(optional[tool.name].orEmpty()))
        }
    }

    @Test fun `descriptions are English sentences for the model and names are snake_case`() {
        for (tool in tools.all) {
            assertTrue(tool.name, Regex("^todo_[a-z_]+$").matches(tool.name))
            assertTrue("${tool.name}: ${tool.description}", tool.description.length > 40)
            assertTrue(tool.name, tool.description.none { it.code > 0x2E7F && it !in '\u2018'..'\u201D' })
        }
    }

    @Test fun `unknown tools are reported`() = runTest {
        assertTrue(err("todo_nope").contains("Unknown tool"))
    }

    // ---------------------------------------------------------------- todo_create

    @Test fun `create with only a title uses the defaults and returns the full shape`() = runTest {
        val t = create("Write PRD")
        assertEquals("t1", t.str("id"))
        assertEquals("Write PRD", t.str("title"))
        assertEquals("todo", t.str("status"))
        assertEquals("medium", t.str("priority"))
        assertTrue(t.isNull("due"))
        assertEquals(false, t["due_all_day"]!!.jsonPrimitive.boolean)
        assertEquals(0, t["tags"]!!.jsonArray.size)
        assertTrue(t.isNull("parent_id"))
        assertTrue(t.isNull("completed_at"))
        assertEquals(false, t["overdue"]!!.jsonPrimitive.boolean)
        assertEquals("", t.str("notes"))
        assertEquals("2026-10-09T10:00:00+08:00", t.str("created_at"))
        assertEquals(t.str("created_at"), t.str("updated_at"))
    }

    @Test fun `create with everything`() = runTest {
        val t = create("Ship it") {
            put("notes", "details")
            put("priority", "high")
            put("due", "2026-10-12")
            put("tags", buildJsonArray { add(JsonPrimitive("work")); add(JsonPrimitive("Work")); add(JsonPrimitive("#ship")) })
            put("status", "doing")
        }
        assertEquals("high", t.str("priority"))
        assertEquals("doing", t.str("status"))
        assertEquals("2026-10-12", t.str("due"))
        assertEquals(true, t["due_all_day"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("work", "ship"), t["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("details", t.str("notes"))
    }

    @Test fun `a due with another offset is returned in the device offset, same instant`() = runTest {
        val t = create("t") { put("due", "2026-10-12T10:00:00+09:00") }
        assertEquals("2026-10-12T09:00:00+08:00", t.str("due"))
        assertEquals(false, t["due_all_day"]!!.jsonPrimitive.boolean)
    }

    @Test fun `due_all_day turns a date-time into an all-day due on the date written`() = runTest {
        val t = create("t") {
            put("due", "2026-10-12T23:30:00-05:00")
            put("due_all_day", true)
        }
        assertEquals("2026-10-12", t.str("due"))
        assertEquals(true, t["due_all_day"]!!.jsonPrimitive.boolean)
    }

    @Test fun `a subtask is created with parent_id and cannot have subtasks`() = runTest {
        val parent = create("parent")
        val child = create("child") { put("parent_id", parent.str("id")) }
        assertEquals(parent.str("id"), child.str("parent_id"))
        assertTrue(err("todo_create", args { put("title", "g"); put("parent_id", child.str("id")) }).contains("Subtasks cannot have"))
        assertTrue(err("todo_create", args { put("title", "g"); put("parent_id", "nope") }).contains("No parent todo"))
    }

    @Test fun `create rejects missing and invalid arguments with one sentence each`() = runTest {
        assertTrue(err("todo_create").contains("title"))
        assertTrue(err("todo_create", args { put("title", "   ") }).contains("title"))
        assertTrue(err("todo_create", args { put("title", "x".repeat(201)) }).contains("200"))
        assertTrue(err("todo_create", args { put("title", "t"); put("priority", "urgent") }).contains("priority must be one of"))
        assertTrue(err("todo_create", args { put("title", "t"); put("status", "finished") }).contains("status must be one of"))
        assertTrue(err("todo_create", args { put("title", "t"); put("due", "tomorrow") }).contains("YYYY-MM-DD"))
        assertTrue(err("todo_create", args { put("title", "t"); put("due", "2026-10-12T17:00") }).contains("UTC offset"))
        assertTrue(err("todo_create", args { put("title", "t"); put("due", "2026-02-30") }).contains("Invalid due"))
        assertTrue(err("todo_create", args { put("title", "t"); put("due_all_day", true) }).contains("due_all_day"))
        assertTrue(err("todo_create", args { put("title", "t"); put("due", "2026-10-12"); put("due_all_day", false) }).contains("due_all_day"))
        assertTrue(err("todo_create", args { put("title", "t"); put("tags", "work") }).contains("array of strings"))
        assertTrue(err("todo_create", args { put("title", "t"); put("tags", buildJsonArray { add(JsonPrimitive("a,b")) }) }).contains("commas"))
        assertEquals(0, env.repo.todos.value.size)
    }

    // ---------------------------------------------------------------- todo_get

    @Test fun `get returns the todo with its subtasks in the order they were added`() = runTest {
        val parent = create("parent") { put("notes", "full notes") }
        val c1 = create("c1") { put("parent_id", parent.str("id")) }
        val c2 = create("c2") { put("parent_id", parent.str("id")); put("priority", "high") }
        ok("todo_set_status", args { put("id", c1.str("id")); put("status", "done") })
        val got = ok("todo_get", args { put("id", parent.str("id")) })
        assertEquals("full notes", got.str("notes"))
        assertEquals(listOf(c1.str("id"), c2.str("id")), got.ids("subtasks"))
        assertEquals(2, got["subtask_total"]!!.jsonPrimitive.int)
        assertEquals(1, got["subtask_done"]!!.jsonPrimitive.int)
        // 子任务是紧凑形式：没有 notes
        assertFalse(got["subtasks"]!!.jsonArray[0].jsonObject.containsKey("notes"))
        // 子任务自己没有子任务
        assertEquals(0, ok("todo_get", args { put("id", c1.str("id")) })["subtasks"]!!.jsonArray.size)
    }

    @Test fun `get needs an id that exists`() = runTest {
        assertTrue(err("todo_get").contains("id"))
        assertTrue(err("todo_get", args { put("id", "nope") }).contains("No todo with id"))
    }

    // ---------------------------------------------------------------- todo_list

    @Test fun `list orders by priority then due and hides done by default`() = runTest {
        create("low") { put("priority", "low"); put("due", "2026-10-10") }
        create("high-late") { put("priority", "high"); put("due", "2026-10-20") }
        create("high-soon") { put("priority", "high"); put("due", "2026-10-10") }
        val done = create("done-one") { put("priority", "high") }
        ok("todo_set_status", args { put("id", done.str("id")); put("status", "done") })
        val shelved = create("shelved-one") { put("priority", "medium") }
        ok("todo_set_status", args { put("id", shelved.str("id")); put("status", "shelved") })

        val list = ok("todo_list")
        assertEquals(listOf("high-soon", "high-late", "shelved-one", "low"), list["todos"]!!.jsonArray.map { it.jsonObject.str("title") })
        assertEquals(4, list["total"]!!.jsonPrimitive.int)
        assertEquals(false, list["has_more"]!!.jsonPrimitive.boolean)

        val withDone = ok("todo_list", args { put("include_done", true) })
        assertEquals(5, withDone["total"]!!.jsonPrimitive.int)
        val onlyDone = ok("todo_list", args { put("status", "done") })
        assertEquals(listOf("done-one"), onlyDone["todos"]!!.jsonArray.map { it.jsonObject.str("title") })
        assertEquals(listOf("shelved-one"), ok("todo_list", args { put("status", "shelved") })["todos"]!!.jsonArray.map { it.jsonObject.str("title") })
    }

    @Test fun `list filters by priority, tag, parent, overdue and due bounds`() = runTest {
        val a = create("a") { put("priority", "high"); put("tags", buildJsonArray { add(JsonPrimitive("Work")) }); put("due", "2026-10-08") }
        create("b") { put("due", "2026-10-09T09:00:00+08:00") }
        create("c") { put("due", "2026-10-09T18:00:00+08:00") }
        create("d") { put("due", "2026-10-15") }
        val sub = create("sub") { put("parent_id", a.str("id")); put("tags", buildJsonArray { add(JsonPrimitive("work")) }) }

        fun titles(o: JsonObject) = o["todos"]!!.jsonArray.map { it.jsonObject.str("title") }
        assertEquals(listOf("a"), titles(ok("todo_list", args { put("priority", "high") })))
        assertEquals(setOf("a", "sub"), titles(ok("todo_list", args { put("tag", "WORK") })).toSet())
        assertEquals(listOf("sub"), titles(ok("todo_list", args { put("parent_id", a.str("id")) })))
        assertEquals(setOf("a", "b"), titles(ok("todo_list", args { put("overdue_only", true) })).toSet()) // 现在是 10:00：b 的 09:00 已过
        assertEquals(setOf("c", "d"), titles(ok("todo_list", args { put("due_after", "2026-10-09T10:00:01+08:00") })).toSet())
        assertEquals(setOf("a", "b", "c"), titles(ok("todo_list", args { put("due_before", "2026-10-09") })).toSet())
        assertEquals(setOf("b", "c"), titles(ok("todo_list", args { put("due_after", "2026-10-09"); put("due_before", "2026-10-09") })).toSet())
        assertEquals(sub.str("id"), ok("todo_list", args { put("parent_id", a.str("id")) }).ids("todos").single())
    }

    @Test fun `list items carry subtask counts, overdue flag and a notes preview`() = runTest {
        val p = create("parent") { put("notes", "n".repeat(500)); put("due", "2026-10-01") }
        val c = create("child") { put("parent_id", p.str("id")) }
        ok("todo_set_status", args { put("id", c.str("id")); put("status", "done") })
        val item = ok("todo_list", args { put("parent_id", "none-such") })
        assertEquals(0, item["todos"]!!.jsonArray.size)
        val parent = ok("todo_list")["todos"]!!.jsonArray.map { it.jsonObject }.single { it.str("id") == p.str("id") }
        assertEquals(1, parent["subtask_total"]!!.jsonPrimitive.int)
        assertEquals(1, parent["subtask_done"]!!.jsonPrimitive.int)
        assertEquals(true, parent["overdue"]!!.jsonPrimitive.boolean)
        assertEquals(200, parent.str("notes").length)
        assertEquals(true, parent["notes_truncated"]!!.jsonPrimitive.boolean)
        assertFalse(parent.containsKey("created_at"))
        // 紧凑形式：空字段不写
        assertFalse(parent.containsKey("tags"))
        assertFalse(parent.containsKey("parent_id"))
        assertFalse(parent.containsKey("completed_at"))
        val childItem = ok("todo_list", args { put("parent_id", p.str("id")); put("include_done", true) })["todos"]!!.jsonArray.single().jsonObject
        assertEquals(p.str("id"), childItem.str("parent_id"))
        assertTrue(childItem.containsKey("completed_at"))
        assertFalse(childItem.containsKey("due"))
        assertFalse(childItem.containsKey("overdue"))
    }

    @Test fun `list pages with limit and offset and says whether more follow`() = runTest {
        repeat(5) { create("t$it") }
        val first = ok("todo_list", args { put("limit", 2) })
        assertEquals(listOf("t1", "t2"), first.ids("todos"))
        assertEquals(true, first["has_more"]!!.jsonPrimitive.boolean)
        assertEquals(2, first["next_offset"]!!.jsonPrimitive.int)
        assertEquals(5, first["total"]!!.jsonPrimitive.int)
        val last = ok("todo_list", args { put("limit", 2); put("offset", 4) })
        assertEquals(listOf("t5"), last.ids("todos"))
        assertEquals(false, last["has_more"]!!.jsonPrimitive.boolean)
        assertFalse(last.containsKey("next_offset"))
        assertEquals(0, ok("todo_list", args { put("offset", 99) })["todos"]!!.jsonArray.size)
    }

    @Test fun `list limit defaults to 50 and is capped at 200`() = runTest {
        repeat(210) { env.repo.create(TodoDraft("t$it")) }
        val default = ok("todo_list")
        assertEquals(50, default["count"]!!.jsonPrimitive.int)
        assertEquals(50, default["limit"]!!.jsonPrimitive.int)
        val capped = ok("todo_list", args { put("limit", 5000) })
        assertEquals(200, capped["limit"]!!.jsonPrimitive.int)
        assertEquals(200, capped["count"]!!.jsonPrimitive.int) // 紧凑形式的 200 条装得下
        assertEquals(true, capped["has_more"]!!.jsonPrimitive.boolean)
        assertTrue(err("todo_list", args { put("limit", 0) }).contains("limit"))
        assertTrue(err("todo_list", args { put("offset", -1) }).contains("offset"))
    }

    @Test fun `list rejects invalid filter values`() = runTest {
        assertTrue(err("todo_list", args { put("status", "finished") }).contains("status must be one of"))
        assertTrue(err("todo_list", args { put("priority", "p0") }).contains("priority must be one of"))
        assertTrue(err("todo_list", args { put("due_before", "next week") }).contains("YYYY-MM-DD"))
        assertTrue(err("todo_list", args { put("overdue_only", "maybe") }).contains("boolean"))
    }

    // ---------------------------------------------------------------- todo_update

    @Test fun `update changes only what is given`() = runTest {
        val t = create("t") { put("notes", "keep"); put("priority", "low"); put("due", "2026-10-12") }
        env.advance(5_000)
        val u = ok("todo_update", args { put("id", t.str("id")); put("title", "t2"); put("priority", "high") })
        assertEquals("t2", u.str("title"))
        assertEquals("high", u.str("priority"))
        assertEquals("keep", u.str("notes"))
        assertEquals("2026-10-12", u.str("due"))
        assertEquals("2026-10-09T10:00:05+08:00", u.str("updated_at"))
    }

    @Test fun `update is idempotent`() = runTest {
        val t = create("t")
        val body = args { put("id", t.str("id")); put("priority", "high"); put("due", "2026-10-12T17:00:00+08:00") }
        val first = ok("todo_update", body)
        env.advance(5_000)
        val second = ok("todo_update", body)
        assertEquals(first, second)
    }

    @Test fun `update can clear due, notes and tags and move or detach a subtask`() = runTest {
        val p = create("p")
        val q = create("q")
        val t = create("t") { put("notes", "n"); put("due", "2026-10-12"); put("tags", buildJsonArray { add(JsonPrimitive("a")) }) }
        val cleared = ok("todo_update", args { put("id", t.str("id")); put("due", ""); put("notes", ""); put("tags", JsonArray(emptyList())) })
        assertTrue(cleared.isNull("due"))
        assertEquals(false, cleared["due_all_day"]!!.jsonPrimitive.boolean)
        assertEquals("", cleared.str("notes"))
        assertEquals(0, cleared["tags"]!!.jsonArray.size)

        val moved = ok("todo_update", args { put("id", t.str("id")); put("parent_id", p.str("id")) })
        assertEquals(p.str("id"), moved.str("parent_id"))
        val reparented = ok("todo_update", args { put("id", t.str("id")); put("parent_id", q.str("id")) })
        assertEquals(q.str("id"), reparented.str("parent_id"))
        assertTrue(ok("todo_update", args { put("id", t.str("id")); put("parent_id", "") }).isNull("parent_id"))
        // 有子任务的不能变成子任务
        ok("todo_update", args { put("id", t.str("id")); put("parent_id", p.str("id")) })
        assertTrue(err("todo_update", args { put("id", p.str("id")); put("parent_id", q.str("id")) }).contains("subtasks of its own"))
    }

    @Test fun `due_all_day on its own converts the current due`() = runTest {
        val t = create("t") { put("due", "2026-10-12T23:30:00+08:00") }
        val allDay = ok("todo_update", args { put("id", t.str("id")); put("due_all_day", true) })
        assertEquals("2026-10-12", allDay.str("due"))
        assertEquals(true, allDay["due_all_day"]!!.jsonPrimitive.boolean)
        // 全天改回带时刻必须给出具体时刻
        assertTrue(err("todo_update", args { put("id", t.str("id")); put("due_all_day", false) }).contains("needs a time"))
        val timed = ok("todo_update", args { put("id", t.str("id")); put("due", "2026-10-12T08:00:00+08:00"); put("due_all_day", false) })
        assertEquals("2026-10-12T08:00:00+08:00", timed.str("due"))
        // 没有截止时 due_all_day 没有对象
        val none = create("none")
        assertTrue(err("todo_update", args { put("id", none.str("id")); put("due_all_day", true) }).contains("no due date"))
        assertTrue(err("todo_update", args { put("id", t.str("id")); put("due", ""); put("due_all_day", true) }).contains("empty due"))
    }

    @Test fun `update through status follows the completed_at rules`() = runTest {
        val t = create("t")
        val done = ok("todo_update", args { put("id", t.str("id")); put("status", "done") })
        assertEquals("2026-10-09T10:00:00+08:00", done.str("completed_at"))
        assertTrue(ok("todo_update", args { put("id", t.str("id")); put("status", "doing") }).isNull("completed_at"))
    }

    @Test fun `update rejects empty updates, bad values and unknown ids`() = runTest {
        val t = create("t")
        assertTrue(err("todo_update", args { put("id", t.str("id")) }).contains("Nothing to update"))
        assertTrue(err("todo_update").contains("id"))
        assertTrue(err("todo_update", args { put("id", "nope"); put("title", "x") }).contains("No todo with id"))
        assertTrue(err("todo_update", args { put("id", t.str("id")); put("title", "  ") }).contains("title"))
        assertTrue(err("todo_update", args { put("id", t.str("id")); put("priority", "p0") }).contains("priority"))
        assertTrue(err("todo_update", args { put("id", t.str("id")); put("due", "2026-13-01") }).contains("Invalid due"))
        assertTrue(err("todo_update", args { put("id", t.str("id")); put("parent_id", "nope") }).contains("No parent todo"))
        assertEquals("t", ok("todo_get", args { put("id", t.str("id")) }).str("title"))
    }

    // ---------------------------------------------------------------- todo_set_status

    @Test fun `set_status walks through the states and records completed_at`() = runTest {
        val t = create("t")
        val id = t.str("id")
        assertEquals("doing", ok("todo_set_status", args { put("id", id); put("status", "doing") }).str("status"))
        env.advance(60_000)
        val done = ok("todo_set_status", args { put("id", id); put("status", "done") })
        assertEquals("2026-10-09T10:01:00+08:00", done.str("completed_at"))
        env.advance(60_000)
        val again = ok("todo_set_status", args { put("id", id); put("status", "done") })
        assertEquals(done, again) // 幂等：完成时间不变，更新时间也不变
        assertTrue(ok("todo_set_status", args { put("id", id); put("status", "todo") }).isNull("completed_at"))
        assertEquals("shelved", ok("todo_set_status", args { put("id", id); put("status", "shelved") }).str("status"))
    }

    @Test fun `set_status rejects missing, invalid and unknown`() = runTest {
        val t = create("t")
        assertTrue(err("todo_set_status", args { put("id", t.str("id")) }).contains("status"))
        assertTrue(err("todo_set_status", args { put("status", "done") }).contains("id"))
        assertTrue(err("todo_set_status", args { put("id", t.str("id")); put("status", "finished") }).contains("status must be one of"))
        assertTrue(err("todo_set_status", args { put("id", "nope"); put("status", "done") }).contains("No todo with id"))
    }

    // ---------------------------------------------------------------- todo_delete

    @Test fun `delete removes a todo and reports one`() = runTest {
        val t = create("t")
        val out = ok("todo_delete", args { put("id", t.str("id")) })
        assertEquals(1, out["deleted"]!!.jsonPrimitive.int)
        assertEquals(0, out["subtasks_deleted"]!!.jsonPrimitive.int)
        assertEquals("t", out.str("title"))
        assertTrue(err("todo_get", args { put("id", t.str("id")) }).contains("No todo with id"))
    }

    @Test fun `delete cascades to subtasks and returns the total`() = runTest {
        val p = create("p")
        repeat(3) { create("c$it") { put("parent_id", p.str("id")) } }
        val other = create("other")
        val out = ok("todo_delete", args { put("id", p.str("id")) })
        assertEquals(4, out["deleted"]!!.jsonPrimitive.int)
        assertEquals(3, out["subtasks_deleted"]!!.jsonPrimitive.int)
        assertEquals(listOf(other.str("id")), ok("todo_list", args { put("include_done", true) }).ids("todos"))
    }

    @Test fun `delete needs an id that exists`() = runTest {
        assertTrue(err("todo_delete").contains("id"))
        assertTrue(err("todo_delete", args { put("id", "nope") }).contains("No todo with id"))
        val t = create("t")
        ok("todo_delete", args { put("id", t.str("id")) })
        assertTrue(err("todo_delete", args { put("id", t.str("id")) }).contains("No todo with id"))
    }

    // ---------------------------------------------------------------- todo_search

    @Test fun `search finds by title, notes and tags and says where`() = runTest {
        create("Write the PRD")
        create("Other") { put("notes", "the prd draft") }
        create("Third") { put("tags", buildJsonArray { add(JsonPrimitive("prd")) }) }
        create("Unrelated")
        val r = ok("todo_search", args { put("query", "PRD") })
        assertEquals(3, r["total"]!!.jsonPrimitive.int)
        val first = r["results"]!!.jsonArray[0].jsonObject
        assertEquals("Write the PRD", first.str("title"))
        assertEquals(listOf("title"), first["matched_in"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("notes"), r["results"]!!.jsonArray.map { it.jsonObject }.single { it.str("title") == "Other" }["matched_in"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(false, r["has_more"]!!.jsonPrimitive.boolean)
    }

    @Test fun `search includes done todos, can be limited by status and limit`() = runTest {
        val a = create("plan alpha")
        create("plan beta")
        ok("todo_set_status", args { put("id", a.str("id")); put("status", "done") })
        assertEquals(2, ok("todo_search", args { put("query", "plan") })["total"]!!.jsonPrimitive.int)
        assertEquals(listOf(a.str("id")), ok("todo_search", args { put("query", "plan"); put("status", "done") }).ids("results"))
        val limited = ok("todo_search", args { put("query", "plan"); put("limit", 1) })
        assertEquals(1, limited["count"]!!.jsonPrimitive.int)
        assertEquals(true, limited["has_more"]!!.jsonPrimitive.boolean)
    }

    @Test fun `search needs a non-blank query`() = runTest {
        assertTrue(err("todo_search").contains("query"))
        assertTrue(err("todo_search", args { put("query", "  ") }).contains("blank"))
        assertTrue(err("todo_search", args { put("query", "x"); put("status", "finished") }).contains("status"))
        assertEquals(0, ok("todo_search", args { put("query", "nothing matches this") })["total"]!!.jsonPrimitive.int)
    }

    // ---------------------------------------------------------------- todo_summary（时区口径的 JSON 层）

    @Test fun `summary reports counts, overdue, today and week in the device zone`() = runTest {
        env.now = millis("2026-10-09T23:30:00+08:00")
        create("A") { put("due", "2026-10-10T00:10:00+08:00") }
        create("B") { put("due", "2026-10-09T23:00:00+08:00") }
        create("C") { put("due", "2026-10-09") }
        create("D") { put("due", "2026-10-08") }
        create("E") { put("due", "2026-10-09T23:45:00+08:00") }
        create("F") { put("due", "2026-10-12") }
        val g = create("G")
        ok("todo_set_status", args { put("id", g.str("id")); put("status", "done") })
        val h = create("H")
        ok("todo_set_status", args { put("id", h.str("id")); put("status", "doing") })

        val s = ok("todo_summary")
        assertEquals(8, s["total"]!!.jsonPrimitive.int)
        assertEquals(setOf("todo", "doing", "done", "shelved"), s["counts"]!!.jsonObject.keys)
        assertEquals(6, s["counts"]!!.jsonObject["todo"]!!.jsonPrimitive.int)
        assertEquals(1, s["counts"]!!.jsonObject["doing"]!!.jsonPrimitive.int)
        assertEquals(1, s["counts"]!!.jsonObject["done"]!!.jsonPrimitive.int)
        assertEquals(0, s["counts"]!!.jsonObject["shelved"]!!.jsonPrimitive.int)
        assertEquals(2, s["overdue"]!!.jsonPrimitive.int)
        assertEquals(2, s["due_today"]!!.jsonPrimitive.int)
        assertEquals(3, s["due_this_week"]!!.jsonPrimitive.int)
        assertEquals("2026-10-09", s.str("today"))
        assertEquals("2026-10-05", s.str("week_start"))
        assertEquals("2026-10-11", s.str("week_end"))
        assertEquals("+08:00", s.str("time_zone"))
    }

    @Test fun `summary of the same data in another time zone differs where the local day differs`() = runTest {
        env.now = millis("2026-10-09T23:30:00+08:00")
        create("A") { put("due", "2026-10-10T00:10:00+08:00") }
        create("C") { put("due", "2026-10-09") }
        val plus8 = ok("todo_summary")
        assertEquals(1, plus8["due_today"]!!.jsonPrimitive.int) // A 在 +08:00 是明天
        val utcTools = env.tools(zone = ZoneOffset.UTC)
        val utc = (utcTools.call("todo_summary") as ToolOutput.Ok).value.jsonObject
        assertEquals(2, utc["due_today"]!!.jsonPrimitive.int) // A 在 UTC 还是 10 月 9 日（16:10Z）
        assertEquals("Z", utc.str("time_zone"))
    }

    @Test fun `summary week start follows the injected week definition`() = runTest {
        env.now = millis("2026-10-11T12:00:00+08:00") // 周日
        val iso = ok("todo_summary")
        assertEquals("2026-10-05", iso.str("week_start"))
        val sundayFirst = (env.tools(week = java.time.temporal.WeekFields.SUNDAY_START).call("todo_summary") as ToolOutput.Ok).value.jsonObject
        assertEquals("2026-10-11", sundayFirst.str("week_start"))
        assertEquals("2026-10-17", sundayFirst.str("week_end"))
    }

    @Test fun `summary of an empty list is all zeros`() = runTest {
        val s = ok("todo_summary")
        assertEquals(0, s["total"]!!.jsonPrimitive.int)
        assertEquals(0, s["overdue"]!!.jsonPrimitive.int)
        assertEquals(0, s["due_today"]!!.jsonPrimitive.int)
        assertEquals(0, s["due_this_week"]!!.jsonPrimitive.int)
    }

    // ---------------------------------------------------------------- 结果体积（单条结果 ≤ 65,536 字符，content 与 structuredContent 各一份）

    private fun wire(o: JsonObject): Int = WireSize.cost(o)

    @Test fun `a big list is cut to fit the channel and says there is more`() = runTest {
        repeat(300) { env.repo.create(TodoDraft("todo number $it", notes = "字".repeat(900), tags = listOf("t$it", "common"))) }
        val r = ok("todo_list", args { put("limit", 200) })
        assertTrue("wire size ${wire(r)}", wire(r) < 65_536)
        val count = r["count"]!!.jsonPrimitive.int
        assertTrue(count in 1 until 200)
        assertEquals(true, r["has_more"]!!.jsonPrimitive.boolean)
        assertEquals(count, r["next_offset"]!!.jsonPrimitive.int)
        assertEquals(count, r["todos"]!!.jsonArray.size)
        // 接着取能连起来
        val next = ok("todo_list", args { put("limit", 200); put("offset", count) })
        assertEquals("t${count + 1}", next.ids("todos").first())
    }

    @Test fun `search results are cut the same way`() = runTest {
        repeat(150) { env.repo.create(TodoDraft("common title $it", notes = "\"quoted\" ".repeat(100))) }
        val r = ok("todo_search", args { put("query", "common"); put("limit", 100) })
        assertTrue("wire size ${wire(r)}", wire(r) < 65_536)
        assertEquals(true, r["has_more"]!!.jsonPrimitive.boolean)
    }

    @Test fun `get with huge notes and a hundred subtasks stays under the limit`() = runTest {
        val p = env.repo.create(TodoDraft("parent", notes = "\"x".repeat(5_000)))
        repeat(100) { env.repo.create(TodoDraft("subtask $it ".repeat(10), parentId = p.id, notes = "x".repeat(2_000))) }
        val r = ok("todo_get", args { put("id", p.id) })
        assertTrue("wire size ${wire(r)}", wire(r) < 65_536)
        assertEquals(10_000, r.str("notes").length)
        assertEquals(100, r["subtask_total"]!!.jsonPrimitive.int)
    }

    @Test fun `a long subtask list is truncated with a flag instead of overflowing`() = runTest {
        val p = env.repo.create(TodoDraft("parent", notes = "\"".repeat(10_000)))
        repeat(100) { env.repo.create(TodoDraft("a very long subtask title ".repeat(7) + it, parentId = p.id, tags = (1..10).map { n -> "tag-number-$n" })) }
        val r = ok("todo_get", args { put("id", p.id) })
        assertTrue("wire size ${wire(r)}", wire(r) < 65_536)
        assertEquals(true, r["subtasks_truncated"]!!.jsonPrimitive.boolean)
        assertTrue(r["subtasks"]!!.jsonArray.size < 100)
    }

    // ---------------------------------------------------------------- 其他

    @Test fun `overdue is computed against the clock of the moment`() = runTest {
        val t = create("t") { put("due", "2026-10-09T10:30:00+08:00") }
        assertEquals(false, ok("todo_get", args { put("id", t.str("id")) })["overdue"]!!.jsonPrimitive.boolean)
        env.advance(31 * 60_000)
        assertEquals(true, ok("todo_get", args { put("id", t.str("id")) })["overdue"]!!.jsonPrimitive.boolean)
        ok("todo_set_status", args { put("id", t.str("id")); put("status", "done") })
        assertEquals(false, ok("todo_get", args { put("id", t.str("id")) })["overdue"]!!.jsonPrimitive.boolean)
    }
}
