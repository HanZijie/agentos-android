package org.agentos.runtime.ports

import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import org.agentos.runtime.testing.FakeHostPort
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostPortFixtureTest {
    private val host = FakeHostPort()

    @AfterTest
    fun cleanup() = host.deleteDatabase()

    @Test
    fun `storage port opens a real SQLite database with WAL`() {
        val conn = host.storage.driver.open(host.storage.databasePath)
        try {
            val mode = conn.prepare("PRAGMA journal_mode=WAL").use { st -> st.step(); st.getText(0) }
            assertEquals("wal", mode.lowercase())
            conn.execSQL("CREATE TABLE t (k TEXT PRIMARY KEY, v INTEGER NOT NULL)")
            conn.prepare("INSERT INTO t (k, v) VALUES (?, ?) RETURNING v").use { st ->
                st.bindText(1, "中文 🙂")
                st.bindLong(2, 42)
                assertTrue(st.step())
                assertEquals(42, st.getLong(0))
            }
            conn.prepare("SELECT k FROM t").use { st ->
                assertTrue(st.step())
                assertEquals("中文 🙂", st.getText(0))
            }
            val version = conn.prepare("SELECT sqlite_version()").use { st -> st.step(); st.getText(0) }
            assertTrue(version.split('.').let { (a, b) -> a.toInt() > 3 || b.toInt() >= 35 }, "SQLite $version supports RETURNING")
        } finally {
            conn.close()
        }
    }

    @Test
    fun `credentials never leak through toString and match by URL prefix`() = runBlocking {
        val credential = host.secrets.credentialFor("${FakeHostPort.FAKE_BASE_URL}/v1/messages")!!
        assertEquals("Credential(****)", credential.toString())
        assertFalse("fake-key" in "$credential")
        assertEquals("fake…6789", credential.masked())
        assertEquals(null, host.secrets.credentialFor("https://other.example/v1"))
    }

    @Test
    fun `M1 null ports are usable`() = runBlocking {
        assertEquals(ToolCatalog.EMPTY, ToolPort.NONE.catalog.value)
        assertEquals(HookOutcome.NONE, HookPort.NONE.dispatch(HookRequest("PreToolUse", "s", "t", kotlinx.serialization.json.JsonObject(emptyMap()))))
        val consent = ConsentPort.DENY_ALL.request(
            ConsentRequest("r", "s", "t", "c", "tool", null, ToolRisk.WRITE, CallerIdentity.SYSTEM, "{}", rememberable = false),
        )
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE), consent)
        assertEquals("uid:10123", CallerIdentity(10123, CallerKind.APP).ownerKey)
        assertEquals("desktop", CallerIdentity(2000, CallerKind.DESKTOP).ownerKey)
    }
}
