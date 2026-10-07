package org.agentos.sample.calendar

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.agentos.sample.calendar.data.CalendarRepository
import org.agentos.sample.calendar.data.SqliteCalendarStore
import org.agentos.sample.calendar.reminder.ReminderNotifications
import org.agentos.sample.calendar.reminder.ReminderScheduler
import org.agentos.sample.calendar.tools.CalendarTools

/**
 * 进程内的单例：界面、提醒、MCP 服务共用同一个仓库（所以 AgentOS 经 MCP 改了数据，前台界面立即刷新）。
 * 任何入口（Activity、广播接收器、导出的 Service）第一次用到时在这里创建，之后都是同一个对象。
 */
@SuppressLint("StaticFieldLeak") // 只持有 applicationContext
object CalendarGraph {
    private var repo: CalendarRepository? = null
    private var sched: ReminderScheduler? = null
    private var toolSet: CalendarTools? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    fun repository(context: Context): CalendarRepository {
        repo?.let { return it }
        val app = context.applicationContext
        val created = CalendarRepository(
            store = SqliteCalendarStore(app),
            defaultCalendarName = app.getString(R.string.default_calendar_name),
        )
        repo = created
        val scheduler = ReminderScheduler(app, created)
        sched = scheduler
        // 数据变了就重排提醒（界面、MCP、导入都走同一个仓库，所以这里一处就够）；去抖一下，批量写入只排一次
        scope.launch { created.events.collectDebounced { scheduler.reschedule() } }
        return created
    }

    fun scheduler(context: Context): ReminderScheduler {
        repository(context)
        return synchronized(this) { sched!! }
    }

    @Synchronized
    fun tools(context: Context): CalendarTools {
        toolSet?.let { return it }
        return CalendarTools(repository(context)).also { toolSet = it }
    }

    @OptIn(FlowPreview::class)
    private suspend fun kotlinx.coroutines.flow.Flow<List<org.agentos.sample.calendar.data.EventSeries>>.collectDebounced(block: () -> Unit) {
        debounce(250).collect { block() }
    }
}

class CalendarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ReminderNotifications.ensureChannel(this)
        // 进程起来（开机后、被提醒唤起、被 MCP 唤起）就把提醒排好，并补发刚错过的
        Thread {
            runCatching { CalendarGraph.scheduler(this).reschedule(catchUp = true) }
        }.start()
    }
}
