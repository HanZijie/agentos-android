package org.agentos.sample.calendar.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 自己的 SQLite（calendar.db），不碰系统 CalendarContract。时间列都是 UTC 毫秒；全天日程另存 epoch day。 */
class SqliteCalendarStore(context: Context, name: String? = DB_NAME) : SQLiteOpenHelper(context, name, null, VERSION), CalendarStore {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE calendars (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                color INTEGER NOT NULL,
                visible INTEGER NOT NULL DEFAULT 1,
                is_default INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE events (
                id TEXT PRIMARY KEY NOT NULL,
                calendar_id TEXT NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                location TEXT NOT NULL DEFAULT '',
                all_day INTEGER NOT NULL DEFAULT 0,
                start_utc INTEGER NOT NULL,
                end_utc INTEGER NOT NULL,
                tz TEXT NOT NULL,
                start_day INTEGER NOT NULL DEFAULT 0,
                end_day INTEGER NOT NULL DEFAULT 0,
                color INTEGER,
                reminders TEXT NOT NULL DEFAULT '',
                recurrence TEXT NOT NULL DEFAULT 'none',
                recurrence_until INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""",
        )
        db.execSQL("CREATE INDEX idx_events_calendar ON events(calendar_id)")
        db.execSQL("CREATE INDEX idx_events_start ON events(start_utc)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun loadCalendars(): List<CalendarInfo> = readableDatabase.query("calendars", null, null, null, null, null, "is_default DESC, created_at ASC").use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    CalendarInfo(
                        id = c.str("id"), name = c.str("name"), color = c.int("color"),
                        visible = c.int("visible") != 0, isDefault = c.int("is_default") != 0, createdAt = c.long("created_at"),
                    ),
                )
            }
        }
    }

    override fun loadEvents(): List<EventSeries> = readableDatabase.query("events", null, null, null, null, null, "start_utc ASC").use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    EventSeries(
                        id = c.str("id"), calendarId = c.str("calendar_id"), title = c.str("title"),
                        description = c.str("description"), location = c.str("location"),
                        allDay = c.int("all_day") != 0, startUtc = c.long("start_utc"), endUtc = c.long("end_utc"), zoneId = c.str("tz"),
                        startDay = c.long("start_day"), endDay = c.long("end_day"),
                        color = if (c.isNull(c.getColumnIndexOrThrow("color"))) null else c.int("color"),
                        reminders = c.str("reminders").split(',').mapNotNull { it.trim().toIntOrNull() },
                        recurrence = Recurrence.parse(c.str("recurrence")) ?: Recurrence.NONE,
                        recurrenceUntilUtc = if (c.isNull(c.getColumnIndexOrThrow("recurrence_until"))) null else c.long("recurrence_until"),
                        createdAt = c.long("created_at"), updatedAt = c.long("updated_at"),
                    ),
                )
            }
        }
    }

    override fun upsertCalendar(calendar: CalendarInfo) {
        val v = ContentValues().apply {
            put("id", calendar.id)
            put("name", calendar.name)
            put("color", calendar.color)
            put("visible", if (calendar.visible) 1 else 0)
            put("is_default", if (calendar.isDefault) 1 else 0)
            put("created_at", calendar.createdAt)
        }
        // 不能用 REPLACE（先删后插会触发级联删除这个日历的日程）：先 IGNORE 插入，已存在再 UPDATE
        writableDatabase.insertWithOnConflict("calendars", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.update("calendars", v, "id = ?", arrayOf(calendar.id))
    }

    override fun deleteCalendar(id: String): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val removed = db.delete("events", "calendar_id = ?", arrayOf(id))
            db.delete("calendars", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
            return removed
        } finally {
            db.endTransaction()
        }
    }

    override fun upsertEvent(event: EventSeries) {
        val v = ContentValues().apply {
            put("id", event.id)
            put("calendar_id", event.calendarId)
            put("title", event.title)
            put("description", event.description)
            put("location", event.location)
            put("all_day", if (event.allDay) 1 else 0)
            put("start_utc", event.startUtc)
            put("end_utc", event.endUtc)
            put("tz", event.zoneId)
            put("start_day", event.startDay)
            put("end_day", event.endDay)
            if (event.color != null) put("color", event.color) else putNull("color")
            put("reminders", event.reminders.joinToString(","))
            put("recurrence", event.recurrence.wire)
            if (event.recurrenceUntilUtc != null) put("recurrence_until", event.recurrenceUntilUtc) else putNull("recurrence_until")
            put("created_at", event.createdAt)
            put("updated_at", event.updatedAt)
        }
        // events 没有子表，REPLACE（先删后插）是安全的
        writableDatabase.insertWithOnConflict("events", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun deleteEvent(id: String) {
        writableDatabase.delete("events", "id = ?", arrayOf(id))
    }

    private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col)) ?: ""
    private fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))
    private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))

    companion object {
        const val DB_NAME = "calendar.db"
        const val VERSION = 1
    }
}
