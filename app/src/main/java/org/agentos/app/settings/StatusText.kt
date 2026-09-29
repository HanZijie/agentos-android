package org.agentos.app.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Human-readable runtime and supervisor status for the settings page, from IAgentControl
 * getRuntimeStatus / getSupervisorStatus / getDiagnostics (supervision contract v0.2, docs/spikes/S2.md).
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

    fun runtime(statusJson: String?): List<Line> {
        val o = parse(statusJson) ?: return listOf(Line("运行时", "读不到状态", warn = true))
        val phase = when (o.s("phase")) {
            "STARTING" -> "启动中"
            "RECOVERING" -> "恢复中"
            "READY" -> "就绪"
            else -> o.s("phase") ?: "未知"
        }
        val tasks = o.i("tasks") ?: 0
        val lines = mutableListOf(
            Line("运行时", "$phase（pid ${o.i("pid") ?: "?"}，已运行 ${duration(o.l("uptimeMs") ?: 0)}）"),
            Line("进行中的任务", if (tasks == 0) "无" else "$tasks 个" + if (o.b("foreground") == true) "，前台运行" else ""),
        )
        if (o.s("foregroundHold") == "desktop_access") {
            // no task, but kept in the foreground for desktop access (F11 item 4, RuntimeLifecycle rule 6)
            lines += Line("保持前台", "为电脑端接入保持前台")
        }
        if (o.b("foregroundDenied") == true) {
            lines += Line("前台服务", "系统拒绝了前台启动，由监督进程代为拉起；建议允许 AgentOS 在后台运行（电池优化豁免）", warn = true)
        }
        return lines
    }

    /**
     * [supervisorJson] is getSupervisorStatus(); [supervisorMissing] comes from getDiagnostics
     * (the runtime has run 30 s without a report from this boot); [currentBootCount] is
     * Settings.Global.BOOT_COUNT, used to tell a status stored in an earlier boot from the current one.
     */
    fun supervisor(supervisorJson: String?, supervisorMissing: Boolean, currentBootCount: Int? = null): List<Line> {
        val o = parse(supervisorJson)
        if (o == null || o.s("state") == null) {
            return listOf(
                Line(
                    "监督进程",
                    if (supervisorMissing) MISSING else "还没有收到监督状态",
                    warn = supervisorMissing,
                ),
            )
        }
        val state = o.s("state")
        val reason = o.s("reason") ?: ""
        val text = when (state) {
            "ok" -> "正常"
            "backoff" -> "运行时异常退出，正在按退避重新拉起（10 分钟内 ${o.i("deaths") ?: 0} 次）"
            "safe_mode" -> "安全模式：" + when (reason) {
                "crash_loop" -> "运行时短时间内反复崩溃"
                "manual" -> "手动进入"
                "unsupported_api" -> "系统版本超出支持范围（可能刚做过系统更新）"
                else -> reason
            } + "。运行时不会被自动拉起，恢复出来的任务不会自动继续；在 root 管理器里用 AgentOS 模块的“动作”按钮退出"
            "stopped" -> "已停止：" + when (reason) {
                "user_stopped" -> "AgentOS 被强行停止过"
                "not_launched" -> "等待首次打开"
                "module_disabled" -> "模块已禁用"
                "supervisor_exited" -> "监督进程已退出"
                "install_failed" -> "模块安装 App 失败"
                else -> reason
            }
            else -> state ?: "未知"
        }
        val lines = mutableListOf<Line>()
        val bootCount = o.i("boot_count")
        val stale = supervisorMissing || (currentBootCount != null && bootCount != null && bootCount != currentBootCount)
        if (stale) {
            // the stored status is from an earlier boot: do not present it as the current state
            lines += if (supervisorMissing) Line("监督进程", MISSING, warn = true) else Line("监督进程", "等待本次开机的监督状态")
            lines += Line("上次收到的状态（不是本次开机的）", text)
        } else {
            lines += Line("监督进程", text, warn = state != "ok")
        }
        o.s("module_version")?.let { v -> lines += Line("模块版本", "$v（${o.i("module_version_code") ?: 0}）") }
        return lines
    }

    private const val MISSING =
        "未运行：没有收到本次开机的监督状态。AgentOS 模块可能被禁用或没有安装；运行时照常可用，但被杀后不会自动拉起"

    fun supervisorMissing(diagnosticsJson: String?): Boolean = parse(diagnosticsJson)?.b("supervisorMissing") == true

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s 秒"
            s < 3600 -> "${s / 60} 分钟"
            s < 86_400 -> "${s / 3600} 小时 ${s % 3600 / 60} 分钟"
            else -> "${s / 86_400} 天 ${s % 86_400 / 3600} 小时"
        }
    }
}
