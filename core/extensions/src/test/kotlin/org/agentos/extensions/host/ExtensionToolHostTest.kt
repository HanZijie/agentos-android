package org.agentos.extensions.host

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.extensions.ToolId
import org.agentos.extensions.ToolNaming
import org.agentos.extensions.registry.RegistryEvent
import org.agentos.extensions.registry.RevokeReason
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.ContentPart
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionToolHostTest {
    private val notesPkg = "org.x.notes"
    private val alarmPkg = "org.x.alarm"
    private val calPkg = "org.x.calendar"

    private fun invocation(name: String, timeout: Long = 60_000, id: String = "call_1") =
        ToolInvocation("ses_1", "tsk_1", id, name, buildJsonObject { put("a", 1) }, CallerIdentity.SYSTEM, timeout)

    private fun ToolInvocationResult.text(): String = (this as ToolInvocationResult.Completed).result.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    /** 三个示例 App，用户都启用，服务器都连得上。 */
    private fun threeApps(scope: kotlinx.coroutines.test.TestScope, builtin: Set<String> = emptySet()): World {
        val w = World(scope, listOf(appView(alarmPkg, "alarm"), appView(calPkg, "calendar"), appView(notesPkg, "notes")), listOf("alarm", "calendar", "notes"), builtin)
        w.addServer(alarmPkg, "alarm", Samples.alarm)
        w.addServer(calPkg, "calendar", Samples.calendar)
        w.addServer(notesPkg, "notes", Samples.notes)
        return w
    }

    // ------------------------------------------------------------------ 目录

    @Test
    fun `the catalog lists the tools of the three sample apps with ToolNaming names, risks and sources`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val catalog = host.catalog.value
        assertEquals(Samples.alarm.size + Samples.calendar.size + Samples.notes.size, catalog.tools.size)
        val del = catalog["mcp__alarm__alarm__alarm_delete"]!!
        assertEquals(ToolRisk.HIGH, del.risk, "destructiveHint raises to high")
        assertEquals(ToolRisk.WRITE, catalog["mcp__alarm__alarm__alarm_create"]!!.risk, "default is write")
        assertEquals(ToolRisk.WRITE, catalog["mcp__alarm__alarm__alarm_list"]!!.risk, "readOnlyHint never lowers a third party tool")
        assertEquals(ToolRisk.HIGH, catalog["mcp__calendar__calendar__event_delete"]!!.risk)
        assertEquals(ToolRisk.HIGH, catalog["mcp__notes__notes__note_delete"]!!.risk)
        assertEquals(ToolSource("notes", "notes", "note_create"), catalog["mcp__notes__notes__note_create"]!!.source)
        assertEquals(pluginId(notesPkg), catalog["mcp__notes__notes__note_create"]!!.provider)
        assertEquals("does note_create", catalog["mcp__notes__notes__note_create"]!!.description)
        // 名字与 ToolNaming.assign 对整个目录算的结果一致
        val ids = (Samples.alarm.map { ToolId("alarm", "alarm", it.name) } + Samples.calendar.map { ToolId("calendar", "calendar", it.name) } + Samples.notes.map { ToolId("notes", "notes", it.name) })
        assertEquals(ToolNaming.assign(ids).values.toSet(), catalog.tools.map { it.name }.toSet())
    }

    @Test
    fun `the builtin plugin may declare read only tools as read, all others may not`() = runTest {
        val w = World(this, listOf(appView("org.agentos.app", "agentos"), appView(notesPkg, "notes")), listOf("notes"), builtin = setOf("org.agentos.app"))
        w.addServer("org.agentos.app", "agentos", listOf(tool("calendar_read", readOnly = true), tool("open_app"), tool("shell", destructive = true), tool("liar", readOnly = true, destructive = true)))
        w.addServer(notesPkg, "notes", listOf(tool("note_list", readOnly = true)))
        val host = w.host()
        runCurrent()
        val c = host.catalog.value
        assertEquals(ToolRisk.READ, c["mcp__agentos__agentos__calendar_read"]!!.risk)
        assertEquals(ToolRisk.WRITE, c["mcp__agentos__agentos__open_app"]!!.risk)
        assertEquals(ToolRisk.HIGH, c["mcp__agentos__agentos__shell"]!!.risk)
        assertEquals(ToolRisk.HIGH, c["mcp__agentos__agentos__liar"]!!.risk, "contradicting hints: the raising one wins")
        assertEquals(ToolRisk.WRITE, c["mcp__notes__notes__note_list"]!!.risk)
    }

    @Test
    fun `the catalog version only changes when the content changes`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val v = host.catalog.value.version
        assertTrue(v > 0)
        w.policy.update { it } // 没变
        host.refreshNow(1_000, force = true) // 重新取到同样的列表
        runCurrent()
        assertEquals(v, host.catalog.value.version)
        w.policy.update { it.withEnabled(PolicyScope.of(ToolSource("notes", "notes", "note_trash")), false) }
        runCurrent()
        assertEquals(v + 1, host.catalog.value.version)
        assertNull(host.catalog.value["mcp__notes__notes__note_trash"])
    }

    @Test
    fun `untrusted text is truncated, duplicates and oversized schemas dropped, the number of tools capped`() = runTest {
        val w = World(this, listOf(appView(notesPkg, "notes")), listOf("notes"))
        val big = buildJsonObject { put("type", "object"); put("padding", "x".repeat(2_000)) }
        w.addServer(notesPkg, "notes", listOf(
            tool("a", description = "d".repeat(5_000)),
            tool("a", description = "duplicate"),
            tool("", description = "no name"),
            tool("huge", schema = big),
            tool("b").copy(title = "t".repeat(500)),
        ) + (1..10).map { tool("t$it") })
        val host = w.host(ExtensionHostConfig(maxDescriptionChars = 100, maxTitleChars = 20, maxSchemaChars = 1_000, maxToolsPerServer = 5))
        runCurrent()
        val tools = host.catalog.value.tools
        assertEquals(5, tools.size, "capped")
        val a = host.catalog.value["mcp__notes__notes__a"]!!
        assertEquals(100, a.description.length)
        assertEquals(20, host.catalog.value["mcp__notes__notes__b"]!!.title!!.length)
        assertNull(host.catalog.value["mcp__notes__notes__huge"])
        assertTrue(tools.none { it.description == "duplicate" })
    }

    // ------------------------------------------------------------------ 策略

    @Test
    fun `disabled plugins, servers and tools are not offered, a disabled server is never connected`() = runTest {
        val w = World(this, listOf(appView(notesPkg, "notes", serverNames = listOf("main", "extra")), appView(alarmPkg, "alarm")), listOf("notes", "alarm"))
        w.addServer(notesPkg, "main", listOf(tool("note_create"), tool("note_trash")))
        w.addServer(notesPkg, "extra", listOf(tool("tag_list")))
        w.addServer(alarmPkg, "alarm", Samples.alarm)
        w.policy.update {
            it.withEnabled(PolicyScope.Plugin("alarm"), false)
                .withEnabled(PolicyScope.Server("notes", "extra"), false)
                .withEnabled(PolicyScope.Tool("notes", "main", "note_trash"), false)
        }
        val host = w.host()
        runCurrent()
        assertEquals(listOf("mcp__notes__main__note_create"), host.catalog.value.tools.map { it.name })
        assertEquals(0, w.connector.server(alarmPkg, "alarm").connects.get(), "never connected")
        assertEquals(0, w.connector.server(notesPkg, "extra").connects.get())
        val states = host.serverStates.value
        assertEquals(ServerState.Disabled(DisabledReason.USER_POLICY), states[ServerKey(pluginId(alarmPkg), "alarm")])
        assertEquals(ServerState.Disabled(DisabledReason.USER_POLICY), states[ServerKey(pluginId(notesPkg), "extra")])
        assertEquals(ServerState.Connected, states[ServerKey(pluginId(notesPkg), "main")])
        // 启用之后：先连一次取工具列表，工具出现
        w.policy.update { it.cleared(PolicyScope.Plugin("alarm")).withEnabled(PolicyScope.Plugin("alarm"), true) }
        runCurrent()
        assertEquals(1, w.connector.server(alarmPkg, "alarm").connects.get())
        assertTrue(host.catalog.value.tools.any { it.name == "mcp__alarm__alarm__alarm_list" })
    }

    @Test
    fun `switching a server off does not rename the others, because naming covers every known tool`() = runTest {
        // 同一个插件的两个服务器 s.1 和 s_1：处理后的名字相同，两个工具都带哈希后缀
        val w = World(this, listOf(appView(notesPkg, "notes", serverNames = listOf("s.1", "s_1"))), listOf("notes"))
        w.addServer(notesPkg, "s.1", listOf(tool("t")))
        w.addServer(notesPkg, "s_1", listOf(tool("t")))
        val host = w.host()
        runCurrent()
        val before = host.catalog.value.tools.associate { it.source!!.server to it.name }
        assertEquals(2, before.size)
        assertTrue(before.values.all { Regex("mcp__notes__s_1__t_[0-9a-f]{6}").matches(it) }, before.toString())
        w.policy.update { it.withEnabled(PolicyScope.Server("notes", "s_1"), false) }
        runCurrent()
        val after = host.catalog.value.tools.associate { it.source!!.server to it.name }
        assertEquals(setOf("s.1"), after.keys)
        assertEquals(before["s.1"], after["s.1"], "the other tool keeps its name")
    }

    @Test
    fun `a plugin that is not ready has no tools and its servers show as not ready`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        assertTrue(host.catalog.value.tools.any { it.provider == pluginId(notesPkg) })
        w.rescan(listOf(appView(alarmPkg, "alarm"), appView(calPkg, "calendar"), appView(notesPkg, "notes", signer = "other-signer")))
        runCurrent()
        assertTrue(host.catalog.value.tools.none { it.provider == pluginId(notesPkg) }, "signature changed: gone")
        assertEquals(ServerState.Disabled(DisabledReason.PLUGIN_NOT_READY), host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
        assertFalse(w.connector.server(notesPkg, "notes").links.any { !it.closed.isCompleted }, "its connection is closed")
        assertTrue(host.catalog.value.tools.any { it.provider == pluginId(alarmPkg) })
    }

    // ------------------------------------------------------------------ 调用的三种结局

    @Test
    fun `completed, with isError kept as it is, and a JSON-RPC error as an error result`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        val ok = host.invoke(invocation("mcp__notes__notes__note_create"))
        assertIs<ToolInvocationResult.Completed>(ok)
        assertEquals("ok:note_create", ok.text())
        assertEquals("note_create", server.calls.single().first)
        assertEquals(buildJsonObject { put("a", 1) }, server.calls.single().second)

        server.handler = { _, _ -> McpCallResult(listOf(McpContentPart.Text("no such note")), isError = true) }
        val failed = host.invoke(invocation("mcp__notes__notes__note_delete")) as ToolInvocationResult.Completed
        assertTrue(failed.result.isError)
        assertEquals("no such note", failed.text())

        server.failNextCall = ServerError(-32602, "unknown tool")
        val rpc = host.invoke(invocation("mcp__notes__notes__note_create")) as ToolInvocationResult.Completed
        assertTrue(rpc.result.isError)
        assertTrue(rpc.text().startsWith("[mcp error -32602]"), rpc.text())
    }

    @Test
    fun `not dispatched when the tool is unknown, switched off, or the server cannot be reached`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        // 不在目录里
        val unknown = host.invoke(invocation("mcp__notes__notes__nope")) as ToolInvocationResult.NotDispatched
        assertEquals(ErrorCode.TOOL_NOT_IN_CATALOG, unknown.error.code)
        // 策略禁用（目录刚变，调用前再查一次）
        w.policy.update { it.withEnabled(PolicyScope.Tool("notes", "notes", "note_trash"), false) }
        val disabled = host.invoke(invocation("mcp__notes__notes__note_trash")) as ToolInvocationResult.NotDispatched
        assertEquals(ErrorCode.TOOL_NOT_IN_CATALOG, disabled.error.code)
        // 连不上：缓存的工具还在目录里，调用被拒绝
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(ServerState.Idle, host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
        server.connectFailure = ConnectFailed("app disabled")
        val unreachable = host.invoke(invocation("mcp__notes__notes__note_create")) as ToolInvocationResult.NotDispatched
        assertEquals(ErrorCode.TOOL_UNAVAILABLE, unreachable.error.code)
        assertTrue(unreachable.error.message.contains("app disabled"))
        assertNotNull(host.catalog.value["mcp__notes__notes__note_create"], "cached tools stay while the server is unreachable")
        assertIs<ServerState.Unreachable>(host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
        assertEquals(0, server.calls.size, "nothing was sent")
        // NotSent 从链接来：也是确定没发出
        server.connectFailure = null
        server.failNextCall = NotSent("closed")
        val notSent = host.invoke(invocation("mcp__notes__notes__note_create")) as ToolInvocationResult.NotDispatched
        assertEquals(ErrorCode.TOOL_UNAVAILABLE, notSent.error.code)
    }

    private fun assertNotNull(v: Any?, message: String) = assertTrue(v != null, message)

    @Test
    fun `a connect that takes too long is a failed connect, nothing was sent`() = runTest {
        val w = threeApps(this)
        val host = w.host(ExtensionHostConfig(connectTimeoutMillis = 5_000))
        runCurrent()
        advanceTimeBy(31_000)
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        server.connectDelayMillis = 60_000
        val r = async { host.invoke(invocation("mcp__notes__notes__note_create")) }
        advanceTimeBy(5_001)
        runCurrent()
        val result = r.await() as ToolInvocationResult.NotDispatched
        assertEquals(ErrorCode.TOOL_UNAVAILABLE, result.error.code)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `unknown when the connection drops after the request was sent, and it is never replayed`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        server.handler = { _, _ -> delay(10_000); McpCallResult(listOf(McpContentPart.Text("late"))) }
        val r = async { host.invoke(invocation("mcp__notes__notes__note_create")) }
        runCurrent()
        advanceTimeBy(1_000)
        server.kill("process died")
        runCurrent()
        val result = r.await() as ToolInvocationResult.Unknown
        assertEquals(ErrorCode.TOOL_RESULT_UNKNOWN, result.error.code)
        assertEquals(1, server.calls.size, "sent once, not replayed")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, server.calls.size)
    }

    @Test
    fun `unknown when the link keeps the call pending although the connection was closed`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        server.ignoresClose = true
        server.handler = { _, _ -> delay(1_000_000); McpCallResult(emptyList()) }
        val r = async { host.invoke(invocation("mcp__notes__notes__note_create")) }
        runCurrent()
        server.kill()
        runCurrent()
        assertEquals(ErrorCode.TOOL_RESULT_UNKNOWN, (r.await() as ToolInvocationResult.Unknown).error.code)
    }

    @Test
    fun `unknown with tool_timeout when no response arrives in time`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        server.handler = { _, _ -> delay(100_000); McpCallResult(emptyList()) }
        val r = async { host.invoke(invocation("mcp__notes__notes__note_create", timeout = 5_000)) }
        advanceTimeBy(5_001)
        runCurrent()
        val result = r.await() as ToolInvocationResult.Unknown
        assertEquals(ErrorCode.TOOL_TIMEOUT, result.error.code)
        assertEquals(1, server.calls.size)
    }

    @Test
    fun `a connection loss reported by the link is unknown too`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        w.connector.server(notesPkg, "notes").failNextCall = ConnectionLost("binder died")
        val r = host.invoke(invocation("mcp__notes__notes__note_create")) as ToolInvocationResult.Unknown
        assertEquals(ErrorCode.TOOL_RESULT_UNKNOWN, r.error.code)
    }

    // ------------------------------------------------------------------ 取消、并发、结果

    @Test
    fun `cancelling the caller is forwarded to the provider and the call ends with a cancellation`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        server.handler = { _, _ -> delay(100_000); McpCallResult(emptyList()) }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { host.invoke(invocation("mcp__notes__notes__note_create")) }
        runCurrent()
        assertEquals(1, server.concurrent.get())
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf("note_create"), server.cancelledCalls, "the link saw the cancellation (it sends notifications/cancelled)")
        assertEquals(0, server.concurrent.get())
        // 在途计数回来了：30 秒后照常空闲回收
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(ServerState.Idle, host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
    }

    @Test
    fun `at most eight calls per server are in flight, the rest wait`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        server.handler = { name, _ -> delay(1_000); McpCallResult(listOf(McpContentPart.Text(name))) }
        val calls = (1..20).map { async { host.invoke(invocation("mcp__notes__notes__note_create", id = "c$it")) } }
        runCurrent()
        assertEquals(8, server.maxConcurrent.get())
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(calls.all { it.await() is ToolInvocationResult.Completed })
        assertEquals(8, server.maxConcurrent.get(), "never more than eight")
        assertEquals(20, server.calls.size)
        // 不同服务器互不占用名额
        assertEquals(0, w.connector.server(alarmPkg, "alarm").calls.size)
    }

    @Test
    fun `big results are cut with an explanation, in total under the limit, other content is summarised`() = runTest {
        val w = threeApps(this)
        val host = w.host(ExtensionHostConfig(maxResultChars = 1_000))
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        server.handler = { _, _ ->
            McpCallResult(listOf(McpContentPart.Text("a".repeat(900)), McpContentPart.Text("b".repeat(900)), McpContentPart.Image("AAAA", "image/png"), McpContentPart.Other("audio", buildJsonObject { put("type", "audio") })))
        }
        val r = (host.invoke(invocation("mcp__notes__notes__note_list")) as ToolInvocationResult.Completed).result
        val texts = r.content.filterIsInstance<ContentPart.Text>()
        assertTrue(texts.sumOf { it.text.length } <= 1_000, "total ${texts.sumOf { it.text.length }}")
        assertTrue(texts.last().text.contains("[agentos:tool_result_too_large] Result truncated from"))
        assertTrue(r.content.any { it is ContentPart.Image }, "images are kept")
        // 小结果里的“其他类型”换成一句说明；只有结构化内容时用它当文字
        server.handler = { _, _ -> McpCallResult(listOf(McpContentPart.Other("audio", buildJsonObject { put("type", "audio") }))) }
        assertEquals("[the tool returned audio content, omitted]", host.invoke(invocation("mcp__notes__notes__note_list")).text())
        server.handler = { _, _ -> McpCallResult(emptyList(), structuredContent = buildJsonObject { put("count", 3) }) }
        val structured = host.invoke(invocation("mcp__notes__notes__note_list")) as ToolInvocationResult.Completed
        assertEquals("""{"count":3}""", structured.text())
        assertEquals(buildJsonObject { put("count", 3) }, structured.result.details)
    }

    // ------------------------------------------------------------------ 连接生命周期

    @Test
    fun `it connects once at start to learn the tools, drops the connection after 30 idle seconds and reconnects on use`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        assertEquals(1, server.connects.get(), "one connection to build the catalog")
        assertEquals(1, server.listCalls.get())
        assertEquals(ServerState.Connected, host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(ServerState.Connected, host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
        advanceTimeBy(2)
        runCurrent()
        assertEquals(ServerState.Idle, host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
        assertTrue(server.links.single().closed.isCompleted, "unbound")
        assertEquals(Samples.notes.size, host.catalog.value.tools.count { it.provider == pluginId(notesPkg) }, "the cached tools stay")
        // 用到时再连
        val r = host.invoke(invocation("mcp__notes__notes__note_create"))
        assertIs<ToolInvocationResult.Completed>(r)
        assertEquals(2, server.connects.get())
        runCurrent()
        assertEquals(ServerState.Connected, host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(ServerState.Idle, host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
    }

    @Test
    fun `a call in flight keeps the connection, the idle clock starts when it ends`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        server.handler = { _, _ -> delay(45_000); McpCallResult(listOf(McpContentPart.Text("slow"))) }
        val r = async { host.invoke(invocation("mcp__notes__notes__note_create", timeout = 120_000)) }
        runCurrent()
        advanceTimeBy(44_000)
        runCurrent()
        assertEquals(ServerState.Connected, host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")], "still in flight after 44 s")
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals("slow", r.await().text())
        assertEquals(ServerState.Connected, host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(ServerState.Idle, host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
    }

    @Test
    fun `list changed refreshes the catalog, also when tools are removed`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        val v = host.catalog.value.version
        server.tools = Samples.notes + tool("note_pin")
        server.toolsChanged()
        runCurrent()
        assertNotNull(host.catalog.value["mcp__notes__notes__note_pin"], "added")
        assertEquals(v + 1, host.catalog.value.version)
        server.tools = Samples.notes.filter { it.name != "note_trash" }
        server.toolsChanged()
        runCurrent()
        assertNull(host.catalog.value["mcp__notes__notes__note_trash"], "removed")
        assertNull(host.catalog.value["mcp__notes__notes__note_pin"])
    }

    @Test
    fun `after the app process dies the cached tools stay, the state says so, and the next call reconnects`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        server.kill("app killed")
        runCurrent()
        val state = host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")]
        assertIs<ServerState.Unreachable>(state)
        assertTrue(state.reason.contains("app killed"))
        assertNotNull(host.catalog.value["mcp__notes__notes__note_create"], "still listed")
        val r = host.invoke(invocation("mcp__notes__notes__note_create"))
        assertIs<ToolInvocationResult.Completed>(r)
        assertEquals(2, server.connects.get())
        runCurrent()
        assertEquals(ServerState.Connected, host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
    }

    @Test
    fun `a server that never connected is not listed, refreshNow retries with a back off, force ignores it`() = runTest {
        val w = threeApps(this)
        val server = w.connector.server(notesPkg, "notes")
        server.connectFailure = ConnectFailed("not installed yet")
        val host = w.host()
        runCurrent()
        assertTrue(host.catalog.value.tools.none { it.provider == pluginId(notesPkg) }, "never succeeded: not listed")
        assertEquals(ServerState.Unreachable("not installed yet"), host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
        // 其他服务器不受影响
        assertTrue(host.catalog.value.tools.any { it.provider == pluginId(alarmPkg) })

        server.connectFailure = null
        val tooSoon = host.refreshNow(1_000)
        assertTrue(tooSoon.refreshed.isEmpty() && tooSoon.failed.isEmpty(), "inside the back off: not retried")
        assertEquals(0, server.connects.get())
        advanceTimeBy(30_001)
        val retried = host.refreshNow(1_000)
        assertEquals(listOf(ServerKey(pluginId(notesPkg), "notes")), retried.refreshed)
        assertFalse(retried.timedOut)
        assertEquals(Samples.notes.size, host.catalog.value.tools.count { it.provider == pluginId(notesPkg) })
        // force：已有缓存的也刷新
        val before = server.listCalls.get()
        host.refreshNow(1_000, force = true)
        assertTrue(server.listCalls.get() > before || server.connects.get() > 1)
    }

    @Test
    fun `refreshNow waits for servers without a cache, gives up at the limit and the refresh goes on`() = runTest {
        val w = threeApps(this)
        w.connector.server(notesPkg, "notes").connectDelayMillis = 20_000
        val host = w.host(ExtensionHostConfig(connectTimeoutMillis = 60_000))
        runCurrent()
        assertTrue(host.catalog.value.tools.none { it.provider == pluginId(notesPkg) })
        val r = async { host.refreshNow(5_000) }
        advanceTimeBy(5_001)
        runCurrent()
        val result = r.await()
        assertTrue(result.timedOut)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(Samples.notes.size, host.catalog.value.tools.count { it.provider == pluginId(notesPkg) }, "the background refresh finished by itself")
        // 都有缓存了：refreshNow 立刻返回，不重连
        val connects = w.connector.server(alarmPkg, "alarm").connects.get()
        val again = host.refreshNow(1_000)
        assertEquals(RefreshResult(emptyList(), emptyList(), false), again)
        assertEquals(connects, w.connector.server(alarmPkg, "alarm").connects.get())
    }

    @Test
    fun `a failing list leaves the old cache and marks the server unreachable`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        server.listFailure = ConnectionLost("list broke")
        server.toolsChanged()
        runCurrent()
        assertEquals(Samples.notes.size, host.catalog.value.tools.count { it.provider == pluginId(notesPkg) })
        assertIs<ServerState.Unreachable>(host.serverStates.value[ServerKey(pluginId(notesPkg), "notes")])
    }

    // ------------------------------------------------------------------ Revoke 与升级

    @Test
    fun `a revoke closes the connection, drops the tools and turns calls in flight into unknown`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        server.ignoresClose = true // 即使链接没有让挂起的调用抛异常
        server.handler = { _, _ -> delay(1_000_000); McpCallResult(emptyList()) }
        val r = async { host.invoke(invocation("mcp__notes__notes__note_create")) }
        runCurrent()
        host.onRegistryEvent(RegistryEvent.Revoke(pluginId(notesPkg), "notes", RevokeReason.SIGNATURE_CHANGED))
        runCurrent()
        assertEquals(ErrorCode.TOOL_RESULT_UNKNOWN, (r.await() as ToolInvocationResult.Unknown).error.code)
        assertTrue(server.links.all { it.closed.isCompleted })
        assertTrue(host.catalog.value.tools.none { it.provider == pluginId(notesPkg) }, "removed at once, before the registry even changed")
        val next = host.invoke(invocation("mcp__notes__notes__note_create"))
        assertIs<ToolInvocationResult.NotDispatched>(next)
        // 其他插件不受影响；注册表里它仍可用时下次会重新取列表
        assertTrue(host.catalog.value.tools.any { it.provider == pluginId(alarmPkg) })
        host.refreshNow(1_000)
        assertTrue(host.catalog.value.tools.any { it.provider == pluginId(notesPkg) })
    }

    @Test
    fun `events other than revoke are ignored`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val v = host.catalog.value.version
        host.onRegistryEvent(RegistryEvent.Added(pluginId(notesPkg), "notes"))
        host.onRegistryEvent(RegistryEvent.Updated(pluginId(notesPkg), 1, 2))
        host.onRegistryEvent(RegistryEvent.MemoryLost)
        runCurrent()
        assertEquals(v, host.catalog.value.version)
    }

    @Test
    fun `an upgrade that changes the service drops the cache and connects again`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        val server = w.connector.server(notesPkg, "notes")
        assertEquals(1, server.connects.get())
        w.rescan(listOf(appView(alarmPkg, "alarm"), appView(calPkg, "calendar"), appView(notesPkg, "notes", versionCode = 2)))
        runCurrent()
        assertEquals(2, server.connects.get(), "new version: connect again to read the tools")
        assertEquals(Samples.notes.size, host.catalog.value.tools.count { it.provider == pluginId(notesPkg) })
        assertEquals(1, w.connector.server(alarmPkg, "alarm").connects.get(), "unchanged plugins keep their cache and are not reconnected")
    }

    @Test
    fun `closing the host closes every connection`() = runTest {
        val w = threeApps(this)
        val host = w.host()
        runCurrent()
        host.close()
        assertTrue(w.connector.servers.values.all { s -> s.links.all { it.closed.isCompleted } })
    }
}
