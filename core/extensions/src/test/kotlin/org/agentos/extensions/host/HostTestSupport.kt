package org.agentos.extensions.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.extensions.McpServerDecl
import org.agentos.extensions.registry.InstalledAppView
import org.agentos.extensions.registry.PersistedRegistry
import org.agentos.extensions.registry.PluginAssets
import org.agentos.extensions.registry.PluginIdentity
import org.agentos.extensions.registry.PluginRecord
import org.agentos.extensions.registry.PluginRegistry
import org.agentos.extensions.registry.PluginScanLogic
import org.agentos.extensions.registry.PluginServiceInfo
import org.agentos.extensions.registry.ScanResult
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.testing.FakeApprovalPolicyPort
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** 假的 MCP 服务器（对应一个 App 里的一个 MCP 服务）：工具列表、调用行为、连接行为都可以脚本化。 */
class FakeServer(var tools: List<McpToolInfo> = emptyList()) {
    var handler: suspend (name: String, args: JsonObject) -> McpCallResult = { name, _ -> McpCallResult(listOf(McpContentPart.Text("ok:$name"))) }
    var connectFailure: Throwable? = null
    var connectDelayMillis: Long = 0
    var listFailure: McpLinkException? = null

    /** 为 true 时链接在被关闭后不让挂起的调用抛异常（测试宿主层自己的“连接关闭”赛跑）。 */
    var ignoresClose: Boolean = false
    var failNextCall: McpLinkException? = null

    val links = ArrayList<FakeLink>()
    val connects = AtomicInteger()
    val listCalls = AtomicInteger()
    val calls = java.util.Collections.synchronizedList(ArrayList<Pair<String, JsonObject>>())
    val cancelledCalls = java.util.Collections.synchronizedList(ArrayList<String>())
    val concurrent = AtomicInteger()
    val maxConcurrent = AtomicInteger()

    val live: FakeLink? get() = links.lastOrNull { !it.closed.isCompleted }

    /** 模拟 App 进程死亡。 */
    fun kill(reason: String = "process died") = links.filter { !it.closed.isCompleted }.forEach { it.die(reason) }

    fun toolsChanged() = live?.changed?.tryEmit(Unit)
}

class FakeLink(private val server: FakeServer) : McpServerLink {
    private val closedDeferred = CompletableDeferred<String>()
    val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override val toolsChanged: Flow<Unit> get() = changed
    override val closed: kotlinx.coroutines.Deferred<String> get() = closedDeferred

    fun die(reason: String) {
        closedDeferred.complete(reason)
    }

    override fun close() {
        closedDeferred.complete("closed by host")
    }

    override suspend fun listTools(timeoutMillis: Long): List<McpToolInfo> {
        server.listCalls.incrementAndGet()
        server.listFailure?.let { throw it }
        if (closedDeferred.isCompleted) throw ConnectionLost("closed")
        return server.tools
    }

    override suspend fun callTool(name: String, arguments: JsonObject, timeoutMillis: Long): McpCallResult {
        if (closedDeferred.isCompleted) throw NotSent("link is closed")
        server.failNextCall?.let {
            server.failNextCall = null
            throw it
        }
        server.calls += name to arguments
        val now = server.concurrent.incrementAndGet()
        server.maxConcurrent.updateAndGet { maxOf(it, now) }
        try {
            return coroutineScope {
                val h = async { server.handler(name, arguments) }
                val timeout = async { delay(timeoutMillis) }
                val dead = closedDeferred
                try {
                    select {
                        h.onAwait { it }
                        timeout.onAwait { throw TimedOut(timeoutMillis) }
                        if (!server.ignoresClose) dead.onAwait { throw ConnectionLost(it) }
                    }
                } finally {
                    h.cancel()
                    timeout.cancel()
                }
            }
        } catch (e: CancellationException) {
            server.cancelledCalls += name
            throw e
        } finally {
            server.concurrent.decrementAndGet()
        }
    }
}

class FakeConnector : McpServerConnector {
    val servers = HashMap<ServerKey, FakeServer>()

    fun server(pkg: String, server: String): FakeServer = servers.getValue(ServerKey(pluginId(pkg), server))

    override suspend fun connect(plugin: PluginRecord, server: McpServerDecl): McpServerLink {
        val fake = servers[ServerKey(plugin.id, server.name)] ?: throw ConnectFailed("no app for ${plugin.id}")
        if (fake.connectDelayMillis > 0) delay(fake.connectDelayMillis)
        fake.connectFailure?.let { throw it }
        fake.connects.incrementAndGet()
        return FakeLink(fake).also { fake.links += it }
    }
}

fun tool(name: String, readOnly: Boolean? = null, destructive: Boolean? = null, description: String? = "does $name", schema: JsonObject = buildJsonObject { put("type", "object") }) =
    McpToolInfo(name, title = null, description = description, inputSchema = schema, annotations = McpToolAnnotations(readOnlyHint = readOnly, destructiveHint = destructive))

/** 三个示例 App（闹钟、日历、备忘录）的工具，注解按 docs/sample-apps.md：list/get/search 只读，delete 破坏性。 */
object Samples {
    val alarm = listOf(tool("alarm_list", readOnly = true), tool("alarm_get", readOnly = true), tool("alarm_create"), tool("alarm_update"), tool("alarm_delete", destructive = true))
    val calendar = listOf(tool("event_list", readOnly = true), tool("event_create"), tool("event_delete", destructive = true), tool("free_slots", readOnly = true))
    val notes = listOf(tool("note_list", readOnly = true), tool("note_create"), tool("note_append"), tool("note_trash"), tool("note_delete", destructive = true))
}

/** 一个插件的安装视图（单个 Binder 服务器，服务器名 = 插件名）。 */
fun appView(pkg: String, name: String, signer: String = "sig-1", versionCode: Long = 1, serverNames: List<String> = listOf(name)): InstalledAppView {
    val servers = serverNames.joinToString(",") { "\"$it\":{\"service\":\"$pkg.Mcp_$it\"}" }
    val json = """{"${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"$name","extensions":{"org.agentos":{"mcpServers":{$servers}}}}"""
    val services = serverNames.mapIndexed { i, s ->
        PluginServiceInfo("$pkg.Mcp_$s", exported = true, permission = PluginScanLogic.BIND_PERMISSION, isPluginAnchor = i == 0, assetsDir = if (i == 0) "agent-plugin" else null)
    }
    return InstalledAppView(PluginIdentity(pkg, signer, versionCode), services, mapOf("agent-plugin" to PluginAssets(json)))
}

fun pluginId(pkg: String) = "$pkg/agent-plugin"

/** 注册表 + 策略的组合：扫描得到注册表，用户把 [enable] 里的插件都启用。 */
class World(val scope: TestScope, views: List<InstalledAppView>, enable: List<String>, builtin: Set<String> = emptySet()) {
    val connector = FakeConnector()
    val policy = FakeApprovalPolicyPort()
    private val registryFlow = MutableStateFlow(PluginRegistry())
    val registry: StateFlow<PluginRegistry> = registryFlow
    var scan: ScanResult = PluginScanLogic.scan(views, PersistedRegistry.EMPTY, ApprovalPolicy.DEFAULT, builtin)
        private set

    init {
        registryFlow.value = scan.registry
        policy.update { var p = scan.policy; for (n in enable) p = p.withEnabled(PolicyScope.Plugin(n), true); p }
    }

    fun rescan(views: List<InstalledAppView>, builtin: Set<String> = emptySet()) {
        scan = PluginScanLogic.scan(views, scan.persisted, policy.policy.value, builtin)
        policy.update { scan.policy }
        registryFlow.value = scan.registry
    }

    fun addServer(pkg: String, server: String, tools: List<McpToolInfo>): FakeServer =
        FakeServer(tools).also { connector.servers[ServerKey(pluginId(pkg), server)] = it }

    fun host(config: ExtensionHostConfig = ExtensionHostConfig()): ExtensionToolHost =
        ExtensionToolHost(registry, policy, connector, scope.backgroundScope, config, nowMillis = { scope.testScheduler.currentTime })
}

/**
 * 端到端测试（真线程、真 SQLite）的等待：**等条件成立，不等挂钟时间**。
 *
 * 以前这些测试用 `delay(50)` / `delay(100)` 后直接断言异步的结果，并把整个测试放在 15 秒的 `withTimeout` 里；机器忙的时候（整个构建并行跑各模块的测试）
 * 第一个用例光冷启动就能慢几倍，睡眠不够、总预算也容易被挤爆。现在：
 * - [awaitUntil]：轮询到条件成立为止，最多 [SETUP_MILLIS]；超时抛 [AssertionError]，说明在等什么和当时的状态（[diagnostics]），不是一个没有信息的 TimeoutCancellationException；
 * - [TEST_MILLIS]：一个用例的总预算，只是防止真挂住时测试永远不结束，不是性能断言；
 * 断言本身一条没有放宽：等的只是断言的前提（目录里出现了、策略传到了、界面回调到了）。
 */
object E2e {
    const val SETUP_MILLIS = 30_000L
    const val TEST_MILLIS = 90_000L

    suspend fun awaitUntil(what: String, diagnostics: () -> String = { "" }, timeoutMillis: Long = SETUP_MILLIS, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out after $timeoutMillis ms waiting for $what" + diagnostics().let { if (it.isEmpty()) "" else ": $it" })
            delay(10)
        }
    }
}
