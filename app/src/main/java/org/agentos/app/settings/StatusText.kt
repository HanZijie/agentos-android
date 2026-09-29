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
        if (o.b("foregroundDenied") == true) {
            lines += Line("前台服务", "系统拒绝了前台启动，由监督进程代为拉起；建议在首次引导里允许忽略电池优化", warn = true)
        }
        return lines
    }

    /**
     * [supervisorJson] is getSupervisorStatus(); [supervisorMissing] comes from getDiagnostics
     * (the runtime has run 30 s without a report from this boot).
     */
    fun supervisor(supervisorJson: String?, supervisorMissing: Boolean): List<Line> {
        val o = parse(supervisorJson)
        if (o == null || o.s("state") == null) {
            return listOf(
                Line(
                    "监督进程",
                    if (supervisorMissing) "未运行：没有收到本次开机的监督状态。AgentOS 模块可能被禁用或没有安装；运行时照常可用，但被杀后不会自动拉起"
                    else "还没有收到监督状态",
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
        val warn = state != "ok" || supervisorMissing
        val lines = mutableListOf(Line("监督进程", text, warn))
        o.s("module_version")?.let { v -> lines += Line("模块版本", "$v（${o.i("module_version_code") ?: 0}）") }
        if (supervisorMissing) lines += Line("注意", "本次开机还没有收到监督状态，监督进程可能没有运行", warn = true)
        return lines
    }

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
