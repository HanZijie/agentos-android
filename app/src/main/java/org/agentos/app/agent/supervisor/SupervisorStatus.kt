package org.agentos.app.agent.supervisor

/**
 * One status report from the root supervisor (module/service.sh), contract item e in
 * docs/spikes/S2.md. Pure Kotlin so the parsing and ordering rules are unit-tested on the JVM.
 *
 * The App only trusts what it can check: a known protocol version, a state from a fixed set, and a
 * reason made of [a-z0-9_]. Everything else is optional and defaults to "unknown".
 */
data class SupervisorStatus(
    val protocol: Int,
    val state: State,
    val reason: String,
    val seq: Long,
    val sinceEpochMillis: Long,
    val deaths: Int,
    val bootCount: Int,
    val moduleVersion: String,
    val moduleVersionCode: Int,
    val runtimePid: Int,
    /** When the App received it (System.currentTimeMillis), not sent by the supervisor. */
    val receivedAtMillis: Long,
) {
    enum class State(val wire: String) {
        OK("ok"),
        BACKOFF("backoff"),
        SAFE_MODE("safe_mode"),
        STOPPED("stopped");

        companion object {
            fun fromWire(value: String?): State? = entries.firstOrNull { it.wire == value }
        }
    }

    val inSafeMode: Boolean get() = state == State.SAFE_MODE

    /**
     * Ordering rule from the contract: (BOOT_COUNT, SEQ). SEQ restarts at 1 with every supervisor
     * process, BOOT_COUNT separates boots; a report from an older boot never replaces a newer one.
     */
    fun isNewerThan(other: SupervisorStatus?): Boolean {
        if (other == null) return true
        if (bootCount != other.bootCount) return bootCount > other.bootCount
        return seq > other.seq
    }

    /**
     * JSON for IAgentControl.getSupervisorStatus (W6 → W8 settings page). Keys are the property keys;
     * all string values are restricted to [A-Za-z0-9._-] by parsing, so no escaping is needed.
     */
    fun toJson(): String = buildString {
        append('{')
        append("\"protocol\":").append(protocol)
        append(",\"state\":\"").append(state.wire).append('"')
        append(",\"reason\":\"").append(reason).append('"')
        append(",\"seq\":").append(seq)
        append(",\"since\":").append(sinceEpochMillis)
        append(",\"deaths\":").append(deaths)
        append(",\"boot_count\":").append(bootCount)
        append(",\"module_version\":\"").append(moduleVersion).append('"')
        append(",\"module_version_code\":").append(moduleVersionCode)
        append(",\"runtime_pid\":").append(runtimePid)
        append(",\"received_at\":").append(receivedAtMillis)
        append('}')
    }

    /** "key=value" lines for the DE-storage status file (read by :agent and the UI). */
    fun toProperties(): String = buildString {
        append("protocol=").append(protocol).append('\n')
        append("state=").append(state.wire).append('\n')
        append("reason=").append(reason).append('\n')
        append("seq=").append(seq).append('\n')
        append("since=").append(sinceEpochMillis).append('\n')
        append("deaths=").append(deaths).append('\n')
        append("boot_count=").append(bootCount).append('\n')
        append("module_version=").append(moduleVersion).append('\n')
        append("module_version_code=").append(moduleVersionCode).append('\n')
        append("runtime_pid=").append(runtimePid).append('\n')
        append("received_at=").append(receivedAtMillis).append('\n')
    }

    companion object {
        const val ACTION = "org.agentos.action.SUPERVISOR_STATUS"
        const val PERMISSION = "org.agentos.permission.SEND_SUPERVISOR_STATUS"
        const val PROTOCOL = 1

        private const val P = "org.agentos.extra."
        const val EXTRA_PROTOCOL = P + "PROTOCOL"
        const val EXTRA_STATE = P + "STATE"
        const val EXTRA_REASON = P + "REASON"
        const val EXTRA_SEQ = P + "SEQ"
        const val EXTRA_SINCE = P + "SINCE"
        const val EXTRA_DEATHS = P + "DEATHS"
        const val EXTRA_BOOT_COUNT = P + "BOOT_COUNT"
        const val EXTRA_MODULE_VERSION = P + "MODULE_VERSION"
        const val EXTRA_MODULE_VERSION_CODE = P + "MODULE_VERSION_CODE"
        const val EXTRA_RUNTIME_PID = P + "RUNTIME_PID"

        private val REASON_PATTERN = Regex("[a-z0-9_]{1,64}")
        private val VERSION_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")

        /**
         * Builds a status from broadcast extras. [extra] returns the raw extra value (Intent extras are
         * typed: Int, Long, String). Returns null for anything the App must not act on.
         */
        fun fromExtras(extra: (String) -> Any?, receivedAtMillis: Long): SupervisorStatus? {
            val protocol = (extra(EXTRA_PROTOCOL) as? Int) ?: return null
            if (protocol != PROTOCOL) return null
            val state = State.fromWire(extra(EXTRA_STATE) as? String) ?: return null
            val reason = (extra(EXTRA_REASON) as? String)?.takeIf { REASON_PATTERN.matches(it) } ?: "unknown"
            val seq = (extra(EXTRA_SEQ) as? Long)?.takeIf { it > 0 } ?: return null
            return SupervisorStatus(
                protocol = protocol,
                state = state,
                reason = reason,
                seq = seq,
                sinceEpochMillis = (extra(EXTRA_SINCE) as? Long) ?: 0L,
                deaths = (extra(EXTRA_DEATHS) as? Int)?.coerceAtLeast(0) ?: 0,
                bootCount = (extra(EXTRA_BOOT_COUNT) as? Int) ?: -1,
                moduleVersion = (extra(EXTRA_MODULE_VERSION) as? String)
                    ?.takeIf { VERSION_PATTERN.matches(it) } ?: "unknown",
                moduleVersionCode = (extra(EXTRA_MODULE_VERSION_CODE) as? Int) ?: 0,
                runtimePid = (extra(EXTRA_RUNTIME_PID) as? Int)?.coerceAtLeast(0) ?: 0,
                receivedAtMillis = receivedAtMillis,
            )
        }

        /** Parses [toProperties] output; null when the file is missing, damaged or from another protocol. */
        fun fromProperties(text: String): SupervisorStatus? {
            val map = text.lineSequence()
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                .toMap()
            val protocol = map["protocol"]?.toIntOrNull() ?: return null
            if (protocol != PROTOCOL) return null
            return SupervisorStatus(
                protocol = protocol,
                state = State.fromWire(map["state"]) ?: return null,
                reason = map["reason"]?.takeIf { REASON_PATTERN.matches(it) } ?: "unknown",
                seq = map["seq"]?.toLongOrNull() ?: return null,
                sinceEpochMillis = map["since"]?.toLongOrNull() ?: 0L,
                deaths = map["deaths"]?.toIntOrNull() ?: 0,
                bootCount = map["boot_count"]?.toIntOrNull() ?: -1,
                moduleVersion = map["module_version"]?.takeIf { VERSION_PATTERN.matches(it) } ?: "unknown",
                moduleVersionCode = map["module_version_code"]?.toIntOrNull() ?: 0,
                runtimePid = map["runtime_pid"]?.toIntOrNull() ?: 0,
                receivedAtMillis = map["received_at"]?.toLongOrNull() ?: 0L,
            )
        }

        /**
         * "Supervisor not running" (contract e): the runtime has been up for 30 s and no report from the
         * current boot has arrived. [runtimeUpMillis] is how long the calling process has been running.
         */
        fun supervisorMissing(last: SupervisorStatus?, currentBootCount: Int, runtimeUpMillis: Long): Boolean =
            runtimeUpMillis >= 30_000L && (last == null || last.bootCount != currentBootCount)
    }
}
