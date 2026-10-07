package org.agentos.app.ext

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.broker.ApprovalMode
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.CatalogTool
import org.agentos.runtime.ports.ContentPart
import org.agentos.runtime.ports.SkillCatalog
import org.agentos.runtime.ports.SkillSummary
import org.agentos.runtime.ports.ToolCatalog
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** IExtensionHost / IExtensionCallback 上的 JSON：:ext 编码、:agent 解码后与原值相同。 */
class ExtWireTest {

    private val echo = CatalogTool(
        name = "mcp__mcptest__test__echo",
        description = "Echo the input",
        inputSchema = buildJsonObject { put("type", "object") },
        risk = ToolRisk.WRITE,
        provider = "org.agentos.test.mcp.plugin/agent-plugin",
        title = "Echo",
        source = ToolSource("mcptest", "test", "echo"),
    )
    private val wipe = echo.copy(name = "mcp__mcptest__test__wipe", title = null, risk = ToolRisk.HIGH, source = ToolSource("mcptest", "test", "wipe"))

    @Test
    fun `outcomes round trip unchanged`() {
        val completed = ToolInvocationResult.Completed(
            ToolResult(
                listOf(ContentPart.Text("hello"), ContentPart.Image("aGk=", "image/png")),
                isError = true,
                details = buildJsonObject { put("n", 3) },
            ),
        )
        val notSent = ToolInvocationResult.NotDispatched(ErrorCode.TOOL_UNAVAILABLE.info("not reachable"))
        val unknown = ToolInvocationResult.Unknown(
            ErrorCode.TOOL_RESULT_UNKNOWN.info("cancelled").copy(details = buildJsonObject { put("cancelled", true) }),
        )
        val timeout = ToolInvocationResult.Unknown(ErrorCode.TOOL_TIMEOUT.info("no reply in 5 ms"))
        for (r in listOf(completed, notSent, unknown, timeout)) {
            assertEquals(r, ExtWire.decodeOutcome(ExtWire.outcomeJson(r)))
        }
    }

    @Test
    fun `an unreadable outcome is unknown, never not dispatched`() {
        for (bad in listOf(null, "", "nope", """{"outcome":"weird"}""", """{"outcome":"completed"}""", """{"outcome":"unknown"}""")) {
            val r = ExtWire.decodeOutcome(bad)
            assertTrue("$bad -> $r", r is ToolInvocationResult.Unknown && r.error.code == ErrorCode.TOOL_RESULT_UNKNOWN)
        }
    }

    @Test
    fun `catalog and policy round trip, fail closed survives the trip`() {
        val policy = ApprovalPolicy.DEFAULT
            .withEnabled(PolicyScope.Plugin("mcptest"), true)
            .withApproval(PolicyScope.Tool("mcptest", "test", "echo"), ApprovalMode.ALWAYS, ToolRisk.WRITE)
        val skills = SkillCatalog(2, listOf(SkillSummary("notes", "notes", "Take notes", echo.provider), SkillSummary("mcptest:x", "x", "", echo.provider)))
        val text = ExtWire.catalogJson(7, ToolCatalog(3, listOf(echo, wipe)), policy, skills)
        val d = ExtWire.decodeCatalog(text)
        assertEquals(7, d.version)
        assertEquals(listOf(echo, wipe), d.tools)
        assertEquals(skills.skills, d.skills)
        assertEquals(policy, d.policy)
        assertEquals(ApprovalMode.ALWAYS, d.policy.resolve(echo.source).approval)

        // fail closed：toJson 不带 unlistedPluginsEnabled，单独的标志把它带过去
        val closed = ApprovalPolicy.DEFAULT.copy(unlistedPluginsEnabled = false)
        val d2 = ExtWire.decodeCatalog(ExtWire.catalogJson(1, ToolCatalog.EMPTY, closed))
        assertFalse(d2.policy.unlistedPluginsEnabled)
        assertFalse(d2.policy.resolve(echo.source).enabled)
    }

    @Test
    fun `tool json carries the resolved policy and the always allow rule`() {
        val policy = ApprovalPolicy.DEFAULT.withApproval(PolicyScope.Plugin("mcptest"), ApprovalMode.ALWAYS)
        val e = ExtWire.toolJson(echo, policy)
        assertEquals("always", (e["approval"] as JsonPrimitive).content)
        assertEquals("true", (e["mayAlwaysAllow"] as JsonPrimitive).content)
        assertEquals(echo.provider, (e["pluginId"] as JsonPrimitive).content)
        val w = ExtWire.toolJson(wipe, policy)
        assertEquals("high", (w["risk"] as JsonPrimitive).content)
        assertEquals("false", (w["mayAlwaysAllow"] as JsonPrimitive).content)
    }

    @Test
    fun `an unknown risk level decodes as high, a duplicate name is dropped`() {
        val text = ExtWire.catalogJson(1, ToolCatalog(1, listOf(echo)), ApprovalPolicy.DEFAULT)
            .replace("\"risk\":\"write\"", "\"risk\":\"catastrophic\"")
        assertEquals(ToolRisk.HIGH, ExtWire.decodeCatalog(text).tools.single().risk)
        val dup = text.replace("\"tools\":[", "\"tools\":[" + ExtWire.toolJson(echo, ApprovalPolicy.DEFAULT) + ",")
        assertEquals(1, ExtWire.decodeCatalog(dup).tools.size)
    }

    @Test
    fun `requests round trip and bad ones are rejected before acceptance`() {
        val inv = ToolInvocation(
            sessionId = "s1", taskId = "t1", toolCallId = "c1", name = echo.name,
            arguments = buildJsonObject { put("text", "hi") },
            caller = CallerIdentity(10123, CallerKind.SELF, "ui"), timeoutMillis = 5_000,
        )
        assertEquals(inv, ExtWire.decodeRequest(ExtWire.requestJson(inv)))
        // 没有 arguments：空对象；没有 caller：SYSTEM
        val minimal = ExtWire.decodeRequest("""{"name":"x"}""")
        assertEquals(JsonObject(emptyMap()), minimal.arguments)
        assertEquals(CallerKind.SYSTEM, minimal.caller.kind)
        assertEquals(0L, minimal.timeoutMillis)
        for (bad in listOf(null, "[]", "{}", """{"name":"x","arguments":[1]}""", """{"name":"x","timeoutMs":-1}""")) {
            try {
                ExtWire.decodeRequest(bad)
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!, e.message!!.startsWith("agentos.ext.bad_request"))
            }
        }
    }
}
