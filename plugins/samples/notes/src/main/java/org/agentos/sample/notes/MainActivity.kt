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
                NotesViewModel(NotesGraph.repository, NotesGraph.preferences()) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotesTheme {
                NotesApp(viewModel)
            }
        }
    }
}
