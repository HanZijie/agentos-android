package org.agentos.sample.calendar

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.agentos.sample.calendar.data.CalendarRepository
import org.agentos.sample.calendar.data.CalendarSettings
import org.agentos.sample.calendar.data.CalendarTexts
import org.agentos.sample.calendar.data.LocalBackend
import org.agentos.sample.calendar.data.SqliteCalendarStore
import org.agentos.sample.calendar.data.SystemBackend
import org.agentos.sample.calendar.data.TimeEnv
import org.agentos.sample.calendar.reminder.ReminderNotifications
import org.agentos.sample.calendar.reminder.ReminderScheduler
import org.agentos.sample.calendar.tools.CalendarTools

/** 系统生成的文字，每次现取：应用语言（`LocaleManager`）改了之后，下一次显示就是新语言（R3）。 */
private class AndroidTexts(private val context: Context) : CalendarTexts {
    override fun defaultCalendarName(): String = context.getString(R.string.default_calendar_name)
}

/** 用户选项存 SharedPreferences（calendar_settings）。 */
class PrefsCalendarSettings(context: Context) : CalendarSettings {
    private val prefs: SharedPreferences = context.getSharedPreferences("calendar_settings", Context.MODE_PRIVATE)

    override var defaultWriteCalendarId: String?
        get() = prefs.getString(KEY_DEFAULT_WRITE, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_DEFAULT_WRITE) else putString(KEY_DEFAULT_WRITE, value) }.apply()
        }

    override fun visibility(id: String): Boolean? = if (prefs.contains(KEY_VISIBLE + id)) prefs.getBoolean(KEY_VISIBLE + id, true) else null

    override fun setVisibility(id: String, visible: Boolean) {
        prefs.edit().putBoolean(KEY_VISIBLE + id, visible).apply()
    }

    private companion object {
        const val KEY_DEFAULT_WRITE = "default_write_calendar"
        const val KEY_VISIBLE = "visible:"
    }
}

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
        val time = TimeEnv.Default
        val created = CalendarRepository(
            local = LocalBackend(SqliteCalendarStore(app), time),
            system = SystemBackend(app, time),
            time = time,
            settings = PrefsCalendarSettings(app),
            texts = AndroidTexts(app),
        )
        repo = created
        val scheduler = ReminderScheduler(app, created)
        sched = scheduler
        // 本机日历的数据变了就重排提醒（界面、MCP、导入都走同一个仓库，所以这里一处就够）；去抖一下，批量写入只排一次
        scope.launch { created.localEvents.collectDebounced { scheduler.reschedule() } }
        return created
    }

    /** 已经建好的仓库（语言变化时通知用，不为此去创建）。 */
    @Synchronized
    fun existingRepository(): CalendarRepository? = repo

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
        // 只有主进程做提醒和数据：debug 的 :selftest 进程（MCP 自测客户端）不能再排一份闹钟
        if (Application.getProcessName() != packageName) return
        ReminderNotifications.ensureChannel(this)
        // 进程起来（开机后、被提醒唤起、被 MCP 唤起）就把提醒排好，并补发刚错过的
        Thread {
            runCatching { CalendarGraph.scheduler(this).reschedule(catchUp = true) }
        }.start()
    }

    /** 应用语言（或系统语言）变了：默认日历的名字按新语言重新生成（R3）。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CalendarGraph.existingRepository()?.refresh()
    }
}
