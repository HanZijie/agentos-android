package org.agentos.runtime.consent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.Clock
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.testing.CollectingLog
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 确认协调器：排队、各自超时（排队计入）、取消撤回、重复 id、上限、选项校验、始终允许的写回、界面回调的顺序。全部在虚拟时间里。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentCoordinatorTest {

    private val source = ToolSource("com.example.notes", "main", "append_note")

    private fun req(
        id: String,
        risk: ToolRisk = ToolRisk.WRITE,
        source: ToolSource? = this.source,
        timeout: Long = 60_000,
        rememberable: Boolean = true,
        title: String? = "追加备忘",
        args: String = "{\"text\":\"hello\"}",
        alwaysAllowOffered: Boolean = true,
    ) = ConsentRequest(
        requestId = id, sessionId = "s1", taskId = "t1", toolCallId = "c-$id", toolName = "append_note", toolTitle = title,
        risk = risk, caller = CallerIdentity(10123, CallerKind.APP, "com.example.app"), argumentsPreview = args,
        rememberable = rememberable, timeoutMillis = timeout, source = source, alwaysAllowOffered = alwaysAllowOffered,
    )

    private class RecordingSurface : ConsentSurface {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val resolutions = Collections.synchronizedMap(mutableMapOf<String, ConsentResolution>())
        val views = Collections.synchronizedMap(mutableMapOf<String, ConsentView>())
        var failNext = false

        override fun requested(view: ConsentView) {
            events += "requested:${view.requestId}"
            views[view.requestId] = view
            if (failNext) {
                failNext = false
                error("surface broke")
            }
        }

        override fun resolved(requestId: String, resolution: ConsentResolution) {
            events += "resolved:$requestId:${resolution.end}"
            resolutions[requestId] = resolution
        }
    }

    private class FakeWriter(var result: () -> ApprovalWriteResult = { ApprovalWriteResult.Saved }) : ApprovalWriter {
        val writes = mutableListOf<Pair<ToolSource, ToolRisk>>()
        var gate: CompletableDeferred<Unit>? = null
        override var available: Boolean = true

        override suspend fun setAlways(source: ToolSource, risk: ToolRisk): ApprovalWriteResult {
            writes += source to risk
            gate?.await()
            return result()
        }
    }

    private class Fixture(val scope: TestScope, val surface: RecordingSurface, val writer: FakeWriter, val log: CollectingLog, config: ConsentConfig) {
        val clock = object : Clock {
            override fun nowMillis() = 1_000_000L + scope.testScheduler.currentTime

            override fun monotonicNanos() = scope.testScheduler.currentTime * 1_000_000
        }
        val coordinator = ConsentCoordinator(surface, writer, scope.backgroundScope, clock, config, log)

        fun ask(request: ConsentRequest): Deferred<ConsentDecision> = scope.async { coordinator.request(request) }
    }

    private fun TestScope.fixture(config: ConsentConfig = ConsentConfig(), writer: FakeWriter = FakeWriter()) =
        Fixture(this, RecordingSurface(), writer, CollectingLog(), config)

    private fun Fixture.ids() = coordinator.pending.value.map { it.requestId }

    // ------------------------------------------------------------------ 基本

    @Test
    fun `a request shows up pending and allow once answers it`() = runTest {
        val f = fixture()
        val d = f.ask(req("r1"))
        runCurrent()
        assertEquals(listOf("r1"), f.ids())
        val v = f.coordinator.pending.value.single()
        assertEquals(1_000_000L, v.createdAtMillis)
        assertEquals(1_060_000L, v.deadlineMillis)
        assertEquals(0, v.queuePosition)
        assertEquals(listOf("requested:r1"), f.surface.events)

        assertTrue(f.coordinator.respond("r1", ConsentChoice.ALLOW_ONCE))
        assertEquals(ConsentDecision.Allow(), d.await())
        runCurrent()
        assertTrue(f.ids().isEmpty())
        assertEquals(listOf("requested:r1", "resolved:r1:ANSWERED"), f.surface.events)
        assertEquals(ConsentChoice.ALLOW_ONCE, f.surface.resolutions["r1"]!!.choice)
    }

    @Test
    fun `deny, allow for session and the user answer map to decisions`() = runTest {
        val f = fixture()
        val a = f.ask(req("a"))
        val b = f.ask(req("b"))
        runCurrent()
        f.coordinator.respond("a", ConsentChoice.DENY)
        f.coordinator.respond("b", ConsentChoice.ALLOW_FOR_SESSION)
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.USER), a.await())
        assertEquals(ConsentDecision.Allow(rememberForSession = true), b.await())
    }

    @Test
    fun `an answered request cannot be answered again and an unknown id is false`() = runTest {
        val f = fixture()
        val d = f.ask(req("r1"))
        runCurrent()
        assertTrue(f.coordinator.respond("r1", ConsentChoice.DENY))
        assertFalse(f.coordinator.respond("r1", ConsentChoice.ALLOW_ONCE), "the second answer loses")
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.USER), d.await())
        runCurrent()
        assertFalse(f.coordinator.respond("r1", ConsentChoice.ALLOW_ONCE), "answering after the request settled is false")
        assertFalse(f.coordinator.respond("nope", ConsentChoice.ALLOW_ONCE))
    }

    // ------------------------------------------------------------------ 排队和超时

    @Test
    fun `requests queue in arrival order and any of them can be answered`() = runTest {
        val f = fixture()
        val a = f.ask(req("a"))
        runCurrent()
        advanceTimeBy(1_000)
        val b = f.ask(req("b"))
        val c = f.ask(req("c"))
        runCurrent()
        assertEquals(listOf("a", "b", "c"), f.ids())
        assertEquals(listOf(0, 1, 2), f.coordinator.pending.value.map { it.queuePosition })
        assertTrue(f.coordinator.pending.value.all { it.queueSize == 3 })

        // 先答中间那条：队列重新编号，其余的不受影响
        f.coordinator.respond("b", ConsentChoice.ALLOW_ONCE)
        assertEquals(ConsentDecision.Allow(), b.await())
        assertEquals(listOf("a", "c"), f.ids())
        assertEquals(listOf(0, 1), f.coordinator.pending.value.map { it.queuePosition })
        assertTrue(f.coordinator.pending.value.all { it.queueSize == 2 })
        assertFalse(a.isCompleted)
        assertFalse(c.isCompleted)
        f.coordinator.respond("a", ConsentChoice.DENY)
        f.coordinator.respond("c", ConsentChoice.DENY)
        a.await()
        c.await()
    }

    @Test
    fun `every request times out on its own clock`() = runTest {
        val f = fixture()
        val a = f.ask(req("a", timeout = 10_000))
        val b = f.ask(req("b", timeout = 30_000))
        val c = f.ask(req("c", timeout = 60_000))
        runCurrent()

        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(listOf("a", "b", "c"), f.ids())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.TIMEOUT), a.await())
        assertEquals(listOf("b", "c"), f.ids())
        assertFalse(b.isCompleted)

        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.TIMEOUT), b.await())
        assertEquals(listOf("c"), f.ids())
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.TIMEOUT), c.await())
        assertTrue(f.ids().isEmpty())
        assertEquals(ConsentEnd.TIMED_OUT, f.surface.resolutions["a"]!!.end)
        assertEquals(ConsentEnd.TIMED_OUT, f.surface.resolutions["c"]!!.end)
    }

    @Test
    fun `time spent in the queue counts against the timeout`() = runTest {
        val f = fixture()
        val first = f.ask(req("first"))
        runCurrent()
        advanceTimeBy(45_000) // 第一条看了 45 秒都没答
        val second = f.ask(req("second"))
        runCurrent()
        assertEquals(1_000_000L + 45_000 + 60_000, f.surface.views["second"]!!.deadlineMillis)

        // 用户 45 秒时才回答第一条；第二条从创建起只剩 60 秒——它自己的 60 秒，不因为排在后面而重新计时
        f.coordinator.respond("first", ConsentChoice.ALLOW_ONCE)
        first.await()
        advanceTimeBy(59_999)
        runCurrent()
        assertFalse(second.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.TIMEOUT), second.await())
    }

    @Test
    fun `a request that waited behind others times out at its own deadline, not when it reaches the front`() = runTest {
        val f = fixture()
        val head = f.ask(req("head", timeout = 60_000))
        runCurrent()
        advanceTimeBy(50_000)
        val tail = f.ask(req("tail", timeout = 20_000))
        runCurrent()
        advanceTimeBy(10_000) // head 到点（60 秒）：队首撤掉，tail 才排了 10 秒
        runCurrent()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.TIMEOUT), head.await())
        assertEquals(listOf("tail"), f.ids())
        assertEquals(0, f.coordinator.pending.value.single().queuePosition)
        advanceTimeBy(9_999)
        runCurrent()
        assertFalse(tail.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.TIMEOUT), tail.await())
    }

    @Test
    fun `an answer after the timeout returns false and does not change the decision`() = runTest {
        val f = fixture()
        val d = f.ask(req("r1", timeout = 5_000))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(f.coordinator.respond("r1", ConsentChoice.ALLOW_ONCE))
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.TIMEOUT), d.await())
    }

    // ------------------------------------------------------------------ 取消

    @Test
    fun `cancelling the caller withdraws the request`() = runTest {
        val f = fixture()
        val a = f.ask(req("a"))
        val b = f.ask(req("b"))
        runCurrent()
        a.cancel()
        runCurrent()
        assertEquals(listOf("b"), f.ids())
        assertEquals(ConsentEnd.CANCELLED, f.surface.resolutions["a"]!!.end)
        assertEquals(listOf("requested:a", "requested:b", "resolved:a:CANCELLED"), f.surface.events)
        assertFalse(f.coordinator.respond("a", ConsentChoice.ALLOW_ONCE))
        assertFalse(b.isCompleted)
        f.coordinator.respond("b", ConsentChoice.DENY)
        b.await()
    }

    @Test
    fun `cancelling while the always-allow write is in flight still ends the request`() = runTest {
        val writer = FakeWriter().also { it.gate = CompletableDeferred() }
        val f = fixture(writer = writer)
        val d = f.ask(req("r1"))
        runCurrent()
        f.coordinator.respond("r1", ConsentChoice.ALWAYS_ALLOW)
        runCurrent()
        assertEquals(1, writer.writes.size)
        d.cancel()
        runCurrent()
        assertTrue(f.ids().isEmpty())
        assertEquals(ConsentEnd.CANCELLED, f.surface.resolutions["r1"]!!.end)
    }

    // ------------------------------------------------------------------ 重复 id 和上限

    @Test
    fun `a duplicate request id is refused and the original is untouched`() = runTest {
        val f = fixture()
        val first = f.ask(req("dup"))
        runCurrent()
        val second = f.ask(req("dup"))
        runCurrent()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE), second.await())
        assertEquals(listOf("dup"), f.ids())
        assertFalse(first.isCompleted)
        assertEquals(listOf("requested:dup"), f.surface.events, "the refused duplicate never reaches the surface")
        assertTrue(f.log.lines.any { "duplicate" in it })
        f.coordinator.respond("dup", ConsentChoice.ALLOW_ONCE)
        assertEquals(ConsentDecision.Allow(), first.await())
    }

    @Test
    fun `more than maxPending at once are refused as unavailable`() = runTest {
        val f = fixture(ConsentConfig(maxPending = 3))
        val held = (1..3).map { f.ask(req("r$it")) }
        runCurrent()
        val over = f.ask(req("r4"))
        runCurrent()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE), over.await())
        assertEquals(listOf("r1", "r2", "r3"), f.ids())
        // 腾出一个位置后可以再进来
        f.coordinator.respond("r1", ConsentChoice.DENY)
        held[0].await()
        val again = f.ask(req("r5"))
        runCurrent()
        assertEquals(listOf("r2", "r3", "r5"), f.ids())
        f.coordinator.close()
        again.await()
    }

    @Test
    fun `the default limit is sixteen`() = runTest {
        val f = fixture()
        val held = (1..16).map { f.ask(req("r$it")) }
        runCurrent()
        assertEquals(16, f.coordinator.pending.value.size)
        val over = f.ask(req("r17"))
        runCurrent()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE), over.await())
        f.coordinator.close()
        held.forEach { it.await() }
    }

    // ------------------------------------------------------------------ 选项校验

    @Test
    fun `high risk offers only allow once and deny`() = runTest {
        val f = fixture()
        f.ask(req("h", risk = ToolRisk.HIGH))
        runCurrent()
        val v = f.coordinator.pending.value.single()
        assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.DENY), v.options.map { it.choice })
        f.coordinator.close()
    }

    @Test
    fun `a high risk request answered with always allow is treated as deny and logged`() = runTest {
        val f = fixture()
        val d = f.ask(req("h", risk = ToolRisk.HIGH))
        runCurrent()
        assertTrue(f.coordinator.respond("h", ConsentChoice.ALWAYS_ALLOW))
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.USER), d.await())
        assertTrue(f.writer.writes.isEmpty(), "nothing was written to the user policy")
        assertTrue(f.log.lines.any { "not allowed" in it })
        runCurrent()
        assertEquals(ConsentChoice.DENY, f.surface.resolutions["h"]!!.choice)
    }

    @Test
    fun `a high risk request answered with allow for session is treated as deny`() = runTest {
        val f = fixture()
        val d = f.ask(req("h", risk = ToolRisk.HIGH))
        runCurrent()
        f.coordinator.respond("h", ConsentChoice.ALLOW_FOR_SESSION)
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.USER), d.await())
    }

    @Test
    fun `write offers all four and read offers no always allow`() = runTest {
        val f = fixture()
        f.ask(req("w", risk = ToolRisk.WRITE))
        f.ask(req("r", risk = ToolRisk.READ))
        runCurrent()
        val byId = f.coordinator.pending.value.associateBy { it.requestId }
        assertEquals(
            listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.ALLOW_FOR_SESSION, ConsentChoice.ALWAYS_ALLOW, ConsentChoice.DENY),
            byId.getValue("w").options.map { it.choice },
        )
        assertTrue(ConsentChoice.ALWAYS_ALLOW !in byId.getValue("r").options.map { it.choice })
        f.coordinator.close()
    }

    // ---- docs/third-party-acp.md 4.4: a request can have options taken away by the caller policy (the strict one does it for third-party apps) ----

    @Test
    fun `a request that offers neither remember nor always allow shows only allow once and decline`() = runTest {
        val f = fixture()
        f.ask(req("n", risk = ToolRisk.WRITE, rememberable = false, alwaysAllowOffered = false))
        runCurrent()
        assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.DENY), f.coordinator.pending.value.single().options.map { it.choice })
        f.coordinator.close()
    }

    @Test
    fun `a forged always allow or session answer to such a request is a decline and writes nothing`() = runTest {
        val f = fixture()
        for (forged in listOf(ConsentChoice.ALWAYS_ALLOW, ConsentChoice.ALLOW_FOR_SESSION)) {
            val d = f.ask(req("forged-$forged", rememberable = false, alwaysAllowOffered = false))
            runCurrent()
            f.coordinator.respond("forged-$forged", forged)
            assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.USER), d.await(), "$forged")
        }
        assertTrue(f.writer.writes.isEmpty(), "the policy was not written")
        val ok = f.ask(req("ok", rememberable = false, alwaysAllowOffered = false))
        runCurrent()
        f.coordinator.respond("ok", ConsentChoice.ALLOW_ONCE)
        assertEquals(ConsentDecision.Allow(), ok.await())
    }

    @Test
    fun `the choices do not depend on who is calling - a third-party app and AgentOS itself see the same options`() = runTest {
        val f = fixture()
        val desktop = CallerIdentity(2000, CallerKind.DESKTOP, "desktop")
        val self = CallerIdentity(10001, CallerKind.SELF, "AgentOS")
        val app = CallerIdentity(10123, CallerKind.APP, "com.example.app")
        for ((id, caller) in listOf("app" to app, "self" to self, "desktop" to desktop)) f.ask(req(id, risk = ToolRisk.WRITE).copy(caller = caller))
        runCurrent()
        val all = listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.ALLOW_FOR_SESSION, ConsentChoice.ALWAYS_ALLOW, ConsentChoice.DENY)
        for (v in f.coordinator.pending.value) assertEquals(all, v.options.map { it.choice }, v.requestId)
        f.coordinator.close()
    }

    @Test
    fun `a tool without a source has no always allow`() = runTest {
        val f = fixture()
        val d = f.ask(req("s", source = null))
        runCurrent()
        val v = f.coordinator.pending.value.single()
        assertNull(v.sourceLine)
        assertTrue(ConsentChoice.ALWAYS_ALLOW !in v.options.map { it.choice })
        f.coordinator.respond("s", ConsentChoice.ALWAYS_ALLOW)
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.USER), d.await())
        assertTrue(f.writer.writes.isEmpty())
    }

    @Test
    fun `no session remembering when the caller did not offer it`() = runTest {
        val f = fixture()
        f.ask(req("n", rememberable = false))
        runCurrent()
        assertTrue(ConsentChoice.ALLOW_FOR_SESSION !in f.coordinator.pending.value.single().options.map { it.choice })
        f.coordinator.close()
    }

    @Test
    fun `without a writable policy there is no always allow option`() = runTest {
        val writer = FakeWriter().also { it.available = false }
        val f = fixture(writer = writer)
        f.ask(req("x"))
        runCurrent()
        assertTrue(ConsentChoice.ALWAYS_ALLOW !in f.coordinator.pending.value.single().options.map { it.choice })
        f.coordinator.close()
    }

    // ------------------------------------------------------------------ 始终允许

    @Test
    fun `always allow writes the policy first and then allows`() = runTest {
        val f = fixture()
        val d = f.ask(req("r1"))
        runCurrent()
        assertTrue(f.coordinator.respond("r1", ConsentChoice.ALWAYS_ALLOW))
        assertEquals(ConsentDecision.Allow(), d.await())
        assertEquals(listOf(source to ToolRisk.WRITE), f.writer.writes)
        runCurrent()
        val resolution = f.surface.resolutions["r1"]!!
        assertEquals(ConsentChoice.ALWAYS_ALLOW, resolution.choice)
        assertNull(resolution.notice, "saved: nothing to tell the user")
    }

    @Test
    fun `the decision waits for the write to finish`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writer = FakeWriter().also { it.gate = gate }
        val f = fixture(writer = writer)
        val d = f.ask(req("r1"))
        runCurrent()
        f.coordinator.respond("r1", ConsentChoice.ALWAYS_ALLOW)
        runCurrent()
        assertFalse(d.isCompleted, "not allowed before the policy is saved")
        gate.complete(Unit)
        assertEquals(ConsentDecision.Allow(), d.await())
    }

    @Test
    fun `a failed always-allow write allows once and tells the user it was not saved`() = runTest {
        val writer = FakeWriter { ApprovalWriteResult.Failed("policy file corrupt: /data/secret/path") }
        val f = fixture(writer = writer)
        val d = f.ask(req("r1"))
        runCurrent()
        f.coordinator.respond("r1", ConsentChoice.ALWAYS_ALLOW)
        assertEquals(ConsentDecision.Allow(), d.await(), "the user said yes: this one goes through, without remembering")
        runCurrent()
        val resolution = f.surface.resolutions["r1"]!!
        val notice = resolution.notice
        assertTrue(notice != null && "没能保存" in notice && "下次还会询问" in notice, "the notice says it was not saved: $notice")
        assertFalse("/data/secret/path" in notice!!, "internal paths stay out of the user-facing notice")
    }

    @Test
    fun `a write that throws or hangs also falls back to allow once with a notice`() = runTest {
        val throwing = FakeWriter { error("ipc died") }
        val f1 = fixture(writer = throwing)
        val d1 = f1.ask(req("t"))
        runCurrent()
        f1.coordinator.respond("t", ConsentChoice.ALWAYS_ALLOW)
        assertEquals(ConsentDecision.Allow(), d1.await())
        runCurrent()
        assertTrue(f1.surface.resolutions["t"]!!.notice != null)

        val hanging = FakeWriter().also { it.gate = CompletableDeferred() }
        val f2 = fixture(ConsentConfig(writeTimeoutMillis = 2_000), hanging)
        val d2 = f2.ask(req("h"))
        runCurrent()
        f2.coordinator.respond("h", ConsentChoice.ALWAYS_ALLOW)
        runCurrent()
        assertFalse(d2.isCompleted)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(ConsentDecision.Allow(), d2.await())
        assertTrue(f2.surface.resolutions["h"]!!.notice!!.contains("没能保存"))
    }

    // ------------------------------------------------------------------ 界面回调

    @Test
    fun `surface callbacks arrive in order and a failing surface does not break the request`() = runTest {
        val f = fixture()
        f.surface.failNext = true
        val a = f.ask(req("a"))
        val b = f.ask(req("b"))
        runCurrent()
        assertEquals(listOf("a", "b"), f.ids(), "the surface blew up on a, the request is still pending")
        f.coordinator.respond("a", ConsentChoice.ALLOW_ONCE)
        f.coordinator.respond("b", ConsentChoice.DENY)
        a.await()
        b.await()
        runCurrent()
        assertEquals(listOf("requested:a", "requested:b", "resolved:a:ANSWERED", "resolved:b:ANSWERED"), f.surface.events)
    }

    @Test
    fun `a surface that answers inside its callback works`() = runTest {
        lateinit var coordinator: ConsentCoordinator
        val surface = object : ConsentSurface {
            override fun requested(view: ConsentView) {
                coordinator.respond(view.requestId, ConsentChoice.ALLOW_ONCE)
            }

            override fun resolved(requestId: String, resolution: ConsentResolution) = Unit
        }
        coordinator = ConsentCoordinator(surface, FakeWriter(), backgroundScope)
        val d = async { coordinator.request(req("r1")) }
        runCurrent()
        assertEquals(ConsentDecision.Allow(), d.await())
    }

    @Test
    fun `the surface callbacks never block the requester`() = runTest {
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val surface = object : ConsentSurface {
            override fun requested(view: ConsentView) {
                log += "requested:${view.requestId}"
            }

            override fun resolved(requestId: String, resolution: ConsentResolution) {
                log += "resolved:$requestId"
            }
        }
        val coordinator = ConsentCoordinator(surface, FakeWriter(), backgroundScope)
        val d = async { coordinator.request(req("r1", timeout = 1_000)) }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.TIMEOUT), d.await())
        assertEquals(listOf("requested:r1", "resolved:r1"), log)
    }

    // ------------------------------------------------------------------ 关闭

    @Test
    fun `close denies everything pending as unavailable and refuses new requests`() = runTest {
        val f = fixture()
        val a = f.ask(req("a"))
        val b = f.ask(req("b"))
        runCurrent()
        f.coordinator.close()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE), a.await())
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE), b.await())
        runCurrent()
        assertTrue(f.ids().isEmpty())
        assertEquals(ConsentEnd.CLOSED, f.surface.resolutions["a"]!!.end)
        val late = f.ask(req("c"))
        runCurrent()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE), late.await())
        assertFalse(f.coordinator.respond("a", ConsentChoice.ALLOW_ONCE))
    }

    @Test
    fun `views carry the cleaned display model`() = runTest {
        val f = fixture()
        f.ask(req("r1", title = "追加备忘"))
        runCurrent()
        val v = f.coordinator.pending.value.single()
        assertEquals("要允许「追加备忘」吗？", v.title)
        assertEquals("由 com.example.app 发起", v.initiatorLine)
        assertEquals(CallerKind.APP, v.caller.kind)
        assertEquals("com.example.app", v.caller.packageName)
        assertEquals("来自插件「com.example.notes」 · 服务器「main」", v.sourceLine)
        assertEquals("会修改数据", v.riskLabel)
        assertEquals(ConsentSeverity.ELEVATED, v.severity)
        assertIs<ConsentView>(v)
        f.coordinator.close()
    }

    @Test
    fun `a cancelled request is resolved exactly once`() = runTest {
        val f = fixture()
        val d = f.ask(req("r1"))
        runCurrent()
        d.cancel()
        runCurrent()
        assertTrue(f.ids().isEmpty())
        assertEquals(1, f.surface.events.count { it.startsWith("resolved") })
    }
}
