@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.runtime.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.broker.CallerPolicy
import org.agentos.runtime.broker.OpenCallerPolicy
import org.agentos.runtime.broker.StrictCallerPolicy
import org.agentos.runtime.errors.RpcCodes
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.quota.CallerQuotaConfig
import org.agentos.runtime.quota.PromptOutcome
import org.agentos.runtime.quota.PromptUsage
import org.agentos.runtime.scheduler.SchedulerConfig
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.TestRuntime
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/third-party-acp.md 4.5 and 4.6 over the wire, with the official SDK client and a third-party caller (`CallerKind.APP`):
 * what `session/new` accepts as a toolScope, what the app gets back when it is over a limit, and that AgentOS itself is not limited.
 */
class ThirdPartyAcpTest {
    private val alarm = ToolSource("alarm", "main", "alarm_create")
    private val delete = ToolSource("notes", "main", "note_delete")
    private val scope = listOf(ToolRef("alarm", "alarm_create"))

    private fun <T> test(
        quota: CallerQuotaConfig = CallerQuotaConfig(),
        policy: CallerPolicy = OpenCallerPolicy,
        block: suspend CoroutineScope.(AcpPair, TestRuntime) -> T,
    ) = runBlocking {
        val rt = TestRuntime(FakeScripts.directives(), config = RuntimeConfig(scheduler = SchedulerConfig(tickMillis = 20), quota = quota, callerPolicy = policy))
        rt.host.tools.registerSimple("alarm_create", ToolRisk.WRITE, alarm) { ToolResult.text("alarm set") }
        rt.host.tools.registerSimple("note_delete", ToolRisk.HIGH, delete) { ToolResult.text("deleted") }
        rt.start()
        val pair = AcpPair(rt)
        try {
            withTimeout(30_000) { block(this, pair, rt) }
        } finally {
            pair.close()
            rt.stop()
            rt.host.deleteDatabase()
        }
        Unit
    }

    private fun directive(vararg pairs: Pair<String, JsonElement>): String = buildJsonObject { put("fake", buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }) }.toString()

    private fun tools(vararg names: String): JsonArray = buildJsonArray { names.forEach { n -> add(buildJsonObject { put("name", n) }) } }

    private suspend fun sessionsOf(rt: TestRuntime, caller: org.agentos.runtime.ports.CallerIdentity = TestRuntime.APP) =
        rt.engine.storeForTesting.read { it.sessions.listByOwner(caller.ownerKey) }

    private fun errorData(pair: AcpPair): JsonObject = pair.lastError()["data"]!!.jsonObject

    private fun metaWith(entry: JsonElement?): JsonObject = buildJsonObject { put(ProfileExtensions.META_KEY, buildJsonObject { if (entry != null) put("toolScope", entry) }) }

    // ------------------------------------------------------------------ 4.5 over the wire

    @Test
    fun `initialize says toolScope is supported, next to autoSelect`() = test { pair, _ ->
        val info = pair.initialize()
        val ext = info._meta!!.jsonObject[ProfileExtensions.META_KEY]!!.jsonObject["extensions"]!!.jsonObject
        assertNotNull(ext["toolScope"])
        assertNotNull(ext["sessionAutoSelect"])
    }

    @Test
    fun `session load is not offered, so a scope cannot be changed by loading a session`() = test { pair, _ ->
        val info = pair.initialize()
        assertEquals(false, info.capabilities.loadSession)
    }

    @Test
    fun `session new takes a scope without any negotiation, and the session is limited to it`() = test { pair, rt ->
        pair.initialize() // no extensions asked for
        val session = pair.newSession(toolScopeMeta(ToolRef("alarm", "alarm_create")))
        val stored = rt.engine.session(TestRuntime.APP, session.sessionId.value)
        assertEquals(scope, stored.toolScope)
        val events = pair.prompt(session, directive("tools" to tools("note_delete", "alarm_create")))
        assertEquals(StopReason.END_TURN, events.response().stopReason)
        assertEquals(listOf("alarm_create"), rt.host.tools.invocations.map { it.name }, "note_delete is not in the scope")
        assertEquals(listOf("alarm_create"), rt.core!!.configs.last().tools.map { it.name })
    }

    /**
     * DOCUMENTED BEHAVIOUR (user decision 2026-10-08): over the wire, a third-party app that sends no toolScope gets the whole catalog and the
     * ordinary confirmation rules. Strict is the opt-in alternative (next test); do not turn this one around.
     */
    @Test
    fun `DOCUMENTED - a session of a third-party app without a scope can use every catalog tool`() = test { pair, rt ->
        pair.initialize()
        val session = pair.newSession()
        assertNull(rt.engine.session(TestRuntime.APP, session.sessionId.value).toolScope)
        pair.prompt(session, directive("tools" to tools("alarm_create", "note_delete")))
        assertEquals(listOf("alarm_create", "note_delete"), rt.core!!.configs.last().tools.map { it.name }.sorted())
        assertEquals(listOf("alarm_create", "note_delete"), rt.host.tools.invocations.map { it.name }, "both ran, each after the user was asked")
        assertEquals(listOf("alarm_create", "note_delete"), rt.host.consent.requests.map { it.toolName })
    }

    @Test
    fun `strict policy - a session of a third-party app without a scope has no tools`() = test(policy = StrictCallerPolicy) { pair, rt ->
        pair.initialize()
        val session = pair.newSession()
        pair.prompt(session, directive("tools" to tools("alarm_create", "note_delete")))
        assertTrue(rt.host.tools.invocations.isEmpty())
        assertEquals(emptyList(), rt.core!!.configs.last().tools)
    }

    @Test
    fun `strict policy - the same app with a scope has exactly that scope`() = test(policy = StrictCallerPolicy) { pair, rt ->
        pair.initialize()
        val session = pair.newSession(toolScopeMeta(ToolRef("alarm", "alarm_create")))
        pair.prompt(session, directive("tools" to tools("note_delete", "alarm_create")))
        assertEquals(listOf("alarm_create"), rt.host.tools.invocations.map { it.name })
    }

    @Test
    fun `an empty scope is accepted and means no tools`() = test { pair, rt ->
        pair.initialize()
        val session = pair.newSession(metaWith(JsonArray(emptyList())))
        assertEquals(emptyList(), rt.engine.session(TestRuntime.APP, session.sessionId.value).toolScope)
    }

    @Test
    fun `a scope that names nothing installed is accepted without a word`() = test { pair, rt ->
        pair.initialize()
        val session = pair.newSession(toolScopeMeta(ToolRef("no-such-plugin", "no_such_tool"), ToolRef("alarm", "alarm_create")))
        pair.prompt(session, directive("tools" to tools("alarm_create")))
        assertEquals(listOf("alarm_create"), rt.host.tools.invocations.map { it.name })
        assertEquals(listOf("alarm_create"), rt.core!!.configs.last().tools.map { it.name })
    }

    @Test
    fun `a scope of the wrong shape is invalid_params and no session is created`() = test { pair, rt ->
        pair.initialize()
        fun entry(plugin: JsonElement?, tool: JsonElement?) = buildJsonObject {
            if (plugin != null) put("plugin", plugin)
            if (tool != null) put("tool", tool)
        }
        val bad = mapOf(
            "not an array" to JsonPrimitive("alarm_create"),
            "an object" to buildJsonObject { put("plugin", "alarm"); put("tool", "alarm_create") },
            "null" to JsonNull,
            "an entry that is a string" to buildJsonArray { add(JsonPrimitive("alarm/alarm_create")) },
            "no tool" to buildJsonArray { add(entry(JsonPrimitive("alarm"), null)) },
            "no plugin" to buildJsonArray { add(entry(null, JsonPrimitive("alarm_create"))) },
            "a number as plugin" to buildJsonArray { add(entry(JsonPrimitive(1), JsonPrimitive("alarm_create"))) },
            "33 entries" to buildJsonArray { repeat(33) { add(entry(JsonPrimitive("p$it"), JsonPrimitive("t"))) } },
            "a plugin of 129 characters" to buildJsonArray { add(entry(JsonPrimitive("a".repeat(129)), JsonPrimitive("t"))) },
            "a tool of 129 characters" to buildJsonArray { add(entry(JsonPrimitive("p"), JsonPrimitive("t".repeat(129)))) },
        )
        for ((what, value) in bad) {
            assertFailsWith<Exception>(what) { pair.newSession(metaWith(value)) }
            val raw = pair.lastError()
            assertEquals(RpcCodes.INVALID_PARAMS, raw["code"]!!.jsonPrimitive.content.toInt(), what)
            assertEquals("invalid_params", raw["data"]!!.jsonObject["agentosCode"]!!.jsonPrimitive.content, what)
            assertEquals("false", raw["data"]!!.jsonObject["retryable"]!!.jsonPrimitive.content, what)
        }
        assertEquals(emptyList(), sessionsOf(rt), "none of them created a session")
        // the largest valid scope still works
        pair.newSession(metaWith(buildJsonArray { repeat(32) { add(entry(JsonPrimitive("p$it"), JsonPrimitive("t".repeat(128)))) } }))
        assertEquals(1, sessionsOf(rt).size)
    }

    @Test
    fun `a scope together with autoSelect creates the session with that scope`() = test { pair, rt ->
        pair.initialize(listOf("sessionAutoSelect"))
        val meta = buildJsonObject {
            put(
                ProfileExtensions.META_KEY,
                buildJsonObject {
                    put("autoSelect", buildJsonObject { put("query", "set an alarm for 9") })
                    put("toolScope", buildJsonArray { add(buildJsonObject { put("plugin", "alarm"); put("tool", "alarm_create") }) })
                },
            )
        }
        val session = pair.newSession(meta)
        assertEquals(scope, rt.engine.session(TestRuntime.APP, session.sessionId.value).toolScope)
    }

    // ------------------------------------------------------------------ 4.6 over the wire

    @Test
    fun `an app prompt over 16000 characters is invalid_params with the reason too_large, and nothing is queued`() = test { pair, rt ->
        pair.initialize()
        val session = pair.newSession()
        assertFailsWith<Exception> { session.prompt(listOf(ContentBlock.Text("x".repeat(16_001)))).toList() }
        val raw = pair.lastError()
        assertEquals(RpcCodes.INVALID_PARAMS, raw["code"]!!.jsonPrimitive.content.toInt())
        val data = raw["data"]!!.jsonObject
        assertEquals("invalid_params", data["agentosCode"]!!.jsonPrimitive.content)
        assertEquals("false", data["retryable"]!!.jsonPrimitive.content)
        assertEquals("too_large", data["details"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals("16000", data["details"]!!.jsonObject["limit"]!!.jsonPrimitive.content)
        assertEquals(0L, rt.engine.quota.usage(TestRuntime.APP).promptsTotal, "a refused prompt counts for nothing")
        // exactly the limit is fine
        assertEquals(StopReason.END_TURN, pair.prompt(session, "y".repeat(16_000)).response().stopReason)
    }

    @Test
    fun `the text of an embedded resource counts towards the limit`() = test { pair, _ ->
        pair.initialize()
        val session = pair.newSession()
        val resource = ContentBlock.Resource(
            com.agentclientprotocol.model.EmbeddedResourceResource.TextResourceContents("z".repeat(16_001), "content://memo/1"),
        )
        assertFailsWith<Exception> { session.prompt(listOf(ContentBlock.Text("summarise"), resource)).toList() }
        assertEquals("too_large", errorData(pair)["details"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an app prompt over the protocol limit is still too_large - the app's own limit comes first`() = test { pair, rt ->
        pair.initialize()
        val session = pair.newSession()
        assertFailsWith<Exception> { session.prompt(listOf(ContentBlock.Text("x".repeat(200_001)))).toList() }
        assertEquals("invalid_params", errorData(pair)["agentosCode"]!!.jsonPrimitive.content)

        // control: AgentOS itself is not held to 16,000, only to the protocol's 200,000
        val own = AcpPair(rt, TestRuntime.SELF)
        try {
            own.initialize()
            val ownSession = own.newSession()
            assertEquals(StopReason.END_TURN, own.prompt(ownSession, "x".repeat(50_000)).response().stopReason)
            assertFailsWith<Exception> { ownSession.prompt(listOf(ContentBlock.Text("x".repeat(200_001)))).toList() }
            assertEquals("payload_too_large", own.lastError()["data"]!!.jsonObject["agentosCode"]!!.jsonPrimitive.content)
        } finally {
            own.close()
        }
    }

    @Test
    fun `a second prompt while one is running is rate limited with the reason busy, a retryable quota error`() = test { pair, rt ->
        pair.initialize()
        val first = pair.newSession()
        val second = pair.newSession()
        val running = async { pair.prompt(first, directive("chunks" to JsonPrimitive(1), "text" to JsonPrimitive("a"), "awaitAbort" to JsonPrimitive(true))) }
        rt.until { rt.engine.quota.usage(TestRuntime.APP).activePrompts == 1 }
        // another session of the same app is no different: it is one app
        assertFailsWith<Exception> { pair.prompt(second, "hello") }
        val raw = pair.lastError()
        assertEquals(RpcCodes.QUOTA_EXCEEDED, raw["code"]!!.jsonPrimitive.content.toInt())
        val data = raw["data"]!!.jsonObject
        assertEquals("quota_exceeded", data["agentosCode"]!!.jsonPrimitive.content)
        assertEquals("true", data["retryable"]!!.jsonPrimitive.content)
        assertEquals("busy", data["details"]!!.jsonObject["reason"]!!.jsonPrimitive.content)

        first.cancel()
        running.await()
        // the slot is free again as soon as the first one has ended
        assertEquals(StopReason.END_TURN, pair.prompt(second, "hello again").response().stopReason)
    }

    @Test
    fun `a new connection does not get around the one at a time rule - a prompt goes on after its connection closed`() = test { pair, rt ->
        pair.initialize()
        val session = pair.newSession()
        val running = async { runCatching { pair.prompt(session, directive("chunks" to JsonPrimitive(1), "text" to JsonPrimitive("a"), "awaitAbort" to JsonPrimitive(true))) } }
        rt.until { rt.engine.quota.usage(TestRuntime.APP).activePrompts == 1 }
        pair.disconnect()
        running.await()

        val again = AcpPair(rt, TestRuntime.APP)
        try {
            again.initialize()
            val fresh = again.newSession()
            assertFailsWith<Exception> { again.prompt(fresh, "hello") }
            assertEquals("busy", again.lastError()["data"]!!.jsonObject["details"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
            assertEquals(1, rt.engine.quota.usage(TestRuntime.APP).activePrompts)
        } finally {
            again.close()
        }
    }

    @Test
    fun `thirty an hour - the next is rate limited with the reason hourly, and the hour slides`() = test(CallerQuotaConfig(maxPromptsPerHour = 3)) { pair, rt ->
        pair.initialize()
        val session = pair.newSession()
        repeat(3) { assertEquals(StopReason.END_TURN, pair.prompt(session, "hello $it").response().stopReason) }
        assertFailsWith<Exception> { pair.prompt(session, "one too many") }
        val raw = pair.lastError()
        assertEquals(RpcCodes.QUOTA_EXCEEDED, raw["code"]!!.jsonPrimitive.content.toInt())
        val details = raw["data"]!!.jsonObject["details"]!!.jsonObject
        assertEquals("hourly", details["reason"]!!.jsonPrimitive.content)
        assertEquals("3", details["limit"]!!.jsonPrimitive.content)
        assertTrue(details["retryAfterSeconds"]!!.jsonPrimitive.content.toLong() in 1..3_600)

        rt.host.clock.advance(3_600_000)
        assertEquals(StopReason.END_TURN, pair.prompt(session, "an hour later").response().stopReason)
    }

    @Test
    fun `AgentOS itself and the desktop are never rate limited`() = test(CallerQuotaConfig(maxPromptsPerHour = 1)) { _, rt ->
        for (caller in listOf(TestRuntime.SELF, TestRuntime.DESKTOP)) {
            val own = AcpPair(rt, caller)
            try {
                own.initialize()
                val session = own.newSession()
                repeat(4) { assertEquals(StopReason.END_TURN, own.prompt(session, "hello $it").response().stopReason, caller.kind.name) }
            } finally {
                own.close()
            }
            assertEquals(0L, rt.engine.quota.usage(caller).promptsTotal)
        }
    }

    @Test
    fun `the usage listener hears about every finished prompt once, with how it ended`() = test { pair, rt ->
        val heard = Collections.synchronizedList(mutableListOf<PromptUsage>())
        rt.engine.quota.addListener { heard += it }
        pair.initialize()
        val session = pair.newSession()

        pair.prompt(session, "hello")
        rt.until { heard.size == 1 }
        assertEquals(PromptOutcome.COMPLETED, heard[0].outcome)
        assertEquals(TestRuntime.APP, heard[0].caller)
        assertEquals(5, heard[0].promptChars)

        val running = async { pair.prompt(session, directive("chunks" to JsonPrimitive(1), "text" to JsonPrimitive("a"), "awaitAbort" to JsonPrimitive(true))) }
        rt.until { rt.engine.quota.usage(TestRuntime.APP).activePrompts == 1 }
        session.cancel()
        running.await()
        rt.until { heard.size == 2 }
        assertEquals(PromptOutcome.CANCELLED, heard[1].outcome)

        assertFailsWith<Exception> { pair.prompt(session, directive("fail" to JsonPrimitive("model_rate_limited"))) }
        rt.until { heard.size == 3 }
        assertEquals(PromptOutcome.FAILED, heard[2].outcome)
        assertEquals(3, heard.size, "one call per prompt")

        // a refused prompt is not reported
        assertFailsWith<Exception> { pair.prompt(session, "x".repeat(16_001)) }
        assertEquals(3, heard.size)
    }

    @Test
    fun `the quota error does not echo the prompt or the package name`() = test { pair, _ ->
        pair.initialize()
        val session = pair.newSession()
        val secret = "TOP-SECRET-" + "x".repeat(16_000)
        assertFailsWith<Exception> { session.prompt(listOf(ContentBlock.Text(secret))).toList() }
        val line = pair.lastError().toString()
        assertTrue("TOP-SECRET" !in line)
        assertTrue(TestRuntime.APP.label!! !in line)
    }
}
