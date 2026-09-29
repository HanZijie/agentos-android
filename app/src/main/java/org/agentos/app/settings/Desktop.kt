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

    fun errorText(message: String?): String = when {
        message?.startsWith("agentos.desktop.disabled") == true -> "请先打开电脑端接入"
        else -> "操作失败，请重试"
    }

    private fun JsonArray?.orEmpty() = this ?: emptyList()
}
