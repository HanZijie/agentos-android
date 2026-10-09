package org.agentos.app.ext

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.extensions.ExtMessages
import org.agentos.extensions.McpServerDecl
import org.agentos.extensions.PluginManifest
import org.agentos.extensions.UnsupportedKind
import org.agentos.extensions.UnsupportedPart
import org.agentos.extensions.host.KnownTool
import org.agentos.extensions.registry.PluginIdentity
import org.agentos.extensions.registry.PluginProblem
import org.agentos.extensions.registry.PluginRecord
import org.agentos.extensions.registry.PluginStatus
import org.agentos.runtime.broker.ApprovalMode
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.i18n.MessageRef
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
    fun `known tool json has exactly the KnownTool fields, title null is kept`() {
        val k = KnownTool(
            name = wipe.name, pluginId = wipe.provider, source = wipe.source!!, title = null, description = "",
            inputSchema = wipe.inputSchema, risk = ToolRisk.HIGH, enabled = false, approval = ApprovalMode.ASK, mayAlwaysAllow = false,
        )
        val j = ExtWire.knownToolJson(k)
        assertEquals(ExtWire.KNOWN_TOOL_KEYS, j.keys.toList())
        assertEquals(JsonNull, j["title"])
        assertEquals("high", (j["risk"] as JsonPrimitive).content)
        assertEquals("ask", (j["approval"] as JsonPrimitive).content)
        assertEquals("false", (j["enabled"] as JsonPrimitive).content)
        assertEquals("false", (j["mayAlwaysAllow"] as JsonPrimitive).content)
        assertEquals(wipe.provider, (j["pluginId"] as JsonPrimitive).content)
        assertEquals("wipe", ((j["source"] as JsonObject)["tool"] as JsonPrimitive).content)
        val titled = ExtWire.knownToolJson(k.copy(title = "Wipe", enabled = true, approval = ApprovalMode.ALWAYS))
        assertEquals("Wipe", (titled["title"] as JsonPrimitive).content)
        assertEquals("always", (titled["approval"] as JsonPrimitive).content)
    }

    // ------------------------------------------------------------------ 插件 JSON（插件页）

    private val signerNow = "a".repeat(64)
    private val signerBefore = "b".repeat(64)

    private fun plugin(status: PluginStatus, trusted: String, servers: List<McpServerDecl> = listOf(McpServerDecl.Binder("test", "org.x.Svc"))) =
        PluginRecord(
            id = "org.x/agent-plugin",
            name = "mcptest",
            identity = PluginIdentity("org.x", signerNow, 7),
            trustedSigner = trusted,
            builtin = false,
            status = status,
            servers = servers,
        )

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun `plugin json has fixed keys and trustedSigningDigest is the confirmed signer or null`() {
        val policy = ApprovalPolicy.DEFAULT.withEnabled(PolicyScope.Plugin("mcptest"), false)
        // 签名变化：现在的与确认过的不同
        val changed = ExtWire.pluginJson(plugin(PluginStatus.SIGNATURE_CHANGED, signerBefore), policy, null, emptyMap(), 0, emptyList())
        assertEquals(ExtWire.PLUGIN_KEYS, changed.keys.toList())
        assertEquals(signerNow, changed.str("signingDigest"))
        assertEquals(signerBefore, changed.str("trustedSigningDigest"))
        assertEquals("signature_changed", changed.str("status"))
        // 记忆丢失后还没确认过（PersistedPlugin.UNCONFIRMED = ""）：null，不是空串
        val unconfirmed = ExtWire.pluginJson(plugin(PluginStatus.SIGNATURE_UNCONFIRMED, ""), policy, null, emptyMap(), 0, emptyList())
        assertEquals(JsonNull, unconfirmed["trustedSigningDigest"])
        assertEquals(ExtWire.PLUGIN_KEYS, unconfirmed.keys.toList())
        // 正常：确认过的就是现在的
        val ready = ExtWire.pluginJson(plugin(PluginStatus.READY, signerNow), ApprovalPolicy.DEFAULT, "1.0", emptyMap(), 2, emptyList())
        assertEquals(signerNow, ready.str("trustedSigningDigest"))
        assertEquals("1.0", ready.str("versionName"))
        // 空值写 null，不省略
        assertEquals(JsonNull, unconfirmed["versionName"])
        assertEquals(JsonNull, unconfirmed["approval"])
        assertEquals(JsonNull, unconfirmed["unavailableReason"])
    }

    @Test
    fun `problems, unsupported parts and skill problems travel as key and arguments with their location, never as a sentence`() {
        val manifest = PluginManifest(
            name = "mcptest", version = null, description = null, authorName = null, display = null, servers = emptyList(), skills = emptyList(), hooks = null,
            unsupported = listOf(UnsupportedPart(UnsupportedKind.STDIO_SERVER, "mcp.json › mcpServers.local", MessageRef.of(ExtMessages.STDIO_UNSUPPORTED))),
        )
        val rec = plugin(PluginStatus.READY, signerNow).copy(
            manifest = manifest,
            problems = listOf(
                PluginProblem("server_rejected", MessageRef.of(ExtMessages.SERVER_REJECTED_NOT_EXPORTED, "a", "org.x.Svc")),
                PluginProblem("schema", MessageRef.of(ExtMessages.MUST_BE_STRING), "plugin.json › version"),
            ),
        )
        val skill = listOf(PluginProblem("skill_problem", MessageRef.of(ExtMessages.SKILL_NO_FRONTMATTER), "broken"))
        val j = ExtWire.pluginJson(rec, ApprovalPolicy.DEFAULT, null, emptyMap(), 0, skill)
        val problems = (j["problems"] as JsonArray).map { it as JsonObject }
        assertEquals("server_rejected", problems[0].str("code"))
        val message = problems[0]["message"] as JsonObject
        assertEquals(ExtMessages.SERVER_REJECTED_NOT_EXPORTED, message.str("key"))
        assertEquals(listOf("a", "org.x.Svc"), (message["args"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals(JsonNull, problems[0]["location"])
        assertEquals("plugin.json › version", problems[1].str("location"))
        val unsupported = ((j["unsupported"] as JsonArray)[0] as JsonObject)
        assertEquals("stdio_server", unsupported.str("kind"))
        assertEquals("mcp.json › mcpServers.local", unsupported.str("location"))
        assertEquals(ExtMessages.STDIO_UNSUPPORTED, (unsupported["detail"] as JsonObject).str("key"))
        val skillJson = ((j["skillProblems"] as JsonArray)[0] as JsonObject)
        assertEquals("broken", skillJson.str("location"))
        assertEquals(ExtMessages.SKILL_NO_FRONTMATTER, (skillJson["message"] as JsonObject).str("key"))
        // no sentence anywhere in the encoded problems
        assertTrue(j.toString().none { it in '一'..'鿿' })
    }

    @Test
    fun `plugin json servers have fixed keys, states and tool counts`() {
        val servers = listOf(McpServerDecl.Binder("test", "org.x.Svc"), McpServerDecl.StreamableHttp("remote", "https://example.org/mcp", emptyList()))
        val p = plugin(PluginStatus.READY, signerNow, servers)
        val j = ExtWire.pluginJson(p, ApprovalPolicy.DEFAULT, null, mapOf("test" to ExtWire.ServerView("connected", null, 7)), 2, emptyList())
        val list = (j["servers"] as JsonArray).map { it as JsonObject }
        assertTrue(list.all { it.keys.toList() == ExtWire.SERVER_KEYS })
        assertEquals("org.x.Svc", list[0].str("service"))
        assertEquals(JsonNull, list[0]["url"])
        assertEquals("connected", list[0].str("state"))
        assertEquals(JsonNull, list[1]["service"])
        assertEquals("https://example.org/mcp", list[1].str("url"))
        assertEquals("idle", list[1].str("state")) // 没有状态、插件启用且可用：idle
        assertEquals("7", (j["toolCount"] as JsonPrimitive).content)
        assertEquals("2", (j["skillCount"] as JsonPrimitive).content)
        // 停用的插件：没有状态的服务器按 disabled
        val off = ExtWire.pluginJson(p, ApprovalPolicy.DEFAULT.withEnabled(PolicyScope.Plugin("mcptest"), false), null, emptyMap(), 0, emptyList())
        assertEquals("disabled", ((off["servers"] as JsonArray)[0] as JsonObject).str("state"))
        assertEquals("false", (off["enabled"] as JsonPrimitive).content)
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
