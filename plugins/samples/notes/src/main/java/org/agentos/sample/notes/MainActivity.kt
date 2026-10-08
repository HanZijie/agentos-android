package org.agentos.sample.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.agentos.sample.notes.ui.NotesApp
import org.agentos.sample.notes.ui.NotesViewModel
import org.agentos.sample.notes.ui.theme.NotesTheme

class MainActivity : ComponentActivity() {
    private val viewModel: NotesViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NotesViewModel(NotesGraph.repository, NotesGraph.preferences(), NotesGraph.agentSchedule) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 上一轮“让 AgentOS 安排”还没走完进程就被回收了：面板会说明（同一进程里重复调用无效）
        NotesGraph.agentSchedule.restoreInterrupted()
        setContent {
            NotesTheme {
                NotesApp(viewModel)
            }
        }
    }
}
