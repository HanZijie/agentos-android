package org.agentos.extensions.skills

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.agentos.extensions.registry.InstalledAppView
import org.agentos.extensions.registry.PersistedRegistry
import org.agentos.extensions.registry.PluginAssets
import org.agentos.extensions.registry.PluginIdentity
import org.agentos.extensions.registry.PluginRecord
import org.agentos.extensions.registry.PluginRegistry
import org.agentos.extensions.registry.PluginScanLogic
import org.agentos.extensions.registry.PluginServiceInfo
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.testing.FakeApprovalPolicyPort
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionSkillPortTest {
    private val schema = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"

    /** 读文件的假实现：键是 `<插件ID>|<相对路径>`；记录被请求的路径。 */
    private class FakeFiles : SkillFileSource {
        val content = HashMap<String, ByteArray>()
        val requested = ArrayList<String>()
        val failing = HashSet<String>()
        val truncating = HashSet<String>()

        fun put(pluginId: String, path: String, text: String) {
            content["$pluginId|$path"] = text.toByteArray(Charsets.UTF_8)
        }

        override suspend fun read(plugin: PluginRecord, path: String, maxBytes: Int): SkillFile? {
            requested += "${plugin.id}|$path"
            if ("${plugin.id}|$path" in failing) throw IOException("disk")
            val bytes = content["${plugin.id}|$path"] ?: return null
            return if (bytes.size > maxBytes) SkillFile(bytes.copyOf(maxBytes), truncated = true) else SkillFile(bytes, "${plugin.id}|$path" in truncating)
        }
    }

    private class Setup(val scope: TestScope) {
        val files = FakeFiles()
        val policy = FakeApprovalPolicyPort()
        val registry = MutableStateFlow(PluginRegistry())
        private val views = ArrayList<InstalledAppView>()
        var persisted = PersistedRegistry.EMPTY
        private val enabled = ArrayList<String>()

        /** 一个插件：[skills] 是 目录 → SKILL.md 全文，[extra] 是其他文件（相对插件根的路径 → 内容）。 */
        fun plugin(pkg: String, name: String, skills: Map<String, String>, extra: Map<String, String> = emptyMap(), listed: List<String>? = null, versionCode: Long = 1) {
            val id = "$pkg/agent-plugin"
            val paths = skills.keys.map { "skills/$it/SKILL.md" } + extra.keys
            skills.forEach { (dir, text) -> files.put(id, "skills/$dir/SKILL.md", text) }
            extra.forEach { (path, text) -> files.put(id, path, text) }
            val json = """{"${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"$name"}"""
            views.removeAll { it.identity.packageName == pkg }
            views += InstalledAppView(
                PluginIdentity(pkg, "sig", versionCode),
                listOf(PluginServiceInfo("$pkg.Mcp", true, PluginScanLogic.BIND_PERMISSION, isPluginAnchor = true, assetsDir = "agent-plugin")),
                mapOf("agent-plugin" to PluginAssets(json, null, listed ?: paths)),
            )
            if (name !in enabled) enabled += name
        }

        fun scan(builtin: Set<String> = emptySet()) {
            val r = PluginScanLogic.scan(views, persisted, policy.policy.value, builtin)
            persisted = r.persisted
            policy.update { var p = r.policy; for (n in enabled) p = p.withEnabled(PolicyScope.Plugin(n), true); p }
            registry.value = r.registry
        }

        fun port(config: SkillConfig = SkillConfig()) = ExtensionSkillPort(registry, policy, files, scope.backgroundScope, config)
    }

    private fun sample(n: String) = ExtensionSkillPortTest::class.java.getResourceAsStream("/samples/$n/SKILL.md")!!.readBytes().toString(Charsets.UTF_8)

    private fun threeSamples(scope: TestScope): Setup = Setup(scope).apply {
        plugin("org.x.alarm", "alarm", mapOf("alarm" to sample("alarm")))
        plugin("org.x.calendar", "calendar", mapOf("calendar" to sample("calendar")))
        plugin("org.x.notes", "notes", mapOf("notes" to sample("notes")))
        scan()
    }

    // ------------------------------------------------------------------ 目录

    @Test
    fun `the three sample apps' skills are listed with name, description and source plugin, and can be read`() = runTest {
        val s = threeSamples(this)
        val port = s.port()
        runCurrent()
        val skills = port.catalog.value.skills
        assertEquals(listOf("alarm", "calendar", "notes"), skills.map { it.id })
        assertEquals(listOf("alarm", "calendar", "notes"), skills.map { it.provider })
        assertTrue(skills.first { it.id == "alarm" }.description.startsWith("Set, list, change and delete alarms"))
        assertTrue(skills.first { it.id == "calendar" }.description.startsWith("Create and look up events in the Calendar app"), "folded block scalar")
        assertTrue(skills.first { it.id == "notes" }.description.startsWith("Keep notes: create, append"), "single quoted scalar")
        val calendar = port.read("calendar")
        assertTrue(calendar.text.contains("with the time zone offset") && !calendar.truncated)
        assertEquals(sample("notes"), port.read("notes").text)
        assertEquals(sample("notes"), port.read("notes", "SKILL.md").text)
        assertTrue(port.problems.value.isEmpty())
    }

    @Test
    fun `a disabled plugin has no skills, they are not readable, and they come back when it is enabled again`() = runTest {
        val s = threeSamples(this)
        val port = s.port()
        runCurrent()
        s.policy.update { it.withEnabled(PolicyScope.Plugin("notes"), false) }
        // 目录还没重建时 read 也已经拒绝
        assertFailsWith<NoSuchElementException> { port.read("notes") }
        runCurrent()
        assertEquals(listOf("alarm", "calendar"), port.catalog.value.skills.map { it.id })
        // 服务器、工具级的禁用不影响 Skill
        s.policy.update { it.withEnabled(PolicyScope.Plugin("notes"), true).withEnabled(PolicyScope.Server("notes", "notes"), false) }
        runCurrent()
        assertTrue("notes" in port.catalog.value.skills.map { it.id })
        assertEquals(sample("notes"), port.read("notes").text)
    }

    @Test
    fun `a plugin that is not ready loses its skills`() = runTest {
        val s = threeSamples(this)
        val port = s.port()
        runCurrent()
        s.plugin("org.x.notes", "notes", mapOf("notes" to sample("notes")))
        // 签名变了
        val changed = InstalledAppView(PluginIdentity("org.x.notes", "other-signer", 1), listOf(PluginServiceInfo("org.x.notes.Mcp", true, PluginScanLogic.BIND_PERMISSION, true, "agent-plugin")),
            mapOf("agent-plugin" to PluginAssets("""{"${'$'}schema":"$schema","name":"notes"}""", null, listOf("skills/notes/SKILL.md"))))
        val r = PluginScanLogic.scan(listOf(changed), s.persisted, s.policy.policy.value)
        s.registry.value = PluginRegistry(s.registry.value.plugins.filter { it.id != "org.x.notes/agent-plugin" } + r.registry.plugins)
        runCurrent()
        assertFalse("notes" in port.catalog.value.skills.map { it.id })
        assertFailsWith<NoSuchElementException> { port.read("notes") }
    }

    @Test
    fun `names that clash are told apart by plugin, the builtin plugin comes first`() = runTest {
        val s = Setup(this)
        s.plugin("org.x.b", "bravo", mapOf("guide" to "---\nname: guide\ndescription: from bravo\n---\n", "other" to "---\nname: other\ndescription: unique\n---\n"))
        s.plugin("org.x.a", "alpha", mapOf("guide" to "---\nname: guide\ndescription: from alpha\n---\n"))
        s.plugin("org.agentos.app", "agentos", mapOf("phone" to "---\nname: phone\ndescription: builtin\n---\n"))
        s.scan(builtin = setOf("org.agentos.app"))
        val port = s.port()
        runCurrent()
        assertEquals(listOf("phone", "alpha:guide", "bravo:guide", "other"), port.catalog.value.skills.map { it.id })
        assertEquals(listOf("guide", "guide"), port.catalog.value.skills.filter { ":" in it.id }.map { it.name })
        assertTrue(port.read("alpha:guide").text.contains("from alpha"))
        assertTrue(port.read("bravo:guide").text.contains("from bravo"))
        assertFailsWith<NoSuchElementException> { port.read("guide") }
    }

    @Test
    fun `two skills with the same name in one plugin are told apart by directory`() = runTest {
        val s = Setup(this)
        s.plugin("org.x.a", "alpha", mapOf("one" to "---\nname: dup\ndescription: 1\n---\n", "two" to "---\nname: dup\ndescription: 2\n---\n"))
        s.scan()
        val port = s.port()
        runCurrent()
        assertEquals(listOf("alpha:one", "alpha:two"), port.catalog.value.skills.map { it.id })
    }

    @Test
    fun `broken skill files never affect the others, and the problems are reported for the plugin page`() = runTest {
        val s = Setup(this)
        val big = "---\nname: big\ndescription: " + "word ".repeat(1_000) + "\n---\n"
        s.plugin(
            "org.x.mix", "mix",
            mapOf(
                "good" to "---\nname: good\ndescription: fine\n---\nbody",
                "nofront" to "# just a title\nno frontmatter",
                "unfinished" to "---\nname: unfinished\ndescription: never ends",
                "garbage" to "---\n\u0001\u0002: : :\n{{{\n---\n",
                "badname" to "---\nname: \"has space and / slash\"\ndescription: d\n---\n",
                "nodesc" to "---\nname: nodesc\n---\n",
                "big" to big,
                "binary" to "---\nname: binary\ndescription: d\n---\n\u0000\u0000",
                "unreadable" to "---\nname: unreadable\ndescription: d\n---\n",
                "missing" to "---\nname: missing\ndescription: d\n---\n",
                "bad dir!" to "no name here",
            ),
        )
        s.scan()
        s.files.failing += "org.x.mix/agent-plugin|skills/unreadable/SKILL.md"
        s.files.content.remove("org.x.mix/agent-plugin|skills/missing/SKILL.md")
        val port = s.port()
        runCurrent()
        val byId = port.catalog.value.skills.associateBy { it.id }
        assertEquals("fine", byId.getValue("good").description)
        // frontmatter 有问题的用目录名，仍然列出
        assertEquals(setOf("good", "nofront", "unfinished", "garbage", "badname", "nodesc", "big"), byId.keys)
        assertEquals("", byId.getValue("nodesc").description, "listed without a description")
        assertEquals(1_024, byId.getValue("big").description.length, "cut to the limit")
        assertFalse(byId.getValue("big").description.contains("  "), "whitespace collapsed")
        val problems = port.problems.value.getValue("org.x.mix/agent-plugin").map { it.message }
        for (dir in listOf("nofront", "unfinished", "garbage", "badname", "nodesc", "binary", "unreadable", "missing", "bad dir!")) {
            assertTrue(problems.any { it.contains("Skill $dir：") }, "a problem for $dir in $problems")
        }
        assertTrue(problems.none { it.contains("Skill good：") })
        // 好的那个照常能读
        assertTrue(port.read("good").text.endsWith("body"))
    }

    @Test
    fun `descriptions lose control characters and the number of skills is capped`() = runTest {
        val s = Setup(this)
        s.plugin("org.x.a", "alpha", (1..10).associate { "s$it" to "---\nname: s$it\ndescription: \"a\\u0007b\\u202ec\"\n---\n" })
        s.scan()
        val port = s.port(SkillConfig(maxSkillsPerPlugin = 3))
        runCurrent()
        assertEquals(listOf("s1", "s10", "s2"), port.catalog.value.skills.map { it.id }, "sorted by directory, first three")
        assertEquals("a b c", port.catalog.value.skills.first().description, "control and invisible characters become spaces")
    }

    @Test
    fun `headers are read once per plugin version, a new version reads again`() = runTest {
        val s = threeSamples(this)
        val port = s.port()
        runCurrent()
        assertEquals(3, s.files.requested.size)
        s.policy.update { it.withEnabled(PolicyScope.Plugin("notes"), false) }
        runCurrent()
        s.policy.update { it.withEnabled(PolicyScope.Plugin("notes"), true) }
        runCurrent()
        assertEquals(3, s.files.requested.size, "cached: not read again after toggling")
        s.plugin("org.x.notes", "notes", mapOf("notes" to "---\nname: notes\ndescription: v2\n---\n"), versionCode = 2)
        s.scan()
        runCurrent()
        assertEquals("v2", port.catalog.value.skills.first { it.id == "notes" }.description)
        assertEquals(4, s.files.requested.size)
        // 目录版本号只在内容变化时递增
        val v = port.catalog.value.version
        s.policy.update { it }
        runCurrent()
        assertEquals(v, port.catalog.value.version)
    }

    // ------------------------------------------------------------------ 读取

    private fun withReferences(scope: TestScope): Setup = Setup(scope).apply {
        plugin(
            "org.x.n", "notes", mapOf("notes" to sample("notes"), "other" to "---\nname: other\ndescription: o\n---\nsecret of other"),
            extra = mapOf("skills/notes/references/guide.md" to "# Guide\nhello", "skills/notes/data.bin" to "x\u0000y", "assets/icon.png" to "png", "plugin.json" to "{}"),
        )
        // 文件源里还有清单没列出的文件（比如符号链接指向的内容、别处的文件）
        files.put("org.x.n/agent-plugin", "skills/notes/link-to-secret", "root:x:0:0")
        files.put("org.x.n/agent-plugin", "skills/notes/unlisted.md", "unlisted")
        scan()
    }

    @Test
    fun `files of the same skill directory can be read, nothing outside it`() = runTest {
        val s = withReferences(this)
        val port = s.port()
        runCurrent()
        assertEquals("# Guide\nhello", port.read("notes", "references/guide.md").text)
        // 同一个插件里别的 Skill、插件根的文件、assets：路径穿越被字面检查拒绝
        for (p in listOf("../other/SKILL.md", "../../plugin.json", "../../assets/icon.png", "/skills/other/SKILL.md", "references/../../other/SKILL.md", "..")) {
            assertFailsWith<IllegalArgumentException>("refuse $p") { port.read("notes", p) }
        }
        // 字面上合法、但不在清单里：读不到，文件源也根本没有被问到（符号链接、清单外的文件）
        s.files.requested.clear()
        for (p in listOf("link-to-secret", "unlisted.md", "SKILL.MD", "skill.md", "references/missing.md", "%2e%2e/other/SKILL.md", "references")) {
            assertFailsWith<NoSuchElementException>("not listed: $p") { port.read("notes", p) }
        }
        assertTrue(s.files.requested.isEmpty(), "the file source is only asked for listed files: ${s.files.requested}")
        // 空字节等
        assertFailsWith<IllegalArgumentException> { port.read("notes", "references/guide.md\u0000.png") }
        assertFailsWith<IllegalArgumentException> { port.read("notes", "references\\guide.md") }
        assertFailsWith<NoSuchElementException> { port.read("nope") }
    }

    @Test
    fun `a binary file is refused, a big file is cut and marked`() = runTest {
        val s = Setup(this)
        s.plugin(
            "org.x.n", "notes", mapOf("notes" to sample("notes")),
            extra = mapOf("skills/notes/data.bin" to "x\u0000y", "skills/notes/big.md" to "a".repeat(200), "skills/notes/exact.md" to "b".repeat(100), "skills/notes/streamed.md" to "c".repeat(10)),
        )
        s.scan()
        s.files.truncating += "org.x.n/agent-plugin|skills/notes/streamed.md"
        val port = s.port(SkillConfig(maxReadBytes = 100))
        runCurrent()
        assertFailsWith<IllegalArgumentException> { port.read("notes", "data.bin") }
        val big = port.read("notes", "big.md")
        assertEquals(100, big.text.length)
        assertTrue(big.truncated)
        val exact = port.read("notes", "exact.md")
        assertEquals(100, exact.text.length)
        assertFalse(exact.truncated, "exactly the limit is not truncated")
        assertTrue(port.read("notes", "streamed.md").truncated, "the source says there is more")
    }

    @Test
    fun `a read error from the file source is reported as not found, not as a crash`() = runTest {
        val s = withReferences(this)
        val port = s.port()
        runCurrent()
        s.files.failing += "org.x.n/agent-plugin|skills/notes/references/guide.md"
        assertFailsWith<NoSuchElementException> { port.read("notes", "references/guide.md") }
        assertEquals(sample("notes"), port.read("notes").text, "other files still work")
    }

    @Test
    fun `a plugin without an entry in the policy but never enabled has no skills`() = runTest {
        val s = Setup(this)
        s.plugin("org.x.n", "notes", mapOf("notes" to sample("notes")))
        s.scan()
        s.policy.update { ApprovalPolicy.DEFAULT.withEnabled(PolicyScope.Plugin("notes"), false) }
        val port = s.port()
        runCurrent()
        assertTrue(port.catalog.value.skills.isEmpty())
        s.policy.update { ApprovalPolicy(unlistedPluginsEnabled = false) }
        runCurrent()
        assertTrue(port.catalog.value.skills.isEmpty(), "fail closed policy: nothing enabled")
    }
}
