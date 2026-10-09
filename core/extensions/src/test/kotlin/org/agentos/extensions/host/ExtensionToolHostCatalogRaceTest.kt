package org.agentos.extensions.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.agentos.extensions.registry.PersistedRegistry
import org.agentos.extensions.registry.PluginRegistry
import org.agentos.extensions.registry.PluginScanLogic
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.ports.ApprovalPolicyPort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 目录重建（读策略 → 算目录 → 写目录）不能被另一次重建夹在中间：读到旧策略的那一次如果最后写入，
 * 目录就停在旧策略上，而且之后没有任何东西会再重建它（策略已经变过了，不会再有通知）。
 *
 * 真实的触发条件很窄（两个线程在微秒级的窗口里交错），在 CI 的 Linux 机器上偶发、在开发机上几乎见不到
 * （ExtensionToolHostEndToEndTest 的“用户关掉的工具不会提供给模型”因此在 CI 上失败过）。
 * 这里用一个读完策略后可以被卡住的策略端口把窗口撑开：卡在“已经读到旧策略”之后，期间把策略改掉，再放开。
 */
class ExtensionToolHostCatalogRaceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notesPkg = "org.x.notes"
    private val connector = FakeConnector()
    private val server = FakeServer(Samples.notes).also { connector.servers[ServerKey(pluginId(notesPkg), "notes")] = it }
    private val scan = PluginScanLogic.scan(listOf(appView(notesPkg, "notes")), PersistedRegistry.EMPTY, ApprovalPolicy.DEFAULT)
    private val trash = PolicyScope.Tool("notes", "notes", "note_trash")
    private val trashName = "mcp__notes__notes__note_trash"

    /** 读到当前值之后、把值交给调用方之前，可以卡住一次的策略端口。 */
    private class StallingPolicyPort(initial: ApprovalPolicy) : ApprovalPolicyPort {
        private val flow = MutableStateFlow(initial)

        /** 下一次读 `policy.value` 的线程读到值之后在这个门上等；用过一次就清掉。 */
        val stall = AtomicReference<CountDownLatch?>(null)
        val stalled = AtomicReference<CountDownLatch?>(null)

        override val policy: StateFlow<ApprovalPolicy> = object : StateFlow<ApprovalPolicy> by flow {
            override val value: ApprovalPolicy
                get() {
                    val v = flow.value
                    stall.getAndSet(null)?.let { gate ->
                        stalled.get()?.countDown()
                        gate.await(30, TimeUnit.SECONDS)
                    }
                    return v
                }
        }

        fun update(change: (ApprovalPolicy) -> ApprovalPolicy) {
            flow.value = change(flow.value)
        }
    }

    private val policy = StallingPolicyPort(scan.policy.withEnabled(PolicyScope.Plugin("notes"), true))
    private val extHost = ExtensionToolHost(MutableStateFlow<PluginRegistry>(scan.registry), policy, connector, scope)

    @AfterTest
    fun cleanUp() {
        extHost.close()
        scope.cancel()
    }

    @Test
    fun `a rebuild that read the old policy cannot overwrite the catalog of the new policy`() = runBlocking {
        E2e.awaitUntil("the tool catalog to list the notes tools", { "serverStates=${extHost.serverStates.value}" }) { extHost.catalog.value.tools.isNotEmpty() }
        assertTrue(extHost.catalog.value.tools.any { it.name == trashName }, "precondition: note_trash is offered")

        // 一次重建（强制刷新走 refreshTools → rebuildCatalog）读到旧策略，然后被卡住
        val reachedRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        policy.stalled.set(reachedRead)
        policy.stall.set(release)
        val refresh = scope.async { extHost.refreshNow(timeoutMillis = 30_000, force = true) }
        assertTrue(reachedRead.await(10, TimeUnit.SECONDS), "a rebuild is in flight and has read the policy")

        // 它被卡住的时候用户关掉 note_trash；策略变更触发的那一次重建，让目录里去掉它
        policy.update { it.withEnabled(trash, false) }
        delay(300)

        // 放开：读到旧策略的那一次现在继续。它不能把目录写回有 note_trash 的样子
        release.countDown()
        refresh.await()
        E2e.awaitUntil(
            "note_trash to leave the catalog after the stale rebuild finished",
            { "catalog=${extHost.catalog.value.tools.map { it.name }}" },
            timeoutMillis = 5_000,
        ) { extHost.catalog.value.tools.none { it.name == trashName } }
    }
}
