package org.agentos.app.agent

/**
 * :agent 运行时的前台与空闲状态机（监督契约 S2 a、b）。纯 Kotlin，不依赖 Android，副作用经 [Port] 交给
 * [AgentProcess]，所以能在电脑上用 JUnit 覆盖各种先后顺序。
 *
 * 任务数来自宿主层（`org.agentos.runtime.AgentRuntime.runState` 的 activeTasks + queuedTasks），经
 * [Port.taskCount] **每次现读**，不缓存。
 *
 * 规则：
 * 1. 有任务就要求前台：任务数从 0 变为正数时（[onTasksChanged]），如果服务不在前台、也没在启动中，经
 *    [Port.requestServiceStart] 调用 `startForegroundService`。系统不允许（后台、未豁免电池优化）时，这一段忙碌期内
 *    不再重试，心跳写成 tasks>0 fg=0，由 root 监督进程用 `REASON=promote` 代为启动。
 * 2. **空闲判断只在“恢复已结束”之后做，并且读的是当时的任务数。** 触发点：恢复结束（[onRecoveryFinished]）、
 *    每次服务命令处理完（[onServiceCommand]）、任务数变化（[onTasksChanged]）。恢复进行中不做空闲判断。
 *    这样避开 S2 整合时发现的缺陷：冷进程里开任务时，恢复流程结束的空闲判断先于任务登记，服务退出前台并销毁，
 *    心跳成了 tasks=1 fg=0。任务一旦登记（宿主层先登记、再等恢复），之后的任何空闲判断都能看到它。
 * 3. 空闲时停止服务用 `stopSelfResult(最近一次 startId)`：如果已经有新的启动请求在路上，停止会失败，服务继续，
 *    等新的命令到达后再判断。
 * 4. 服务被销毁时还有任务（系统停止了它，或与停止请求擦肩而过），重新要求前台。
 * 5. **空闲宽限期**（[idleGraceMillis]）：任务数归零后要持续这么久才停止服务。宿主层从收到 session/new 起就计入
 *    任务数，session/new 返回到客户端发来 prompt 之间有几毫秒是 0；没有宽限期的话每次对话都会退出前台再进入一次
 *    （后台时再次进入还可能被系统拒绝）。宽限期内心跳照常写 tasks=0 state=idle，监督进程按“没有任务”处理。
 * 6. **保持前台的其他理由**（[Port.holdForeground]，现读）：电脑端接入打开期间（architecture F11 第 4 点）即使没有任务
 *    也留在前台，否则空闲的 `:agent` 是 cached 进程，约 10 秒后被 cached-apps freezer 冻结，抽象 socket 上的连接得不到
 *    服务。规则 1～5 里的“有任务”都换成“有任务或有保持理由”：理由出现时要求前台（[onHoldChanged]），理由消失且没有
 *    任务时照常走宽限期再停止。心跳格式不变（tasks=0 fg=1 state=idle），wake lock 仍只在有任务时持有。
 *
 * 所有方法都在同一把锁里执行；[Port] 的方法会在锁内被调用，实现里不能反过来等待本类。
 */
class RuntimeLifecycle(private val port: Port, private val idleGraceMillis: Long = 0) {

    interface Port {
        /** 当前未结束的任务数（排队中 + 执行中），现读宿主层的状态。 */
        fun taskCount(): Int

        /** `startForegroundService(AgentService)`。返回 false：系统不允许（ForegroundServiceStartNotAllowedException 等）。 */
        fun requestServiceStart(): Boolean

        /** `stopSelfResult(startId)`，成功后退出前台。返回服务是否真的停了。 */
        fun stopService(startId: Int): Boolean

        /** 状态变化（tasks、fg、state 任何一个）：写心跳。 */
        fun publish(status: Status)

        /** 有任务 / 没任务的切换：持有或释放 wake lock，开始或停止心跳的定时刷新。 */
        fun onBusyChanged(busy: Boolean)

        /** 单调时钟（毫秒），宽限期计时用。 */
        fun uptimeMillis(): Long = 0L

        /** [delayMillis] 之后调用一次 [RuntimeLifecycle.onIdleCheck]（宽限期到点）。新的请求可以取代未到点的旧请求。 */
        fun scheduleIdleCheck(delayMillis: Long) = Unit

        /**
         * 没有任务时是否也要留在前台（规则 6：电脑端接入已打开）。每次判断都现读，不缓存；会在本类的锁里被调用，
         * 实现只能读内存状态，不能反过来等待本类。
         */
        fun holdForeground(): Boolean = false
    }

    enum class Phase { STARTING, RECOVERING, READY }

    /** 心跳里的三个字段。state 取值：starting | recovering | idle | busy。 */
    data class Status(val tasks: Int, val foreground: Boolean, val state: String)

    private var phase = Phase.STARTING
    private var serviceRunning = false
    private var foreground = false
    private var startRequested = false
    private var deniedThisBusyPeriod = false
    private var lastStartId = 0
    private var busy = false
    private var lastPublished: Status? = null

    /** 任务数最近一次归零（或恢复结束时就是 0）的时刻；有任务时为 null。 */
    private var idleSince: Long? = null

    /** 最近一次要求前台被系统拒绝（交给 root 提升）。诊断用。 */
    var foregroundDenied = false
        private set
    var foregroundDeniedCount = 0
        private set
    var idleStops = 0
        private set

    @Synchronized fun phase(): Phase = phase

    @Synchronized fun status(): Status = currentStatus(port.taskCount())

    @Synchronized fun isServiceRunning(): Boolean = serviceRunning

    /** 当前是否有保持前台的其他理由（诊断用）。 */
    @Synchronized fun holdingForeground(): Boolean = port.holdForeground()

    /** 宿主层的任务数变了（runState 的收集者调用；可能晚于实际变化，所以本类总是现读 [Port.taskCount]）。 */
    @Synchronized
    fun onTasksChanged() {
        val n = port.taskCount()
        if (n > 0) ensureForeground(n) else deniedThisBusyPeriod = false
        publish(n)
        maybeStop(n)
    }

    /**
     * 保持前台的理由变了（[Port.holdForeground]，例如电脑端接入的开关）。出现：要求前台（之前被系统拒绝过也再试一次，
     * 这是一次新的请求，通常来自用户在设置页的操作）；消失：没有任务就走宽限期再停止。
     */
    @Synchronized
    fun onHoldChanged() {
        val n = port.taskCount()
        if (port.holdForeground()) {
            deniedThisBusyPeriod = false
            ensureForeground(n)
        }
        publish(n)
        maybeStop(n)
    }

    @Synchronized
    fun onRecoveryStarted() {
        if (phase == Phase.STARTING) phase = Phase.RECOVERING
        publish(port.taskCount())
    }

    /** 恢复流程结束（宿主层的 `start()` 返回）。 */
    @Synchronized
    fun onRecoveryFinished() {
        phase = Phase.READY
        val n = port.taskCount()
        ensureForeground(n)
        publish(n)
        maybeStop(n)
    }

    /**
     * AgentService.onStartCommand 里、`startForeground` 之后调用。[foregroundOk] 为 `startForeground` 是否成功。
     * 空闲判断在此之后做，读的是当时的任务数。
     */
    @Synchronized
    fun onServiceCommand(startId: Int, foregroundOk: Boolean) {
        serviceRunning = true
        startRequested = false
        lastStartId = startId
        foreground = foregroundOk
        val n = port.taskCount()
        publish(n)
        maybeStop(n)
    }

    /** AgentService.onDestroy。 */
    @Synchronized
    fun onServiceDestroyed() {
        serviceRunning = false
        foreground = false
        startRequested = false
        deniedThisBusyPeriod = false
        val n = port.taskCount()
        ensureForeground(n)
        publish(n)
    }

    /** 有任务期间每 30 秒刷新一次心跳的 at 字段。 */
    @Synchronized
    fun refresh() {
        val n = port.taskCount()
        if (n > 0) port.publish(currentStatus(n))
    }

    /** 空闲宽限期到点（[Port.scheduleIdleCheck] 的回调）。 */
    @Synchronized
    fun onIdleCheck() {
        val n = port.taskCount()
        publish(n)
        maybeStop(n)
    }

    /** 有任务，或有保持前台的其他理由（规则 6）。 */
    private fun wantsForeground(n: Int): Boolean = n > 0 || port.holdForeground()

    private fun ensureForeground(n: Int) {
        if (!wantsForeground(n) || foreground || startRequested || deniedThisBusyPeriod) return
        if (port.requestServiceStart()) {
            startRequested = true
            foregroundDenied = false
        } else {
            deniedThisBusyPeriod = true
            foregroundDenied = true
            foregroundDeniedCount++
        }
    }

    private fun maybeStop(n: Int) {
        if (wantsForeground(n)) {
            idleSince = null
            return
        }
        if (phase != Phase.READY || !serviceRunning || startRequested) return
        val now = port.uptimeMillis()
        val since = idleSince ?: now.also { idleSince = it }
        val remaining = idleGraceMillis - (now - since)
        if (remaining > 0) {
            port.scheduleIdleCheck(remaining)
            return
        }
        if (port.stopService(lastStartId)) {
            serviceRunning = false
            foreground = false
            idleStops++
            publish(n)
        }
    }

    private fun currentStatus(n: Int): Status {
        val state = when (phase) {
            Phase.STARTING -> "starting"
            Phase.RECOVERING -> "recovering"
            Phase.READY -> if (n == 0) "idle" else "busy"
        }
        return Status(n, foreground, state)
    }

    private fun publish(n: Int) {
        val s = currentStatus(n)
        val nowBusy = n > 0
        if (nowBusy != busy) {
            busy = nowBusy
            port.onBusyChanged(nowBusy)
        }
        if (s != lastPublished) {
            lastPublished = s
            port.publish(s)
        }
    }
}

/**
 * 上一个 `:agent` 进程是不是用户主动停止的（监督契约 S2 a 第 6 条）。是的话，恢复出来的任务不继续。
 *
 * 契约只要求 `REASON=restart` 时检查；这里不论由谁拉起都检查（bind 拉起的冷进程同样不该继续用户停掉的任务），
 * 比契约更严。结论经 `HostPort.environment.previousExitStoppedByUser`（A3）交给宿主层：恢复流程取消排队的任务，
 * 结果未知的照常暂停。
 */
object RecoveryPolicy {
    /** ApplicationExitInfo.REASON_USER_REQUESTED / REASON_USER_STOPPED 的取值（API 30 起固定）。 */
    const val REASON_USER_REQUESTED = 10
    const val REASON_USER_STOPPED = 11

    fun userStopped(lastExitReason: Int?): Boolean =
        lastExitReason == REASON_USER_REQUESTED || lastExitReason == REASON_USER_STOPPED
}
