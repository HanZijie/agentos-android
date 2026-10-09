package org.agentos.sample.todo.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.plugin.McpToolAnnotations
import org.agentos.plugin.McpToolRegistry
import org.agentos.plugin.McpToolResult
import org.agentos.sample.todo.TestEnv
import org.agentos.sample.todo.data.TodoDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具层 → SDK 注册表的薄层：8 个工具都注册、名字合法、注解原样带过去、结果映射正确。 */
class TodoMcpBridgeTest {
    private class Recorded(
        val name: String,
        val description: String,
        val schema: JsonObject,
        val annotations: McpToolAnnotations,
        val title: String?,
        val handler: suspend (JsonObject) -> McpToolResult,
    )

    private class RecordingRegistry : McpToolRegistry {
        val tools = LinkedHashMap<String, Recorded>()
        override fun tool(
            name: String,
            description: String,
            inputSchema: JsonObject,
            annotations: McpToolAnnotations,
            title: String?,
            handler: suspend (arguments: JsonObject) -> McpToolResult,
        ) {
            // 与 SDK 的 ToolTable.build 同样的校验
            require(Regex("^[A-Za-z0-9_.-]{1,128}$").matches(name)) { "bad name $name" }
            require(name !in tools) { "duplicate $name" }
            require(inputSchema["type"]?.jsonPrimitive?.content == "object") { "schema of $name" }
            tools[name] = Recorded(name, description, inputSchema, annotations, title, handler)
        }
    }

    private val env = TestEnv()
    private val tools = env.tools()
    private val registry = RecordingRegistry().also { TodoMcpBridge.register(it, tools) }

    @Test fun `registers all eight contracted tools with valid names and schemas`() {
        assertEquals(
            listOf("todo_list", "todo_get", "todo_create", "todo_update", "todo_set_status", "todo_delete", "todo_search", "todo_summary"),
            registry.tools.keys.toList(),
        )
        registry.tools.values.forEach {
            assertTrue(it.name, it.description.isNotBlank())
            assertNotNull(it.name, it.title)
        }
    }

    @Test fun `annotations are passed through unchanged`() {
        for (def in tools.all) {
            val a = registry.tools.getValue(def.name).annotations
            assertEquals(def.name, def.annotations.readOnlyHint, a.readOnlyHint)
            assertEquals(def.name, def.annotations.destructiveHint, a.destructiveHint)
            assertEquals(def.name, def.annotations.idempotentHint, a.idempotentHint)
            assertEquals(def.name, def.annotations.openWorldHint, a.openWorldHint)
        }
        assertEquals(true, registry.tools.getValue("todo_delete").annotations.destructiveHint)
        assertEquals(true, registry.tools.getValue("todo_search").annotations.readOnlyHint)
        assertEquals(true, registry.tools.getValue("todo_summary").annotations.readOnlyHint)
        assertEquals(true, registry.tools.getValue("todo_update").annotations.idempotentHint)
        assertEquals(true, registry.tools.getValue("todo_set_status").annotations.idempotentHint)
    }

    @Test fun `success maps to compact json text plus structured content`() = runTest {
        val r = registry.tools.getValue("todo_create").handler(buildJsonObject { put("title", "Write PRD"); put("due", "2026-10-12") })
        assertFalse(r.isError)
        val structured = r.structuredContent!!
        assertEquals("Write PRD", structured["title"]!!.jsonPrimitive.content)
        assertEquals("2026-10-12", structured["due"]!!.jsonPrimitive.content)
        assertEquals(structured.toString(), r.text)
        assertFalse(r.text.contains("\n"))
    }

    @Test fun `failure maps to an isError result with one sentence`() = runTest {
        val r = registry.tools.getValue("todo_get").handler(buildJsonObject { put("id", "nope") })
        assertTrue(r.isError)
        assertTrue(r.text, r.text.startsWith("No todo with id"))
        assertNull(r.structuredContent)
    }

    @Test fun `the whole round trip works through the registered handlers`() = runTest {
        val created = registry.tools.getValue("todo_create").handler(buildJsonObject { put("title", "t") }).structuredContent!!
        val id = created["id"]!!.jsonPrimitive.content
        val done = registry.tools.getValue("todo_set_status").handler(buildJsonObject { put("id", id); put("status", "done") })
        assertEquals("done", done.structuredContent!!["status"]!!.jsonPrimitive.content)
        val deleted = registry.tools.getValue("todo_delete").handler(buildJsonObject { put("id", id) })
        assertEquals("1", deleted.structuredContent!!["deleted"]!!.jsonPrimitive.content)
        assertTrue(registry.tools.getValue("todo_get").handler(buildJsonObject { put("id", id) }).isError)
    }

    @Test fun `the result of a big list fits the channel limit even though it is sent twice`() = runTest {
        repeat(300) { env.repo.create(TodoDraft("todo $it", notes = "字".repeat(900), tags = listOf("t$it"))) }
        val r = registry.tools.getValue("todo_list").handler(buildJsonObject { put("limit", 200) })
        assertFalse(r.isError)
        // content 里的 JSON 文本 + structuredContent 各一份；65,536 是 binder-channel 的单条消息上限
        val wire = r.text.length + r.structuredContent!!.toString().length
        assertTrue("wire size $wire", wire < 65_536)
        assertEquals(true, r.structuredContent!!["has_more"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(r.structuredContent!!["count"]!!.jsonPrimitive.content.toInt(), (r.structuredContent!!["todos"] as JsonArray).size)
    }
}
