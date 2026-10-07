package org.agentos.sample.alarm

import android.app.Application
import android.content.Context
import org.agentos.sample.alarm.data.AlarmRepository
import org.agentos.sample.alarm.data.SqliteAlarmStore
import org.agentos.sample.alarm.ring.AlarmNotifications
import org.agentos.sample.alarm.ring.RingController
import org.agentos.sample.alarm.schedule.SystemAlarmScheduler
import org.agentos.sample.alarm.tools.AlarmTools

/**
 * 进程内的单例装配：界面、响铃服务、MCP 服务共用同一个 [repository]，
 * 所以 MCP 改了数据，前台界面立即刷新，并且系统闹钟同步重排。
 */
class AlarmGraph private constructor(context: Context) {
    val repository: AlarmRepository = AlarmRepository(
        store = SqliteAlarmStore(context),
        scheduler = SystemAlarmScheduler(context),
    )
    val ring: RingController = RingController
    val tools: AlarmTools = AlarmTools(repository, ring)

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
        AlarmNotifications.ensureChannels(this)
        AlarmGraph.get(this)
    }
}
