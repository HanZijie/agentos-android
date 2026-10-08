package org.agentos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RuntimeLifecycle 的先后顺序。假 Port 模拟 Android：requestServiceStart 之后，测试自己决定 onStartCommand
 * （onServiceCommand）什么时候到达；stopService 模拟 stopSelfResult。
 */
class RuntimeLifecycleTest {

    private class FakePort : RuntimeLifecycle.Port {
        var tasks = 0
        var allowStart = true
        var stopResult = true
        val starts = mutableListOf<Int>()          // requestServiceStart 被调用时的任务数
        val stops = mutableListOf<Int>()           // stopService 的 startId
        val published = mutableListOf<RuntimeLifecycle.Status>()
        val busy = mutableListOf<Boolean>()

        override fun taskCount() = tasks
        override fun requestServiceStart(): Boolean {
            starts += tasks
            return allowStart
        }
        override fun stopService(startId: Int): Boolean {
            stops += startId
            return stopResult
        }
        override fun publish(status: RuntimeLifecycle.Status) {
            published += status
        }
        override fun onBusyChanged(busy: Boolean) {
            this.busy += busy
        }

        val last get() = published.last()
    }

    private fun status(tasks: Int, fg: Boolean, state: String) = RuntimeLifecycle.Status(tasks, fg, state)

    /** 发生过“有任务、恢复已结束、服务却不在前台且没有在启动”的心跳吗（S2 缺陷的症状）。 */
    private fun lostForeground(p: FakePort, afterIndex: Int) =
        p.published.drop(afterIndex).any { it.tasks > 0 && it.state == "busy" && !it.foreground }

    /**
     * S2 整合时发现的缺陷的先后顺序：冷进程，客户端的 prompt 在恢复结束之前就登记了任务，服务在恢复期间收到命令；
     * 恢复结束时的空闲判断必须看到这个任务，服务留在前台。
     */
    @Test
    fun coldProcess_taskRegisteredDuringRecovery_staysForeground() {
        val p = FakePort()
        val lc = RuntimeLifecycle(p)
        lc.onTasksChanged()                 // runState 收集者的第一次回调（0 个任务）
        lc.onRecoveryStarted()
        assertEquals(status(0, false, "recovering"), p.last)

        p.tasks = 1                         // 宿主层先登记任务，再等恢复
        lc.onTasksChanged()
        assertEquals(listOf(1), p.starts)   // 要求前台
        assertEquals(status(1, false, "recovering"), p.last)

        lc.onServiceCommand(startId = 1, foregroundOk = true)
        val afterCommand = p.published.size - 1
        assertTrue(p.stops.isEmpty())       // 恢复期间不做空闲判断
        assertEquals(status(1, true, "recovering"), p.last)

        lc.onRecoveryFinished()
        assertTrue("recovery end must not stop the service while a task is registered", p.stops.isEmpty())
        assertEquals(status(1, true, "busy"), p.last)
        assertTrue(lc.isServiceRunning())
        assertFalse("heartbeat must never show tasks=1 fg=0 once the service is up", lostForeground(p, afterCommand))

        p.tasks = 0
        lc.onTasksChanged()
        assertEquals(listOf(1), p.stops)
        assertEquals(status(0, false, "idle"), p.last)
        assertEquals(1, lc.idleStops)
    }

    /**
     * 收集者的通知晚于登记：任务已经登记（runState 已变），但 onTasksChanged 还没到，恢复先结束了。
     * 空闲判断现读任务数，所以不会停。
     */
    @Test
    fun recoveryFinishesBeforeTaskNotification_readsLiveCount() {
        val p = FakePort()
        val lc = RuntimeLifecycle(p)
        lc.onRecoveryStarted()
        // 监督进程先拉起了服务（REASON=boot）
        lc.onServiceCommand(startId = 1, foregroundOk = true)
        p.tasks = 1                         // 登记了，但通知还在路上
        lc.onRecoveryFinished()
        assertTrue(p.stops.isEmpty())
        assertEquals(status(1, true, "busy"), p.last)
        lc.onTasksChanged()                 // 迟到的通知
        assertTrue(p.stops.isEmpty())
        assertTrue(p.starts.isEmpty())      // 已在前台，不用再要
    }

    /** 缺陷原样（旧实现）：命令先触发恢复并做空闲判断、之后才登记任务。新实现里命令不带任务，空闲判断只看现读的数。 */
    @Test
    fun supervisorBootWithoutTasks_stopsAfterRecoveryOnly() {
        val p = FakePort()
        val lc = RuntimeLifecycle(p)
        lc.onRecoveryStarted()
        lc.onServiceCommand(startId = 1, foregroundOk = true)
        assertTrue("no idle stop while recovering", p.stops.isEmpty())
        assertEquals(status(0, true, "recovering"), p.last)
        lc.onRecoveryFinished()
        assertEquals(listOf(1), p.stops)
        assertEquals(status(0, false, "idle"), p.last)
    }

    @Test
    fun taskArrivesAfterIdleStop_requestsForegroundAgain() {
        val p = FakePort()
        val lc = RuntimeLifecycle(p)
        lc.onRecoveryStarted()
        lc.onServiceCommand(1, true)
        lc.onRecoveryFinished()
        assertEquals(listOf(1), p.stops)
        p.tasks = 1
        lc.onTasksChanged()
        assertEquals(listOf(1), p.starts)
        lc.onServiceCommand(2, true)
        assertEquals(status(1, true, "busy"), p.last)
        assertEquals(listOf(1), p.stops)
    }

    /** stopSelfResult 失败（已有更新的启动请求在路上）：服务继续，等新命令到达后再判断。 */
    @Test
    fun stopRejectedWhenNewerStartPending() {
        val p = FakePort()
        val lc = RuntimeLifecycle(p)
        lc.onRecoveryStarted()
        lc.onRecoveryFinished()
        p.tasks = 1
        lc.onTasksChanged()
        lc.onServiceCommand(1, true)
        p.tasks = 0
        p.stopResult = false
        lc.onTasksChanged()
        assertEquals(listOf(1), p.stops)
        assertTrue(lc.isServiceRunning())
        assertEquals(0, lc.idleStops)
        p.stopResult = true
        lc.onServiceCommand(2, true)       // 新的命令到达，再判断一次
        assertEquals(listOf(1, 2), p.stops)
        assertFalse(lc.isServiceRunning())
        assertEquals(1, lc.idleStops)
    }

    /** 系统不允许后台启动前台服务：心跳 tasks>0 fg=0 交给监督进程，同一段忙碌期内不再重试。 */
    @Test
    fun foregroundDenied_noRetryWithinBusyPeriod() {
        val p = FakePort()
        p.allowStart = false
        val lc = RuntimeLifecycle(p)
        lc.onRecoveryStarted()
        lc.onRecoveryFinished()
        p.tasks = 1
        lc.onTasksChanged()
        p.tasks = 2
        lc.onTasksChanged()
        assertEquals("only one attempt per busy period", listOf(1), p.starts)
        assertTrue(lc.foregroundDenied)
        assertEquals(status(2, false, "busy"), p.last)
        // 监督进程 promote：root 发起的启动到达
        lc.onServiceCommand(1, true)
        assertEquals(status(2, true, "busy"), p.last)
        // 忙碌期结束后，下一段忙碌期可以再试
        p.tasks = 0
        lc.onTasksChanged()
        p.allowStart = true
        p.tasks = 1
        lc.onTasksChanged()
        assertEquals(listOf(1, 1), p.starts)
    }

    /** 服务被销毁时还有任务：重新要求前台。 */
    @Test
    fun serviceDestroyedWithTasks_requestsAgain() {
        val p = FakePort()
        val lc = RuntimeLifecycle(p)
        lc.onRecoveryStarted()
        lc.onRecoveryFinished()
        p.tasks = 1
        lc.onTasksChanged()
        lc.onServiceCommand(1, true)
        lc.onServiceDestroyed()
        assertEquals(listOf(1, 1), p.starts)
        assertEquals(status(1, false, "busy"), p.last)
    }

    /** 只在状态变化时写心跳；忙闲切换各通知一次。 */
    @Test
    fun publishesOnlyOnChange_andBusyTransitions() {
        val p = FakePort()
        val lc = RuntimeLifecycle(p)
        lc.onTasksChanged()
        lc.onTasksChanged()
        assertEquals(listOf(status(0, false, "starting")), p.published)
        lc.onRecoveryStarted()
        lc.onRecoveryFinished()
        p.tasks = 1; lc.onTasksChanged()
        p.tasks = 3; lc.onTasksChanged()
        lc.onServiceCommand(1, true)
        p.tasks = 0; lc.onTasksChanged()
        assertEquals(listOf(true, false), p.busy)
        assertEquals(
            listOf(
                status(0, false, "starting"), status(0, false, "recovering"), status(0, false, "idle"),
                status(1, false, "busy"), status(3, false, "busy"), status(3, true, "busy"), status(0, true, "idle"),
                status(0, false, "idle"),
            ),
            p.published,
        )
    }

    /** 任意交错下的不变量：恢复结束后，只要有任务、服务已收到命令且没被停，心跳就不会是 fg=0。 */
    @Test
    fun randomInterleavings_neverLoseForegroundWhileBusy() {
        val rnd = java.util.Random(42)
        repeat(2_000) {
            val p = FakePort()
            val lc = RuntimeLifecycle(p)
            var pendingStart = false
            var startId = 0
            var recovering = false
            var recovered = false
            repeat(40) {
                when (rnd.nextInt(6)) {
                    0 -> { p.tasks++; val before = p.starts.size; lc.onTasksChanged(); if (p.starts.size > before) pendingStart = true }
                    1 -> if (p.tasks > 0) { p.tasks--; lc.onTasksChanged() }
                    2 -> if (!recovering && !recovered) { recovering = true; lc.onRecoveryStarted() }
                    3 -> if (recovering) { recovering = false; recovered = true; lc.onRecoveryFinished() }
                    4 -> if (pendingStart) { pendingStart = false; lc.onServiceCommand(++startId, true) }
                    5 -> lc.onTasksChanged()
                }
                val s = lc.status()
                if (recovered && s.tasks > 0 && lc.isServiceRunning()) {
                    assertTrue("busy but not foreground: ${p.published}", s.foreground)
                }
                if (s.tasks > 0) assertTrue(p.stops.size <= startId)
            }
            // 恢复结束、任务清零、服务命令都处理完后，服务一定会被停
            if (!recovered) {
                if (!recovering) lc.onRecoveryStarted()
                lc.onRecoveryFinished()
            }
            if (pendingStart) lc.onServiceCommand(++startId, true)
            p.tasks = 0
            lc.onTasksChanged()
            assertFalse(lc.isServiceRunning())
            assertEquals("idle", lc.status().state)
        }
    }

    @Test
    fun recoveryPolicy_userStop() {
        assertTrue(RecoveryPolicy.userStopped(RecoveryPolicy.REASON_USER_REQUESTED))
        assertTrue(RecoveryPolicy.userStopped(RecoveryPolicy.REASON_USER_STOPPED))
        assertFalse(RecoveryPolicy.userStopped(2)) // REASON_SIGNALED
        assertFalse(RecoveryPolicy.userStopped(null))
    }

    // ------------------------------------------------------------------ 空闲宽限期（规则 5）

    private class ClockPort : RuntimeLifecycle.Port {
        var tasks = 0
        var now = 1_000L
        var hold = false
        var allowStart = true
        val starts = mutableListOf<Int>()
        val stops = mutableListOf<Int>()
        val scheduled = mutableListOf<Long>()
        val published = mutableListOf<RuntimeLifecycle.Status>()

        override fun taskCount() = tasks
        override fun holdForeground() = hold
        override fun requestServiceStart(): Boolean {
            starts += tasks
            return allowStart
        }
        override fun stopService(startId: Int): Boolean {
            stops += startId
            return true
        }
        override fun publish(status: RuntimeLifecycle.Status) {
            published += status
        }
        override fun onBusyChanged(busy: Boolean) = Unit
        override fun uptimeMillis() = now
        override fun scheduleIdleCheck(delayMillis: Long) {
            scheduled += delayMillis
        }
    }

    /** 已恢复、服务在前台、有一个任务（session/new 计入）。 */
    private fun busyLifecycle(p: ClockPort, grace: Long): RuntimeLifecycle {
        val lc = RuntimeLifecycle(p, idleGraceMillis = grace)
        lc.onRecoveryStarted()
        p.tasks = 1
        lc.onTasksChanged()
        lc.onServiceCommand(startId = 1, foregroundOk = true)
        lc.onRecoveryFinished()
        return lc
    }

    /** session/new 返回到 prompt 到达之间任务数短暂为 0：宽限期内不退出前台，也不重新要求前台。 */
    @Test
    fun idleGrace_bridgesTheGapBetweenSessionNewAndPrompt() {
        val p = ClockPort()
        val lc = busyLifecycle(p, grace = 2_000)
        p.tasks = 0                           // session/new 返回
        lc.onTasksChanged()
        assertTrue(p.stops.isEmpty())
        assertEquals(listOf(2_000L), p.scheduled)
        assertEquals(RuntimeLifecycle.Status(0, true, "idle"), p.published.last())

        p.now += 5
        p.tasks = 1                           // prompt 到达
        lc.onTasksChanged()
        assertEquals("only the first start request", listOf(1), p.starts)
        assertEquals(RuntimeLifecycle.Status(1, true, "busy"), p.published.last())

        p.now += 2_000
        lc.onIdleCheck()                      // 旧的到点回调：有任务，不停
        assertTrue(p.stops.isEmpty())

        p.tasks = 0                           // 任务结束
        lc.onTasksChanged()
        assertEquals(2_000L, p.scheduled.last())   // 宽限期从这次归零重新算
        p.now += 1_999
        lc.onIdleCheck()
        assertTrue(p.stops.isEmpty())
        assertEquals(1L, p.scheduled.last())
        p.now += 1
        lc.onIdleCheck()
        assertEquals(listOf(1), p.stops)
        assertEquals(RuntimeLifecycle.Status(0, false, "idle"), p.published.last())
        assertEquals(1, lc.idleStops)
    }

    /** 监督进程拉起、没有任务：恢复结束后等过宽限期再停。 */
    @Test
    fun idleGrace_appliesAfterRecoveryWithoutTasks() {
        val p = ClockPort()
        val lc = RuntimeLifecycle(p, idleGraceMillis = 2_000)
        lc.onRecoveryStarted()
        lc.onServiceCommand(startId = 3, foregroundOk = true)
        assertTrue(p.stops.isEmpty())
        lc.onRecoveryFinished()
        assertTrue(p.stops.isEmpty())
        assertEquals(listOf(2_000L), p.scheduled)
        p.now += 2_000
        lc.onIdleCheck()
        assertEquals(listOf(3), p.stops)
    }

    // ------------------------------------------------------------------ 保持前台的其他理由（规则 6：电脑端接入）

    private fun recoveredIdle(p: ClockPort): RuntimeLifecycle {
        val lc = RuntimeLifecycle(p, idleGraceMillis = 2_000)
        lc.onRecoveryStarted()
        lc.onRecoveryFinished()
        return lc
    }

    /** 开关打开：没有任务也进前台、一直留着（任务来去都不停）；关闭后照常走宽限期再停。 */
    @Test
    fun hold_keepsForegroundWhileIdle_andReleasesAfterGrace() {
        val p = ClockPort()
        val lc = recoveredIdle(p)
        assertTrue(p.starts.isEmpty())

        p.hold = true
        lc.onHoldChanged()
        assertEquals("start requested with no task", listOf(0), p.starts)
        lc.onServiceCommand(startId = 1, foregroundOk = true)
        assertEquals(RuntimeLifecycle.Status(0, true, "idle"), p.published.last())
        assertTrue(lc.holdingForeground())

        p.now += 60_000
        lc.onIdleCheck()
        assertTrue("idle for a minute with desktop access on: still foreground", p.stops.isEmpty())

        p.tasks = 1
        lc.onTasksChanged()
        p.tasks = 0
        lc.onTasksChanged()
        p.now += 5_000
        lc.onIdleCheck()
        assertTrue(p.stops.isEmpty())
        assertEquals("already foreground: no second start request", listOf(0), p.starts)

        p.hold = false
        lc.onHoldChanged()
        assertTrue("grace period first", p.stops.isEmpty())
        assertEquals(2_000L, p.scheduled.last())
        p.now += 2_000
        lc.onIdleCheck()
        assertEquals(listOf(1), p.stops)
        assertEquals(RuntimeLifecycle.Status(0, false, "idle"), p.published.last())
    }

    /** 进程带着“已打开”的开关重新启动：bind 拉起的冷进程在恢复结束时要求前台；监督进程拉起的（服务命令先到）不停。 */
    @Test
    fun hold_atStartup_appliesWhenRecoveryEnds() {
        val cold = ClockPort().apply { hold = true }
        val lc = RuntimeLifecycle(cold, idleGraceMillis = 2_000)
        lc.onTasksChanged()
        lc.onRecoveryStarted()
        assertTrue("no idle decisions before recovery ends", cold.starts.isEmpty())
        lc.onRecoveryFinished()
        assertEquals(listOf(0), cold.starts)
        lc.onServiceCommand(startId = 1, foregroundOk = true)
        cold.now += 10_000
        lc.onIdleCheck()
        assertTrue(cold.stops.isEmpty())

        val boot = ClockPort().apply { hold = true }
        val lc2 = RuntimeLifecycle(boot, idleGraceMillis = 2_000)
        lc2.onRecoveryStarted()
        lc2.onServiceCommand(startId = 3, foregroundOk = true)   // REASON=boot
        lc2.onRecoveryFinished()
        assertTrue(boot.starts.isEmpty())
        assertTrue("no grace timer: nothing to stop", boot.scheduled.isEmpty())
        boot.now += 10_000
        lc2.onIdleCheck()
        assertTrue(boot.stops.isEmpty())
        assertEquals(RuntimeLifecycle.Status(0, true, "idle"), boot.published.last())
    }

    /** 系统不允许进前台：不在每次判断时重试；用户再次打开开关时重试。 */
    @Test
    fun hold_startDenied_retriesOnlyOnNextToggle() {
        val p = ClockPort().apply { allowStart = false }
        val lc = recoveredIdle(p)
        p.hold = true
        lc.onHoldChanged()
        assertEquals(listOf(0), p.starts)
        assertTrue(lc.foregroundDenied)
        lc.onTasksChanged()
        lc.onIdleCheck()
        assertEquals("no retry loop", listOf(0), p.starts)

        p.hold = false
        lc.onHoldChanged()
        p.hold = true
        p.allowStart = true
        lc.onHoldChanged()
        assertEquals(listOf(0, 0), p.starts)
        assertFalse(lc.foregroundDenied)
    }

    /** 开关打开期间服务被系统销毁：重新要求前台。 */
    @Test
    fun hold_serviceDestroyed_requestsForegroundAgain() {
        val p = ClockPort()
        val lc = recoveredIdle(p)
        p.hold = true
        lc.onHoldChanged()
        lc.onServiceCommand(startId = 1, foregroundOk = true)
        lc.onServiceDestroyed()
        assertEquals(listOf(0, 0), p.starts)
    }
}
