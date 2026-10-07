package org.agentos.sample.calendar.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.agentos.sample.calendar.CalendarGraph
import org.agentos.sample.calendar.data.CalendarException
import org.agentos.sample.calendar.data.CalendarRepository
import java.time.LocalDate

sealed interface Route {
    /** 事件详情；id 是 series id 或 `series@key`。 */
    data class Detail(val occurrenceId: String) : Route

    /** 新建（eventId = null，可带预填的开始时刻 / 全天）或编辑。 */
    data class Editor(val eventId: String?, val prefillStartMs: Long? = null, val prefillAllDay: Boolean = false) : Route
    data object Calendars : Route
    data object Search : Route
}

enum class HomeTab { Month, Week, Agenda }

class CalendarViewModel(app: Application) : AndroidViewModel(app) {
    val repo: CalendarRepository = CalendarGraph.repository(app)

    var tab by mutableStateOf(HomeTab.Month)
    var selectedDate by mutableStateOf(LocalDate.now(repo.zone))
    val stack = mutableStateListOf<Route>()

    fun push(route: Route) {
        stack.add(route)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    /** 在后台线程跑仓库操作；失败时把一句话原因交给 [onError]（在主线程）。 */
    fun <T> io(onError: (String) -> Unit = {}, onDone: (T) -> Unit = {}, block: CalendarRepository.() -> T) {
        viewModelScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) { repo.block() }
                onDone(result)
            } catch (e: CalendarException) {
                onError(e.message ?: "")
            }
        }
    }
}
