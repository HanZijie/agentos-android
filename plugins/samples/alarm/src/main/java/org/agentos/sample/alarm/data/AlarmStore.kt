package org.agentos.sample.alarm.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.DayOfWeek

/** 持久化接口：仓库只依赖它，JVM 测试用 [InMemoryAlarmStore]，App 用 [SqliteAlarmStore]。 */
interface AlarmStore {
    fun loadAll(): List<Alarm>

    /** 插入。[Alarm.id] 为空串时由存储分配新 id；不为空时按该 id 插入（用于“撤销删除”）。返回带 id 的闹钟。 */
    fun insert(alarm: Alarm): Alarm

    fun update(alarm: Alarm)

    fun delete(id: String)
}

class InMemoryAlarmStore(initial: List<Alarm> = emptyList()) : AlarmStore {
    private val rows = LinkedHashMap<String, Alarm>().apply { initial.forEach { put(it.id, it) } }
    private var nextId = (initial.mapNotNull { it.id.toLongOrNull() }.maxOrNull() ?: 0L) + 1

    @Synchronized
    override fun loadAll(): List<Alarm> = rows.values.toList()

    @Synchronized
    override fun insert(alarm: Alarm): Alarm {
        val saved = if (alarm.id.isEmpty()) alarm.copy(id = (nextId++).toString()) else alarm
        rows[saved.id] = saved
        return saved
    }

    @Synchronized
    override fun update(alarm: Alarm) {
        rows[alarm.id] = alarm
    }

    @Synchronized
    override fun delete(id: String) {
        rows.remove(id)
    }
}

/** App 自己的 SQLite（平台自带的 SQLiteOpenHelper，不引入 Room）。id 对外是自增整数的字符串形式。 */
class SqliteAlarmStore(context: Context) : AlarmStore {
    private val helper = Helper(context.applicationContext)

    // Cursor 由下面的 use {} 关闭；lint 的 Recycle 看不懂 use 里直接 return 的写法
    @Suppress("Recycle")
    override fun loadAll(): List<Alarm> {
        val cursor = helper.readableDatabase.query(TABLE, null, null, null, null, null, "$COL_ID ASC")
        cursor.use { c -> return buildList { while (c.moveToNext()) add(c.toAlarm()) } }
    }

    override fun insert(alarm: Alarm): Alarm {
        val values = alarm.toValues()
        if (alarm.id.isNotEmpty()) values.put(COL_ID, alarm.id.toLong())
        val rowId = helper.writableDatabase.insertOrThrow(TABLE, null, values)
        return alarm.copy(id = rowId.toString())
    }

    override fun update(alarm: Alarm) {
        helper.writableDatabase.update(TABLE, alarm.toValues(), "$COL_ID = ?", arrayOf(alarm.id))
    }

    override fun delete(id: String) {
        helper.writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id))
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_HOUR INTEGER NOT NULL,
                    $COL_MINUTE INTEGER NOT NULL,
                    $COL_LABEL TEXT NOT NULL DEFAULT '',
                    $COL_DAYS INTEGER NOT NULL DEFAULT 0,
                    $COL_ENABLED INTEGER NOT NULL DEFAULT 1,
                    $COL_VIBRATE INTEGER NOT NULL DEFAULT 1,
                    $COL_SNOOZE INTEGER NOT NULL DEFAULT ${Alarm.DEFAULT_SNOOZE_MINUTES},
                    $COL_RINGTONE TEXT,
                    $COL_SNOOZED_UNTIL INTEGER,
                    $COL_FIRE_AT INTEGER,
                    $COL_CREATED INTEGER NOT NULL DEFAULT 0,
                    $COL_UPDATED INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 目前只有 v1；以后升级在这里按版本号逐步迁移
        }
    }

    private fun Alarm.toValues() = ContentValues().apply {
        put(COL_HOUR, hour)
        put(COL_MINUTE, minute)
        put(COL_LABEL, label)
        put(COL_DAYS, days.toMask())
        put(COL_ENABLED, if (enabled) 1 else 0)
        put(COL_VIBRATE, if (vibrate) 1 else 0)
        put(COL_SNOOZE, snoozeMinutes)
        put(COL_RINGTONE, ringtoneUri)
        put(COL_SNOOZED_UNTIL, snoozedUntil)
        put(COL_FIRE_AT, fireAt)
        put(COL_CREATED, createdAt)
        put(COL_UPDATED, updatedAt)
    }

    private fun Cursor.toAlarm() = Alarm(
        id = getLong(getColumnIndexOrThrow(COL_ID)).toString(),
        hour = getInt(getColumnIndexOrThrow(COL_HOUR)),
        minute = getInt(getColumnIndexOrThrow(COL_MINUTE)),
        label = getString(getColumnIndexOrThrow(COL_LABEL)).orEmpty(),
        days = getInt(getColumnIndexOrThrow(COL_DAYS)).toDays(),
        enabled = getInt(getColumnIndexOrThrow(COL_ENABLED)) != 0,
        vibrate = getInt(getColumnIndexOrThrow(COL_VIBRATE)) != 0,
        snoozeMinutes = getInt(getColumnIndexOrThrow(COL_SNOOZE)),
        ringtoneUri = getString(getColumnIndexOrThrow(COL_RINGTONE)),
        snoozedUntil = getLongOrNull(COL_SNOOZED_UNTIL),
        fireAt = getLongOrNull(COL_FIRE_AT),
        createdAt = getLong(getColumnIndexOrThrow(COL_CREATED)),
        updatedAt = getLong(getColumnIndexOrThrow(COL_UPDATED)),
    )

    private fun Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private companion object {
        const val DB_NAME = "alarms.db"
        const val DB_VERSION = 1
        const val TABLE = "alarms"
        const val COL_ID = "id"
        const val COL_HOUR = "hour"
        const val COL_MINUTE = "minute"
        const val COL_LABEL = "label"
        const val COL_DAYS = "days"
        const val COL_ENABLED = "enabled"
        const val COL_VIBRATE = "vibrate"
        const val COL_SNOOZE = "snooze_minutes"
        const val COL_RINGTONE = "ringtone_uri"
        const val COL_SNOOZED_UNTIL = "snoozed_until"
        const val COL_FIRE_AT = "fire_at"
        const val COL_CREATED = "created_at"
        const val COL_UPDATED = "updated_at"
    }
}

/** 星期集合 ↔ 位掩码（周一 = bit0 … 周日 = bit6）。 */
fun Set<DayOfWeek>.toMask(): Int = fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

fun Int.toDays(): Set<DayOfWeek> = DayOfWeek.entries.filter { this and (1 shl (it.value - 1)) != 0 }.toSet()
