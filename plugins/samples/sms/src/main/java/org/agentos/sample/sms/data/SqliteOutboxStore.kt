package org.agentos.sample.sms.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** outbox 的 SQLite 存储。只存本 App 发出的短信（不是系统短信库）。 */
class SqliteOutboxStore(context: Context, name: String = "sms_outbox.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, VERSION), OutboxStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE outbox (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, recipient TEXT NOT NULL, text TEXT NOT NULL, parts INTEGER NOT NULL, " +
                "state TEXT NOT NULL, sent_mask INTEGER NOT NULL, delivered_mask INTEGER NOT NULL, error TEXT, " +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX outbox_created ON outbox(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun insert(to: String, text: String, parts: Int, now: Long): OutboxEntry {
        val values = ContentValues().apply {
            put("recipient", to)
            put("text", text)
            put("parts", parts)
            put("state", OutboxState.QUEUED.wire)
            put("sent_mask", 0L)
            put("delivered_mask", 0L)
            put("created_at", now)
            put("updated_at", now)
        }
        val rowId = writableDatabase.insertOrThrow("outbox", null, values)
        return OutboxEntry(rowId.toString(), to, text, parts, OutboxState.QUEUED, 0, 0, null, now, now)
    }

    @Suppress("Recycle") // lint 看不出 use { } 里已关闭
    override fun get(id: String): OutboxEntry? {
        val rowId = id.toLongOrNull() ?: return null
        return readableDatabase.query("outbox", COLUMNS, "id = ?", arrayOf(rowId.toString()), null, null, null).use { it.toList().firstOrNull() }
    }

    override fun update(entry: OutboxEntry) {
        val values = ContentValues().apply {
            put("state", entry.state.wire)
            put("sent_mask", entry.sentMask)
            put("delivered_mask", entry.deliveredMask)
            put("error", entry.error)
            put("updated_at", entry.updatedAt)
        }
        writableDatabase.update("outbox", values, "id = ?", arrayOf(entry.id))
    }

    override fun list(offset: Int, limit: Int): List<OutboxEntry> =
        readableDatabase.query("outbox", COLUMNS, null, null, null, null, "created_at DESC, id DESC", "$offset,$limit").use { it.toList() }

    override fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun createdSince(sinceMillis: Long): List<OutboxEntry> =
        readableDatabase.query("outbox", COLUMNS, "created_at >= ?", arrayOf(sinceMillis.toString()), null, null, "created_at DESC, id DESC").use { it.toList() }

    override fun clear(): Int = writableDatabase.delete("outbox", "1", null)

    private fun Cursor.toList(): List<OutboxEntry> {
        val out = ArrayList<OutboxEntry>(count)
        while (moveToNext()) out += toEntry()
        return out
    }

    private fun Cursor.toEntry() = OutboxEntry(
        id = getLong(0).toString(),
        to = getString(1),
        text = getString(2),
        parts = getInt(3),
        state = OutboxState.fromWire(getString(4)),
        sentMask = getLong(5),
        deliveredMask = getLong(6),
        error = getString(7),
        createdAt = getLong(8),
        updatedAt = getLong(9),
    )

    private companion object {
        const val VERSION = 1
        val COLUMNS = arrayOf("id", "recipient", "text", "parts", "state", "sent_mask", "delivered_mask", "error", "created_at", "updated_at")
    }
}
