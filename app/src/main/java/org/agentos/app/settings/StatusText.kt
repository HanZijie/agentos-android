package org.agentos.app.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.agentos.app.R
import org.agentos.app.i18n.Strings

/**
 * Human-readable runtime and supervisor status for the settings page, from IAgentControl
 * getRuntimeStatus / getSupervisorStatus / getDiagnostics (supervision contract v0.2, docs/spikes/S2.md).
 * The words live in `strings_p2.xml` (`status_*`), read through [Strings].
 */
object StatusText {
    data class Line(val label: String, val value: String, val warn: Boolean = false)

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(text: String?): JsonObject? =
        text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

    /**
     * True when the root supervisor has reported in this boot (any state, even "not launched yet"):
     * the AgentOS module is running as root on this phone. False with no report, or with one stored in
     * an earlier boot: no module, module disabled, phone not rooted, or not reported yet.
     */
    fun supervisorThisBoot(supervisorJson: String?, currentBootCount: Int?): Boolean {
        val o = parse(supervisorJson) ?: return false
        if (o.s("state") == null || currentBootCount == null) return false
        return o.i("boot_count") == currentBootCount
    }

    /** getRuntimeStatus `foregroundDenied`: the system refused the last foreground start. */
    fun foregroundDenied(statusJson: String?): Boolean = parse(statusJson)?.b("foregroundDenied") == true

    /** [batteryExempt]: whether the App has the battery optimisation exemption (words the foregroundDenied line). */
    fun runtime(statusJson: String?, strings: Strings, batteryExempt: Boolean = false): List<Line> {
        val label = strings.get(R.string.status_runtime_label)
        val o = parse(statusJson) ?: return listOf(Line(label, strings.get(R.string.status_runtime_unreadable), warn = true))
        val phase = when (o.s("phase")) {
            "STARTING" -> strings.get(R.string.status_phase_starting)
            "RECOVERING" -> strings.get(R.string.status_phase_recovering)
            "READY" -> strings.get(R.string.status_phase_ready)
            else -> o.s("phase") ?: strings.get(R.string.status_unknown)
        }
        val tasks = o.i("tasks") ?: 0
        val tasksValue = when {
            tasks == 0 -> strings.get(R.string.status_tasks_none)
            o.b("foreground") == true -> strings.plural(R.plurals.status_tasks_count_foreground, tasks, tasks)
            else -> strings.plural(R.plurals.status_tasks_count, tasks, tasks)
        }
        val lines = mutableListOf(
            Line(label, strings.get(R.string.status_runtime_value, phase, o.i("pid")?.toString() ?: "?", duration(o.l("uptimeMs") ?: 0, strings))),
            Line(strings.get(R.string.status_tasks_label), tasksValue),
        )
        if (o.s("foregroundHold") == "desktop_access") {
            // no task, but kept in the foreground for desktop access (F11 item 4, RuntimeLifecycle rule 6);
            // foregroundHold is the reason, "foreground" whether the service actually is in the foreground
            lines += if (o.b("foreground") == true) {
                Line(strings.get(R.string.status_hold_label), strings.get(R.string.status_hold_ok))
            } else {
                Line(strings.get(R.string.status_hold_label), strings.get(R.string.status_hold_not_foreground), warn = true)
            }
        }
        if (o.b("foregroundDenied") == true) {
            lines += Line(BatteryText.deniedLabel(strings), BatteryText.denied(batteryExempt, strings), warn = !batteryExempt)
        }
        return lines
    }

    /**
     * [supervisorJson] is getSupervisorStatus(); [supervisorMissing] comes from getDiagnostics
     * (the runtime has run 30 s without a report from this boot); [currentBootCount] is
     * Settings.Global.BOOT_COUNT, used to tell a status stored in an earlier boot from the current one.
     */
    fun supervisor(supervisorJson: String?, supervisorMissing: Boolean, strings: Strings, currentBootCount: Int? = null): List<Line> {
        val label = strings.get(R.string.status_supervisor_label)
        val missing = strings.get(R.string.status_supervisor_missing)
        val o = parse(supervisorJson)
        if (o == null || o.s("state") == null) {
            return listOf(
                Line(
                    label,
                    if (supervisorMissing) missing else strings.get(R.string.status_supervisor_none_yet),
                    warn = supervisorMissing,
                ),
            )
        }
        val state = o.s("state")
        val reason = o.s("reason") ?: ""
        val text = when (state) {
            "ok" -> strings.get(R.string.status_supervisor_ok)
            "backoff" -> {
                val deaths = o.i("deaths") ?: 0
                strings.plural(R.plurals.status_supervisor_backoff, deaths, deaths)
            }
            "safe_mode" -> strings.get(
                R.string.status_supervisor_safe_mode,
                when (reason) {
                    "crash_loop" -> strings.get(R.string.status_safe_reason_crash_loop)
                    "manual" -> strings.get(R.string.status_safe_reason_manual)
                    "unsupported_api" -> strings.get(R.string.status_safe_reason_unsupported_api)
                    else -> reason
                },
            )
            "stopped" -> strings.get(
                R.string.status_supervisor_stopped,
                when (reason) {
                    "user_stopped" -> strings.get(R.string.status_stopped_reason_user_stopped)
                    "not_launched" -> strings.get(R.string.status_stopped_reason_not_launched)
                    "module_disabled" -> strings.get(R.string.status_stopped_reason_module_disabled)
                    "supervisor_exited" -> strings.get(R.string.status_stopped_reason_supervisor_exited)
                    "install_failed" -> strings.get(R.string.status_stopped_reason_install_failed)
                    else -> reason
                },
            )
            else -> state ?: strings.get(R.string.status_unknown)
        }
        val lines = mutableListOf<Line>()
        val bootCount = o.i("boot_count")
        val stale = supervisorMissing || (currentBootCount != null && bootCount != null && bootCount != currentBootCount)
        if (stale) {
            // the stored status is from an earlier boot: do not present it as the current state
            lines += if (supervisorMissing) Line(label, missing, warn = true) else Line(label, strings.get(R.string.status_supervisor_waiting))
            lines += Line(strings.get(R.string.status_supervisor_last_label), text)
        } else {
            lines += Line(label, text, warn = state != "ok")
        }
        o.s("module_version")?.let { v ->
            lines += Line(strings.get(R.string.status_module_version_label), strings.get(R.string.status_module_version_value, v, o.i("module_version_code") ?: 0))
        }
        return lines
    }

    fun supervisorMissing(diagnosticsJson: String?): Boolean = parse(diagnosticsJson)?.b("supervisorMissing") == true

    fun duration(ms: Long, strings: Strings): String {
        val s = ms / 1000
        return when {
            s < 60 -> strings.get(R.string.status_duration_seconds, s)
            s < 3600 -> strings.get(R.string.status_duration_minutes, s / 60)
            s < 86_400 -> strings.get(R.string.status_duration_hours, s / 3600, s % 3600 / 60)
            else -> strings.get(R.string.status_duration_days, s / 86_400, s % 86_400 / 3600)
        }
    }
}
