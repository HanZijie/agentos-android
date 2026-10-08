package org.agentos.sample.alarm.data

import java.time.DayOfWeek
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.agentos.sample.alarm.schedule.AlarmScheduler

/**
 * 闹钟仓库：界面和 MCP 工具共用的同一个进程内单例（见 [org.agentos.sample.alarm.AlarmGraph]）。
 *
 * - 所有读写都经过这里。任何写操作都会同步重排或取消系统闹钟（AlarmScheduler），并刷新 alarms。
 * - 方法是阻塞的、线程安全的（内部一把锁）；界面从 IO 线程调用，工具从协程里直接调用。
 * - 校验失败或找不到 id 抛 [AlarmException]（消息是一句话原因），调用方负责转换成界面提示 / 工具的 isError。
 */
class AlarmRepository(
    private val store: AlarmStore,
    private val scheduler: AlarmScheduler,
    private val time: TimeSource = SystemTimeSource,
) {
    private val lock = Any()
    private val rows = LinkedHashMap<String, Alarm>().also { map -> store.loadAll().forEach { map[it.id] = it } }
    private val _alarms = MutableStateFlow(sorted(rows.values))

    /** 全部闹钟，按一天里的时刻排序（界面列表用）。 */
    val alarms: StateFlow<List<Alarm>> = _alarms

    fun now(): ZonedDateTime = time.now()

    fun get(id: String): Alarm? = synchronized(lock) { rows[id] }

    fun require(id: String): Alarm = get(id) ?: throw AlarmException("No alarm with id '$id'")

    /** 下一次响铃时刻（含贪睡）；关闭且没有贪睡的闹钟为 null。 */
    fun nextFireOf(alarm: Alarm): ZonedDateTime? = NextFire.compute(alarm, time.now())

    /** 全部闹钟，按下次响铃时间排序；没有下次响铃的（已关闭）排在最后，再按时刻排。 */
    fun list(enabledOnly: Boolean = false): List<Alarm> {
        val now = time.now()
        val all = synchronized(lock) { rows.values.toList() }
        return all
            .filter { !enabledOnly || it.enabled || it.snoozedUntil != null }
            .map { it to NextFire.compute(it, now) }
            .sortedWith(
                compareBy<Pair<Alarm, ZonedDateTime?>> { it.second == null }
                    .thenBy { it.second?.toInstant() }
                    .thenBy { it.first.hour * 60 + it.first.minute },
            )
            .map { it.first }
    }

    fun nextAlarm(): NextAlarm? {
        val now = time.now()
        return synchronized(lock) { rows.values.toList() }
            .mapNotNull { a -> NextFire.compute(a, now)?.let { NextAlarm(a, it.toInstant().toEpochMilli()) } }
            .minByOrNull { it.fireAtMillis }
    }

    fun create(draft: AlarmDraft): Alarm = synchronized(lock) {
        checkTime(draft.hour, draft.minute)
        val label = checkLabel(draft.label)
        checkSnooze(draft.snoozeMinutes)
        val nowMillis = time.now().toInstant().toEpochMilli()
        val alarm = Alarm(
            id = "",
            hour = draft.hour,
            minute = draft.minute,
            label = label,
            days = draft.days,
            enabled = draft.enabled,
            vibrate = draft.vibrate,
            snoozeMinutes = draft.snoozeMinutes,
            ringtoneUri = draft.ringtoneUri?.takeIf { it.isNotBlank() },
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        val saved = store.insert(withFireAt(alarm))
        rows[saved.id] = saved
        applySchedule(saved)
        publish()
        saved
    }

    /**
     * 给系统标准 Intent（ACTION_SET_ALARM）用：时刻、标签、重复日都相同的闹钟已存在时复用它，不重复建。
     * 已存在且开着：原样返回（[Reuse.EXISTING]）；已存在但关着：重新打开（[Reuse.REENABLED]，官方文档：该 action 总是启用闹钟）；
     * 没有：新建（[Reuse.CREATED]）。判重只看时刻 + 标签 + 重复日，不比较振动、贪睡长度等其他字段（复用时保持原样）。
     */
    fun createOrReuse(draft: AlarmDraft): CreateResult = synchronized(lock) {
        checkTime(draft.hour, draft.minute)
        val label = checkLabel(draft.label)
        val same = rows.values.filter {
            it.hour == draft.hour && it.minute == draft.minute && it.label == label && it.days == draft.days
        }
        val enabled = same.firstOrNull { it.enabled }
        when {
            enabled != null -> CreateResult(enabled, Reuse.EXISTING)
            same.isNotEmpty() -> CreateResult(setEnabled(same.first().id, true), Reuse.REENABLED)
            else -> CreateResult(create(draft), Reuse.CREATED)
        }
    }

    fun update(id: String, patch: AlarmPatch): Alarm = synchronized(lock) {
        val current = require(id)
        val hour = patch.hour ?: current.hour
        val minute = patch.minute ?: current.minute
        checkTime(hour, minute)
        val label = patch.label?.let { checkLabel(it) } ?: current.label
        val snooze = patch.snoozeMinutes?.also { checkSnooze(it) } ?: current.snoozeMinutes
        val touchesSchedule = patch.hour != null || patch.minute != null || patch.days != null || patch.enabled != null
        val updated = current.copy(
            hour = hour,
            minute = minute,
            label = label,
            days = patch.days ?: current.days,
            enabled = patch.enabled ?: current.enabled,
            vibrate = patch.vibrate ?: current.vibrate,
            snoozeMinutes = snooze,
            ringtoneUri = when {
                patch.ringtoneUri == null -> current.ringtoneUri
                patch.ringtoneUri.isBlank() -> null
                else -> patch.ringtoneUri
            },
            // 改时刻 / 重复 / 开关都会结束贪睡
            snoozedUntil = if (touchesSchedule) null else current.snoozedUntil,
            updatedAt = time.now().toInstant().toEpochMilli(),
        )
        commit(updated)
    }

    fun setEnabled(id: String, enabled: Boolean): Alarm = update(id, AlarmPatch(enabled = enabled))

    /** 删除并返回被删的闹钟（界面用它做“撤销”）。 */
    fun delete(id: String): Alarm = synchronized(lock) {
        val current = require(id)
        store.delete(id)
        rows.remove(id)
        scheduler.cancel(id)
        publish()
        current
    }

    /** 撤销删除：用原来的 id 放回去，并按当前时间重新排期。 */
    fun restore(alarm: Alarm): Alarm = synchronized(lock) {
        if (rows.containsKey(alarm.id)) return@synchronized rows.getValue(alarm.id)
        val saved = store.insert(withFireAt(alarm.copy(updatedAt = time.now().toInstant().toEpochMilli())))
        rows[saved.id] = saved
        applySchedule(saved)
        publish()
        saved
    }

    /** 清空全部闹钟并取消它们的系统闹钟；返回删掉的条数。给 debug 的 reset 命令用（测试环境复位），界面和 MCP 工具不暴露。 */
    fun clearAll(): Int = synchronized(lock) {
        val all = rows.values.toList()
        for (alarm in all) {
            store.delete(alarm.id)
            scheduler.cancel(alarm.id)
        }
        rows.clear()
        publish()
        all.size
    }

    /**
     * 系统闹钟到点时调用：返回响铃用的闹钟快照，并推进状态——
     * 只响一次的关掉开关，重复的排下一次，结束贪睡。找不到或已被关闭（过期的触发）返回 null。
     */
    fun onFired(id: String): Alarm? = synchronized(lock) {
        val current = rows[id] ?: return@synchronized null
        if (!current.enabled && current.snoozedUntil == null) return@synchronized null
        commit(current.copy(snoozedUntil = null, enabled = current.isRepeating))
        current
    }

    /**
     * 贪睡：[minutes]（默认用该闹钟自己的 [Alarm.snoozeMinutes]）分钟后再响（只响一次的闹钟也会重新打开，等贪睡到点）。
     * [minutes] 只对这一次生效，不改闹钟自己的贪睡时长（与 ACTION_SNOOZE_ALARM 的官方语义一致）。
     */
    fun snooze(id: String, minutes: Int? = null): Alarm = synchronized(lock) {
        val current = require(id)
        minutes?.let { checkSnooze(it) }
        val until = time.now().toInstant().toEpochMilli() + (minutes ?: current.snoozeMinutes) * 60_000L
        commit(current.copy(snoozedUntil = until, enabled = true))
    }

    /**
     * 重新向系统登记所有闹钟（进程启动、开机、时区 / 时间变化后）。
     * [detectMissed] = true 时，识别“应该已经响过但没响”的闹钟（关机期间错过，晚于计划时刻超过 [MISSED_GRACE_MILLIS]）：
     * 只响一次的关掉，重复的排下一次，并把它们返回给调用方发“错过的闹钟”通知。
     */
    fun rescheduleAll(detectMissed: Boolean): List<Alarm> = synchronized(lock) {
        val nowMillis = time.now().toInstant().toEpochMilli()
        val missed = ArrayList<Alarm>()
        for (alarm in rows.values.toList()) {
            val fireAt = alarm.fireAt
            if (detectMissed && fireAt != null && fireAt <= nowMillis) {
                if (nowMillis - fireAt <= MISSED_GRACE_MILLIS) continue // 刚刚到点，系统正在投递，别动它
                missed += alarm
                commit(alarm.copy(snoozedUntil = null, enabled = alarm.isRepeating))
            } else {
                commit(alarm)
            }
        }
        missed
    }

    // ---- 内部 ----

    private fun commit(alarm: Alarm): Alarm {
        val scheduled = withFireAt(alarm)
        store.update(scheduled)
        rows[scheduled.id] = scheduled
        applySchedule(scheduled)
        publish()
        return scheduled
    }

    private fun withFireAt(alarm: Alarm): Alarm =
        alarm.copy(fireAt = NextFire.compute(alarm, time.now())?.toInstant()?.toEpochMilli())

    private fun applySchedule(alarm: Alarm) {
        val fireAt = alarm.fireAt
        if (fireAt != null) scheduler.schedule(alarm.id, fireAt) else scheduler.cancel(alarm.id)
    }

    private fun publish() {
        _alarms.value = sorted(rows.values)
    }

    private fun sorted(all: Collection<Alarm>): List<Alarm> =
        all.sortedWith(compareBy<Alarm> { it.hour }.thenBy { it.minute }.thenBy { it.id.toLongOrNull() ?: 0L })

    private fun checkTime(hour: Int, minute: Int) {
        if (hour !in 0..23) throw AlarmException("Hour must be between 0 and 23, got $hour")
        if (minute !in 0..59) throw AlarmException("Minute must be between 0 and 59, got $minute")
    }

    private fun checkLabel(label: String): String {
        val trimmed = label.trim()
        if (trimmed.length > Alarm.MAX_LABEL_LENGTH) {
            throw AlarmException("Label is too long (max ${Alarm.MAX_LABEL_LENGTH} characters)")
        }
        return trimmed
    }

    private fun checkSnooze(minutes: Int) {
        if (minutes !in 1..Alarm.MAX_SNOOZE_MINUTES) {
            throw AlarmException("snooze_minutes must be between 1 and ${Alarm.MAX_SNOOZE_MINUTES}, got $minutes")
        }
    }

    companion object {
        /** 晚于计划时刻不到这个时间，视为系统正在投递，不算“错过”。 */
        const val MISSED_GRACE_MILLIS = 60_000L
    }
}

/** [AlarmRepository.createOrReuse] 的结果：新建、复用了开着的同款、还是把关着的同款重新打开。 */
enum class Reuse { CREATED, EXISTING, REENABLED }

data class CreateResult(val alarm: Alarm, val status: Reuse)

/** 一周里的“重复日”摘要用：工作日 / 周末 / 每天。 */
val WEEKDAYS: Set<DayOfWeek> = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)
val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
val EVERY_DAY: Set<DayOfWeek> = DayOfWeek.entries.toSet()
