package org.agentos.extensions

import org.agentos.extensions.registry.InstalledAppView
import org.agentos.extensions.registry.PersistedRegistry
import org.agentos.extensions.registry.PluginAssets
import org.agentos.extensions.registry.PluginIdentity
import org.agentos.extensions.registry.PluginScanLogic
import org.agentos.extensions.registry.PluginServiceInfo
import org.agentos.extensions.skills.SkillFrontmatter
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.i18n.MessageRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 核心层给用户的校验信息是 key + 参数，不是文字（docs/next-apps-plan.md 7.3）：每个 key 都登记在 [ExtMessages]，参数个数和登记的一致，
 * 核心层的信息里没有任何中文（语言由 app 层的资源决定；app 的 `ExtCoreMessagesTest` 检查每个 key 在 app 里有中英文模板、占位符个数一致）。
 */
class ExtMessagesTest {
    private val pluginSchema = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"
    private val mcpSchema = "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json"
    private val dollar = '$'

    private fun check(ref: MessageRef, what: String) {
        val arity = ExtMessages.ARITY[ref.key] ?: error("$what: key ${ref.key} is not registered in ExtMessages")
        assertEquals(arity, ref.args.size, "$what: arguments of ${ref.key}")
        assertTrue(ref.key.startsWith("ext_msg_"), "$what: ${ref.key}")
        assertTrue(ref.key.none { it.code > 0x7f }, "$what: the key is ASCII")
    }

    private fun checkAll(files: PluginFiles, origin: PluginOrigin = PluginOrigin.INSTALLED_APP) {
        when (val r = ManifestReader.read(files, origin)) {
            is ManifestResult.Rejected -> r.errors.forEach { check(it.message, "${it.location} ${it.code}") }
            is ManifestResult.Accepted -> r.manifest.unsupported.forEach { check(it.reason, "${it.location} ${it.kind}") }
        }
    }

    @Test
    fun `every message ManifestReader can produce is registered with the right number of arguments`() {
        val bad = listOf(
            "{", "[]", "{}",
            """{"name":"Bad--Name","version":1,"extra":true,"keywords":[1],"author":{"zz":1,"name":2},"extensions":{"x":1}}""",
            """{"${dollar}schema":"x","name":"${"a".repeat(65)}"}""",
            """{"${dollar}schema":"$pluginSchema","name":5}""",
            """{"${dollar}schema":"$pluginSchema","name":"ok","extensions":[]}""",
            """{"${dollar}schema":"$pluginSchema","name":"ok","extensions":{"org.agentos":{"mcpServers":[]}}}""",
            """{"${dollar}schema":"$pluginSchema","name":"ok","extensions":{"org.agentos":{"mcpServers":{"a":"p.A","":{"service":"x"},"b":{"service":"p. B"}}}}}""",
            """{"${dollar}schema":"$pluginSchema","name":"ok","extensions":{"com.openai":{"interface":{"logo":"../x.png"}},"org.agentos":{"mcpServers":{"n":{"service":"p.A"}}}}}""",
        )
        for (text in bad) {
            checkAll(PluginFiles(text))
            checkAll(PluginFiles(text), PluginOrigin.IMPORTED)
        }
        val okPlugin = """{"${dollar}schema":"$pluginSchema","name":"ok"}"""
        val servers = """"a":1,"b":{},"c":{"type":"ftp"},"d":{"type":"stdio","bad":1,"command":5,"args":[1],"env":{"PLUGIN_ROOT":"x"},"cwd":"x"},"e":{"type":"stdio"},""" +
            """"f":{"type":"sse","url":"","headers":[]},"g":{"type":"streamable-http","bad":1},"h":{"type":"streamable-http","url":"http://a.b/"},""" +
            """"i":{"type":"streamable-http","url":"https://u:p@a.b/"},"j":{"type":"streamable-http","url":"https:///x"},"k":{"type":"streamable-http","url":"::bad::"},""" +
            """"l":{"type":"stdio","command":"x"},"m":{"type":"sse","url":"https://a.b/sse"}"""
        for (mcp in listOf("nope", "[]", "{}", """{"x":1}""", """{"${dollar}schema":"x","mcpServers":[]}""", """{"${dollar}schema":"$mcpSchema","mcpServers":{$servers}}""")) {
            checkAll(PluginFiles(okPlugin, mcp))
        }
    }

    private fun app(pkg: String, json: String?, services: List<PluginServiceInfo>, dir: String? = "agent-plugin") = InstalledAppView(
        PluginIdentity(pkg, "sig", 1),
        services,
        if (dir != null && json != null) mapOf(dir to PluginAssets(json)) else emptyMap(),
    )

    @Test
    fun `every problem PluginScanLogic produces is registered with the right number of arguments`() {
        val perm = PluginScanLogic.BIND_PERMISSION
        fun svc(cls: String, exported: Boolean = true, permission: String? = perm, dir: String? = "agent-plugin") = PluginServiceInfo(cls, exported, permission, true, dir)
        fun json(name: String, servers: Map<String, String>) =
            """{"${dollar}schema":"$pluginSchema","name":"$name","extensions":{"org.agentos":{"mcpServers":{${servers.entries.joinToString(",") { "\"${it.key}\":{\"service\":\"${it.value}\"}" }}}}}}"""
        val views = listOf(
            app("org.x.rej", json("rej", mapOf("a" to "org.x.rej.A", "b" to "org.x.rej.B", "c" to "org.x.rej.C", "d" to "other.D")), listOf(svc("org.x.rej.A", exported = false), svc("org.x.rej.B", permission = null), svc("org.x.rej.C"))),
            app("org.x.bad", """{"name":"Bad Name"}""", listOf(svc("org.x.bad.S"))),
            app("org.x.nodir", null, listOf(svc("org.x.nodir.S", dir = null)), dir = null),
            app("org.x.nojson", null, listOf(svc("org.x.nojson.S"))),
            app("org.x.dup1", json("dup", mapOf("a" to "org.x.dup1.A")), listOf(svc("org.x.dup1.A"))),
            app("org.x.dup2", json("dup", mapOf("a" to "org.x.dup2.A")), listOf(svc("org.x.dup2.A"))),
            app("org.x.user", json("user.mine", mapOf("a" to "org.x.user.A")), listOf(svc("org.x.user.A"))),
        )
        // a lost memory (previous == null) gives signature_unconfirmed; a known memory + a new signer gives signature_changed
        val lost = PluginScanLogic.scan(views, previous = null, policy = ApprovalPolicy.DEFAULT)
        val known = PluginScanLogic.scan(views, previous = PersistedRegistry(emptyList()), policy = ApprovalPolicy.DEFAULT)
        val changed = PluginScanLogic.scan(views.map { it.copy(identity = it.identity.copy(signerDigest = "other")) }, known.persisted, known.policy)
        var seen = 0
        for (r in listOf(lost, known, changed)) for (rec in r.registry.plugins) for (p in rec.problems) {
            check(p.message, "${rec.id} ${p.code}")
            seen++
        }
        assertTrue(seen >= 8, "the scenario produced $seen problems")
        val keys = (lost.registry.plugins + known.registry.plugins + changed.registry.plugins).flatMap { it.problems }.map { it.message.key }.toSet()
        for (k in listOf(
            ExtMessages.ASSETS_NOT_DECLARED, ExtMessages.ASSETS_NO_PLUGIN_JSON, ExtMessages.SERVER_REJECTED_NOT_IN_PACKAGE, ExtMessages.SERVER_REJECTED_NOT_EXPORTED,
            ExtMessages.SERVER_REJECTED_MISSING_PERMISSION, ExtMessages.SIGNATURE_UNCONFIRMED, ExtMessages.SIGNATURE_CHANGED, ExtMessages.NAME_CONFLICT,
        )) assertTrue(k in keys, "the scenario should produce $k, got $keys")
    }

    @Test
    fun `every frontmatter problem is registered with the right number of arguments`() {
        for (text in listOf("", "x", "---\nname: a\n", "---\nname: a\nname: b\n---\n", "---\n---\n", "---\nname: \"open\ndescription: 'open\n---\n")) {
            SkillFrontmatter.parse(text).problems.forEach { check(it, text) }
        }
    }

    @Test
    fun `the registry of keys has no duplicates and every key is prefixed`() {
        assertEquals(ExtMessages.ALL.size, ExtMessages.ALL.toSet().size)
        assertTrue(ExtMessages.ALL.all { it.startsWith("ext_msg_") && it == it.lowercase() })
    }
}
