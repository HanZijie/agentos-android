package org.agentos.sample.notes

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    fun init(context: Context) {
        appContext = context.applicationContext
        // 进程一起来就读库并（只在第一次）放入示例备忘录；界面和 MCP 都不用等
        appScope.launch {
            repository.load()
            Seeds.seedIfFirstRun(appContext, repository)
        }
    }

    fun preferences() = appContext.getSharedPreferences("notes_prefs", Context.MODE_PRIVATE)
}
