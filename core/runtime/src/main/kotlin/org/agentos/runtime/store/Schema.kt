package org.agentos.runtime.store

/**
 * Store 的 schema。Android（AndroidStore / SQLiteDriver）与电脑测试共用这一份；版本记在 `PRAGMA user_version`。
 *
 * 迁移只向前（W12 起加 Migrations.kt：迁移前备份，失败回滚并停在 safe mode）。v1 是第一版。
 */
internal object Schema {
    const val VERSION = 1

    /** v1：会话、事件、任务、工具调用、各会话的 Pi messages。 */
    val V1 = """
        CREATE TABLE sessions (
            id               TEXT PRIMARY KEY,
            owner_key        TEXT NOT NULL,
            caller_kind      TEXT NOT NULL,
            caller_uid       INTEGER NOT NULL,
            state            TEXT NOT NULL,
            pause_reason     TEXT,
            cwd              TEXT,
            created_at       INTEGER NOT NULL,
            last_activity_at INTEGER NOT NULL,
            ready_since      INTEGER NOT NULL DEFAULT 0,
            last_sequence    INTEGER NOT NULL DEFAULT 0,
            first_query      TEXT,
            first_answer     TEXT,
            latest_answer    TEXT,
            recent_turns     TEXT NOT NULL DEFAULT '[]'
        );
        CREATE INDEX sessions_owner_idx ON sessions(owner_key, last_activity_at);
        CREATE INDEX sessions_state_idx ON sessions(state, ready_since);

        CREATE TABLE events (
            session_id  TEXT NOT NULL,
            sequence    INTEGER NOT NULL,
            task_id     TEXT,
            event_type  TEXT NOT NULL,
            timestamp   INTEGER NOT NULL,
            payload     TEXT NOT NULL,
            error       TEXT,
            PRIMARY KEY (session_id, sequence)
        ) WITHOUT ROWID;
        CREATE INDEX events_task_idx ON events(task_id);

        CREATE TABLE tasks (
            id                 TEXT PRIMARY KEY,
            session_id         TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
            position           INTEGER NOT NULL,
            state              TEXT NOT NULL,
            input              TEXT NOT NULL,
            input_hash         TEXT NOT NULL,
            client_request_id  TEXT,
            caller_kind        TEXT NOT NULL,
            caller_uid         INTEGER NOT NULL,
            caller_label       TEXT,
            attempt            INTEGER NOT NULL DEFAULT 0,
            created_at         INTEGER NOT NULL,
            started_at         INTEGER,
            finished_at        INTEGER,
            queue_deadline     INTEGER,
            execution_deadline INTEGER,
            cancel_reason      TEXT,
            stop_reason        TEXT,
            error              TEXT,
            UNIQUE (session_id, client_request_id),
            UNIQUE (session_id, position)
        );
        CREATE INDEX tasks_state_idx ON tasks(state);

        CREATE TABLE tool_calls (
            task_id       TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
            tool_call_id  TEXT NOT NULL,
            session_id    TEXT NOT NULL,
            name          TEXT NOT NULL,
            provider      TEXT,
            state         TEXT NOT NULL,
            dispatched_at INTEGER,
            settled_at    INTEGER,
            PRIMARY KEY (task_id, tool_call_id)
        );
        CREATE INDEX tool_calls_state_idx ON tool_calls(state);

        CREATE TABLE pi_messages (
            session_id      TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
            messages        TEXT NOT NULL,
            stable_messages TEXT,
            updated_at      INTEGER NOT NULL
        )
    """.trimIndent()

    fun migrate(db: DbScope, now: Long) {
        val version = db.queryOne("PRAGMA user_version") { it.int(0) } ?: 0
        check(version <= VERSION) { "store schema $version is newer than this runtime ($VERSION)" }
        if (version < 1) {
            db.execScript(V1)
            db.exec(
                "INSERT INTO sessions (id, owner_key, caller_kind, caller_uid, state, created_at, last_activity_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                org.agentos.runtime.events.EventTypes.SYSTEM_STREAM, "system", "system", -1, SessionState.SYSTEM.wire, now, now,
            )
            db.exec("PRAGMA user_version = 1")
        }
    }
}
