package org.agentos.extensions.samples

import org.agentos.extensions.ManifestResult
import org.agentos.extensions.McpServerDecl
import org.agentos.extensions.PluginFiles
import org.agentos.extensions.PluginOrigin
import org.agentos.extensions.ManifestReader
import org.agentos.extensions.ToolId
import org.agentos.extensions.ToolNaming
import org.agentos.extensions.skills.SkillFrontmatter
import org.junit.Assume.assumeTrue
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 三个示例 App 的**真实文件**与 docs/sample-apps.md 的一致性（A12）。读的是各 App 的 `assets/agent-plugin/`、`AndroidManifest.xml` 和工具层 Kotlin 源码，
 * 不是测试里手写的样本（[org.agentos.extensions.SamplePluginsTest] 是手写样本的契约测试）。
 *
 * ## 文件从哪里读
 * 默认读仓库根的 `plugins/samples/{alarm,calendar,notes}/`。环境变量 `AGENTOS_SAMPLES_ROOT` 可以改：
 * 一个或多个目录，用 [File.pathSeparator]（macOS / Linux 是 `:`）分隔，每个目录是**一个仓库根**（里面有 `plugins/samples/<名>/`），或者就是
 * `samples` 目录本身（里面直接有 `<名>/`）；每个 App 取第一个找得到 `src/main/assets/agent-plugin/plugin.json` 的目录。例如在 App 还没合入 main 时：
 *
 * ```
 * AGENTOS_SAMPLES_ROOT=/path/agentos-wt-app-alarm:/path/agentos-wt-app-calendar:/path/agentos-wt-app-notes ./gradlew :core:extensions:test --tests '*SamplePluginsConformanceTest*'
 * ```
 *
 * 找不到某个 App 的目录时，那个 App 的测试**跳过**（JUnit assumption），测试名里写明了“skipped when ... is absent”，
 * 报告里显示为跳过而不是通过。
 *
 * ## 工具名怎么匹配（只多不少）
 * docs/sample-apps.md 第 4 节的清单是**最低要求**：每个必需的工具名必须能在该 App 的工具层源码里找到。找法是**按字面量匹配**，不做任何解释执行：
 * 1. 读 `src/main` 下路径里含 `/tools/` 的全部 `.kt` 文件（没有这样的目录就读全部 `.kt`）；先去掉 `/* … */` 块注释和整行 `//` 注释；
 * 2. 用正则 `(?:\bname\s*=\s*|\b(?:tool|ToolDef)\(\s*)"([a-z][a-z0-9_]*)"` 找出字面量：`name = "alarm_list"`（具名参数）
 *    和 `tool("calendar_list", …)` / `ToolDef("x", …)`（第一个位置参数）两种写法；
 * 3. 必需的名字都在找到的集合里就通过；多出来的名字（如 `alarm_snooze`、`calendar_update`）不算错，只在失败信息里列出，供人看。
 *
 * 局限：工具名如果是拼出来的（`"note_" + verb`）或来自常量，匹配不到，测试会失败并提示——那种写法也读不出它到底注册了什么，应该改成字面量。
 * 源码里找到不等于注册进了 MCP 服务：注册由各 App 自己的单元测试和 debug 自测入口验证（设备上由整合人跑）。
 */
class SamplePluginsConformanceTest {

    private class Spec(
        val dir: String,
        val plugin: String,
        val server: String,
        val applicationId: String,
        /** docs/sample-apps.md 第 4 节的最低工具清单。 */
        val tools: List<String>,
        val serviceClass: String,
    )

    private val specs = listOf(
        Spec(
            "alarm", "alarm", "alarm", "org.agentos.sample.alarm",
            listOf("alarm_list", "alarm_get", "alarm_create", "alarm_update", "alarm_set_enabled", "alarm_delete", "alarm_next", "alarm_dismiss"),
            "org.agentos.sample.alarm.agent.AlarmMcpService",
        ),
        Spec(
            "calendar", "calendar", "calendar", "org.agentos.sample.calendar",
            listOf(
                "calendar_list", "calendar_create", "calendar_delete", "event_list", "event_get", "event_create", "event_update",
                "event_delete", "event_search", "agenda_today", "free_slots",
            ),
            "org.agentos.sample.calendar.agent.CalendarMcpService",
        ),
        Spec(
            "notes", "notes", "notes", "org.agentos.sample.notes",
            listOf("note_list", "note_get", "note_create", "note_update", "note_append", "note_search", "note_trash", "note_restore", "note_delete", "tag_list"),
            "org.agentos.sample.notes.agent.NotesMcpService",
        ),
        // docs/next-apps-plan.md 第 4 节（sample-apps.md 第 4 节还没有这一条）
        Spec(
            "sms", "sms", "sms", "org.agentos.sample.sms",
            listOf("sms_thread_list", "sms_message_list", "sms_search", "sms_send", "sms_send_status", "sms_compose"),
            "org.agentos.sample.sms.agent.SmsMcpService",
        ),
    )

    private fun spec(dir: String) = specs.single { it.dir == dir }

    // ------------------------------------------------------------------ 找文件

    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").isFile) d = d.parentFile
        return d ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
    }

    private fun roots(): List<File> {
        val env = System.getenv("AGENTOS_SAMPLES_ROOT")?.split(File.pathSeparatorChar)?.map { it.trim() }?.filter { it.isNotEmpty() }
        return if (env.isNullOrEmpty()) listOf(repoRoot()) else env.map { File(it) }
    }

    /** App 的根目录（里面是 `src/main/…`）；找不到返回 null。 */
    private fun appDir(name: String): File? {
        for (root in roots()) {
            for (candidate in listOf(File(root, "plugins/samples/$name"), File(root, name))) {
                if (File(candidate, "src/main/assets/agent-plugin/plugin.json").isFile) return candidate
            }
        }
        return null
    }

    private fun requireApp(name: String): File {
        val dir = appDir(name)
        assumeTrue("plugins/samples/$name is absent (looked in ${roots().joinToString(File.pathSeparator)})", dir != null)
        return dir!!
    }

    private fun assets(app: File) = File(app, "src/main/assets/agent-plugin")

    private fun skillFiles(app: File): List<String> {
        val root = assets(app)
        return File(root, "skills").walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
    }

    // ------------------------------------------------------------------ 每个 App 的检查

    private fun checkManifest(name: String) {
        val app = requireApp(name)
        val s = spec(name)
        val result = ManifestReader.read(PluginFiles(File(assets(app), "plugin.json").readText(), skillFiles = skillFiles(app)), PluginOrigin.INSTALLED_APP)
        assertIs<ManifestResult.Accepted>(result, "plugin.json of $name is rejected: ${(result as? ManifestResult.Rejected)?.errors}")
        val m = result.manifest
        assertEquals(s.plugin, m.name, "plugin name (docs/sample-apps.md 1)")
        assertEquals(listOf(s.server), m.servers.map { it.name }, "MCP server name (docs/sample-apps.md 1)")
        val server = m.servers.single()
        assertIs<McpServerDecl.Binder>(server)
        assertEquals(s.serviceClass, server.service)
        assertTrue(!m.version.isNullOrBlank(), "plugin.json has a version")
        assertTrue(!m.description.isNullOrBlank(), "plugin.json has a description")
        assertTrue(!m.display?.displayName.isNullOrBlank(), "extensions.com.openai.interface.displayName is set (the plugin page shows it)")
        assertEquals(listOf(s.dir), m.skills.map { it.directory }, "one skill, in skills/${s.dir}/")
        assertTrue(m.unsupported.isEmpty(), "nothing unsupported: ${m.unsupported}")
        // 同一份文件作为导入的 zip 必须被拒（Binder 端点只属于已安装的 App）
        val imported = ManifestReader.read(PluginFiles(File(assets(app), "plugin.json").readText()), PluginOrigin.IMPORTED)
        assertIs<ManifestResult.Rejected>(imported)
    }

    /** manifest 里的类名：以 `.` 开头的是相对包名（namespace = applicationId）的缩写。 */
    private fun resolveClass(name: String, pkg: String) = if (name.startsWith(".")) pkg + name else name

    private fun checkAndroidManifest(name: String) {
        val app = requireApp(name)
        val s = spec(name)
        val file = File(app, "src/main/AndroidManifest.xml")
        assertTrue(file.isFile, "AndroidManifest.xml exists")
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val services = doc.getElementsByTagName("service")
        val matches = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .filter { resolveClass(it.getAttributeNS(ns, "name"), s.applicationId) == s.serviceClass }
        assertEquals(1, matches.size, "exactly one <service> for ${s.serviceClass}")
        val svc = matches.single()
        assertEquals("true", svc.getAttributeNS(ns, "exported"), "the MCP service is exported")
        assertEquals("org.agentos.permission.BIND_MCP_SERVICE", svc.getAttributeNS(ns, "permission"), "and requires the bind permission")
        val actions = svc.getElementsByTagName("action")
        assertTrue((0 until actions.length).any { (actions.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") == "org.agentos.intent.action.PLUGIN" }, "intent-filter action org.agentos.intent.action.PLUGIN")
        val metas = svc.getElementsByTagName("meta-data").let { n -> (0 until n.length).map { n.item(it) as org.w3c.dom.Element } }
        val assets = metas.singleOrNull { it.getAttributeNS(ns, "name") == "org.agentos.plugin.assets" }
        assertTrue(assets != null, "meta-data org.agentos.plugin.assets")
        assertEquals("agent-plugin", assets!!.getAttributeNS(ns, "value"))
    }

    private fun checkSkills(name: String) {
        val app = requireApp(name)
        val s = spec(name)
        val files = skillFiles(app)
        val skillMd = files.filter { it.endsWith("/SKILL.md") }
        assertEquals(listOf("skills/${s.dir}/SKILL.md"), skillMd, "SKILL.md files")
        val text = File(assets(app), skillMd.single()).readText()
        val parsed = SkillFrontmatter.parse(text)
        assertEquals(emptyList(), parsed.problems, "SKILL.md frontmatter problems")
        assertEquals(s.dir, parsed.name, "frontmatter name equals the skill directory")
        assertTrue(parsed.description.orEmpty().length in 20..1024, "description is a real sentence for the model, got ${parsed.description?.length} chars")
        val missing = s.tools.filter { it !in text }
        assertEquals(emptyList(), missing, "the SKILL.md tells the model about every required tool")
    }

    private fun checkTools(name: String) {
        val app = requireApp(name)
        val s = spec(name)
        val found = registeredToolNames(app)
        val missing = s.tools.filter { it !in found }
        assertEquals(
            emptyList(),
            missing,
            "required tools not found as literals in ${name}'s tool sources (docs/sample-apps.md 4; matching rule in this class's KDoc). " +
                "found: ${found.sorted()}",
        )
    }

    // 具名参数：每个 App 一个测试，名字里写明缺目录时跳过
    @Test fun `alarm - plugin json is accepted with the documented names (skipped when plugins-samples-alarm is absent)`() = checkManifest("alarm")
    @Test fun `calendar - plugin json is accepted with the documented names (skipped when plugins-samples-calendar is absent)`() = checkManifest("calendar")
    @Test fun `notes - plugin json is accepted with the documented names (skipped when plugins-samples-notes is absent)`() = checkManifest("notes")
    @Test fun `sms - plugin json is accepted with the documented names (skipped when plugins-samples-sms is absent)`() = checkManifest("sms")

    @Test fun `alarm - the app manifest exports the bound MCP service (skipped when plugins-samples-alarm is absent)`() = checkAndroidManifest("alarm")
    @Test fun `calendar - the app manifest exports the bound MCP service (skipped when plugins-samples-calendar is absent)`() = checkAndroidManifest("calendar")
    @Test fun `notes - the app manifest exports the bound MCP service (skipped when plugins-samples-notes is absent)`() = checkAndroidManifest("notes")
    @Test fun `sms - the app manifest exports the bound MCP service (skipped when plugins-samples-sms is absent)`() = checkAndroidManifest("sms")

    @Test fun `alarm - SKILL md is valid and covers every required tool (skipped when plugins-samples-alarm is absent)`() = checkSkills("alarm")
    @Test fun `calendar - SKILL md is valid and covers every required tool (skipped when plugins-samples-calendar is absent)`() = checkSkills("calendar")
    @Test fun `notes - SKILL md is valid and covers every required tool (skipped when plugins-samples-notes is absent)`() = checkSkills("notes")
    @Test fun `sms - SKILL md is valid and covers every required tool (skipped when plugins-samples-sms is absent)`() = checkSkills("sms")

    @Test fun `alarm - every documented tool is registered in the Kotlin sources, more is fine (skipped when plugins-samples-alarm is absent)`() = checkTools("alarm")
    @Test fun `calendar - every documented tool is registered in the Kotlin sources, more is fine (skipped when plugins-samples-calendar is absent)`() = checkTools("calendar")
    @Test fun `notes - every documented tool is registered in the Kotlin sources, more is fine (skipped when plugins-samples-notes is absent)`() = checkTools("notes")
    @Test fun `sms - every documented tool is registered in the Kotlin sources, more is fine (skipped when plugins-samples-sms is absent)`() = checkTools("sms")

    // ------------------------------------------------------------------ 不依赖 App 文件的检查（总是运行）

    @Test
    fun `the tool constants in this test are exactly the lists in docs sample-apps md section 4`() {
        val doc = File(repoRoot(), "docs/sample-apps.md").readText()
        val section = doc.substringAfter("## 4.").substringBefore("\n## 5.")
        val parts = Regex("(?m)^### 4\\.\\d+ .*`([a-z]+)`\\s*$").findAll(section).toList()
        assertEquals(listOf("alarm", "calendar", "notes"), parts.map { it.groupValues[1] }, "sections 4.1–4.3 of docs/sample-apps.md")
        for ((i, part) in parts.withIndex()) {
            val end = parts.getOrNull(i + 1)?.range?.first ?: section.length
            val body = section.substring(part.range.last, end)
            val names = Regex("(?m)^\\| `([a-z][a-z0-9_]*)` \\|").findAll(body).map { it.groupValues[1] }.toList()
            assertEquals(spec(part.groupValues[1]).tools, names, "tool table of ${part.groupValues[1]} in docs/sample-apps.md")
        }
        assertEquals(listOf(8, 11, 10), specs.take(3).map { it.tools.size }) // alarm, calendar, notes：sample-apps.md 第 4 节只有这三个
    }

    @Test
    fun `the 29 documented tools get distinct valid model-facing names`() {
        val ids = specs.take(3).flatMap { s -> s.tools.map { ToolId(s.plugin, s.server, it) } }
        val names = ToolNaming.assign(ids)
        assertEquals(29, names.values.toSet().size)
        for ((id, n) in names) {
            assertEquals("mcp__${id.plugin}__${id.server}__${id.tool}", n)
            assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(n), n)
        }
    }

    @Test
    fun `the tool name matcher finds named and positional literals and ignores comments`() {
        val src = """
            // name = "commented_out"
            /* tool("also_commented") */
            private val a = tool(
                "calendar_list",
                "desc",
            )
            ToolDef(name = "alarm_get", title = "x")
            ToolDef("alarm_dismiss", "d")
            thread(name = "Calendar-reminder")
        """.trimIndent()
        val found = toolLiterals(src)
        assertEquals(setOf("calendar_list", "alarm_get", "alarm_dismiss"), found)
    }

    // ------------------------------------------------------------------ 匹配实现

    private fun registeredToolNames(app: File): Set<String> {
        val kotlin = File(app, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val tools = kotlin.filter { "/tools/" in it.invariantSeparatorsPath }.ifEmpty { kotlin }
        return tools.flatMapTo(LinkedHashSet()) { toolLiterals(it.readText()) }
    }

    private fun toolLiterals(source: String): Set<String> {
        val code = source.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")
        return LITERAL.findAll(code).map { it.groupValues[1] }.toCollection(LinkedHashSet())
    }

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("(?m)^\\s*//.*$")
        val LITERAL = Regex("(?:\\bname\\s*=\\s*|\\b(?:tool|ToolDef)\\(\\s*)\"([a-z][a-z0-9_]*)\"")
    }
}
