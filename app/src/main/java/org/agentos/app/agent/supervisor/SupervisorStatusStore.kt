package org.agentos.app.agent.supervisor

import java.io.File
import java.io.IOException

/**
 * The last supervisor status, kept in device-protected storage at `files/supervisor/status` so the
 * main process (receiver, UI) and `:agent` (via IAgentControl, W6) read the same file, before and after
 * the first unlock. Writes are atomic (tmp + rename); the receiver is the only writer.
 */
class SupervisorStatusStore(private val dir: File) {
    val file: File get() = File(dir, "status")

    fun read(): SupervisorStatus? = try {
        if (file.isFile) SupervisorStatus.fromProperties(file.readText()) else null
    } catch (e: IOException) {
        null
    }

    /** Stores [status] if it is newer than what is stored (contract ordering). Returns true when stored. */
    @Synchronized
    fun offer(status: SupervisorStatus): Boolean {
        if (!status.isNewerThan(read())) return false
        dir.mkdirs()
        val tmp = File(dir, "status.tmp")
        tmp.writeText(status.toProperties())
        if (!tmp.renameTo(file)) {
            file.writeText(status.toProperties())
            tmp.delete()
        }
        return true
    }
}
