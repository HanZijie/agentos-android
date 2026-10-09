package org.agentos.sample.alarm

import android.app.Application
import android.content.Context
import org.agentos.sample.alarm.data.AlarmRepository
import org.agentos.sample.alarm.data.SqliteAlarmStore
import org.agentos.sample.alarm.ring.AlarmNotifications
import org.agentos.sample.alarm.ring.RingController
import org.agentos.sample.alarm.schedule.AndroidSystemAlarmInfo
import org.agentos.sample.alarm.schedule.SystemAlarmInfo
import org.agentos.sample.alarm.schedule.SystemAlarmScheduler
import org.agentos.sample.alarm.tools.AlarmTools

/**
 * 进程内的单例装配：界面、响铃服务、MCP 服务共用同一个 [repository]，
 * 所以 MCP 改了数据，前台界面立即刷新，并且系统闹钟同步重排。
 */
class AlarmGraph private constructor(context: Context) {
    val scheduler: SystemAlarmScheduler = SystemAlarmScheduler(context)
    val repository: AlarmRepository = AlarmRepository(
        store = SqliteAlarmStore(context),
        scheduler = scheduler,
    )
    val ring: RingController = RingController
    val systemAlarms: SystemAlarmInfo = AndroidSystemAlarmInfo(context)
    val tools: AlarmTools = AlarmTools(repository, ring, systemAlarms)

    companion object {
        @Volatile
        private var instance: AlarmGraph? = null

        fun get(context: Context): AlarmGraph =
            instance ?: synchronized(this) {
                instance ?: AlarmGraph(context.applicationContext).also { instance = it }
            }
    }
}

class AlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 只在主进程装配仓库：其他进程（debug 的 :selftest）各持一份缓存会互相覆盖数据库
        if (getProcessName() != packageName) return
        AlarmNotifications.ensureChannels(this)
        val repository = AlarmGraph.get(this).repository
        // 兜底：用户在设置里“强行停止”App 后，系统会清掉它登记的全部闹钟；下次进程启动（打开 App、AgentOS 经 MCP 唤起）时补登记。
        // 已登记的闹钟重复登记是幂等的（同一个 PendingIntent 会被替换）
        Thread { runCatching { repository.rescheduleAll(detectMissed = false) } }.start()
    }
}
