package org.agentos.sample.calendar.data

/**
 * 本机日历的持久化接口（本 App 自己的 SQLite）。数据量很小，[LocalBackend] 整表加载到内存；存储层只管落盘。
 * 这里的 id 都是库里的裸 id（不带 `local:` 前缀），前缀由 [LocalBackend] 加减。
 */
interface CalendarStore {
    fun loadCalendars(): List<CalendarInfo>
    fun loadEvents(): List<EventSeries>
    fun upsertCalendar(calendar: CalendarInfo)

    /** 删除日历及其全部日程，返回删掉的日程数。 */
    fun deleteCalendar(id: String): Int
    fun upsertEvent(event: EventSeries)
    fun deleteEvent(id: String)
}

/**
 * 升级前后“系统生成的默认名”的识别（R3）。旧版本把创建时那种语言的默认名直接写进了库
 * （`CalendarApp` 传 `R.string.default_calendar_name`），之后切换语言也不会变。
 *
 * 迁移的取舍：默认日历（`is_default = 1`）的名字**恰好等于**下面某个名字，就当作“没被用户改过”，改存标记、显示时按语言翻译；
 * 用户改成别的名字的不动。代价：用户恰好把默认日历改回这两个名字之一，也会被当作默认名跟着语言变——可以接受（名字本来就一样）。
 */
object LegacyDefaultNames {
    /**
     * 中文默认名（`values/strings.xml` 的 `default_calendar_name`，“我的日历”）与英文默认名（`values-en`，“My Calendar”）。
     * 中文用 Unicode 转义写：用户可见的中文只放资源文件，Kotlin 源码里不写中文字面量（R9 门禁）。
     */
    val all: List<String> = listOf("\u6211\u7684\u65e5\u5386", "My Calendar")

    fun isSystemDefault(name: String, isDefault: Boolean): Boolean = isDefault && name in all

    /** 给标记行写入库的占位文字（不会被显示）。 */
    const val FILLER = "My Calendar"
}
