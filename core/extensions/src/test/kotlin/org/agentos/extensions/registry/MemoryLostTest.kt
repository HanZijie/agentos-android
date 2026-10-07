package org.agentos.extensions.registry

import org.agentos.runtime.broker.ApprovalMode
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.DefaultCapabilityBroker
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.testing.FakeHostPort
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 持久化的记忆丢失（scan 的 previous == null）：第三方插件一律未确认，自带插件不受影响。 */
class MemoryLostTest {
    private val schema = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"
    private val perm = PluginScanLogic.BIND_PERMISSION
    private val host = FakeHostPort()

    @AfterTest
    fun cleanUp() = host.deleteDatabase()

    private fun app(pkg: String, name: String, signer: String = "sig-1", versionCode: Long = 1, pluginJson: String? = null) = InstalledAppView(
        PluginIdentity(pkg, signer, versionCode),
        listOf(PluginServiceInfo("$pkg.Mcp", true, perm, isPluginAnchor = true, assetsDir = "agent-plugin")),
        mapOf(
            "agent-plugin" to PluginAssets(
                pluginJson ?: """{"${'$'}schema":"$schema","name":"$name","extensions":{"org.agentos":{"mcpServers":{"$name":{"service":"$pkg.Mcp"}}}}}""",
            ),
        ),
    )

    private val builtinApp = app("org.agentos.app", "agentos")
    private val notesApp = app("org.x.notes", "notes")
    private val alarmApp = app("org.x.alarm", "alarm")
    private val builtin = setOf("org.agentos.app")

    /** 用户原来启用并设成“始终允许”的状态。 */
    private fun established(): Triple<PersistedRegistry, ApprovalPolicy, ScanResult> {
        val first = PluginScanLogic.scan(listOf(builtinApp, notesApp, alarmApp), PersistedRegistry.EMPTY, ApprovalPolicy.DEFAULT, builtin)
        val policy = first.policy
            .withEnabled(PolicyScope.Plugin("notes"), true).withApproval(PolicyScope.Plugin("notes"), ApprovalMode.ALWAYS)
            .withEnabled(PolicyScope.Plugin("alarm"), true)
        return Triple(first.persisted, policy, first)
    }

    private fun ScanResult.record(id: String) = registry[id]!!

    @Test
    fun `without a memory every third party plugin is unconfirmed, disabled and has no usable server`() {
        val (_, policy, _) = established()
        val r = PluginScanLogic.scan(listOf(builtinApp, notesApp, alarmApp), previous = null, policy = policy, builtinPackages = builtin)
        for (id in listOf("org.x.notes/agent-plugin", "org.x.alarm/agent-plugin")) {
            val rec = r.record(id)
            assertEquals(PluginStatus.SIGNATURE_UNCONFIRMED, rec.status, id)
            assertTrue(rec.activeServers.isEmpty())
            assertTrue(rec.problems.any { it.code == "signature_unconfirmed" })
            assertEquals(PersistedPlugin.UNCONFIRMED, rec.trustedSigner)
        }
        // 策略：清空并停用（原来的“始终允许”也作废）
        assertEquals(false, r.policy.entryOf(PolicyScope.Plugin("notes")).enabled)
        assertNull(r.policy.entryOf(PolicyScope.Plugin("notes")).approval)
        assertEquals(false, r.policy.entryOf(PolicyScope.Plugin("alarm")).enabled)
        // 新的记忆里记着“还没确认”，而不是把当前签名当作可信
        assertTrue(r.persisted.plugins.filter { it.packageName != "org.agentos.app" }.all { it.trustedSigner == PersistedPlugin.UNCONFIRMED && it.observedSigner == "sig-1" })
    }

    @Test
    fun `the builtin plugin is not affected by a lost memory`() {
        val (_, policy, _) = established()
        val r = PluginScanLogic.scan(listOf(builtinApp, notesApp), null, policy, builtin)
        val rec = r.record("org.agentos.app/agent-plugin")
        assertEquals(PluginStatus.READY, rec.status)
        assertTrue(rec.builtin)
        assertEquals(listOf("agentos"), rec.activeServers.map { it.name })
        assertNull(r.policy.plugins["agentos"], "no policy written for the builtin plugin")
        assertTrue(r.events.none { it is RegistryEvent.Revoke && it.pluginId.startsWith("org.agentos.app") })
    }

    @Test
    fun `the events say what happened, once, and tell the host to revoke each third party plugin`() {
        val (_, policy, _) = established()
        val r = PluginScanLogic.scan(listOf(builtinApp, notesApp, alarmApp), null, policy, builtin)
        assertEquals(RegistryEvent.MemoryLost, r.events.first())
        assertEquals(1, r.events.count { it === RegistryEvent.MemoryLost })
        assertEquals(
            listOf(
                RegistryEvent.Revoke("org.x.alarm/agent-plugin", "alarm", RevokeReason.MEMORY_LOST),
                RegistryEvent.Revoke("org.x.notes/agent-plugin", "notes", RevokeReason.MEMORY_LOST),
            ),
            r.events.filterIsInstance<RegistryEvent.Revoke>(),
        )
        assertTrue(r.events.none { it is RegistryEvent.Added }, "they are not new installs")
        assertTrue(r.events.none { it is RegistryEvent.SignatureChanged })
    }

    @Test
    fun `an empty memory and a lost memory are different things`() {
        val empty = PluginScanLogic.scan(listOf(notesApp), PersistedRegistry.EMPTY, ApprovalPolicy.DEFAULT)
        assertEquals(PluginStatus.READY, empty.record("org.x.notes/agent-plugin").status, "empty means nothing was installed before: trust on first sight")
        val lost = PluginScanLogic.scan(listOf(notesApp), null, ApprovalPolicy.DEFAULT)
        assertEquals(PluginStatus.SIGNATURE_UNCONFIRMED, lost.record("org.x.notes/agent-plugin").status)
    }

    @Test
    fun `later scans keep them unconfirmed without new events until the user confirms, which still leaves them disabled`() {
        val (_, policy, _) = established()
        val lost = PluginScanLogic.scan(listOf(builtinApp, notesApp), null, policy, builtin)

        val again = PluginScanLogic.scan(listOf(builtinApp, notesApp), lost.persisted, lost.policy, builtin)
        assertEquals(PluginStatus.SIGNATURE_UNCONFIRMED, again.record("org.x.notes/agent-plugin").status)
        assertTrue(again.events.isEmpty())
        assertEquals(lost.policy, again.policy)

        val confirmed = PluginScanLogic.confirmSignature(again.persisted, "org.x.notes/agent-plugin")
        val after = PluginScanLogic.scan(listOf(builtinApp, notesApp), confirmed, again.policy, builtin)
        assertEquals(PluginStatus.READY, after.record("org.x.notes/agent-plugin").status)
        assertEquals("sig-1", after.record("org.x.notes/agent-plugin").trustedSigner)
        assertEquals(false, after.policy.entryOf(PolicyScope.Plugin("notes")).enabled, "confirmed, but the user still has to enable it")
        assertTrue(after.events.isEmpty())
        // 之后签名再变，照常报告
        val changed = PluginScanLogic.scan(listOf(builtinApp, app("org.x.notes", "notes", signer = "sig-2")), after.persisted, after.policy, builtin)
        assertEquals(PluginStatus.SIGNATURE_CHANGED, changed.record("org.x.notes/agent-plugin").status)
    }

    @Test
    fun `a signature that changes while unconfirmed is not reported as a change, the user confirms what they see`() {
        val lost = PluginScanLogic.scan(listOf(notesApp), null, ApprovalPolicy.DEFAULT)
        val moved = PluginScanLogic.scan(listOf(app("org.x.notes", "notes", signer = "sig-9")), lost.persisted, lost.policy)
        assertEquals(PluginStatus.SIGNATURE_UNCONFIRMED, moved.record("org.x.notes/agent-plugin").status)
        assertTrue(moved.events.none { it is RegistryEvent.SignatureChanged })
        assertEquals("sig-9", moved.persisted.plugins.single().observedSigner)
        val confirmed = PluginScanLogic.confirmSignature(moved.persisted, "org.x.notes/agent-plugin")
        assertEquals("sig-9", confirmed.plugins.single().trustedSigner)
    }

    @Test
    fun `a plugin that is broken stays unavailable, with the unconfirmed signature remembered for after the fix`() {
        val broken = app("org.x.notes", "notes", pluginJson = "{")
        val lost = PluginScanLogic.scan(listOf(broken), null, ApprovalPolicy.DEFAULT)
        assertEquals(PluginStatus.UNAVAILABLE, lost.record("org.x.notes/agent-plugin").status)
        val fixed = PluginScanLogic.scan(listOf(notesApp), lost.persisted, lost.policy)
        assertEquals(PluginStatus.SIGNATURE_UNCONFIRMED, fixed.record("org.x.notes/agent-plugin").status)
    }

    @Test
    fun `the model cannot see third party tools after a lost memory, sees them again once confirmed and enabled, builtin tools stay`() {
        host.tools.registerSimple("agentos_open_app", ToolRisk.WRITE, ToolSource("agentos", "agentos", "open_app")) { ToolResult.text("ok") }
        host.tools.registerSimple("note_create", ToolRisk.WRITE, ToolSource("notes", "notes", "note_create")) { ToolResult.text("ok") }
        host.tools.registerSimple("alarm_create", ToolRisk.WRITE, ToolSource("alarm", "alarm", "alarm_create")) { ToolResult.text("ok") }
        val broker = DefaultCapabilityBroker(host)
        fun visible() = broker.declarations().map { it.name }.toSet()

        val (_, policy, _) = established()
        host.approvals.update { policy }
        assertEquals(setOf("agentos_open_app", "note_create", "alarm_create"), visible(), "before: the user had enabled everything")

        val lost = PluginScanLogic.scan(listOf(builtinApp, notesApp, alarmApp), null, policy, builtin)
        host.approvals.update { lost.policy }
        assertEquals(setOf("agentos_open_app"), visible(), "only the builtin plugin's tool is left")

        val confirmed = PluginScanLogic.confirmSignature(lost.persisted, "org.x.notes/agent-plugin")
        val after = PluginScanLogic.scan(listOf(builtinApp, notesApp, alarmApp), confirmed, lost.policy, builtin)
        host.approvals.update { after.policy }
        assertEquals(PluginStatus.READY, after.record("org.x.notes/agent-plugin").status)
        assertEquals(setOf("agentos_open_app"), visible(), "confirmed is not enabled")
        host.approvals.update { it.withEnabled(PolicyScope.Plugin("notes"), true) }
        assertEquals(setOf("agentos_open_app", "note_create"), visible(), "now enabled: notes is back, alarm is still unconfirmed")
        assertFalse("alarm_create" in visible())
    }
}
