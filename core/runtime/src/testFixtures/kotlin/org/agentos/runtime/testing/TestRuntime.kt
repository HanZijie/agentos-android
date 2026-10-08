package org.agentos.runtime.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.AgentRuntimes
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.RuntimeEngine
import org.agentos.runtime.broker.CallerPolicy
import org.agentos.runtime.broker.OpenCallerPolicy
import org.agentos.runtime.events.EventEnvelope
import org.agentos.runtime.ports.AgentCoreFactory
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.quota.CallerQuotaConfig
import org.agentos.runtime.scheduler.SchedulerConfig
import java.io.File
import java.nio.file.Files

/**
 * 测试用的完整运行时：FakeHostPort（临时目录里的真实 SQLite）+ FakeAgentCore + RuntimeEngine。
 * 同一个 [databaseFile] 可以先后交给两个 TestRuntime，模拟进程重启。
 *
 * @param coreFactory 换掉 FakeAgentCore（例如 `AcpStdioAgent --core=pi` 用真实 Pi：PiAdapter + 电脑上的 QuickJS + 假模型端点）；
 *   这时 [core]、[cores]、[turnsStarted] 没有意义。
 */
class TestRuntime(
    scripts: (FakeTurnContext) -> FakeTurnScript = FakeScripts.echo(),
    val databaseFile: File = Files.createTempDirectory("agentos-rt").resolve("agent.db").toFile(),
    val host: FakeHostPort = FakeHostPort(databaseFile = databaseFile),
    schedulerConfig: SchedulerConfig = SchedulerConfig(tickMillis = 20),
    config: RuntimeConfig = RuntimeConfig(scheduler = schedulerConfig, quota = UNLIMITED_QUOTA),
    coreFactory: AgentCoreFactory? = null,
) {
    /** 最近一次由工厂创建的 FakeAgentCore（泵故障后会换新的）。 */
    @Volatile var core: FakeAgentCore? = null
        private set

    val cores = mutableListOf<FakeAgentCore>()

    val factory: AgentCoreFactory = coreFactory
        ?: AgentCoreFactory { FakeAgentCore(scripts).also { core = it; synchronized(cores) { cores += it } } }

    val engine: RuntimeEngine = AgentRuntimes.create(host, factory, config)

    suspend fun start(): TestRuntime = apply { engine.start() }

    suspend fun stop() = engine.shutdown()

    /** 所有 FakeAgentCore 累计开始的轮次数。 */
    fun turnsStarted(): Int = synchronized(cores) { cores.sumOf { it.turnsStarted.get() } }

    /** 等某个会话里出现满足条件的事件。 */
    suspend fun awaitEvent(sessionId: String, timeoutMillis: Long = 5_000, predicate: (EventEnvelope) -> Boolean): EventEnvelope =
        withTimeout(timeoutMillis) { engine.events(sessionId).first(predicate) }

    /** 轮询直到条件成立。 */
    suspend fun until(timeoutMillis: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMillis) {
        while (!condition()) delay(5)
    }

    companion object {
        val APP = CallerIdentity(10_123, CallerKind.APP, "com.example.app")
        val OTHER_APP = CallerIdentity(10_456, CallerKind.APP, "com.example.other")
        val SELF = CallerIdentity(10_001, CallerKind.SELF, "AgentOS")

        /** The desktop (the paired computer); shares one session space. */
        val DESKTOP = CallerIdentity(2_000, CallerKind.DESKTOP, "desktop")

        /**
         * The default of [TestRuntime]: no per-app limits, so tests of the scheduler and of the protocol can let a third-party caller submit as
         * much as they need. Tests of the limits themselves (CallerQuotaTest, ThirdPartyQuotaTest) give their own `RuntimeConfig(quota = ...)`.
         */
        val UNLIMITED_QUOTA = CallerQuotaConfig(maxPromptChars = Int.MAX_VALUE, maxPromptsPerHour = Int.MAX_VALUE, maxConcurrentPrompts = Int.MAX_VALUE)

        /** A [RuntimeConfig] with [callerPolicy] (default: the shipped default, open) and no per-app limits. */
        fun config(callerPolicy: CallerPolicy = OpenCallerPolicy, quota: CallerQuotaConfig = UNLIMITED_QUOTA): RuntimeConfig =
            RuntimeConfig(scheduler = SchedulerConfig(tickMillis = 20), quota = quota, callerPolicy = callerPolicy)

        fun text(vararg parts: String): JsonArray = buildJsonArray {
            parts.forEach { t -> add(buildJsonObject { put("type", "text"); put("text", JsonPrimitive(t)) }) }
        }
    }
}
