package org.agentos.runtime.testing

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.ports.ApprovalPolicyPort
import org.agentos.runtime.ports.CatalogTool
import org.agentos.runtime.ports.Clock
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ConsentPort
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.Credential
import org.agentos.runtime.ports.EnvironmentPort
import org.agentos.runtime.ports.HookOutcome
import org.agentos.runtime.ports.HookPort
import org.agentos.runtime.ports.HookRequest
import org.agentos.runtime.ports.HostPort
import org.agentos.runtime.ports.ModelChoice
import org.agentos.runtime.ports.ModelConfigPort
import org.agentos.runtime.ports.ModelSpec
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.SafeModeState
import org.agentos.runtime.ports.SecretPort
import org.agentos.runtime.ports.SkillCatalog
import org.agentos.runtime.ports.SkillContent
import org.agentos.runtime.ports.SkillPort
import org.agentos.runtime.ports.SkillSummary
import org.agentos.runtime.ports.StoragePort
import org.agentos.runtime.ports.ToolCatalog
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolPort
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** 可以手动拨动的时钟。 */
class ManualClock(start: Long = 1_700_000_000_000L) : Clock {
    private val now = AtomicLong(start)
    private val mono = AtomicLong(0)

    override fun nowMillis(): Long = now.get()

    override fun monotonicNanos(): Long = mono.get()

    fun advance(millis: Long) {
        now.addAndGet(millis)
        mono.addAndGet(millis * 1_000_000)
    }
}

/** 内存里的工具目录：测试注册工具实现，或让某个工具返回 NotDispatched / Unknown。 */
open class FakeToolPort : ToolPort {
    private val impls = mutableMapOf<String, suspend (ToolInvocation) -> ToolInvocationResult>()
    private val _catalog = MutableStateFlow(ToolCatalog.EMPTY)
    override val catalog: StateFlow<ToolCatalog> get() = _catalog

    /** 按发生顺序记录的调用。 */
    val invocations: MutableList<ToolInvocation> = Collections.synchronizedList(mutableListOf())

    fun register(
        name: String,
        risk: ToolRisk = ToolRisk.READ,
        inputSchema: JsonObject = OBJECT_SCHEMA,
        source: ToolSource? = null,
        impl: suspend (ToolInvocation) -> ToolInvocationResult,
    ) = synchronized(this) {
        impls[name] = impl
        val tools = _catalog.value.tools.filter { it.name != name } +
            CatalogTool(name, "fake tool $name", inputSchema, risk, provider = "fake", source = source)
        _catalog.value = ToolCatalog(_catalog.value.version + 1, tools)
    }

    /** 注册一个直接返回结果的工具。 */
    fun registerSimple(name: String, risk: ToolRisk = ToolRisk.READ, source: ToolSource? = null, impl: suspend (JsonObject) -> ToolResult) =
        register(name, risk, source = source) { ToolInvocationResult.Completed(impl(it.arguments)) }

    fun unregister(name: String) = synchronized(this) {
        impls.remove(name)
        _catalog.value = ToolCatalog(_catalog.value.version + 1, _catalog.value.tools.filter { it.name != name })
    }

    override suspend fun invoke(invocation: ToolInvocation): ToolInvocationResult {
        invocations += invocation
        val impl = synchronized(this) { impls[invocation.name] }
            ?: return ToolInvocationResult.NotDispatched(ErrorCode.TOOL_NOT_IN_CATALOG.info("fake tool ${invocation.name} is not registered"))
        return impl(invocation)
    }

    companion object {
        val OBJECT_SCHEMA: JsonObject = buildJsonObject { put("type", "object") }
    }
}

/** 内存里的 Skill：测试注册 Skill（id → 内容和附属文件），读取时路径不在里面就抛 NoSuchElementException。 */
open class FakeSkillPort : SkillPort {
    private val flow = MutableStateFlow(SkillCatalog.EMPTY)
    private val files = mutableMapOf<String, Map<String, String>>()
    override val catalog: StateFlow<SkillCatalog> get() = flow

    /** 读这些 Skill 时报告“已截断”。 */
    val truncated: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** 注册一个 Skill：[files] 的键是相对 Skill 目录的路径，`SKILL.md` 必须有。 */
    fun register(id: String, description: String, provider: String = "fake", files: Map<String, String>) {
        this.files[id] = files
        flow.value = SkillCatalog(flow.value.version + 1, flow.value.skills.filter { it.id != id } + SkillSummary(id, id.substringAfter(':'), description, provider))
    }

    fun unregister(id: String) {
        files.remove(id)
        flow.value = SkillCatalog(flow.value.version + 1, flow.value.skills.filter { it.id != id })
    }

    override suspend fun read(skillId: String, path: String?): SkillContent {
        val skill = files[skillId] ?: throw NoSuchElementException("unknown skill: $skillId")
        val rel = if (path.isNullOrEmpty()) "SKILL.md" else path
        require(!rel.startsWith("/") && ".." !in rel.split('/')) { "invalid path" }
        return SkillContent(skill[rel] ?: throw NoSuchElementException("no such file in skill $skillId: $rel"), skillId in truncated)
    }
}

/** 内存里的用户工具策略：测试直接改 [policy]。 */
class FakeApprovalPolicyPort(initial: ApprovalPolicy = ApprovalPolicy.DEFAULT) : ApprovalPolicyPort {
    private val flow = MutableStateFlow(initial)
    override val policy: StateFlow<ApprovalPolicy> = flow

    fun update(change: (ApprovalPolicy) -> ApprovalPolicy) {
        flow.value = change(flow.value)
    }
}

/** 按脚本回答的确认端口，并记录请求。 */
class FakeConsentPort(var answer: suspend (ConsentRequest) -> ConsentDecision = { ConsentDecision.Allow() }) : ConsentPort {
    val requests: MutableList<ConsentRequest> = Collections.synchronizedList(mutableListOf())

    override suspend fun request(request: ConsentRequest): ConsentDecision {
        requests += request
        return answer(request)
    }
}

/** 按脚本回答的 Hook 端口，并记录请求。 */
class FakeHookPort(var answer: suspend (HookRequest) -> HookOutcome = { HookOutcome.NONE }) : HookPort {
    val requests: MutableList<HookRequest> = Collections.synchronizedList(mutableListOf())

    override suspend fun dispatch(request: HookRequest): HookOutcome {
        requests += request
        return answer(request)
    }
}

/** 记录日志行，供断言“日志里没有 key”。 */
class CollectingLog : RuntimeLog {
    val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun log(level: RuntimeLog.Level, tag: String, message: String, error: Throwable?) {
        lines += "$level/$tag: $message" + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
    }
}

/**
 * HostPort 的假实现。存储是临时目录里的真实 SQLite（BundledSQLiteDriver，与 Android 上同一份 SQL），
 * 测试结束调用 [deleteDatabase] 清理。
 */
class FakeHostPort(
    override val tools: FakeToolPort = FakeToolPort(),
    override val consent: FakeConsentPort = FakeConsentPort(),
    override val hooks: FakeHookPort = FakeHookPort(),
    override val approvals: FakeApprovalPolicyPort = FakeApprovalPolicyPort(),
    override val skills: FakeSkillPort = FakeSkillPort(),
    override val sessionTools: org.agentos.runtime.ports.SessionToolPort = FakeSessionToolPort(),
    override val clock: ManualClock = ManualClock(),
    override val log: CollectingLog = CollectingLog(),
    databaseFile: File = Files.createTempDirectory("agentos-store").resolve("agent.db").toFile(),
    model: ModelSpec? = FAKE_MODEL,
    credentials: Map<String, String> = mapOf(FAKE_BASE_URL to "fake-key-0123456789"),
) : HostPort {
    /** [sessionTools] 是假实现时的它（测试断言用）；构造时换成别的实现（例如 NONE）则抛异常。 */
    val fakeSessionTools: FakeSessionToolPort get() = sessionTools as FakeSessionToolPort

    val activeModel = MutableStateFlow(model)
    val safeMode = MutableStateFlow(SafeModeState.OFF)

    /** 模拟“上一个进程被用户主动停止”。 */
    @Volatile var stoppedByUser: Boolean = false
    private val secretsByPrefix = credentials

    override val storage: StoragePort = object : StoragePort {
        override val driver: SQLiteDriver = BundledSQLiteDriver()
        override val databasePath: String = databaseFile.absolutePath
    }

    /** 会话可以选的模型（`ModelConfigPort.choices`）；默认没有。 */
    val choices = MutableStateFlow<List<ModelChoice>>(emptyList())

    override val models: ModelConfigPort = object : ModelConfigPort {
        override val activeModel: StateFlow<ModelSpec?> = this@FakeHostPort.activeModel
        override val choices: StateFlow<List<ModelChoice>> = this@FakeHostPort.choices
    }

    // 只适合测试：这里用 startsWith 做字符串前缀匹配，生产不要照抄（前缀匹配会把 key 交给 https://api.example.com.evil.net）。
    // 生产按 endpoint 匹配，见 SecretPort.credentialFor 与 net/BaseUrlCredentials。
    override val secrets: SecretPort = object : SecretPort {
        override suspend fun credentialFor(url: String): Credential? =
            secretsByPrefix.entries.firstOrNull { url.startsWith(it.key) }?.let { Credential(it.value) }
    }

    override val environment: EnvironmentPort = object : EnvironmentPort {
        override val safeMode: StateFlow<SafeModeState> = this@FakeHostPort.safeMode
        override val previousExitStoppedByUser: Boolean get() = this@FakeHostPort.stoppedByUser
    }

    fun deleteDatabase() {
        val f = File(storage.databasePath)
        listOf(f, File("${f.path}-wal"), File("${f.path}-shm"), File("${f.path}-journal")).forEach { it.delete() }
        f.parentFile?.delete()
    }

    companion object {
        const val FAKE_BASE_URL = "https://fake.agentos.test/anthropic"

        val FAKE_MODEL = ModelSpec(
            buildJsonObject {
                put("id", "fake-model")
                put("name", "Fake model")
                put("api", "anthropic-messages")
                put("provider", "fake")
                put("baseUrl", FAKE_BASE_URL)
                put("reasoning", false)
                put("contextWindow", 200_000)
                put("maxTokens", 8_192)
            },
        )
    }
}


/**
 * `SessionToolPort` 的假实现（会话级工具）：记录每个会话 attach 过什么，给每个“连得上”的服务器一个写级工具 `ses__<server>__ping`，
 * 调用它返回 `pong:<server>`。可以让指定名字的服务器连不上，或让整批被拒绝。
 */
class FakeSessionToolPort : org.agentos.runtime.ports.SessionToolPort {
    class Attached(val owner: org.agentos.runtime.ports.CallerIdentity, val servers: List<org.agentos.runtime.ports.SessionMcpServer>)

    val attached = java.util.concurrent.ConcurrentHashMap<String, Attached>()
    val detached: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val invocations: MutableList<org.agentos.runtime.ports.ToolInvocation> = java.util.Collections.synchronizedList(mutableListOf())
    private val tools = java.util.concurrent.ConcurrentHashMap<String, List<org.agentos.runtime.ports.CatalogTool>>()

    /** 非 null 时 [attach] 一律抛它。 */
    @Volatile var reject: org.agentos.runtime.ports.SessionMcpRejected? = null

    /** 这些名字的服务器“连不上”。 */
    @Volatile var unreachable: Set<String> = emptySet()

    override suspend fun attach(
        sessionId: String,
        owner: org.agentos.runtime.ports.CallerIdentity,
        servers: List<org.agentos.runtime.ports.SessionMcpServer>,
    ): List<org.agentos.runtime.ports.SessionMcpResult> {
        reject?.let { throw it }
        attached[sessionId] = Attached(owner, servers)
        tools[sessionId] = servers.filter { it.name !in unreachable }.map {
            org.agentos.runtime.ports.CatalogTool(
                name = "ses__${it.name}__ping",
                description = "Ping ${it.name}",
                inputSchema = buildJsonObject { put("type", "object") },
                risk = org.agentos.runtime.ports.ToolRisk.WRITE,
                provider = "session:${it.name}",
            )
        }
        return servers.map {
            if (it.name in unreachable) {
                org.agentos.runtime.ports.SessionMcpResult(it.name, connected = false, reason = "connect_failed")
            } else {
                org.agentos.runtime.ports.SessionMcpResult(it.name, connected = true, toolCount = 1)
            }
        }
    }

    override fun detach(sessionId: String) {
        detached += sessionId
        attached.remove(sessionId)
        tools.remove(sessionId)
    }

    override fun tools(sessionId: String): List<org.agentos.runtime.ports.CatalogTool> = tools[sessionId].orEmpty()

    override suspend fun invoke(invocation: org.agentos.runtime.ports.ToolInvocation): org.agentos.runtime.ports.ToolInvocationResult {
        invocations += invocation
        val tool = tools(invocation.sessionId).firstOrNull { it.name == invocation.name }
            ?: return org.agentos.runtime.ports.ToolInvocationResult.NotDispatched(
                org.agentos.runtime.errors.ErrorCode.TOOL_NOT_IN_CATALOG.info("no such session tool"),
            )
        return org.agentos.runtime.ports.ToolInvocationResult.Completed(
            org.agentos.runtime.ports.ToolResult.text("pong:" + tool.provider.removePrefix("session:")),
        )
    }
}
