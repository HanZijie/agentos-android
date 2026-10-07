package org.agentos.sample.notes.agent

import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.plugin.McpToolAnnotations
import org.agentos.plugin.McpToolRegistry
import org.agentos.plugin.McpToolResult
import org.agentos.sample.notes.TestEnv
import org.agentos.sample.notes.data.NoteDraft
import org.agentos.sample.notes.tools.NotesTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具层 → SDK 注册表的薄层：10 个工具都注册、名字合法、注解原样带过去、结果映射正确。 */
class NotesMcpBridgeTest {
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
    private val tools = NotesTools(env.repo, zone = { ZoneOffset.ofHours(8) })
    private val registry = RecordingRegistry().also { NotesMcpBridge.register(it, tools) }

    @Test fun `registers all ten contracted tools with valid names and schemas`() {
        assertEquals(
            listOf("note_list", "note_get", "note_create", "note_update", "note_append", "note_search", "note_trash", "note_restore", "note_delete", "tag_list"),
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
        assertEquals(true, registry.tools.getValue("note_delete").annotations.destructiveHint)
        assertEquals(true, registry.tools.getValue("note_search").annotations.readOnlyHint)
    }

    @Test fun `success maps to compact json text plus structured content`() = runTest {
        val r = registry.tools.getValue("note_create").handler(buildJsonObject { put("content", "# Hi\nbody") })
        assertFalse(r.isError)
        val structured = r.structuredContent!!
        assertEquals("Hi", structured["title"]!!.jsonPrimitive.content)
        assertEquals(structured.toString(), r.text)
    }

    @Test fun `failure maps to an isError result with one sentence`() = runTest {
        val r = registry.tools.getValue("note_get").handler(buildJsonObject { put("id", "nope") })
        assertTrue(r.isError)
        assertTrue(r.text, r.text.startsWith("No note with id"))
        assertEquals(null, r.structuredContent)
    }

    @Test fun `the result of a big list fits the channel limit even though it is sent twice`() = runTest {
        repeat(150) { env.repo.create(NoteDraft(title = "note $it", content = "字".repeat(900), tags = listOf("t$it"))) }
        val r = registry.tools.getValue("note_list").handler(buildJsonObject { put("limit", 200) })
        assertFalse(r.isError)
        // content 里的 JSON 文本 + structuredContent 各一份；65,536 是 binder-channel 的单条消息上限
        val wire = r.text.length + r.structuredContent!!.toString().length
        assertTrue("wire size $wire", wire < 65_536)
        assertEquals(true, r.structuredContent!!["has_more"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(r.structuredContent!!["notes"].toString().isNotEmpty())
        assertEquals(r.structuredContent!!["count"]!!.jsonPrimitive.content.toInt(), (r.structuredContent!!["notes"] as kotlinx.serialization.json.JsonArray).size)
        r.structuredContent!!.jsonObject
    }
}
