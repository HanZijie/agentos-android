package org.agentos.sample.notes

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.agentos.sample.notes.agentos.AgentScheduleUseCase
import org.agentos.sample.notes.agentos.GatewayProvider
import org.agentos.sample.notes.agentos.PrefsRunMarker
import org.agentos.sample.notes.data.NoteRepository
import org.agentos.sample.notes.data.SqliteNoteStore
import org.agentos.sample.notes.tools.NotesTools

/**
 * 进程内单例：界面（MainActivity）和 MCP 服务（NotesMcpService）拿到的是同一个 [repository]，
 * 所以 MCP 改了数据，前台界面通过它的 StateFlow 立刻刷新。由 [NotesApplication] 在进程启动时初始化。
 */
object NotesGraph {
    private lateinit var appContext: Context
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository: NoteRepository by lazy { NoteRepository(SqliteNoteStore(appContext)) }
    val tools: NotesTools by lazy { NotesTools(repository) }

    /**
     * “让 AgentOS 安排”的用例，进程内单例：面板按钮和 debug 的 ask_agent 用的是同一个，所以旋转屏幕、退出再进都不影响进行中的一轮。
     * 进程被回收后上一轮丢了的处理见 [AgentScheduleUseCase.restoreInterrupted]（MainActivity 重建时调）。
     */
    val agentSchedule: AgentScheduleUseCase by lazy {
        AgentScheduleUseCase(
            scope = appScope,
            gatewayFactory = { GatewayProvider.create(appContext) },
            marker = PrefsRunMarker(appContext.getSharedPreferences("notes_agentos", Context.MODE_PRIVATE)),
        )
    }

    @Volatile private var initJob: Job? = null

    /** 进程启动时的读库 + 首次示例数据写完之后返回（debug 的 dump 用，免得首次启动时读到一半）。 */
    suspend fun awaitInit() {
        initJob?.join()
        repository.load()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // 只有主进程持有数据；debug 的 :selftest 进程只是 MCP 客户端，不碰库、不放示例
        if (Application.getProcessName() != appContext.packageName) return
        // 进程一起来就读库并（只在第一次）放入示例备忘录；界面和 MCP 都不用等
        initJob = appScope.launch {
            repository.load()
            Seeds.seedIfFirstRun(appContext, repository)
        }
    }

    fun preferences() = appContext.getSharedPreferences("notes_prefs", Context.MODE_PRIVATE)
}
