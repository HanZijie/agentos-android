package org.agentos.sample.calendar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import org.agentos.sample.calendar.reminder.ReminderNotifications
import org.agentos.sample.calendar.ui.CalendarRoot
import org.agentos.sample.calendar.ui.CalendarViewModel
import org.agentos.sample.calendar.ui.theme.CalendarTheme

class MainActivity : ComponentActivity() {
    /** 通知点进来时要打开的日程 id；处理完置空。 */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openRequest.value = intent.getStringExtra(ReminderNotifications.EXTRA_EVENT_ID)
        setContent {
            CalendarTheme {
                val vm: CalendarViewModel = viewModel()
                CalendarRoot(vm, openRequest.value) { openRequest.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequest.value = intent.getStringExtra(ReminderNotifications.EXTRA_EVENT_ID)
    }
}
