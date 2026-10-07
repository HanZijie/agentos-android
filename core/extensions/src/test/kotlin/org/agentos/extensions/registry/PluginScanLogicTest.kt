package org.agentos.extensions.registry

import org.agentos.extensions.ManifestErrorCode
import org.agentos.extensions.McpServerDecl
import org.agentos.extensions.ToolId
import org.agentos.extensions.ToolNaming
import org.agentos.runtime.broker.ApprovalMode
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.ports.ToolSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginScanLogicTest {
    private val schema = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"
    private val mcpSchema = "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json"
    private val perm = PluginScanLogic.BIND_PERMISSION

    /** 一个插件的 plugin.json：name + 若干 Binder 服务器（服务器名 → Service 类名）。 */
    private fun pluginJson(name: String, servers: Map<String, String> = mapOf(name to "org.x.$name.McpService"), version: String = "1.0.0"): String {
        val srv = servers.entries.joinToString(",") { "\"${it.key}\":{\"service\":\"${it.value}\"}" }
        return """{"${'$'}schema":"$schema","name":"$name","version":"$version",
            "extensions":{"org.agentos":{"mcpServers":{$srv}}}}"""
    }

    private fun anchor(cls: String, exported: Boolean = true, permission: String? = perm, dir: String? = "agent-plugin") =
        PluginServiceInfo(cls, exported, permission, isPluginAnchor = true, assetsDir = dir)

    private fun app(
        pkg: String,
        name: String = pkg.substringAfterLast('.'),
        signer: String = "sig-1",
        versionCode: Long = 1,
        services: List<PluginServiceInfo> = listOf(anchor("org.x.$name.McpService")),
        pluginJson: String? = pluginJson(name),
        mcpJson: String? = null,
        skills: List<String> = emptyList(),
    ) = InstalledAppView(
        PluginIdentity(pkg, signer, versionCode),
        services,
        mapOf("agent-plugin" to PluginAssets(pluginJson, mcpJson, skills)),
    )

    private fun scan(views: List<InstalledAppView>, previous: PersistedRegistry = PersistedRegistry.EMPTY, policy: ApprovalPolicy = ApprovalPolicy.DEFAULT, builtin: Set<String> = emptySet()) =
        PluginScanLogic.scan(views, previous, policy, builtin)

    private fun ScanResult.record(id: String) = registry[id] ?: error("no record $id in ${registry.plugins.map { it.id }}")

    private fun enabledOf(policy: ApprovalPolicy, name: String) = policy.entryOf(PolicyScope.Plugin(name)).enabled

    // ------------------------------------------------------------------ 第一次扫描

    @Test
    fun `three sample apps are discovered, ready, and switched off by default`() {
        val tools = mapOf(
            "alarm" to listOf("alarm_list", "alarm_create", "alarm_delete"),
            "calendar" to listOf("event_list", "event_create"),
            "notes" to listOf("note_list", "note_create", "note_delete"),
        )
        val r = scan(tools.keys.map { app("org.agentos.sample.$it", it) })
        assertEquals(listOf("org.agentos.sample.alarm/agent-plugin", "org.agentos.sample.calendar/agent-plugin", "org.agentos.sample.notes/agent-plugin"), r.registry.plugins.map { it.id })
        for (rec in r.registry.plugins) {
            assertEquals(PluginStatus.READY, rec.status)
            assertEquals(listOf(McpServerDecl.Binder(rec.name!!, "org.x.${rec.name}.McpService")), rec.activeServers)
            assertEquals(false, enabledOf(r.policy, rec.name!!), "third party plugins start switched off")
            assertFalse(rec.builtin)
        }
        assertEquals(3, r.events.count { it is RegistryEvent.Added })
        assertTrue(r.events.none { it is RegistryEvent.Revoke })
        // 与 ToolNaming 组合：目录里的全部工具，名字唯一
        val ids = tools.flatMap { (p, ts) -> ts.map { ToolId(p, p, it) } }
        assertEquals(ids.size, ToolNaming.assign(ids).values.toSet().size)
        // 策略按 ToolSource 解析：启用之前不可用，启用之后可用
        assertFalse(r.policy.resolve(ToolSource("notes", "notes", "note_list")).enabled)
        assertTrue(r.policy.withEnabled(PolicyScope.Plugin("notes"), true).resolve(ToolSource("notes", "notes", "note_list")).enabled)
        assertEquals("notes", r.registry.byName("notes")!!.name)
    }

    @Test
    fun `the builtin plugin is enabled by default and a user choice is never overwritten`() {
        val r = scan(listOf(app("org.agentos.app", "agentos"), app("org.x.other", "other")), builtin = setOf("org.agentos.app"))
        assertTrue(r.record("org.agentos.app/agent-plugin").builtin)
        assertNull(enabledOf(r.policy, "agentos"), "no entry: enabled")
        assertEquals(false, enabledOf(r.policy, "other"))
        // 用户启用之后，再扫描不会改回去
        val enabled = r.policy.withEnabled(PolicyScope.Plugin("other"), true)
        val again = scan(listOf(app("org.agentos.app", "agentos"), app("org.x.other", "other")), r.persisted, enabled, setOf("org.agentos.app"))
        assertEquals(true, enabledOf(again.policy, "other"))
        assertTrue(again.events.isEmpty())
    }

    // ------------------------------------------------------------------ Binder 服务器的三项检查

    @Test
    fun `a binder server must be in the package, exported and protected by the bind permission`() {
        val services = listOf(
            anchor("org.x.p.Good"),
            PluginServiceInfo("org.x.p.Hidden", exported = false, permission = perm),
            PluginServiceInfo("org.x.p.Open", exported = true, permission = null),
            PluginServiceInfo("org.x.p.Other", exported = true, permission = "android.permission.BIND_JOB_SERVICE"),
        )
        val servers = mapOf("good" to "org.x.p.Good", "hidden" to "org.x.p.Hidden", "open" to "org.x.p.Open", "other" to "org.x.p.Other", "foreign" to "com.evil.Service")
        val r = scan(listOf(app("org.x.p", "p", services = services, pluginJson = pluginJson("p", servers))))
        val rec = r.record("org.x.p/agent-plugin")
        assertEquals(PluginStatus.READY, rec.status, "one good server keeps the plugin")
        assertEquals(listOf("good"), rec.activeServers.map { it.name })
        assertEquals(
            mapOf(
                "hidden" to ServerRejection.NOT_EXPORTED,
                "open" to ServerRejection.MISSING_PERMISSION,
                "other" to ServerRejection.MISSING_PERMISSION,
                "foreign" to ServerRejection.NOT_IN_PACKAGE,
            ),
            rec.rejectedServers.associate { it.name to it.reason },
        )
        assertEquals(4, rec.problems.count { it.code == "server_rejected" })
    }

    @Test
    fun `when every declared server is rejected the plugin is unavailable, unless it still has skills or a remote server`() {
        val bad = listOf(anchor("org.x.p.A", exported = false))
        val onlyBad = scan(listOf(app("org.x.p", "p", services = bad, pluginJson = pluginJson("p", mapOf("a" to "org.x.p.A")))))
        val rec = onlyBad.record("org.x.p/agent-plugin")
        assertEquals(PluginStatus.UNAVAILABLE, rec.status)
        assertEquals(UnavailableReason.NO_USABLE_SERVER, rec.unavailableReason)
        assertEquals(1, rec.rejectedServers.size, "kept in the registry with its reasons")
        assertTrue(rec.problems.any { it.code == "no_usable_server" })

        val withSkills = scan(listOf(app("org.x.p", "p", services = bad, pluginJson = pluginJson("p", mapOf("a" to "org.x.p.A")), skills = listOf("skills/s/SKILL.md"))))
        assertEquals(PluginStatus.READY, withSkills.record("org.x.p/agent-plugin").status)

        val mcp = """{"${'$'}schema":"$mcpSchema","mcpServers":{"docs":{"type":"streamable-http","url":"https://x.test/mcp"}}}"""
        val withRemote = scan(listOf(app("org.x.p", "p", services = bad, pluginJson = pluginJson("p", mapOf("a" to "org.x.p.A")), mcpJson = mcp)))
        val remote = withRemote.record("org.x.p/agent-plugin")
        assertEquals(PluginStatus.READY, remote.status)
        assertEquals(listOf("docs"), remote.activeServers.map { it.name })

        // 一个服务器都没声明、只有 skills 的插件是正常的
        val skillsOnly = scan(listOf(app("org.x.p", "p", pluginJson = pluginJson("p", emptyMap()), skills = listOf("skills/s/SKILL.md"))))
        assertEquals(PluginStatus.READY, skillsOnly.record("org.x.p/agent-plugin").status)
    }

    @Test
    fun `a rejected manifest or missing assets keeps the plugin in the registry as unavailable, with the reasons`() {
        val r = scan(
            listOf(
                app("org.x.broken", "broken", pluginJson = """{"name":"Bad Name"}"""),
                app("org.x.empty", "empty", pluginJson = null),
                InstalledAppView(PluginIdentity("org.x.nodir", "s", 1), listOf(anchor("org.x.nodir.S", dir = null))),
            ),
        )
        val broken = r.record("org.x.broken/agent-plugin")
        assertEquals(PluginStatus.UNAVAILABLE, broken.status)
        assertEquals(UnavailableReason.MANIFEST_REJECTED, broken.unavailableReason)
        assertTrue(broken.manifestErrors.any { it.code == ManifestErrorCode.SCHEMA })
        assertTrue(broken.problems.isNotEmpty() && broken.problems.all { it.message.isNotBlank() })
        assertNull(broken.name)
        assertEquals(UnavailableReason.ASSETS_MISSING, r.record("org.x.empty/agent-plugin").unavailableReason)
        assertEquals(UnavailableReason.ASSETS_MISSING, r.record("org.x.nodir/").unavailableReason)
        assertTrue(r.registry.plugins.all { it.activeServers.isEmpty() })
        assertEquals(3, r.persisted.plugins.size, "remembered too, so a later fix is seen as an update, not a new plugin")
    }

    @Test
    fun `an app without a plugin anchor is not a plugin`() {
        val v = InstalledAppView(PluginIdentity("org.x.plain", "s", 1), listOf(PluginServiceInfo("org.x.plain.S", true, perm)))
        assertTrue(scan(listOf(v)).registry.plugins.isEmpty())
    }

    // ------------------------------------------------------------------ 签名

    @Test
    fun `a changed signature disables the plugin, revokes everything once, and needs a confirmation`() {
        val first = scan(listOf(app("org.x.notes", "notes")))
        val user = first.policy
            .withEnabled(PolicyScope.Plugin("notes"), true)
            .withApproval(PolicyScope.Server("notes", "notes"), ApprovalMode.ALWAYS)
            .withEnabled(PolicyScope.Tool("notes", "notes", "note_delete"), false)

        val changed = scan(listOf(app("org.x.notes", "notes", signer = "sig-2")), first.persisted, user)
        val rec = changed.record("org.x.notes/agent-plugin")
        assertEquals(PluginStatus.SIGNATURE_CHANGED, rec.status)
        assertEquals("sig-1", rec.trustedSigner)
        assertTrue(rec.activeServers.isEmpty(), "no server is usable before the user confirms")
        assertTrue(rec.problems.any { it.code == "signature_changed" })
        assertEquals(
            listOf(
                RegistryEvent.SignatureChanged("org.x.notes/agent-plugin", "notes", "sig-1", "sig-2"),
                RegistryEvent.Revoke("org.x.notes/agent-plugin", "notes", RevokeReason.SIGNATURE_CHANGED),
            ),
            changed.events,
        )
        // 授权作废：策略清空，插件停用
        assertEquals(false, enabledOf(changed.policy, "notes"))
        assertNull(changed.policy.entryOf(PolicyScope.Server("notes", "notes")).approval)
        assertFalse(changed.policy.resolve(ToolSource("notes", "notes", "note_list")).enabled)

        // 再扫描一次：状态不变，不再发事件
        val again = scan(listOf(app("org.x.notes", "notes", signer = "sig-2")), changed.persisted, changed.policy)
        assertEquals(PluginStatus.SIGNATURE_CHANGED, again.record("org.x.notes/agent-plugin").status)
        assertTrue(again.events.isEmpty())

        // 用户确认：恢复可用，但仍然是停用的
        val confirmed = PluginScanLogic.confirmSignature(again.persisted, "org.x.notes/agent-plugin")
        val after = scan(listOf(app("org.x.notes", "notes", signer = "sig-2")), confirmed, again.policy)
        assertEquals(PluginStatus.READY, after.record("org.x.notes/agent-plugin").status)
        assertEquals("sig-2", after.record("org.x.notes/agent-plugin").trustedSigner)
        assertEquals(false, enabledOf(after.policy, "notes"))
        assertTrue(after.events.isEmpty())
    }

    @Test
    fun `a signature that goes back to the trusted one makes the plugin ready again without a new event`() {
        val first = scan(listOf(app("org.x.notes", "notes")))
        val changed = scan(listOf(app("org.x.notes", "notes", signer = "sig-2")), first.persisted, first.policy)
        val back = scan(listOf(app("org.x.notes", "notes", signer = "sig-1")), changed.persisted, changed.policy)
        assertEquals(PluginStatus.READY, back.record("org.x.notes/agent-plugin").status)
        assertTrue(back.events.isEmpty())
    }

    @Test
    fun `a second signature change after the first is reported again`() {
        val first = scan(listOf(app("org.x.notes", "notes")))
        val c1 = scan(listOf(app("org.x.notes", "notes", signer = "sig-2")), first.persisted, first.policy)
        val c2 = scan(listOf(app("org.x.notes", "notes", signer = "sig-3")), c1.persisted, c1.policy)
        assertEquals(RegistryEvent.SignatureChanged("org.x.notes/agent-plugin", "notes", "sig-2", "sig-3"), c2.events.first())
        assertEquals("sig-1", c2.record("org.x.notes/agent-plugin").trustedSigner, "trust is not moved by an unconfirmed change")
    }

    @Test
    fun `the builtin plugin does not need a signature confirmation`() {
        val first = scan(listOf(app("org.agentos.app", "agentos")), builtin = setOf("org.agentos.app"))
        val changed = scan(listOf(app("org.agentos.app", "agentos", signer = "release-key")), first.persisted, first.policy, setOf("org.agentos.app"))
        assertEquals(PluginStatus.READY, changed.record("org.agentos.app/agent-plugin").status)
        assertTrue(changed.events.isEmpty())
    }

    // ------------------------------------------------------------------ 升级与卸载

    @Test
    fun `an upgrade with the same signature re-reads the manifest and keeps the user policy`() {
        val first = scan(listOf(app("org.x.notes", "notes")))
        val user = first.policy
            .withEnabled(PolicyScope.Plugin("notes"), true)
            .withApproval(PolicyScope.Tool("notes", "notes", "note_create"), ApprovalMode.ALWAYS)
        val upgraded = scan(
            listOf(
                app(
                    "org.x.notes", "notes", versionCode = 2,
                    services = listOf(anchor("org.x.notes.McpService"), PluginServiceInfo("org.x.notes.Extra", true, perm)),
                    pluginJson = pluginJson("notes", mapOf("notes" to "org.x.notes.McpService", "extra" to "org.x.notes.Extra")),
                ),
            ),
            first.persisted, user,
        )
        assertEquals(listOf(RegistryEvent.Updated("org.x.notes/agent-plugin", 1, 2)), upgraded.events)
        assertEquals(listOf("notes", "extra"), upgraded.record("org.x.notes/agent-plugin").activeServers.map { it.name }, "the new manifest is in effect")
        assertEquals(user, upgraded.policy, "policy untouched")
        assertEquals(2, upgraded.persisted.plugins.single().versionCode)
    }

    @Test
    fun `an upgrade that renames the plugin drops the old policy and revokes`() {
        val first = scan(listOf(app("org.x.notes", "notes")))
        val user = first.policy.withEnabled(PolicyScope.Plugin("notes"), true)
        val renamed = scan(
            listOf(app("org.x.notes", versionCode = 2, services = listOf(anchor("org.x.n.McpService")), pluginJson = pluginJson("memo", mapOf("memo" to "org.x.n.McpService")))),
            first.persisted, user,
        )
        assertTrue(RegistryEvent.Renamed("org.x.notes/agent-plugin", "notes", "memo") in renamed.events)
        assertTrue(RegistryEvent.Revoke("org.x.notes/agent-plugin", "notes", RevokeReason.RENAMED) in renamed.events)
        assertNull(renamed.policy.plugins["notes"])
        assertEquals(false, enabledOf(renamed.policy, "memo"), "a new name starts switched off")
    }

    @Test
    fun `uninstalling removes the plugin, clears its policy and revokes`() {
        val first = scan(listOf(app("org.x.notes", "notes"), app("org.x.alarm", "alarm")))
        val user = first.policy.withEnabled(PolicyScope.Plugin("notes"), true).withEnabled(PolicyScope.Plugin("alarm"), true)
        val gone = scan(listOf(app("org.x.alarm", "alarm")), first.persisted, user)
        assertEquals(
            listOf(
                RegistryEvent.Removed("org.x.notes/agent-plugin", "notes"),
                RegistryEvent.Revoke("org.x.notes/agent-plugin", "notes", RevokeReason.UNINSTALLED),
            ),
            gone.events,
        )
        assertNull(gone.registry["org.x.notes/agent-plugin"])
        assertNull(gone.persisted.plugins.firstOrNull { it.name == "notes" })
        assertNull(gone.policy.plugins["notes"], "authorizations are gone")
        assertEquals(true, enabledOf(gone.policy, "alarm"), "others untouched")
        // 重新安装：当作新插件，默认关闭，不会继承旧的授权
        val back = scan(listOf(app("org.x.alarm", "alarm"), app("org.x.notes", "notes")), gone.persisted, gone.policy)
        assertEquals(false, enabledOf(back.policy, "notes"))
        assertTrue(back.events.single() is RegistryEvent.Added)
    }

    @Test
    fun `a plugin that becomes unavailable and is fixed later is not treated as new`() {
        val ok = scan(listOf(app("org.x.notes", "notes")))
        val user = ok.policy.withEnabled(PolicyScope.Plugin("notes"), true)
        val broken = scan(listOf(app("org.x.notes", "notes", versionCode = 2, pluginJson = "{")), ok.persisted, user)
        assertEquals(PluginStatus.UNAVAILABLE, broken.record("org.x.notes/agent-plugin").status)
        assertEquals(user, broken.policy, "the user's choice survives a broken update")
        assertEquals("notes", broken.persisted.plugins.single().name, "the name is remembered")
        val fixed = scan(listOf(app("org.x.notes", "notes", versionCode = 3)), broken.persisted, broken.policy)
        assertEquals(PluginStatus.READY, fixed.record("org.x.notes/agent-plugin").status)
        assertEquals(true, enabledOf(fixed.policy, "notes"))
    }

    // ------------------------------------------------------------------ 名字

    @Test
    fun `two apps with the same plugin name, the first owner keeps it`() {
        val first = scan(listOf(app("org.x.zzz", "shared")))
        val both = scan(listOf(app("org.x.aaa", "shared"), app("org.x.zzz", "shared")), first.persisted, first.policy)
        assertEquals(PluginStatus.READY, both.record("org.x.zzz/agent-plugin").status, "the existing owner wins although its id sorts later")
        val loser = both.record("org.x.aaa/agent-plugin")
        assertEquals(UnavailableReason.NAME_CONFLICT, loser.unavailableReason)
        assertTrue(loser.activeServers.isEmpty())
        // 没有“原来的主人”时 id 小的赢，与扫描顺序无关
        val fresh1 = scan(listOf(app("org.x.aaa", "shared"), app("org.x.zzz", "shared")))
        val fresh2 = scan(listOf(app("org.x.zzz", "shared"), app("org.x.aaa", "shared")))
        assertEquals(fresh1.registry, fresh2.registry)
        assertEquals(PluginStatus.READY, fresh1.record("org.x.aaa/agent-plugin").status)
    }

    @Test
    fun `the builtin plugin wins a name conflict and user dot names are reserved`() {
        val r = scan(listOf(app("org.x.aaa", "agentos"), app("org.agentos.app", "agentos"), app("org.x.u", "user.mine")), builtin = setOf("org.agentos.app"))
        assertEquals(PluginStatus.READY, r.record("org.agentos.app/agent-plugin").status)
        assertEquals(UnavailableReason.NAME_CONFLICT, r.record("org.x.aaa/agent-plugin").unavailableReason)
        assertEquals(UnavailableReason.NAME_CONFLICT, r.record("org.x.u/agent-plugin").unavailableReason)
        assertNull(r.policy.plugins["user.mine"], "a loser never writes the winner's policy")
    }

    @Test
    fun `scanning is deterministic and independent of the order of the views`() {
        val views = listOf(app("org.x.c", "c"), app("org.x.a", "a"), app("org.x.b", "b"))
        assertEquals(scan(views), scan(views.reversed()))
    }

    // ------------------------------------------------------------------ 持久化的记忆

    @Test
    fun `the persisted registry round trips and tolerates unknown fields`() {
        val r = scan(listOf(app("org.x.notes", "notes"), app("org.x.broken", "broken", pluginJson = "{")))
        assertEquals(r.persisted, PersistedRegistry.fromJson(r.persisted.toJson()))
        assertEquals(r.persisted.toJson(), PersistedRegistry.fromJson(r.persisted.toJson()).toJson())
        val newer = PersistedRegistry.fromJson(
            """{"version":5,"future":1,"plugins":[{"id":"a/b","package":"a","trustedSigner":"t","observedSigner":"o","versionCode":9,"extra":[1]}]}""",
        )
        assertEquals(PersistedPlugin("a/b", "a", null, "t", "o", 9), newer.plugins.single())
        assertEquals(PersistedRegistry.EMPTY, PersistedRegistry.fromJson("""{"version":1}"""))
    }

    @Test
    fun `an unreadable memory is an error, never an empty registry`() {
        for (bad in listOf("", "x", "[]", "{}", """{"version":"1"}""", """{"version":1,"plugins":{}}""", """{"version":1,"plugins":[1]}""", """{"version":1,"plugins":[{"id":"a"}]}""")) {
            kotlin.test.assertFailsWith<PersistedRegistryFormatException>(bad) { PersistedRegistry.fromJson(bad) }
        }
    }

    @Test
    fun `the manifest error codes reach the plugin page unchanged`() {
        val dup = pluginJson("p", mapOf("a" to "org.x.p.A"))
        val mcp = """{"${'$'}schema":"$mcpSchema","mcpServers":{"a":{"type":"streamable-http","url":"https://x.test/"}}}"""
        val r = scan(listOf(app("org.x.p", "p", services = listOf(anchor("org.x.p.A")), pluginJson = dup, mcpJson = mcp)))
        val rec = r.record("org.x.p/agent-plugin")
        assertEquals(listOf(ManifestErrorCode.DUPLICATE_SERVER), rec.manifestErrors.map { it.code })
    }
}
