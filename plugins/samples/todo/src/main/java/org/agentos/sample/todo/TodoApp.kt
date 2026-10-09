package org.agentos.sample.todo

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.agentos.sample.todo.data.SqliteTodoStore
import org.agentos.sample.todo.data.TodoRepository
import org.agentos.sample.todo.tools.TodoTools

/**
 * 进程内单例：界面（MainActivity）和 MCP 服务（TodoMcpService）拿到的是同一个 [repository]，
 * 所以 MCP 改了数据，前台界面通过它的 StateFlow 立刻刷新。由 [TodoApp] 在进程启动时初始化。
 */
object TodoGraph {
    private lateinit var appContext: Context
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository: TodoRepository by lazy { TodoRepository(SqliteTodoStore(appContext)) }
    val tools: TodoTools by lazy { TodoTools(repository) }

    @Volatile private var initJob: Job? = null

    /** 进程启动时的读库完成之后返回（debug 的 dump 用，免得首次启动时读到一半）。 */
    suspend fun awaitInit() {
        initJob?.join()
        repository.load()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // 只有主进程持有数据；debug 的 :selftest 进程只是 MCP 客户端，不碰库
        if (Application.getProcessName() != appContext.packageName) return
        initJob = appScope.launch { repository.load() }
    }
}

class TodoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TodoGraph.init(this)
    }
}
