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

    /**
     * How to connect, shown under the switch (F11). acp-bridge first: it does the adb forward and the pairing
     * handshake; an ordinary ACP client behind a bare `adb forward` never sends the handshake and gets no answer.
     */
    const val HOW_TO = "推荐用项目提供的 acp-bridge（仓库里的 tools/acp-bridge）：电脑用 USB 连上手机，点下面的“生成配对码”，" +
        "在电脑上运行 acp-bridge pair <配对码> 完成第一次配对；之后把 acp-bridge 设为 ACP 客户端（如 Zed）的 Agent 命令即可。" +
        "只执行 adb forward tcp:8765 localabstract:agentos-acp 的话，普通 ACP 客户端不会发配对握手，连不上。" +
        "手机上的确认照常出现，电脑端无法绕过。"

    /** The command to run on the computer for a pairing code (tools/acp-bridge/README.md). */
    fun pairCommand(code: String): String = "node tools/acp-bridge/acp-bridge.mjs pair $code"

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
     * warning on the card while it is on and still missing (wording in [BatteryText]).
     */
    fun needsBatteryExemption(enabled: Boolean, exempt: Boolean): Boolean = enabled && !exempt

    fun errorText(message: String?): String = when {
        message?.startsWith("agentos.desktop.disabled") == true -> "请先打开电脑端接入"
        else -> "操作失败，请重试"
    }

    private fun JsonArray?.orEmpty() = this ?: emptyList()
}
