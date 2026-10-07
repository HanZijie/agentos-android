package org.agentos.extensions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestReaderTest {
    private val pluginSchema = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"
    private val mcpSchema = "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json"

    private fun plugin(name: String = "notes", extra: String = "", ext: String? = null): String {
        val parts = mutableListOf("\"\$schema\":\"$pluginSchema\"", "\"name\":\"$name\"")
        if (extra.isNotEmpty()) parts += extra
        if (ext != null) parts += "\"extensions\":{$ext}"
        return "{${parts.joinToString(",")}}"
    }

    private fun mcp(servers: String) = """{"${'$'}schema":"$mcpSchema","mcpServers":{$servers}}"""

    private fun accepted(files: PluginFiles, origin: PluginOrigin = PluginOrigin.INSTALLED_APP): PluginManifest {
        val r = ManifestReader.read(files, origin)
        assertIs<ManifestResult.Accepted>(r, "expected accepted, got $r")
        return r.manifest
    }

    private fun rejected(files: PluginFiles, origin: PluginOrigin = PluginOrigin.INSTALLED_APP): List<ManifestError> {
        val r = ManifestReader.read(files, origin)
        assertIs<ManifestResult.Rejected>(r, "expected rejected, got $r")
        return r.errors
    }

    // ------------------------------------------------------------------ 正常路径

    @Test
    fun `the example plugin from the design doc is read completely`() {
        val json = """
            {
              "${'$'}schema": "$pluginSchema",
              "name": "notes", "version": "1.2.0", "description": "读写本机笔记",
              "author": {"name": "Example"},
              "extensions": {
                "com.openai": {
                  "hooks": "./hooks/hooks.json",
                  "interface": {"displayName": "笔记", "shortDescription": "短", "composerIcon": "./assets/icon.png"},
                  "apps": {"ignored": true}
                },
                "org.agentos": {"mcpServers": {"notes": {"service": "com.example.notes.agent.NotesMcpService"}}}
              }
            }
        """.trimIndent()
        val m = accepted(PluginFiles(json, skillFiles = listOf("skills/b/SKILL.md", "skills/a/SKILL.md", "skills/a/scripts/x.sh", "skills/SKILL.md", "other/SKILL.md")))
        assertEquals("notes", m.name)
        assertEquals("1.2.0", m.version)
        assertEquals("读写本机笔记", m.description)
        assertEquals("Example", m.authorName)
        assertEquals(PluginDisplay("笔记", "短", null, "./assets/icon.png"), m.display)
        assertEquals("笔记", m.displayName)
        assertEquals(listOf(McpServerDecl.Binder("notes", "com.example.notes.agent.NotesMcpService")), m.servers)
        assertEquals(listOf(SkillRef("a", "skills/a/SKILL.md"), SkillRef("b", "skills/b/SKILL.md")), m.skills)
        assertEquals("./hooks/hooks.json", m.hooks!!.jsonPrimitive.content)
        assertTrue(m.unsupported.isEmpty())
    }

    @Test
    fun `a minimal plugin needs only schema and name`() {
        val m = accepted(PluginFiles(plugin("a")))
        assertEquals("a", m.name)
        assertEquals("a", m.displayName)
        assertTrue(m.servers.isEmpty() && m.skills.isEmpty() && m.unsupported.isEmpty())
        assertNull(m.display)
        assertNull(m.hooks)
    }

    @Test
    fun `a remote https server is usable, its header names are kept but never values`() {
        val m = accepted(PluginFiles(plugin(), mcp("""  "docs": {"type":"streamable-http","url":"https://mcp.example.com/mcp","headers":{"Authorization":"Bearer REPLACE-ME","X-Team":"x"}}""")))
        val s = m.servers.single() as McpServerDecl.StreamableHttp
        assertEquals("docs", s.name)
        assertEquals("https://mcp.example.com/mcp", s.url)
        assertEquals(listOf("Authorization", "X-Team"), s.headerNames)
        assertFalse(m.toString().contains("REPLACE-ME"), "header values never reach the manifest")
    }

    @Test
    fun `binder servers come first, then remote ones, in declaration order`() {
        val ext = """"org.agentos":{"mcpServers":{"a":{"service":"p.A"},"b":{"service":"p.B"}}}"""
        val m = accepted(PluginFiles(plugin(ext = ext), mcp(""""r1":{"type":"streamable-http","url":"https://x.test/1"},"r2":{"type":"streamable-http","url":"https://x.test/2"}""")))
        assertEquals(listOf("a", "b", "r1", "r2"), m.servers.map { it.name })
    }

    // ------------------------------------------------------------------ 不可用的部分：不拒绝整个插件

    @Test
    fun `stdio, sse and non-https entries are unsupported but the plugin is still accepted`() {
        val servers = """
            "ok": {"type":"streamable-http","url":"https://ok.test/mcp"},
            "local": {"type":"stdio","command":"npx","args":["-y","x"],"env":{"A":"b"},"cwd":"./tools"},
            "old": {"type":"sse","url":"https://old.test/sse"},
            "plain": {"type":"streamable-http","url":"http://plain.test/mcp"},
            "creds": {"type":"streamable-http","url":"https://user:pw@host.test/mcp"},
            "nohost": {"type":"streamable-http","url":"https:///path"}
        """.trimIndent()
        val m = accepted(PluginFiles(plugin(), mcp(servers)))
        assertEquals(listOf("ok"), m.servers.map { it.name })
        val byLocation = m.unsupported.associateBy { it.location.substringAfterLast('.') }
        assertEquals(UnsupportedKind.STDIO_SERVER, byLocation.getValue("local").kind)
        assertEquals(UnsupportedKind.SSE_SERVER, byLocation.getValue("old").kind)
        assertEquals(UnsupportedKind.INSECURE_URL, byLocation.getValue("plain").kind)
        assertEquals(UnsupportedKind.INSECURE_URL, byLocation.getValue("creds").kind)
        assertEquals(UnsupportedKind.INSECURE_URL, byLocation.getValue("nohost").kind)
        assertTrue(m.unsupported.all { it.reason.isNotBlank() && it.location.startsWith("mcp.json › mcpServers.") })
    }

    @Test
    fun `an icon path that leaves the plugin root is dropped and reported`() {
        for (bad in listOf("../icon.png", "./a/../../icon.png", "/etc/passwd", "icon.png", "./", "C:\\\\x.png")) {
            val m = accepted(PluginFiles(plugin(ext = """"com.openai":{"interface":{"displayName":"X","composerIcon":"$bad"}}""")))
            assertNull(m.display!!.iconPath, bad)
            assertEquals(UnsupportedKind.UNSAFE_PATH, m.unsupported.single().kind, bad)
            assertEquals("X", m.displayName)
        }
        val logo = accepted(PluginFiles(plugin(ext = """"com.openai":{"interface":{"logo":"./assets/logo.svg"}}""")))
        assertEquals("./assets/logo.svg", logo.display!!.iconPath)
    }

    // ------------------------------------------------------------------ 整个插件校验失败

    @Test
    fun `a duplicate server name across plugin json and mcp json rejects the whole plugin`() {
        val ext = """"org.agentos":{"mcpServers":{"notes":{"service":"p.A"}}}"""
        val errors = rejected(PluginFiles(plugin(ext = ext), mcp(""""notes":{"type":"streamable-http","url":"https://x.test/"}""")))
        assertEquals(listOf(ManifestErrorCode.DUPLICATE_SERVER), errors.map { it.code })
        // 即使 mcp.json 里那条本来是“不支持”的 stdio，也算占用了名字
        val stdio = rejected(PluginFiles(plugin(ext = ext), mcp(""""notes":{"type":"stdio","command":"x"}""")))
        assertEquals(listOf(ManifestErrorCode.DUPLICATE_SERVER), stdio.map { it.code })
    }

    @Test
    fun `an imported package must not declare binder servers, an installed app may`() {
        val ext = """"org.agentos":{"mcpServers":{"notes":{"service":"p.A"}}}"""
        val errors = rejected(PluginFiles(plugin(ext = ext)), PluginOrigin.IMPORTED)
        assertEquals(listOf(ManifestErrorCode.BINDER_NOT_ALLOWED), errors.map { it.code })
        assertTrue(errors.single().location.contains("org.agentos.mcpServers"))
        // 空对象也算“出现”
        assertEquals(listOf(ManifestErrorCode.BINDER_NOT_ALLOWED), rejected(PluginFiles(plugin(ext = """"org.agentos":{"mcpServers":{}}""")), PluginOrigin.IMPORTED).map { it.code })
        accepted(PluginFiles(plugin(ext = ext)), PluginOrigin.INSTALLED_APP)
        // org.agentos 里没有 mcpServers 的导入包没问题（以后的字段向前兼容）
        accepted(PluginFiles(plugin(ext = """"org.agentos":{"future":{"x":1}}""")), PluginOrigin.IMPORTED)
    }

    @Test
    fun `a malformed org-agentos part is rejected with its own code`() {
        for (bad in listOf(
            """"org.agentos":{"mcpServers":[]}""",
            """"org.agentos":{"mcpServers":{"a":"p.A"}}""",
            """"org.agentos":{"mcpServers":{"a":{}}}""",
            """"org.agentos":{"mcpServers":{"a":{"service":""}}}""",
            """"org.agentos":{"mcpServers":{"a":{"service":"p. A"}}}""",
            """"org.agentos":{"mcpServers":{"":{"service":"p.A"}}}""",
        )) {
            val codes = rejected(PluginFiles(plugin(ext = bad))).map { it.code }.toSet()
            assertEquals(setOf(ManifestErrorCode.ORG_AGENTOS), codes, bad)
        }
    }

    @Test
    fun `unknown fields inside the org-agentos server entry are ignored for forward compatibility`() {
        val m = accepted(PluginFiles(plugin(ext = """"org.agentos":{"mcpServers":{"a":{"service":"p.A","future":true}},"other":1}""")))
        assertEquals(listOf(McpServerDecl.Binder("a", "p.A")), m.servers)
    }

    @Test
    fun `not json, or not an object, is NOT_JSON`() {
        assertEquals(listOf(ManifestErrorCode.NOT_JSON), rejected(PluginFiles("{")).map { it.code })
        assertEquals(listOf(ManifestErrorCode.NOT_JSON), rejected(PluginFiles("[]")).map { it.code })
        assertEquals(listOf(ManifestErrorCode.NOT_JSON), rejected(PluginFiles(plugin(), "nope")).map { it.code })
    }

    @Test
    fun `all problems are collected in one pass`() {
        val bad = """{"name":"Bad--Name","version":1,"extra":true,"extensions":{"x":1}}"""
        val errors = rejected(PluginFiles(bad, """{"mcpServers":{"a":{"type":"ftp"}},"x":1}"""))
        val where = errors.map { it.location }
        assertTrue(where.any { it == "plugin.json › \$schema" }, where.toString())
        assertTrue(where.any { it == "plugin.json › name" })
        assertTrue(where.any { it == "plugin.json › version" })
        assertTrue(where.any { it == "plugin.json › extra" })
        assertTrue(where.any { it == "plugin.json › extensions.x" })
        assertTrue(where.any { it == "mcp.json › \$schema" })
        assertTrue(where.any { it == "mcp.json › x" })
        assertTrue(where.any { it == "mcp.json › mcpServers.a.type" })
        assertTrue(errors.all { it.code == ManifestErrorCode.SCHEMA && it.message.isNotBlank() })
    }

    @Test
    fun `plugin name rules from the schema`() {
        val longest = "a".repeat(64)
        accepted(PluginFiles(plugin(longest)))
        for (good in listOf("a", "a1", "my-plugin", "my.plugin", "a.b-c", "0", "x".repeat(64))) accepted(PluginFiles(plugin(good)))
        for (bad in listOf("", "A", "Notes", "a".repeat(65), "-a", "a-", ".a", "a.", "a--b", "a..b", "a b", "a_b", "笔记", "a\n", "a/b")) {
            val errors = rejected(PluginFiles(plugin(bad.replace("\n", "\\n"))))
            assertTrue(errors.any { it.location == "plugin.json › name" }, "name \"$bad\" should be rejected")
        }
    }

    @Test
    fun `the schema constants must be exact`() {
        rejected(PluginFiles(plugin().replace(pluginSchema, "https://agent-plugins.org/schemas/1.0.1/plugin.schema.json")))
        rejected(PluginFiles("""{"name":"a"}"""))
        rejected(PluginFiles(plugin(), mcp("").replace(mcpSchema, pluginSchema)))
        rejected(PluginFiles(plugin(), """{"mcpServers":{}}"""))
        rejected(PluginFiles(plugin(), """{"${'$'}schema":"$mcpSchema"}"""))
    }

    @Test
    fun `mcp json server shapes follow the schema`() {
        for (bad in listOf(
            """"a":{"type":"stdio"}""",
            """"a":{"type":"stdio","command":""}""",
            """"a":{"type":"stdio","command":"x","args":[1]}""",
            """"a":{"type":"stdio","command":"x","env":{"PLUGIN_ROOT":"x"}}""",
            """"a":{"type":"stdio","command":"x","env":{"A":1}}""",
            """"a":{"type":"stdio","command":"x","cwd":"/abs"}""",
            """"a":{"type":"stdio","command":"x","url":"https://x"}""",
            """"a":{"type":"streamable-http"}""",
            """"a":{"type":"streamable-http","url":""}""",
            """"a":{"type":"streamable-http","url":"https://x","headers":{"A":1}}""",
            """"a":{"type":"streamable-http","url":"https://x","command":"x"}""",
            """"a":{"type":"sse"}""",
            """"a":{"url":"https://x"}""",
            """"a":"https://x"""",
        )) rejected(PluginFiles(plugin(), mcp(bad)))
        for (good in listOf(
            """"a":{"type":"stdio","command":"x","cwd":"./t"}""",
            """"a":{"type":"stdio","command":"x","cwd":"${'$'}{PLUGIN_ROOT}"}""",
            """"a":{"type":"stdio","command":"x","cwd":"${'$'}{PLUGIN_DATA}/d"}""",
        )) accepted(PluginFiles(plugin(), mcp(good)))
    }

    // ------------------------------------------------------------------ 与固定的 schema 副本核对

    private fun resource(name: String): JsonObject =
        Json.parseToJsonElement(
            ManifestReader::class.java.getResourceAsStream("/org/agentos/extensions/agent-plugins-1.0/$name")!!.readBytes().toString(Charsets.UTF_8),
        ).jsonObject

    @Test
    fun `the hand-written rules match the pinned schema files`() {
        val plugin = resource("plugin.schema.json")
        val mcp = resource("mcp.schema.json")
        assertEquals(pluginSchema, plugin.getValue("\$id").jsonPrimitive.content)
        assertEquals(mcpSchema, mcp.getValue("\$id").jsonPrimitive.content)
        assertEquals(ManifestReader.PLUGIN_SCHEMA_ID, plugin["properties"]!!.jsonObject["\$schema"]!!.jsonObject["const"]!!.jsonPrimitive.content)
        assertEquals(ManifestReader.MCP_SCHEMA_ID, mcp["properties"]!!.jsonObject["\$schema"]!!.jsonObject["const"]!!.jsonPrimitive.content)

        val props = plugin.getValue("properties").jsonObject
        val nameSchema = props.getValue("name").jsonObject
        assertEquals(ManifestReader.MAX_NAME_LENGTH, nameSchema.getValue("maxLength").jsonPrimitive.content.toInt())
        // pattern：我们的是去掉首尾锚点的同一个表达式
        val pattern = nameSchema.getValue("pattern").jsonPrimitive.content
        assertEquals("^" + ManifestReader.NAME_REGEX.pattern + "$", pattern)
        // plugin.json 的顶层字段、required、additionalProperties
        assertEquals(props.keys, ManifestReader.PLUGIN_KEYS)
        assertEquals(setOf("\$schema", "name"), plugin.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("false", plugin.getValue("additionalProperties").jsonPrimitive.content)
        assertEquals(setOf("name", "email", "url"), props.getValue("author").jsonObject.getValue("properties").jsonObject.keys)

        // mcp.json：三种服务器与各自的字段
        val defs = mcp.getValue("\$defs").jsonObject
        assertEquals(setOf("type", "command", "args", "env", "cwd"), defs.getValue("stdioServer").jsonObject.getValue("properties").jsonObject.keys)
        assertEquals(setOf("type", "url", "headers"), defs.getValue("streamableHttpServer").jsonObject.getValue("properties").jsonObject.keys)
        assertEquals(setOf("type", "url", "headers"), defs.getValue("sseServer").jsonObject.getValue("properties").jsonObject.keys)
        val envNames = defs.getValue("stdioServer").jsonObject.getValue("properties").jsonObject.getValue("env").jsonObject
            .getValue("propertyNames").jsonObject.getValue("not").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(setOf("PLUGIN_ROOT", "PLUGIN_DATA"), envNames.toSet())
        // cwd 的 pattern：正例与反例交给同一个表达式
        val cwd = Regex(defs.getValue("stdioServer").jsonObject.getValue("properties").jsonObject.getValue("cwd").jsonObject.getValue("pattern").jsonPrimitive.content.replace("|$)", "|\\z)"))
        for (s in listOf("./x", "\${PLUGIN_ROOT}", "\${PLUGIN_ROOT}/a", "\${PLUGIN_DATA}/")) {
            assertTrue(cwd.containsMatchIn(s) && ManifestReader.CWD_REGEX.matches(s), s)
        }
        for (s in listOf("/abs", "x", "\${PLUGIN_ROOTx}", "..", "\${OTHER}/x")) {
            assertFalse(cwd.containsMatchIn(s) || ManifestReader.CWD_REGEX.matches(s), s)
        }
    }
}
