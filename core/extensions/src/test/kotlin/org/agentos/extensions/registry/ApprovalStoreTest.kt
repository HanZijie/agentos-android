package org.agentos.extensions.registry

import org.agentos.runtime.broker.ApprovalMode
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.ports.ToolSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovalStoreTest {
    private val dir: File = Files.createTempDirectory("approval-store").toFile()
    private val policyFile = File(dir, "approvals.json")
    private val backupFile = File(dir, "approvals.json.bak")
    private val notes = ToolSource("notes", "main", "create")
    private val agentos = ToolSource("agentos", "builtin", "open_app")

    private fun store(backup: Boolean = false, builtin: Set<String> = setOf("agentos")) =
        ApprovalStore(FileBackedTextFile(policyFile), if (backup) FileBackedTextFile(backupFile) else null, builtin)

    /** 按脚本失败的假文件。 */
    private class FlakyFile(var content: String? = null, var failWrites: Boolean = false, var failReads: Boolean = false) : AtomicTextFile {
        override fun readText(): String? = if (failReads) throw IOException("disk") else content

        override fun writeAtomically(text: String) {
            if (failWrites) throw IOException("disk full")
            content = text
        }
    }

    @Test
    fun `a first run has no file and means all defaults`() {
        val s = store()
        assertEquals(ApprovalPolicy.DEFAULT, s.policy.value)
        assertEquals(PolicyHealth.Ok, s.health.value)
        assertFalse(policyFile.exists(), "nothing is written until something changes")
    }

    @Test
    fun `an update is written atomically, published, and survives a restart`() {
        val s = store()
        val next = s.update { it.withEnabled(PolicyScope.Plugin("notes"), true).withApproval(PolicyScope.Plugin("notes"), ApprovalMode.ALWAYS) }
        assertEquals(next, s.policy.value)
        assertTrue(s.policy.value.resolve(notes).enabled)
        assertEquals(next.toJson(), policyFile.readText())
        assertEquals(listOf("approvals.json"), dir.list()!!.sorted(), "no temp file is left behind")
        // “重启”：新的 store 读同一个文件
        assertEquals(next, store().policy.value)
    }

    @Test
    fun `a failed write leaves the policy and the file as they were`() {
        val file = FlakyFile()
        val s = ApprovalStore(file)
        val good = s.update { it.withEnabled(PolicyScope.Plugin("notes"), true) }
        val before = file.content
        file.failWrites = true
        assertFailsWith<IOException> { s.update { it.withEnabled(PolicyScope.Plugin("notes"), false) } }
        assertEquals(good, s.policy.value, "not published")
        assertEquals(before, file.content)
        assertEquals(PolicyHealth.Ok, s.health.value)
    }

    @Test
    fun `a write that cannot even create its temp file never touches the target`() {
        val s = store()
        val first = s.update { it.withEnabled(PolicyScope.Plugin("notes"), true) }
        // 临时文件的路径被一个目录占着：写临时文件就失败
        assertTrue(File(dir, "approvals.json.tmp").mkdir())
        assertFailsWith<IOException> { s.update { it.withEnabled(PolicyScope.Plugin("notes"), false) } }
        assertEquals(first.toJson(), policyFile.readText())
        assertEquals(first, s.policy.value)
    }

    @Test
    fun `a broken file with nothing to fall back on fails closed, builtin plugins stay enabled`() {
        policyFile.writeText("{ this is not json")
        val s = store()
        val h = assertIs<PolicyHealth.Corrupt>(s.health.value)
        assertEquals(PolicyHealth.Using.FAIL_CLOSED, h.using)
        assertFalse(s.policy.value.resolve(notes).enabled, "third party tools are off")
        assertFalse(s.policy.value.resolve(ToolSource("never-seen", "s", "t")).enabled)
        assertTrue(s.policy.value.resolve(agentos).enabled, "the builtin plugin keeps working")
        assertTrue(s.policy.value.resolve(null).enabled, "tools without a plugin are not plugins")
        assertFailsWith<IllegalStateException> { s.update { it } }
        assertEquals("{ this is not json", policyFile.readText(), "the broken file is not overwritten behind the user's back")
    }

    @Test
    fun `every kind of unreadable file is a failure, not the default`() {
        for (bad in listOf("", "[]", "{}", """{"plugins":{}}""", """{"version":"1"}""", """{"version":1,"plugins":[]}""")) {
            policyFile.writeText(bad)
            val s = store()
            assertIs<PolicyHealth.Corrupt>(s.health.value, bad)
            assertFalse(s.policy.value.resolve(notes).enabled, bad)
        }
        val unreadable = ApprovalStore(FlakyFile(failReads = true))
        assertEquals(PolicyHealth.Using.FAIL_CLOSED, (unreadable.health.value as PolicyHealth.Corrupt).using)
    }

    @Test
    fun `a file that goes bad later keeps the last good policy`() {
        val s = store()
        val good = s.update { it.withEnabled(PolicyScope.Plugin("notes"), true) }
        policyFile.writeText("garbage")
        val h = s.load()
        assertEquals(PolicyHealth.Using.PREVIOUS, (h as PolicyHealth.Corrupt).using)
        assertEquals(good, s.policy.value)
        assertTrue(s.policy.value.resolve(notes).enabled)
        // 之后的写入成功就回到正常
        val next = s.update { it.withApproval(PolicyScope.Plugin("notes"), ApprovalMode.ALWAYS) }
        assertEquals(PolicyHealth.Ok, s.health.value)
        assertEquals(next.toJson(), policyFile.readText())
    }

    @Test
    fun `after a restart a broken file falls back to the backup`() {
        val s = store(backup = true)
        val good = s.update { it.withEnabled(PolicyScope.Plugin("notes"), true) }
        assertEquals(good.toJson(), backupFile.readText())
        policyFile.writeText("garbage")
        val restarted = store(backup = true)
        assertEquals(PolicyHealth.Using.BACKUP, (restarted.health.value as PolicyHealth.Corrupt).using)
        assertEquals(good, restarted.policy.value)
        // 备份也坏了才 fail closed
        backupFile.writeText("also garbage")
        assertEquals(PolicyHealth.Using.FAIL_CLOSED, (store(backup = true).health.value as PolicyHealth.Corrupt).using)
    }

    @Test
    fun `a backup that cannot be written does not fail the update`() {
        val backup = FlakyFile(failWrites = true)
        val s = ApprovalStore(FlakyFile(), backup)
        s.update { it.withEnabled(PolicyScope.Plugin("notes"), true) }
        assertEquals("IOException", s.lastBackupError)
        backup.failWrites = false
        s.update { it.withEnabled(PolicyScope.Plugin("notes"), false) }
        assertNull(s.lastBackupError)
    }

    @Test
    fun `reset after a failure writes a fresh policy and keeps the listed plugins switched off`() {
        policyFile.writeText("garbage")
        val s = store()
        val fresh = s.resetToDefault(disablePlugins = listOf("notes", "alarm"))
        assertEquals(PolicyHealth.Ok, s.health.value)
        assertFalse(s.policy.value.resolve(notes).enabled)
        assertTrue(s.policy.value.resolve(agentos).enabled)
        assertEquals(fresh, store().policy.value)
        // 重置之后又可以正常改了
        s.update { it.withEnabled(PolicyScope.Plugin("notes"), true) }
        assertTrue(s.policy.value.resolve(notes).enabled)
    }

    @Test
    fun `the fail closed flag is never written to disk`() {
        policyFile.writeText("garbage")
        val s = store()
        s.resetToDefault()
        assertFalse(policyFile.readText().contains("unlisted"))
        assertTrue(store().policy.value.unlistedPluginsEnabled)
        assertTrue(ApprovalPolicy(unlistedPluginsEnabled = false).toJson() == ApprovalPolicy.DEFAULT.toJson())
        assertTrue(ApprovalPolicy.fromJson(ApprovalPolicy(unlistedPluginsEnabled = false).toJson()).unlistedPluginsEnabled)
    }

    @Test
    fun `it is the policy port the broker reads, and subscribers see every update`() {
        val s = store()
        val port: org.agentos.runtime.ports.ApprovalPolicyPort = s
        assertTrue(port.policy.value.resolve(notes).enabled)
        s.update { it.withEnabled(PolicyScope.of(notes), false) }
        assertFalse(port.policy.value.resolve(notes).enabled)
        s.update { it.cleared(PolicyScope.of(notes)) }
        assertTrue(port.policy.value.resolve(notes).enabled)
    }

    @Test
    fun `file backed text file writes and replaces`() {
        val f = FileBackedTextFile(File(dir, "sub/dir/x.txt"))
        assertNull(f.readText())
        f.writeAtomically("a")
        f.writeAtomically("日本語 b")
        assertEquals("日本語 b", f.readText())
    }
}
