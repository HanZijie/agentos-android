package org.agentos.sample.todo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 平台自带的 SQLite（SQLiteOpenHelper），不用 Room。标签存成 JSON 数组文本。
 * 截止时间两列：`due_at`（带时刻，毫秒）和 `due_date`（全天，`YYYY-MM-DD`），至多一个非空。
 */
class SqliteTodoStore(context: Context, name: String? = DB_NAME) : TodoStore {
    private val helper = Helper(context.applicationContext, name)

    @Suppress("Recycle") // Cursor 由下面的 use 关闭
    override fun loadAll(): List<Todo> {
        val result = ArrayList<Todo>()
        helper.readableDatabase.query(
            TABLE,
            arrayOf("id", "title", "notes", "status", "priority", "due_at", "due_date", "tags", "parent_id", "completed_at", "created_at", "updated_at"),
            null, null, null, null, null,
        ).use { c ->
            while (c.moveToNext()) {
                val dueDate = if (c.isNull(6)) null else runCatching { LocalDate.parse(c.getString(6)) }.getOrNull()
                result += Todo(
                    id = c.getString(0),
                    title = c.getString(1),
                    notes = c.getString(2),
                    status = TodoStatus.fromCode(c.getInt(3)),
                    priority = Priority.fromCode(c.getInt(4)),
                    due = when {
                        !c.isNull(5) -> Due.At(c.getLong(5))
                        dueDate != null -> Due.Day(dueDate)
                        else -> null
                    },
                    tags = decodeTags(c.getString(7)),
                    parentId = if (c.isNull(8)) null else c.getString(8),
                    completedAt = if (c.isNull(9)) null else c.getLong(9),
                    createdAt = c.getLong(10),
                    updatedAt = c.getLong(11),
                )
            }
        }
        return result
    }

    override fun save(todo: Todo) {
        helper.writableDatabase.insertWithOnConflict(TABLE, null, values(todo), SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun saveAll(todos: Collection<Todo>) {
        if (todos.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (t in todos) db.insertWithOnConflict(TABLE, null, values(t), SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.delete(TABLE, "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Suppress("Recycle") // Cursor 由 use 关闭
    override fun count(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun values(todo: Todo) = ContentValues().apply {
        put("id", todo.id)
        put("title", todo.title)
        put("notes", todo.notes)
        put("status", todo.status.code)
        put("priority", todo.priority.code)
        when (val due = todo.due) {
            is Due.At -> { put("due_at", due.epochMillis); putNull("due_date") }
            is Due.Day -> { putNull("due_at"); put("due_date", due.date.toString()) }
            null -> { putNull("due_at"); putNull("due_date") }
        }
        put("tags", encodeTags(todo.tags))
        if (todo.parentId == null) putNull("parent_id") else put("parent_id", todo.parentId)
        if (todo.completedAt == null) putNull("completed_at") else put("completed_at", todo.completedAt)
        put("created_at", todo.createdAt)
        put("updated_at", todo.updatedAt)
    }

    private class Helper(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    notes TEXT NOT NULL,
                    status INTEGER NOT NULL,
                    priority INTEGER NOT NULL,
                    due_at INTEGER,
                    due_date TEXT,
                    tags TEXT NOT NULL,
                    parent_id TEXT,
                    completed_at INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX todos_parent ON $TABLE (parent_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 目前只有 v1
        }
    }

    companion object {
        const val DB_NAME = "todo.db"
        private const val TABLE = "todos"
        private const val VERSION = 1

        fun encodeTags(tags: List<String>): String = JsonArray(tags.map { JsonPrimitive(it) }).toString()

        fun decodeTags(text: String): List<String> = runCatching {
            Json.parseToJsonElement(text).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrDefault(emptyList())
    }
}
