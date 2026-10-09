package org.agentos.sample.todo.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.agentos.sample.todo.TodoGraph
import org.agentos.sample.todo.ui.theme.TodoTheme

class MainActivity : ComponentActivity() {
    private val viewModel: TodoViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TodoViewModel(TodoGraph.repository) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TodoTheme {
                TodoRoot(viewModel, onLanguage = ::openLanguageSettings)
            }
        }
    }

    /** 跳到系统设置里本 App 的“应用语言”（minSdk 35，平台自带，不引入 AppCompat）。 */
    private fun openLanguageSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (_: ActivityNotFoundException) {
            // 没有这个设置页的系统：什么都不做
        }
    }
}
