package org.agentos.sample.todo.ui

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.time.ZoneId
import java.util.Locale
import org.agentos.sample.todo.R
import org.agentos.sample.todo.data.Priority
import org.agentos.sample.todo.data.TodoStatus

/** 相对词来自字符串资源（中英文各一份）。 */
class ResourceDueTexts(private val resources: Resources) : DueTexts {
    override val today: String get() = resources.getString(R.string.due_today)
    override val tomorrow: String get() = resources.getString(R.string.due_tomorrow)
    override val yesterday: String get() = resources.getString(R.string.due_yesterday)
}

/** 日期时间的写法交给系统（`getBestDateTimePattern`）：随语言、区域和 12 / 24 小时设置变化。 */
@Composable
fun rememberDueFormatter(): DueFormatter {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale: Locale = configuration.locales[0]
    val is24 = DateFormat.is24HourFormat(context)
    return remember(locale, is24, configuration) {
        DueFormatter(
            locale = locale,
            zone = ZoneId.systemDefault(),
            is24Hour = is24,
            texts = ResourceDueTexts(context.resources),
            bestPattern = { skeleton -> DateFormat.getBestDateTimePattern(locale, skeleton) },
        )
    }
}

@Composable
fun TodoStatus.label(): String = stringResource(
    when (this) {
        TodoStatus.TODO -> R.string.status_todo
        TodoStatus.DOING -> R.string.status_doing
        TodoStatus.DONE -> R.string.status_done
        TodoStatus.SHELVED -> R.string.status_shelved
    },
)

@Composable
fun Priority.label(): String = stringResource(
    when (this) {
        Priority.HIGH -> R.string.priority_high
        Priority.MEDIUM -> R.string.priority_medium
        Priority.LOW -> R.string.priority_low
    },
)

@Composable
fun SectionKind.label(): String = stringResource(
    when (this) {
        SectionKind.OVERDUE -> R.string.section_overdue
        SectionKind.TODAY -> R.string.section_today
        SectionKind.UPCOMING -> R.string.section_upcoming
        SectionKind.NO_DATE -> R.string.section_no_date
        SectionKind.SHELVED -> R.string.section_shelved
        SectionKind.DONE -> R.string.section_done
    },
)
