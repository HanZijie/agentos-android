package org.agentos.runtime.store

/**
 * Store 的 schema。Android（AndroidStore / SQLiteDriver）与电脑测试共用这一份；版本记在 `PRAGMA user_version`。
 *
 * 迁移只向前（W12 起加 Migrations.kt：迁移前备份，失败回滚并停在 safe mode）。v1 是第一版，v2 给会话加 toolScope 列，v3 给任务加调用方包名列，
 * v4 给会话加 model_id、mode 两列（会话级模型与模式）。
 */
internal object Schema {
    const val VERSION = 4

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

    /**
     * v2 (docs/third-party-acp.md 4.5): the tools a session may use (`toolScope`), a JSON array of `{"plugin", "tool"}`, written once when the
     * session is created and never changed. NULL = the session was created without a scope (every session that existed before v2).
     */
    const val V2 = "ALTER TABLE sessions ADD COLUMN tool_scope TEXT"

    /**
     * v3: the package name of a third-party caller, next to `caller_label` (the display name). The confirmation dialog is rebuilt from the task
     * record when the task starts (also after a restart), and it must name the real package, not only the name the app gave itself.
     * NULL = not a third-party app, or the task was queued before v3.
     */
    const val V3 = "ALTER TABLE tasks ADD COLUMN caller_package TEXT"

    /**
     * v4: per-session model and mode (ACP `session/set_model`, `session/set_mode`, config options). `model_id` is a [org.agentos.runtime.ports.ModelChoice.id]
     * (NULL = follow the model the user picked in settings); `mode` is a [org.agentos.runtime.store.SessionMode] wire name (NULL = `default`).
     * Both can change while the session lives; the next task of the session uses the new value, a running task keeps the old one.
     */
    const val V4_MODEL = "ALTER TABLE sessions ADD COLUMN model_id TEXT"
    const val V4_MODE = "ALTER TABLE sessions ADD COLUMN mode TEXT"

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
        if (version < 2) {
            db.exec(V2)
            db.exec("PRAGMA user_version = 2")
        }
        if (version < 3) {
            db.exec(V3)
            db.exec("PRAGMA user_version = 3")
        }
        if (version < 4) {
            db.exec(V4_MODEL)
            db.exec(V4_MODE)
            db.exec("PRAGMA user_version = 4")
        }
    }
}
