package org.agentos.runtime.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.acp.ProfileExtensions.ToolScopeParse
import org.agentos.runtime.ports.ToolRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** docs/third-party-acp.md 4.5: reading `_meta."org.agentos".toolScope` of `session/new` (the shape only; whether a tool exists is not looked at). */
class ProfileExtensionsToolScopeTest {
    private fun meta(toolScope: kotlinx.serialization.json.JsonElement?) = buildJsonObject {
        put(ProfileExtensions.META_KEY, buildJsonObject { if (toolScope != null) put("toolScope", toolScope) })
    }

    private fun entry(plugin: kotlinx.serialization.json.JsonElement?, tool: kotlinx.serialization.json.JsonElement?) = buildJsonObject {
        if (plugin != null) put("plugin", plugin)
        if (tool != null) put("tool", tool)
    }

    private fun entry(plugin: String, tool: String) = entry(JsonPrimitive(plugin), JsonPrimitive(tool))

    private fun parse(toolScope: kotlinx.serialization.json.JsonElement?) = ProfileExtensions.toolScope(meta(toolScope))

    private fun assertInvalid(result: ToolScopeParse, what: String) {
        val invalid = assertIs<ToolScopeParse.Invalid>(result, what)
        assertTrue(invalid.reason.isNotBlank(), what)
    }

    @Test
    fun `absent when there is no _meta, no org agentos object or no toolScope key`() {
        assertEquals(ToolScopeParse.Absent, ProfileExtensions.toolScope(null))
        assertEquals(ToolScopeParse.Absent, ProfileExtensions.toolScope(JsonNull))
        assertEquals(ToolScopeParse.Absent, ProfileExtensions.toolScope(buildJsonObject { }))
        assertEquals(ToolScopeParse.Absent, ProfileExtensions.toolScope(buildJsonObject { put("other", 1) }))
        assertEquals(ToolScopeParse.Absent, ProfileExtensions.toolScope(buildJsonObject { put(ProfileExtensions.META_KEY, JsonNull) }))
        assertEquals(ToolScopeParse.Absent, parse(null), "org.agentos is there, toolScope is not")
        assertEquals(ToolScopeParse.Absent, ProfileExtensions.toolScope(buildJsonObject { put(ProfileExtensions.META_KEY, "text") }))
    }

    @Test
    fun `a well formed scope is read, with its entries sorted and distinct`() {
        val result = parse(buildJsonArray { add(entry("calendar", "event_create")); add(entry("alarm", "alarm_create")); add(entry("alarm", "alarm_create")) })
        assertEquals(ToolScopeParse.Scope(listOf(ToolRef("alarm", "alarm_create"), ToolRef("calendar", "event_create"))), result)
    }

    @Test
    fun `an empty array is a valid scope with no tools`() {
        assertEquals(ToolScopeParse.Scope(emptyList()), parse(JsonArray(emptyList())))
    }

    @Test
    fun `extra keys in an entry are ignored`() {
        val e = buildJsonObject { put("plugin", "alarm"); put("tool", "alarm_create"); put("note", "later"); put("server", "main") }
        assertEquals(ToolScopeParse.Scope(listOf(ToolRef("alarm", "alarm_create"))), parse(buildJsonArray { add(e) }))
    }

    @Test
    fun `entries that name nothing installed are accepted - the shape is all that is checked`() {
        assertEquals(ToolScopeParse.Scope(listOf(ToolRef("no-such-plugin", "no_such_tool"))), parse(buildJsonArray { add(entry("no-such-plugin", "no_such_tool")) }))
    }

    @Test
    fun `not an array is invalid`() {
        assertInvalid(parse(JsonPrimitive("alarm_create")), "string")
        assertInvalid(parse(JsonPrimitive(1)), "number")
        assertInvalid(parse(JsonNull), "null")
        assertInvalid(parse(buildJsonObject { put("plugin", "alarm"); put("tool", "alarm_create") }), "a single object")
    }

    @Test
    fun `an entry that is not an object, or lacks plugin or tool, or has the wrong type, is invalid`() {
        assertInvalid(parse(buildJsonArray { add(JsonPrimitive("alarm/alarm_create")) }), "string entry")
        assertInvalid(parse(buildJsonArray { add(JsonNull) }), "null entry")
        assertInvalid(parse(buildJsonArray { add(JsonArray(emptyList())) }), "array entry")
        assertInvalid(parse(buildJsonArray { add(entry(null, JsonPrimitive("alarm_create"))) }), "no plugin")
        assertInvalid(parse(buildJsonArray { add(entry(JsonPrimitive("alarm"), null)) }), "no tool")
        assertInvalid(parse(buildJsonArray { add(entry(JsonPrimitive(1), JsonPrimitive("alarm_create"))) }), "plugin is a number")
        assertInvalid(parse(buildJsonArray { add(entry(JsonPrimitive("alarm"), JsonPrimitive(true))) }), "tool is a boolean")
        assertInvalid(parse(buildJsonArray { add(entry(JsonNull, JsonPrimitive("alarm_create"))) }), "plugin is null")
        assertInvalid(parse(buildJsonArray { add(entry(JsonPrimitive(""), JsonPrimitive("alarm_create"))) }), "empty plugin")
        assertInvalid(parse(buildJsonArray { add(entry(JsonPrimitive("alarm"), JsonPrimitive(""))) }), "empty tool")
        assertInvalid(parse(buildJsonArray { add(entry("alarm", "alarm_create")); add(JsonPrimitive(5)) }), "one good entry does not save a bad one")
    }

    @Test
    fun `at most 32 entries`() {
        fun many(n: Int) = buildJsonArray { repeat(n) { add(entry("p$it", "t")) } }
        assertIs<ToolScopeParse.Scope>(parse(many(32)))
        assertInvalid(parse(many(33)), "33 entries")
        // 33 entries that are really 1 distinct entry are still too many: the limit is on what the caller sent
        assertInvalid(parse(buildJsonArray { repeat(33) { add(entry("alarm", "alarm_create")) } }), "33 duplicates")
    }

    @Test
    fun `strings are at most 128 characters`() {
        val ok = "a".repeat(128)
        val long = "a".repeat(129)
        assertEquals(ToolScopeParse.Scope(listOf(ToolRef(ok, ok))), parse(buildJsonArray { add(entry(ok, ok)) }))
        assertInvalid(parse(buildJsonArray { add(entry(long, "t")) }), "long plugin")
        assertInvalid(parse(buildJsonArray { add(entry("p", long)) }), "long tool")
    }

    @Test
    fun `the reason does not echo what the caller sent`() {
        val secret = "x".repeat(200)
        val invalid = assertIs<ToolScopeParse.Invalid>(parse(buildJsonArray { add(entry(secret, "t")) }))
        assertTrue(secret !in invalid.reason)
    }

    @Test
    fun `initialize declares toolScope next to autoSelect so a client can probe it`() {
        val ext = ProfileExtensions.initializeMeta("1.0")[ProfileExtensions.META_KEY]!!.jsonObject["extensions"]!!.jsonObject
        assertNotNull(ext[ProfileExtensions.SESSION_TOOL_SCOPE], "toolScope is declared")
        assertEquals("1", ext[ProfileExtensions.SESSION_TOOL_SCOPE]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertNotNull(ext[ProfileExtensions.SESSION_AUTO_SELECT], "autoSelect is still declared")
        assertEquals("toolScope", ProfileExtensions.SESSION_TOOL_SCOPE)
    }

    @Test
    fun `a client can ask for toolScope in initialize like any other extension`() {
        val request = buildJsonObject {
            put(ProfileExtensions.META_KEY, buildJsonObject { put("extensions", buildJsonArray { add(JsonPrimitive("toolScope")); add(JsonPrimitive("unknown")) }) })
        }
        assertEquals(setOf("toolScope"), ProfileExtensions.clientExtensions(request))
    }
}
