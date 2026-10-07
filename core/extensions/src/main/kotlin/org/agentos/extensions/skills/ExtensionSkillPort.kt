package org.agentos.extensions.skills

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.agentos.extensions.registry.PluginProblem
import org.agentos.extensions.registry.PluginRecord
import org.agentos.extensions.registry.PluginRegistry
import org.agentos.extensions.registry.PluginStatus
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.ports.ApprovalPolicyPort
import org.agentos.runtime.ports.SkillCatalog
import org.agentos.runtime.ports.SkillContent
import org.agentos.runtime.ports.SkillPort
import org.agentos.runtime.ports.SkillSummary
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** [ExtensionSkillPort] 的参数。 */
data class SkillConfig(
    /** 目录里描述的上限（字符，第三方文本）。 */
    val maxDescriptionChars: Int = 1_024,
    /** 为取 name 和 description 最多读 SKILL.md 的前这么多字节。 */
    val maxFrontmatterBytes: Int = 16 * 1024,
    /** `read` 单次读取的上限（字节）；超过截断并标记。 */
    val maxReadBytes: Int = 64 * 1024,
    val maxSkillsPerPlugin: Int = 64,
    val maxSkillsTotal: Int = 256,
)

/**
 * `HostPort.skills` 的实现（docs/extensions.md 第 6 节）：Skill 目录来自插件注册表，**不依赖 Android**；文件内容经 [SkillFileSource]。
 *
 * ## 目录
 * - 只列**插件可用（READY）且 plugin 级启用**的插件（[ApprovalPolicy.pluginEnabled]）的 Skill；插件被禁用、签名变化、被移除，
 *   它的 Skill 立刻从目录消失，也读不到（[read] 每次都再检查一次）。服务器、工具级的禁用不影响 Skill。
 * - 来源是 [PluginRecord.manifest] 的 `skills/<目录>/SKILL.md` 清单；名字和描述从 SKILL.md 的 frontmatter 读（[SkillFrontmatter]，只读前
 *   [SkillConfig.maxFrontmatterBytes] 字节）。**每个 Skill 的问题都不影响别的 Skill**：frontmatter 缺失、没结束、非法、SKILL.md 读不到，
 *   原因记在 [problems]（按插件，给插件页显示）。没有合法 name 时用目录名；目录名也不合法（不是 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`）时这个 Skill 跳过。
 *   description 缺失时仍然列出（描述为空）。
 * - **标识**（[SkillSummary.id]，`read_skill` 用）：名字在整个目录里唯一就用名字；冲突的**全部**改用 `<插件名>:<Skill 名>`；
 *   同一个插件里还重名的，改用 `<插件名>:<目录名>`。目录按“自带插件在前，然后按插件 ID，再按目录名”排序（确定的顺序；系统提示截断时按这个顺序保留）。
 * - 描述截断到 [SkillConfig.maxDescriptionChars]，空白折叠成单个空格，去掉控制字符。数量有上限。
 *
 * ## 读取
 * [read]：`path` 相对 Skill 目录（空表示 SKILL.md），只能读该目录里、且在插件包文件清单里的文件（[SkillPaths]：`..`、绝对路径、NUL、反斜杠…
 * 都拒绝）；单次最多 [SkillConfig.maxReadBytes] 字节，超过截断并标记；含 NUL 的文件当作二进制拒绝。内容是第三方文本，不可信。
 * 错误：[NoSuchElementException]（没有这个 Skill / 文件），[IllegalArgumentException]（路径不合法、不是文本）。
 */
class ExtensionSkillPort(
    private val registry: StateFlow<PluginRegistry>,
    private val approvals: ApprovalPolicyPort,
    private val files: SkillFileSource,
    parentScope: CoroutineScope,
    private val config: SkillConfig = SkillConfig(),
) : SkillPort, AutoCloseable {

    private class Entry(val plugin: PluginRecord, val directory: String, val summary: SkillSummary)

    private class Header(val name: String?, val description: String, val problems: List<String>)

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + CoroutineName("extension-skills"))
    private val entries = AtomicReference<Map<String, Entry>>(emptyMap())
    private val headers = ConcurrentHashMap<String, Header>()
    private val catalogFlow = MutableStateFlow(SkillCatalog.EMPTY)
    private val problemsFlow = MutableStateFlow<Map<String, List<PluginProblem>>>(emptyMap())

    override val catalog: StateFlow<SkillCatalog> = catalogFlow.asStateFlow()

    /** 每个插件的 Skill 问题（插件 ID → 问题），给插件页显示；没有问题的插件不出现。 */
    val problems: StateFlow<Map<String, List<PluginProblem>>> = problemsFlow.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(registry, approvals.policy) { r, p -> r to p }.collectLatest { (r, p) -> rebuild(r, p) }
        }
    }

    override suspend fun read(skillId: String, path: String?): SkillContent {
        val entry = entries.get()[skillId] ?: throw NoSuchElementException("unknown skill: $skillId")
        // 目录是异步重建的：读之前再确认一次插件仍然可用、启用
        val current = registry.value[entry.plugin.id]
        val name = entry.plugin.name
        if (current == null || current.status != PluginStatus.READY || name == null || !approvals.policy.value.pluginEnabled(name)) {
            throw NoSuchElementException("unknown skill: $skillId")
        }
        val full = SkillPaths.resolve(entry.directory, path)
        if (full !in entry.plugin.skillFiles) throw NoSuchElementException("no such file in skill $skillId: ${path ?: SkillPaths.SKILL_FILE}")
        val file = try {
            files.read(entry.plugin, full, config.maxReadBytes + 1)
        } catch (e: IOException) {
            throw NoSuchElementException("cannot read ${path ?: SkillPaths.SKILL_FILE} of skill $skillId")
        } ?: throw NoSuchElementException("no such file in skill $skillId: ${path ?: SkillPaths.SKILL_FILE}")
        if (file.bytes.any { it == 0.toByte() }) throw IllegalArgumentException("not a text file: ${path ?: SkillPaths.SKILL_FILE}")
        val truncated = file.truncated || file.bytes.size > config.maxReadBytes
        val bytes = if (file.bytes.size > config.maxReadBytes) file.bytes.copyOf(config.maxReadBytes) else file.bytes
        return SkillContent(String(bytes, Charsets.UTF_8), truncated)
    }

    override fun close() {
        scope.cancel()
    }

    // ------------------------------------------------------------------ 目录

    private data class Candidate(val plugin: PluginRecord, val directory: String, val name: String, val description: String)

    private suspend fun rebuild(reg: PluginRegistry, policy: ApprovalPolicy) {
        val problems = LinkedHashMap<String, MutableList<PluginProblem>>()
        val candidates = ArrayList<Candidate>()
        val live = HashSet<String>()
        val ready = reg.plugins.filter { it.status == PluginStatus.READY && it.name != null }
            .sortedWith(compareByDescending<PluginRecord> { it.builtin }.thenBy { it.id })
        for (plugin in ready) {
            val manifest = plugin.manifest ?: continue
            val enabled = policy.pluginEnabled(plugin.name!!)
            for (ref in manifest.skills.sortedBy { it.directory }.take(config.maxSkillsPerPlugin)) {
                val key = "${plugin.id}|${plugin.identity.versionCode}|${plugin.identity.signerDigest}|${ref.path}"
                live += key // 被禁用的插件也保留缓存：重新启用时不用再读文件
                if (!enabled) continue
                val header = headers[key] ?: loadHeader(plugin, ref.directory, ref.path).also { headers[key] = it }
                for (p in header.problems) problems.getOrPut(plugin.id) { ArrayList() } += PluginProblem("skill_problem", "Skill ${ref.directory}：$p")
                val name = header.name ?: continue
                candidates += Candidate(plugin, ref.directory, name, header.description)
            }
        }
        headers.keys.retainAll(live)

        val ids = assignIds(candidates.take(config.maxSkillsTotal))
        val newEntries = LinkedHashMap<String, Entry>()
        for ((c, id) in ids) {
            newEntries[id] = Entry(c.plugin, c.directory, SkillSummary(id, c.name, c.description, c.plugin.name!!))
        }
        entries.set(newEntries)
        problemsFlow.value = problems
        val skills = newEntries.values.map { it.summary }
        val current = catalogFlow.value
        if (current.skills != skills) catalogFlow.value = SkillCatalog(current.version + 1, skills)
    }

    private suspend fun loadHeader(plugin: PluginRecord, directory: String, path: String): Header {
        val file = try {
            files.read(plugin, path, config.maxFrontmatterBytes)
        } catch (e: IOException) {
            return Header(null, "", listOf("读不了 SKILL.md"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Header(null, "", listOf("读 SKILL.md 时出错"))
        } ?: return Header(null, "", listOf("插件包里没有 SKILL.md"))
        if (file.bytes.any { it == 0.toByte() }) return Header(null, "", listOf("SKILL.md 不是文本文件"))
        val parsed = SkillFrontmatter.parse(String(file.bytes, Charsets.UTF_8))
        val problems = ArrayList(parsed.problems)
        var name = parsed.name?.let(::cleanLine)
        if (name != null && !NAME_REGEX.matches(name)) {
            problems += "name \"${name.take(40)}\" 不合法（只能用字母、数字、`.`、`_`、`-`，最长 64 个字符，以字母或数字开头），改用目录名"
            name = null
        }
        if (name == null) {
            if (NAME_REGEX.matches(directory)) {
                name = directory
            } else {
                problems += "目录名 \"${directory.take(40)}\" 也不能当 Skill 名，这个 Skill 被跳过"
                return Header(null, "", problems)
            }
        }
        return Header(name, cleanText(parsed.description.orEmpty(), config.maxDescriptionChars), problems)
    }

    /** 冲突的全部改用 `<插件名>:<Skill 名>`，同一个插件里还重名的改用 `<插件名>:<目录名>`。 */
    private fun assignIds(candidates: List<Candidate>): List<Pair<Candidate, String>> {
        val byName = candidates.groupingBy { it.name }.eachCount()
        val first = candidates.map { c -> c to if (byName.getValue(c.name) == 1) c.name else "${c.plugin.name}:${c.name}" }
        val count = first.groupingBy { it.second }.eachCount()
        return first.map { (c, id) -> if (count.getValue(id) == 1) c to id else c to "${c.plugin.name}:${c.directory}" }
    }

    private fun cleanLine(s: String): String = cleanText(s, 200)

    private fun cleanText(s: String, max: Int): String {
        val sb = StringBuilder(minOf(s.length, max))
        var space = false
        for (c in s) {
            val blank = c.isWhitespace() || Character.isISOControl(c) || Character.getType(c) == Character.FORMAT.toInt()
            if (blank) {
                space = sb.isNotEmpty()
                continue
            }
            if (space) sb.append(' ')
            space = false
            sb.append(c)
            if (sb.length >= max) break
        }
        return sb.toString().trim()
    }

    private companion object {
        val NAME_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}
