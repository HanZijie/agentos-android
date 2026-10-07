package org.agentos.sample.notes.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** 平台自带的 SQLite（SQLiteOpenHelper），不用 Room。标签存成 JSON 数组文本。 */
class SqliteNoteStore(context: Context, name: String? = DB_NAME) : NoteStore {
    private val helper = Helper(context.applicationContext, name)

    override fun loadAll(): List<Note> {
        val result = ArrayList<Note>()
        helper.readableDatabase.query(
            TABLE,
            arrayOf("id", "title", "content", "tags", "color", "pinned", "status", "created_at", "updated_at", "trashed_at", "revision"),
            null, null, null, null, null,
        ).use { c ->
            while (c.moveToNext()) {
                result += Note(
                    id = c.getString(0),
                    title = c.getString(1),
                    content = c.getString(2),
                    tags = decodeTags(c.getString(3)),
                    color = NoteColor.fromKey(c.getString(4)) ?: NoteColor.DEFAULT,
                    pinned = c.getInt(5) != 0,
                    status = NoteStatus.fromCode(c.getInt(6)),
                    createdAt = c.getLong(7),
                    updatedAt = c.getLong(8),
                    trashedAt = if (c.isNull(9)) null else c.getLong(9),
                    revision = c.getLong(10),
                )
            }
        }
        return result
    }

    override fun save(note: Note) {
        val values = ContentValues().apply {
            put("id", note.id)
            put("title", note.title)
            put("content", note.content)
            put("tags", encodeTags(note.tags))
            put("color", note.color.key)
            put("pinned", if (note.pinned) 1 else 0)
            put("status", note.status.code)
            put("created_at", note.createdAt)
            put("updated_at", note.updatedAt)
            if (note.trashedAt == null) putNull("trashed_at") else put("trashed_at", note.trashedAt)
            put("revision", note.revision)
        }
        helper.writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
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

    private class Helper(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    tags TEXT NOT NULL,
                    color TEXT NOT NULL,
                    pinned INTEGER NOT NULL,
                    status INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    trashed_at INTEGER,
                    revision INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX notes_status_updated ON $TABLE (status, updated_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 目前只有 v1
        }
    }

    companion object {
        const val DB_NAME = "notes.db"
        private const val TABLE = "notes"
        private const val VERSION = 1

        fun encodeTags(tags: List<String>): String = JsonArray(tags.map { JsonPrimitive(it) }).toString()

        fun decodeTags(text: String): List<String> = runCatching {
            Json.parseToJsonElement(text).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrDefault(emptyList())
    }
}
