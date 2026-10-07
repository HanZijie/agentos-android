package org.agentos.runtime.broker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 用户策略的数据模型：解析规则、改动、持久化格式与向前兼容。 */
class ApprovalPolicyTest {
    private val notes = ToolSource("notes", "main", "create_note")
    private val other = ToolSource("notes", "main", "list_notes")
    private val otherServer = ToolSource("notes", "extra", "create_note")
    private val otherPlugin = ToolSource("alarm", "main", "set")

    @Test
    fun `no settings means enabled and ask, for sourced and unsourced tools`() {
        val p = ApprovalPolicy.DEFAULT
        assertEquals(ToolPolicy(true, ApprovalMode.ASK), p.resolve(notes))
        assertEquals(ToolPolicy(true, ApprovalMode.ASK), p.resolve(null))
    }

    @Test
    fun `approval is taken from the most specific level that has one`() {
        val p = ApprovalPolicy.DEFAULT
            .withApproval(PolicyScope.Plugin("notes"), ApprovalMode.ALWAYS)
            .withApproval(PolicyScope.Server("notes", "extra"), ApprovalMode.ASK)
            .withApproval(PolicyScope.Tool("notes", "main", "list_notes"), ApprovalMode.ASK)
        assertEquals(ApprovalMode.ALWAYS, p.resolve(notes).approval, "plugin level")
        assertEquals(ApprovalMode.ASK, p.resolve(other).approval, "tool level beats plugin")
        assertEquals(ApprovalMode.ASK, p.resolve(otherServer).approval, "server level beats plugin")
        assertEquals(ApprovalMode.ASK, p.resolve(otherPlugin).approval, "other plugin untouched")
        // 工具级设为“始终允许”盖过服务器级的“每次确认”
        val q = p.withApproval(PolicyScope.Tool("notes", "extra", "create_note"), ApprovalMode.ALWAYS)
        assertEquals(ApprovalMode.ALWAYS, q.resolve(otherServer).approval)
    }

    @Test
    fun `any level that disables wins, a lower level cannot re-enable`() {
        for (scope in listOf(PolicyScope.Plugin("notes"), PolicyScope.Server("notes", "main"), PolicyScope.Tool("notes", "main", "create_note"))) {
            val p = ApprovalPolicy.DEFAULT.withEnabled(scope, false)
            assertFalse(p.resolve(notes).enabled, "$scope")
        }
        val pluginOff = ApprovalPolicy.DEFAULT
            .withEnabled(PolicyScope.Plugin("notes"), false)
            .withEnabled(PolicyScope.Server("notes", "main"), true)
            .withEnabled(PolicyScope.Tool("notes", "main", "create_note"), true)
        assertFalse(pluginOff.resolve(notes).enabled)
        assertFalse(pluginOff.resolve(other).enabled)
        // 禁用一个工具不影响同服务器的别的工具、别的插件
        val toolOff = ApprovalPolicy.DEFAULT.withEnabled(PolicyScope.of(notes), false)
        assertFalse(toolOff.resolve(notes).enabled)
        assertTrue(toolOff.resolve(other).enabled)
        assertTrue(toolOff.resolve(otherPlugin).enabled)
    }

    @Test
    fun `high risk tools cannot be set to always allow at tool level, plugin and server level are allowed`() {
        val scope = PolicyScope.Tool("notes", "main", "wipe")
        assertFailsWith<IllegalArgumentException> { ApprovalPolicy.DEFAULT.withApproval(scope, ApprovalMode.ALWAYS, ToolRisk.HIGH) }
        ApprovalPolicy.DEFAULT.withApproval(scope, ApprovalMode.ALWAYS, ToolRisk.WRITE)
        ApprovalPolicy.DEFAULT.withApproval(scope, ApprovalMode.ASK, ToolRisk.HIGH)
        ApprovalPolicy.DEFAULT.withApproval(PolicyScope.Plugin("notes"), ApprovalMode.ALWAYS) // 运行时高风险仍然每次确认
    }

    @Test
    fun `clearing a setting prunes empty levels, so equal policies are equal`() {
        val set = ApprovalPolicy.DEFAULT
            .withEnabled(PolicyScope.of(notes), false)
            .withApproval(PolicyScope.Server("notes", "main"), ApprovalMode.ALWAYS)
        val cleared = set
            .withEnabled(PolicyScope.of(notes), null)
            .withApproval(PolicyScope.Server("notes", "main"), null)
        assertEquals(ApprovalPolicy.DEFAULT, cleared)
        assertEquals(ApprovalPolicy.DEFAULT, set.cleared(PolicyScope.Plugin("notes")))
        assertEquals(PolicyEntry(), set.entryOf(PolicyScope.Plugin("notes")))
        assertEquals(PolicyEntry(approval = ApprovalMode.ALWAYS), set.entryOf(PolicyScope.Server("notes", "main")))
        // 清掉服务器，连它下面的工具设置一起清掉；清掉工具，服务器自己的设置还在
        assertEquals(PolicyEntry(), set.cleared(PolicyScope.Server("notes", "main")).entryOf(PolicyScope.of(notes)))
        assertEquals(PolicyEntry(approval = ApprovalMode.ALWAYS), set.cleared(PolicyScope.of(notes)).entryOf(PolicyScope.Server("notes", "main")))
        assertEquals(ApprovalPolicy.DEFAULT, set.cleared(PolicyScope.of(notes)).cleared(PolicyScope.Server("notes", "main")))
    }

    // ------------------------------------------------------------------ 持久化

    private val sample = ApprovalPolicy.DEFAULT
        .withEnabled(PolicyScope.Plugin("notes"), true)
        .withApproval(PolicyScope.Plugin("notes"), ApprovalMode.ASK)
        .withApproval(PolicyScope.Server("notes", "main"), ApprovalMode.ALWAYS)
        .withEnabled(PolicyScope.Tool("notes", "main", "delete_note"), false)
        .withEnabled(PolicyScope.Plugin("third.party"), false)

    @Test
    fun `the persisted form is versioned, canonical and round trips`() {
        val json = sample.toJson()
        assertEquals(
            """{"version":1,"plugins":{"notes":{"enabled":true,"approval":"ask","servers":{"main":{"approval":"always","tools":{"delete_note":{"enabled":false}}}}},"third.party":{"enabled":false}}}""",
            json,
        )
        assertEquals(sample, ApprovalPolicy.fromJson(json))
        assertEquals(json, ApprovalPolicy.fromJson(json).toJson())
        assertEquals("""{"version":1,"plugins":{}}""", ApprovalPolicy.DEFAULT.toJson())
        assertEquals(ApprovalPolicy.DEFAULT, ApprovalPolicy.fromJson(ApprovalPolicy.DEFAULT.toJson()))
        assertEquals(1, Json.parseToJsonElement(json).jsonObject["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `the same policy gives the same text whatever order it was built in`() {
        val a = ApprovalPolicy.DEFAULT.withEnabled(PolicyScope.Plugin("b"), false).withEnabled(PolicyScope.Plugin("a"), false)
        val b = ApprovalPolicy.DEFAULT.withEnabled(PolicyScope.Plugin("a"), false).withEnabled(PolicyScope.Plugin("b"), false)
        assertEquals(a.toJson(), b.toJson())
    }

    @Test
    fun `unknown fields at every level and a newer version are read, unknown parts ignored`() {
        val text = """
            {"version": 7, "futureTopLevel": {"x": 1},
             "plugins": {"notes": {"enabled": false, "approval": "ask", "futureField": [1,2],
               "servers": {"main": {"approval": "always", "futureServerField": true,
                 "tools": {"t": {"enabled": true, "futureToolField": "x"}}}}}}}
        """.trimIndent()
        val p = ApprovalPolicy.fromJson(text)
        assertFalse(p.resolve(ToolSource("notes", "main", "t")).enabled)
        assertEquals(ApprovalMode.ALWAYS, p.resolve(ToolSource("notes", "main", "t")).approval)
        assertEquals(ApprovalMode.ASK, p.resolve(ToolSource("notes", "other", "x")).approval)
        assertEquals(1, ApprovalPolicy.fromJson(p.toJson()).toJsonObject()["version"]!!.jsonPrimitive.content.toInt(), "written back as the version we know")
    }

    @Test
    fun `values this version does not understand fall back to the strictest meaning`() {
        val p = ApprovalPolicy.fromJson(
            """{"version":2,"plugins":{
                "a":{"approval":"always-and-forever"},
                "b":{"enabled":"yes"},
                "c":{"enabled":1},
                "d":{"approval":{"mode":"always"}},
                "e":"disabled",
                "f":{"servers":{"s":{"tools":{"t":7}}}}
            }}""",
        )
        assertEquals(ApprovalMode.ASK, p.resolve(ToolSource("a", "s", "t")).approval, "unknown approval is ask")
        assertFalse(p.resolve(ToolSource("b", "s", "t")).enabled, "non-boolean enabled is disabled")
        assertFalse(p.resolve(ToolSource("c", "s", "t")).enabled)
        assertEquals(ApprovalMode.ASK, p.resolve(ToolSource("d", "s", "t")).approval)
        assertFalse(p.resolve(ToolSource("e", "s", "t")).enabled, "a plugin entry that is not an object is disabled")
        assertFalse(p.resolve(ToolSource("f", "s", "t")).enabled, "a tool entry that is not an object is disabled")
        assertTrue(p.resolve(ToolSource("f", "s", "other")).enabled)
    }

    @Test
    fun `null means inherit`() {
        val p = ApprovalPolicy.fromJson("""{"version":1,"plugins":{"a":{"enabled":null,"approval":null}}}""")
        assertEquals(ApprovalPolicy.DEFAULT, p)
    }

    @Test
    fun `a missing or invalid version, or a broken structure, is an error and never silently the default`() {
        for (bad in listOf(
            "", "nope", "[]", "{}", """{"plugins":{}}""", """{"version":0,"plugins":{}}""", """{"version":-1}""",
            """{"version":"1","plugins":{}}""", """{"version":1.5,"plugins":{}}""", """{"version":1,"plugins":[]}""", """{"version":1,"plugins":"x"}""",
        )) {
            assertFailsWith<ApprovalPolicyFormatException>(bad) { ApprovalPolicy.fromJson(bad) }
        }
        // plugins 缺省是合法的空策略
        assertEquals(ApprovalPolicy.DEFAULT, ApprovalPolicy.fromJson("""{"version":1}"""))
    }

    @Test
    fun `always on a high risk tool in the file is still confirmed`() {
        val p = ApprovalPolicy.fromJson("""{"version":1,"plugins":{"notes":{"approval":"always"}}}""")
        val approval = p.resolve(notes).approval
        assertEquals(ApprovalMode.ALWAYS, approval)
        assertTrue(RiskPolicy.needsConsent(ToolRisk.HIGH, approval))
        assertFalse(RiskPolicy.needsConsent(ToolRisk.WRITE, approval))
    }
}
