package org.agentos.app.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Desktop access (F11, W9) as returned by IAgentControl v3 getDesktopAccess / newDesktopPairingCode. */
object Desktop {
    data class Pairing(val id: String, val label: String, val pairedAtMs: Long, val lastSeenMs: Long?)
    data class Access(
        val enabled: Boolean,
        val listening: Boolean,
        val socket: String,
        val listenError: String?,
        val codeExpiresAtMs: Long?,
        val codeAttemptsLeft: Int?,
        val pairings: List<Pairing>,
        val connections: Int,
    )

    data class Code(val code: String, val expiresAtMs: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

    fun parseAccess(text: String): Access {
        val o = json.parseToJsonElement(text) as JsonObject
        val code = o["code"] as? JsonObject
        return Access(
            enabled = o.b("enabled") ?: false,
            listening = o.b("listening") ?: false,
            socket = o.s("socket") ?: "agentos-acp",
            listenError = o.s("listenError"),
            codeExpiresAtMs = code?.l("expiresAtMs"),
            codeAttemptsLeft = (code?.get("attemptsLeft") as? JsonPrimitive)?.intOrNull,
            pairings = (o["pairings"] as? JsonArray).orEmpty().mapNotNull { e ->
                val p = e as? JsonObject ?: return@mapNotNull null
                Pairing(p.s("id") ?: return@mapNotNull null, p.s("label") ?: "电脑", p.l("pairedAtMs") ?: 0, p.l("lastSeenMs"))
            },
            connections = (o["connections"] as? JsonArray)?.size ?: 0,
        )
    }

    fun parseCode(text: String): Code {
        val o = json.parseToJsonElement(text) as JsonObject
        return Code(o.s("code") ?: "", o.l("expiresAtMs") ?: 0)
    }

    /** One line for the settings page. */
    fun summary(a: Access): String = when {
        !a.enabled -> "关闭"
        a.listenError != null -> "打不开监听（${a.listenError}）：可能有别的 App 占用了 ${a.socket}"
        !a.listening -> "已打开，正在启动监听"
        else -> "已打开：${a.pairings.size} 台已配对，${a.connections} 个连接"
    }

    /** How to connect, shown under the switch (F11). */
    const val HOW_TO = "在电脑上执行 adb forward tcp:8765 localabstract:agentos-acp，或直接用项目提供的 acp-bridge 命令；" +
        "第一次连接时输入这里显示的配对码。手机上的确认照常出现，电脑端无法绕过。"

    /** Notification text and button of the foreground service while the switch is on (architecture F11 item 4, C6). */
    const val NOTIFICATION_TITLE = "电脑端接入已开启"
    const val NOTIFICATION_ACTION = "关闭"

    /** Shown next to the switch whether it is on or off, so the user knows before turning it on (F11 item 4). */
    const val FOREGROUND_NOTE = "打开期间 AgentOS 会保持在后台运行，并显示常驻通知“$NOTIFICATION_TITLE”。" +
        "用完记得关闭：关掉这个开关，或点通知上的“$NOTIFICATION_ACTION”。"

    /**
     * F11 item 4 (M1): with desktop access on, the runtime relies on the battery optimisation exemption to
     * enter the foreground when it is started in the background; without it the system freezes the idle
     * process and computers stop getting answers. Asked when the switch is turned on, and kept as a
     * warning on the card while it is on and still missing.
     */
    fun needsBatteryExemption(enabled: Boolean, exempt: Boolean): Boolean = enabled && !exempt

    const val BATTERY_LABEL = "后台运行"
    const val BATTERY_WARNING = "还没有允许：运行时在后台被重新拉起后可能进不了前台、被系统冻结，电脑端会连不上"
    const val BATTERY_ACTION = "允许在后台运行"
    const val BATTERY_DIALOG_TITLE = "允许 AgentOS 在后台运行？"
    const val BATTERY_DIALOG_MESSAGE =
        "电脑端接入打开期间，AgentOS 的运行时要一直在后台待命。没有电池优化豁免时，运行时被系统重新拉起后可能进不了前台、" +
            "被系统冻结，电脑端就连不上。\n\n在接下来的系统弹窗里选择“允许”即可，以后可以在系统设置里撤销。"

    fun errorText(message: String?): String = when {
        message?.startsWith("agentos.desktop.disabled") == true -> "请先打开电脑端接入"
        else -> "操作失败，请重试"
    }

    private fun JsonArray?.orEmpty() = this ?: emptyList()
}
