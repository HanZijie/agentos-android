package org.agentos.sample.calendar.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.sample.calendar.CalendarGraph
import org.agentos.sample.calendar.data.CalendarException
import org.agentos.sample.calendar.data.CalendarRepository
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.Occurrence
import java.time.LocalDate

sealed interface Route {
    /** 事件详情；id 是 series id 或 `series@key`。 */
    data class Detail(val occurrenceId: String) : Route

    /** 新建（eventId = null，可带预填的开始时刻 / 全天）或编辑。 */
    data class Editor(val eventId: String?, val prefillStartMs: Long? = null, val prefillAllDay: Boolean = false) : Route
    data object Calendars : Route
    data object Settings : Route
    data object Search : Route
}

enum class HomeTab { Month, Week, Agenda }

/** 异步读一条日程的结果：还在读 / 读到了 / 已经不存在（比如别处删掉了）。 */
sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ok<T>(val value: T) : Loaded<T>
    data object Gone : Loaded<Nothing>
}

class CalendarViewModel(app: Application) : AndroidViewModel(app) {
    val repo: CalendarRepository = CalendarGraph.repository(app)

    var tab by mutableStateOf(HomeTab.Month)
    var selectedDate by mutableStateOf(LocalDate.now(repo.zone))

    /** 议程当前展开到今天之后多少天；滚到底会加。 */
    var agendaDays by mutableIntStateOf(60)
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
                val result = withContext(Dispatchers.IO) { repo.block() }
                onDone(result)
            } catch (e: CalendarException) {
                onError(e.message ?: "")
            }
        }
    }

    /** 某次出现；读不到（不存在、没权限）返回 null。 */
    suspend fun loadOccurrence(id: String): Occurrence? = withContext(Dispatchers.IO) { runCatching { repo.occurrence(id) }.getOrNull() }

    suspend fun loadEvent(id: String): EventSeries? = withContext(Dispatchers.IO) { runCatching { repo.event(id) }.getOrNull() }

    suspend fun search(query: String): List<Occurrence> = withContext(Dispatchers.IO) { runCatching { repo.search(query, limit = 200) }.getOrDefault(emptyList()) }

    suspend fun eventCount(calendarId: String): Int = withContext(Dispatchers.IO) { runCatching { repo.eventCount(calendarId) }.getOrDefault(0) }
}
