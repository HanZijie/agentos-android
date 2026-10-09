package org.agentos.sample.todo.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.sample.todo.NOW
import org.agentos.sample.todo.TestEnv
import org.agentos.sample.todo.data.Due
import org.agentos.sample.todo.data.Priority
import org.agentos.sample.todo.data.TodoDraft
import org.agentos.sample.todo.data.TodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** debug 的 `dump`（分页）与 `reset`（清空）所依赖的纯函数和仓库操作。 */
class TodoDumpTest {
    private val env = TestEnv()
    private val tools = env.tools()

    @Test fun `dump lists every todo in creation order with the same fields as todo_get`() = runTest {
        val p = env.repo.create(TodoDraft("parent", notes = "n", priority = Priority.HIGH, due = Due.At(NOW + 3_600_000), tags = listOf("a")))
        env.advance()
        val c = env.repo.create(TodoDraft("child", parentId = p.id, status = TodoStatus.DONE))
        val dump = TodoDump.build(tools, env.repo.all(), env.now)
        assertEquals(listOf(p.id, c.id), dump["todos"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(2, dump["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, dump["count"]!!.jsonPrimitive.content.toInt())
        assertTrue(dump["next_offset"] is JsonNull)
        assertEquals("1", dump["counts"]!!.jsonObject["todo"]!!.jsonPrimitive.content)
        assertEquals("1", dump["counts"]!!.jsonObject["done"]!!.jsonPrimitive.content)
        // 与 MCP 的 todo_get 返回逐字段一致（todo_get 多一个 subtasks 数组）
        val got = (tools.call("todo_get", buildJsonObject { put("id", p.id) }) as ToolOutput.Ok).value.jsonObject
        val fromDump = dump["todos"]!!.jsonArray[0].jsonObject
        assertEquals(got.filterKeys { it !in setOf("subtasks", "subtask_total", "subtask_done") }, fromDump)
        assertEquals("2026-10-09T11:00:00+08:00", fromDump["due"]!!.jsonPrimitive.content)
        assertEquals("true", dump["todos"]!!.jsonArray[1].jsonObject["completed_at"].let { if (it is JsonNull) "false" else "true" })
    }

    @Test fun `dump pages with limit and next_offset until the end`() = runTest {
        repeat(7) { env.repo.create(TodoDraft("t$it")) }
        val all = env.repo.all()
        val seen = ArrayList<String>()
        var offset = 0
        var pages = 0
        while (true) {
            val page = TodoDump.build(tools, all, env.now, offset, limit = 3)
            seen += page["todos"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            pages++
            val next = page["next_offset"]
            if (next is JsonNull) break
            offset = next!!.jsonPrimitive.content.toInt()
        }
        assertEquals(3, pages)
        assertEquals((1..7).map { "t$it" }, seen)
    }

    @Test fun `dump offset past the end gives an empty last page and a huge limit is clamped`() = runTest {
        repeat(3) { env.repo.create(TodoDraft("t$it")) }
        val past = TodoDump.build(tools, env.repo.all(), env.now, offset = 99)
        assertEquals(0, past["count"]!!.jsonPrimitive.content.toInt())
        assertTrue(past["next_offset"] is JsonNull)
        assertEquals(3, TodoDump.build(tools, env.repo.all(), env.now, limit = 1_000_000)["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, TodoDump.build(tools, env.repo.all(), env.now, limit = 0)["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `dump stops at the character budget and next_offset resumes exactly there`() = runTest {
        repeat(400) { env.repo.create(TodoDraft("t$it", notes = "n".repeat(1_000))) }
        val all = env.repo.all()
        val first = TodoDump.build(tools, all, env.now)
        val count = first["count"]!!.jsonPrimitive.content.toInt()
        assertTrue("count $count", count in 1 until 400)
        assertTrue(first.toString().length < TodoDump.PAGE_BUDGET_CHARS + 20_000)
        val next = first["next_offset"]!!.jsonPrimitive.content.toInt()
        assertEquals(count, next)
        val second = TodoDump.build(tools, all, env.now, offset = next)
        assertEquals("t${count + 1}", second["todos"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun `reset empties the repository and the store synchronously`() = runTest {
        val p = env.repo.create(TodoDraft("parent"))
        env.repo.create(TodoDraft("child", parentId = p.id))
        env.repo.create(TodoDraft("other"))
        assertEquals(3, env.repo.clearAll())
        assertEquals(0, env.repo.todos.value.size)
        assertEquals(0, env.repo.storedCount())
        assertEquals(0, TodoDump.build(tools, env.repo.all(), env.now)["total"]!!.jsonPrimitive.content.toInt())
    }
}
