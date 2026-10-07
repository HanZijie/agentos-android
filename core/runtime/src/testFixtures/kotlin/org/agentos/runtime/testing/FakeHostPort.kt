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
import org.agentos.runtime.ports.ModelConfigPort
import org.agentos.runtime.ports.ModelSpec
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.SafeModeState
import org.agentos.runtime.ports.SecretPort
import org.agentos.runtime.ports.SkillPort
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
class FakeToolPort : ToolPort {
    private val impls = mutableMapOf<String, suspend (ToolInvocation) -> ToolInvocationResult>()
    private val _catalog = MutableStateFlow(ToolCatalog.EMPTY)
    override val catalog: StateFlow<ToolCatalog> = _catalog

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
    override val skills: SkillPort = SkillPort.NONE,
    override val clock: ManualClock = ManualClock(),
    override val log: CollectingLog = CollectingLog(),
    databaseFile: File = Files.createTempDirectory("agentos-store").resolve("agent.db").toFile(),
    model: ModelSpec? = FAKE_MODEL,
    credentials: Map<String, String> = mapOf(FAKE_BASE_URL to "fake-key-0123456789"),
) : HostPort {
    val activeModel = MutableStateFlow(model)
    val safeMode = MutableStateFlow(SafeModeState.OFF)

    /** 模拟“上一个进程被用户主动停止”。 */
    @Volatile var stoppedByUser: Boolean = false
    private val secretsByPrefix = credentials

    override val storage: StoragePort = object : StoragePort {
        override val driver: SQLiteDriver = BundledSQLiteDriver()
        override val databasePath: String = databaseFile.absolutePath
    }

    override val models: ModelConfigPort = object : ModelConfigPort {
        override val activeModel: StateFlow<ModelSpec?> = this@FakeHostPort.activeModel
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
