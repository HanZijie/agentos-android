package org.agentos.sample.todo.data

import java.time.LocalDate

/** 待办状态。[key] 是对外（MCP / dump）的枚举值，不本地化；[code] 是存进 SQLite 的整数。 */
enum class TodoStatus(val key: String, val code: Int) {
    TODO("todo", 0),
    DOING("doing", 1),
    DONE("done", 2),
    SHELVED("shelved", 3),
    ;

    /** 还在“做”的状态：待办和进行中。逾期、今天到期、本周到期只算这两种。 */
    val isOpen: Boolean get() = this == TODO || this == DOING

    companion object {
        val keys: List<String> = entries.map { it.key }
        fun fromKey(key: String): TodoStatus? = entries.firstOrNull { it.key == key.trim().lowercase() }
        fun fromCode(code: Int): TodoStatus = entries.firstOrNull { it.code == code } ?: TODO
    }
}

/** 优先级。[rank] 越小越靠前（排序用）。 */
enum class Priority(val key: String, val code: Int) {
    HIGH("high", 0),
    MEDIUM("medium", 1),
    LOW("low", 2),
    ;

    val rank: Int get() = code

    companion object {
        val keys: List<String> = entries.map { it.key }
        fun fromKey(key: String): Priority? = entries.firstOrNull { it.key == key.trim().lowercase() }
        fun fromCode(code: Int): Priority = entries.firstOrNull { it.code == code } ?: MEDIUM
    }
}

/**
 * 截止时间：要么是一个绝对时刻（[At]，带偏移的 ISO-8601，存成毫秒），要么是一个日历日（[Day]，全天，不带时区——
 * “10 月 12 日到期”不随用户所在时区漂移）。比较、分组、逾期判断一律按设备本地时区。
 */
sealed interface Due {
    data class At(val epochMillis: Long) : Due
    data class Day(val date: LocalDate) : Due

    val isAllDay: Boolean get() = this is Day
}

/** 一条待办。子任务通过 [parentId] 指向父任务（只支持一层）。时间戳都是毫秒。 */
data class Todo(
    val id: String,
    val title: String,
    val notes: String,
    val status: TodoStatus,
    val priority: Priority,
    val due: Due?,
    val tags: List<String>,
    val parentId: String?,
    val completedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isSubtask: Boolean get() = parentId != null
}

object TodoLimits {
    const val MAX_TITLE_CHARS = 200
    const val MAX_NOTES_CHARS = 10_000
    const val MAX_TAGS = 20
    const val MAX_TAG_CHARS = 32
    const val MAX_SUBTASKS = 100
}

/** 仓库 / 校验失败。消息就是返回给模型的那句话（英文，不本地化）。 */
class TodoException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { INVALID, NOT_FOUND, STATE }
}

/** 新建时的输入。 */
data class TodoDraft(
    val title: String,
    val notes: String = "",
    val status: TodoStatus = TodoStatus.TODO,
    val priority: Priority = Priority.MEDIUM,
    val due: Due? = null,
    val tags: List<String> = emptyList(),
    val parentId: String? = null,
)

/** 局部更新里一个可清除的字段：不动 / 设为某值 / 清掉。 */
sealed interface Change<out T> {
    data object Keep : Change<Nothing>
    data class Set<T>(val value: T) : Change<T>
    data object Clear : Change<Nothing>
}

/** 只改给出的字段。 */
data class TodoPatch(
    val title: String? = null,
    val notes: String? = null,
    val status: TodoStatus? = null,
    val priority: Priority? = null,
    val due: Change<Due> = Change.Keep,
    val tags: List<String>? = null,
    val parentId: Change<String> = Change.Keep,
) {
    val isEmpty: Boolean
        get() = title == null && notes == null && status == null && priority == null && due == Change.Keep && tags == null && parentId == Change.Keep
}
