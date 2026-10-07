package org.agentos.extensions.host

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.agentos.extensions.registry.RegistryEvent
import org.agentos.extensions.registry.RevokeReason
import org.agentos.runtime.broker.ApprovalMode
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [ExtensionToolHost.knownTools]：Host 知道的全部工具（含被策略禁用的），名字与目录一致、不随禁用、断开、重建而变。 */
class KnownToolsTest {
    private val notesPkg = "org.x.notes"
    private val alarmPkg = "org.x.alarm"

    private fun notesWorld(scope: kotlinx.coroutines.test.TestScope) =
        World(scope, listOf(appView(notesPkg, "notes")), listOf("notes")).also { it.addServer(notesPkg, "notes", Samples.notes) }

    private val noteTrash = PolicyScope.Tool("notes", "notes", "note_trash")
    private val notesId = pluginId(notesPkg)

    private fun ExtensionToolHost.byName() = knownTools(notesId).associateBy { it.name }

    @Test
    fun `every tool of a server is known, with the catalog's name, risk and source`() = runTest {
        val w = notesWorld(this)
        val host = w.host()
        runCurrent()
        val known = host.knownTools()
        assertEquals(Samples.notes.size, known.size)
        assertEquals(known.map { it.name }, known.map { it.name }.sorted(), "sorted by name")
        val fromCatalog = host.catalog.value.tools.associateBy { it.name }
        for (k in known) {
            val c = assertNotNull(fromCatalog[k.name], "known tool ${k.name} is in the catalog while nothing is disabled")
            assertEquals(c.risk, k.risk)
            assertEquals(c.source, k.source)
            assertEquals(c.title, k.title)
            assertEquals(c.description, k.description)
            assertEquals(c.inputSchema, k.inputSchema)
            assertEquals(c.provider, k.pluginId)
            assertTrue(k.enabled)
            assertEquals(ApprovalMode.ASK, k.approval)
        }
        val delete = known.single { it.source.tool == "note_delete" }
        assertEquals(ToolRisk.HIGH, delete.risk)
        assertFalse(delete.mayAlwaysAllow, "a destructive tool cannot be set to always allow")
        assertTrue(known.single { it.source.tool == "note_create" }.mayAlwaysAllow)
        assertEquals("mcp__notes__notes__note_create", known.single { it.source.tool == "note_create" }.name)
    }

    @Test
    fun `a tool the user switched off is still known, with the same name, and is not in the catalog`() = runTest {
        val w = notesWorld(this)
        val host = w.host()
        runCurrent()
        val before = host.byName().getValue("mcp__notes__notes__note_trash")
        w.policy.update { it.withEnabled(noteTrash, false) }
        runCurrent()
        assertTrue(host.catalog.value.tools.none { it.name == "mcp__notes__notes__note_trash" })
        val after = host.byName()
        assertEquals(setOf(before.name) + (Samples.notes.map { "mcp__notes__notes__" + it.name }).toSet(), after.keys + before.name)
        assertEquals(Samples.notes.size, after.size, "nothing disappeared from the known list")
        val k = after.getValue("mcp__notes__notes__note_trash")
        assertFalse(k.enabled)
        assertEquals(before.copy(enabled = false), k, "only the enabled flag changed")
        assertTrue(after.filterKeys { it != k.name }.values.all { it.enabled })
        // 再打开：回到目录，名字还是它
        w.policy.update { it.cleared(noteTrash) }
        runCurrent()
        assertTrue(host.byName().getValue(k.name).enabled)
        assertTrue(host.catalog.value.tools.any { it.name == k.name })
    }

    @Test
    fun `after the user switched a tool off and the connection was dropped it is still known`() = runTest {
        val w = notesWorld(this)
        val server = w.connector.server(notesPkg, "notes")
        val host = w.host()
        runCurrent()
        w.policy.update { it.withEnabled(noteTrash, false) }
        runCurrent()
        advanceTimeBy(31_000) // 空闲 30 秒：连接断开（unbind）
        runCurrent()
        assertEquals(null, server.live, "the connection was dropped")
        assertEquals(ServerState.Idle, host.serverStates.value.getValue(ServerKey(notesId, "notes")))
        val k = host.byName().getValue("mcp__notes__notes__note_trash")
        assertFalse(k.enabled)
        assertEquals(Samples.notes.size, host.knownTools(notesId).size)
        // 连接死亡（App 进程被杀）也一样
        host.refreshNow(5_000, force = true)
        runCurrent()
        server.kill()
        runCurrent()
        assertEquals(Samples.notes.size, host.knownTools(notesId).size)
        assertEquals(k.name, host.byName().keys.single { it.endsWith("note_trash") })
    }

    @Test
    fun `a server that cannot be reached any more keeps its known tools`() = runTest {
        val w = notesWorld(this)
        val server = w.connector.server(notesPkg, "notes")
        val host = w.host()
        runCurrent()
        val names = host.knownTools().map { it.name }
        server.kill()
        server.connectFailure = ConnectFailed("app is gone")
        w.policy.update { it.withEnabled(noteTrash, false) }
        runCurrent()
        host.refreshNow(5_000, force = true)
        runCurrent()
        assertTrue(host.serverStates.value.getValue(ServerKey(notesId, "notes")) is ServerState.Unreachable)
        assertEquals(names, host.knownTools().map { it.name })
        assertFalse(host.knownTools().single { it.source.tool == "note_trash" }.enabled)
    }

    @Test
    fun `switching the whole plugin or server off keeps the known tools and does not reconnect`() = runTest {
        val w = notesWorld(this)
        val server = w.connector.server(notesPkg, "notes")
        val host = w.host()
        runCurrent()
        val names = host.knownTools().map { it.name }
        val connects = server.connects.get()
        w.policy.update { it.withEnabled(PolicyScope.Plugin("notes"), false) }
        runCurrent()
        assertTrue(host.catalog.value.tools.isEmpty())
        assertEquals(names, host.knownTools(notesId).map { it.name }, "known although the plugin is off")
        assertTrue(host.knownTools(notesId).none { it.enabled })
        w.policy.update { it.cleared(PolicyScope.Plugin("notes")).withEnabled(PolicyScope.Server("notes", "notes"), false) }
        runCurrent()
        assertEquals(names, host.knownTools(notesId).map { it.name })
        assertTrue(host.knownTools(notesId).none { it.enabled })
        assertEquals(connects, server.connects.get(), "known tools never connect")
    }

    @Test
    fun `a rebuilt host lists the switched-off tool again under the same name once its server was read`() = runTest {
        val w = notesWorld(this)
        val first = w.host()
        runCurrent()
        w.policy.update { it.withEnabled(noteTrash, false).withApproval(PolicyScope.Tool("notes", "notes", "note_append"), ApprovalMode.ALWAYS) }
        runCurrent()
        val before = first.knownTools(notesId)
        first.close() // :ext 进程重建

        val second = w.host()
        assertTrue(second.knownTools(notesId).isEmpty(), "a new host starts without a cache")
        runCurrent()
        val after = second.knownTools(notesId)
        assertEquals(before, after, "same tools, same names, same flags")
        assertFalse(after.single { it.source.tool == "note_trash" }.enabled)
        assertEquals(ApprovalMode.ALWAYS, after.single { it.source.tool == "note_append" }.approval)
        assertTrue(second.catalog.value.tools.none { it.source!!.tool == "note_trash" })
    }

    @Test
    fun `a plugin that was off when the host was rebuilt is not read, so it has no known tools until it was enabled once`() = runTest {
        val w = notesWorld(this)
        w.policy.update { it.withEnabled(PolicyScope.Plugin("notes"), false) }
        val host = w.host()
        runCurrent()
        assertEquals(0, w.connector.server(notesPkg, "notes").connects.get(), "a disabled plugin is never connected")
        assertTrue(host.knownTools(notesId).isEmpty())
        // 启用一次：读到列表；再关掉：还在
        w.policy.update { it.cleared(PolicyScope.Plugin("notes")).withEnabled(PolicyScope.Plugin("notes"), true) }
        runCurrent()
        val names = host.knownTools(notesId).map { it.name }
        assertEquals(Samples.notes.size, names.size)
        w.policy.update { it.withEnabled(PolicyScope.Plugin("notes"), false) }
        runCurrent()
        assertEquals(names, host.knownTools(notesId).map { it.name })
    }

    @Test
    fun `names stay the same across the colliding-names case when one of them is switched off`() = runTest {
        // 同一个插件的服务器 s.1 和 s_1：处理后的名字相同，两个工具都带哈希后缀
        val w = World(this, listOf(appView(notesPkg, "notes", serverNames = listOf("s.1", "s_1"))), listOf("notes"))
        w.addServer(notesPkg, "s.1", listOf(tool("t")))
        w.addServer(notesPkg, "s_1", listOf(tool("t")))
        val host = w.host()
        runCurrent()
        val before = host.knownTools(notesId).associate { it.source.server to it.name }
        assertEquals(2, before.size)
        assertTrue(before.values.all { Regex("mcp__notes__s_1__t_[0-9a-f]{6}").matches(it) }, before.toString())
        w.policy.update { it.withEnabled(PolicyScope.Server("notes", "s_1"), false) }
        runCurrent()
        assertEquals(before, host.knownTools(notesId).associate { it.source.server to it.name })
        assertEquals(listOf("s.1"), host.catalog.value.tools.map { it.source!!.server })
    }

    @Test
    fun `known tools are per plugin, and what is no longer a ready plugin is forgotten`() = runTest {
        val w = World(this, listOf(appView(alarmPkg, "alarm"), appView(notesPkg, "notes")), listOf("alarm", "notes"))
        w.addServer(alarmPkg, "alarm", Samples.alarm)
        w.addServer(notesPkg, "notes", Samples.notes)
        val host = w.host()
        runCurrent()
        assertEquals(Samples.alarm.size, host.knownTools(pluginId(alarmPkg)).size)
        assertEquals(Samples.notes.size, host.knownTools(pluginId(notesPkg)).size)
        assertEquals(Samples.alarm.size + Samples.notes.size, host.knownTools().size)
        assertTrue(host.knownTools("org.nobody/agent-plugin").isEmpty())
        assertTrue(host.knownTools(pluginId(alarmPkg)).all { it.pluginId == pluginId(alarmPkg) && it.source.plugin == "alarm" })

        // 签名变了：插件不再可用，缓存丢弃
        w.rescan(listOf(appView(alarmPkg, "alarm", signer = "sig-2"), appView(notesPkg, "notes")))
        runCurrent()
        assertTrue(host.knownTools(pluginId(alarmPkg)).isEmpty(), "a signature change drops the cache")
        assertEquals(Samples.notes.size, host.knownTools(pluginId(notesPkg)).size)
    }

    @Test
    fun `revoke forgets the plugin's tools`() = runTest {
        val w = notesWorld(this)
        val host = w.host()
        runCurrent()
        assertEquals(Samples.notes.size, host.knownTools(notesId).size)
        host.onRegistryEvent(RegistryEvent.Revoke(notesId, "notes", RevokeReason.SIGNATURE_CHANGED))
        runCurrent()
        assertTrue(host.knownTools(notesId).isEmpty())
    }

    @Test
    fun `knownTools needs no connection and does not start one`() = runTest {
        val w = notesWorld(this)
        val host = w.host()
        val server = w.connector.server(notesPkg, "notes")
        assertTrue(host.knownTools().isEmpty(), "before the first read there is nothing")
        runCurrent()
        val connects = server.connects.get()
        advanceTimeBy(31_000)
        runCurrent()
        repeat(5) { host.knownTools() }
        runCurrent()
        assertEquals(connects, server.connects.get())
    }

    @Test
    fun `the tool list is the one the app reported last, a changed list changes the known tools`() = runTest {
        val w = notesWorld(this)
        val server = w.connector.server(notesPkg, "notes")
        val host = w.host()
        runCurrent()
        server.tools = Samples.notes.filter { it.name != "note_trash" } + tool("note_pin")
        server.toolsChanged()
        runCurrent()
        val names = host.knownTools(notesId).map { it.source.tool }
        assertTrue("note_pin" in names && "note_trash" !in names, names.toString())
        assertEquals(ToolSource("notes", "notes", "note_pin"), host.knownTools(notesId).single { it.source.tool == "note_pin" }.source)
    }
}
