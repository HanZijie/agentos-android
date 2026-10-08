package org.agentos.runtime.ports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/third-party-acp.md 4.5: what a [ToolScope] allows, how it is stored, and what a damaged stored value means. */
class ToolScopeTest {
    private val alarm = ToolSource("alarm", "main", "alarm_create")
    private val event = ToolSource("calendar", "main", "event_create")
    private val delete = ToolSource("notes", "main", "note_delete")

    @Test
    fun `ALL allows every tool, also one with no source`() {
        assertTrue(ToolScope.ALL.allows(alarm))
        assertTrue(ToolScope.ALL.allows(null))
        assertFalse(ToolScope.ALL.restricted)
        assertTrue(ToolScope.ALL.allowsBuiltinTools)
    }

    @Test
    fun `a scope allows exactly the named plugin and tool pairs`() {
        val scope = ToolScope.only(listOf(ToolRef("alarm", "alarm_create"), ToolRef("calendar", "event_create")))
        assertTrue(scope.allows(alarm))
        assertTrue(scope.allows(event))
        assertFalse(scope.allows(delete))
        assertTrue(scope.restricted)
    }

    @Test
    fun `the match is on plugin and tool, not on the tool name alone and not on the server`() {
        val scope = ToolScope.only(listOf(ToolRef("alarm", "alarm_create")))
        assertFalse(scope.allows(ToolSource("evil", "main", "alarm_create")), "same tool name, other plugin")
        assertFalse(scope.allows(ToolSource("alarm", "main", "alarm_delete")), "same plugin, other tool")
        assertTrue(scope.allows(ToolSource("alarm", "other-server", "alarm_create")), "the server is not part of the match")
    }

    @Test
    fun `a restricted scope never allows a tool without a source, and the empty scope allows nothing`() {
        assertFalse(ToolScope.only(listOf(ToolRef("alarm", "alarm_create"))).allows(null))
        assertFalse(ToolScope.only(listOf(ToolRef("alarm", "alarm_create"))).allowsBuiltinTools)
        assertFalse(ToolScope.NONE.allows(alarm))
        assertTrue(ToolScope.NONE.restricted)
    }

    @Test
    fun `a third-party app never gets ALL, everybody else keeps what they have`() {
        assertEquals(ToolScope.NONE, ToolScope.ALL.forCaller(CallerKind.APP))
        assertEquals(ToolScope.ALL, ToolScope.ALL.forCaller(CallerKind.SELF))
        assertEquals(ToolScope.ALL, ToolScope.ALL.forCaller(CallerKind.DESKTOP))
        assertEquals(ToolScope.ALL, ToolScope.ALL.forCaller(CallerKind.SYSTEM))
        val narrow = ToolScope.only(listOf(ToolRef("alarm", "alarm_create")))
        for (kind in CallerKind.entries) assertEquals(narrow, narrow.forCaller(kind), "a scope that was given applies to $kind too")
    }

    @Test
    fun `entries are distinct and sorted so equal scopes are equal and persist identically`() {
        val a = ToolScope.only(listOf(ToolRef("b", "x"), ToolRef("a", "y"), ToolRef("a", "x"), ToolRef("a", "x")))
        val b = ToolScope.only(listOf(ToolRef("a", "x"), ToolRef("a", "y"), ToolRef("b", "x")))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(listOf(ToolRef("a", "x"), ToolRef("a", "y"), ToolRef("b", "x")), a.entries)
        assertNotEquals(ToolScope.ALL, ToolScope.NONE)
    }

    @Test
    fun `the stored form round trips`() {
        val refs = listOf(ToolRef("calendar", "event_create"), ToolRef("alarm", "alarm_create"))
        val text = ToolScope.toJson(refs).toString()
        assertEquals(ToolScope.normalize(refs), ToolScope.fromJson(text))
        val first = Json.parseToJsonElement(text).jsonArray[0].jsonObject
        assertEquals("alarm", first["plugin"]!!.jsonPrimitive.content)
        assertEquals("alarm_create", first["tool"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), ToolScope.fromJson("[]"), "an empty scope is a scope with no tools")
    }

    @Test
    fun `no stored value means no scope was given`() {
        assertNull(ToolScope.fromJson(null))
        assertNull(ToolScope.fromJson(""))
        assertNull(ToolScope.fromJson("   "))
    }

    @Test
    fun `a damaged stored value reads as no tools, never as no restriction`() {
        for (broken in listOf("not json", "{}", "\"alarm\"", "[1]", "[{\"plugin\":\"a\"}]", "[{\"plugin\":1,\"tool\":\"x\"}]", "[{\"plugin\":\"a\",\"tool\":\"x\"}, 5]", "[")) {
            assertEquals(emptyList(), ToolScope.fromJson(broken), broken)
            assertFalse(ToolScope.only(ToolScope.fromJson(broken)!!).allows(alarm), broken)
        }
    }
}
