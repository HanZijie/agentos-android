package org.agentos.app.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.agentos.app.R
import org.agentos.app.i18n.Strings

/**
 * Desktop access (F11, W9) as returned by IAgentControl v3 getDesktopAccess / newDesktopPairingCode.
 * The words live in `strings_p2.xml` (`desktop_*`), read through [Strings].
 */
object Desktop {
    /** [label] is what the computer called itself (null: it did not say; shown as [pairingLabel]). */
    data class Pairing(val id: String, val label: String?, val pairedAtMs: Long, val lastSeenMs: Long?)
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
                Pairing(p.s("id") ?: return@mapNotNull null, p.s("label"), p.l("pairedAtMs") ?: 0, p.l("lastSeenMs"))
            },
            connections = (o["connections"] as? JsonArray)?.size ?: 0,
        )
    }

    fun parseCode(text: String): Code {
        val o = json.parseToJsonElement(text) as JsonObject
        return Code(o.s("code") ?: "", o.l("expiresAtMs") ?: 0)
    }

    /** Name of a pairing on the settings page. */
    fun pairingLabel(p: Pairing, strings: Strings): String = p.label ?: strings.get(R.string.desktop_default_label)

    /** One line for the settings page. */
    fun summary(a: Access, strings: Strings): String = when {
        !a.enabled -> strings.get(R.string.desktop_summary_off)
        a.listenError != null -> strings.get(R.string.desktop_summary_listen_error, a.listenError, a.socket)
        !a.listening -> strings.get(R.string.desktop_summary_starting)
        else -> strings.get(
            R.string.desktop_summary_on,
            strings.plural(R.plurals.desktop_paired_count, a.pairings.size, a.pairings.size),
            strings.plural(R.plurals.desktop_connection_count, a.connections, a.connections),
        )
    }

    /** "2 computers" / "2 台电脑": the count the disable and revoke-all dialogs name. */
    fun computers(n: Int, strings: Strings): String = strings.plural(R.plurals.desktop_computer_count, n, n)

    /** "3 connections" / "3 个连接". */
    fun connections(n: Int, strings: Strings): String = strings.plural(R.plurals.desktop_connection_count, n, n)

    /** "5 minutes" / "5 分钟". */
    fun minutes(n: Int, strings: Strings): String = strings.plural(R.plurals.desktop_minutes, n, n)

    /**
     * How to connect, shown under the switch (F11). acp-bridge first: it does the adb forward and the pairing
     * handshake; an ordinary ACP client behind a bare `adb forward` never sends the handshake and gets no answer.
     * It ends with "confirmations on the phone still appear, the computer cannot bypass them" (a security statement:
     * both languages carry it, see SettingsLogicTest).
     */
    fun howTo(strings: Strings): String = strings.get(R.string.desktop_how_to)

    /** The command to run on the computer for a pairing code (tools/acp-bridge/README.md). */
    fun pairCommand(code: String): String = "node tools/acp-bridge/acp-bridge.mjs pair $code"

    /** Notification text and button of the foreground service while the switch is on (architecture F11 item 4, C6). */
    fun notificationTitle(strings: Strings): String = strings.get(R.string.desktop_notification_title)
    fun notificationAction(strings: Strings): String = strings.get(R.string.desktop_notification_action)

    /** Shown next to the switch whether it is on or off, so the user knows before turning it on (F11 item 4). */
    fun foregroundNote(strings: Strings): String =
        strings.get(R.string.desktop_foreground_note, notificationTitle(strings), notificationAction(strings))

    /**
     * F11 item 4 (M1): with desktop access on, the runtime relies on the battery optimisation exemption to
     * enter the foreground when it is started in the background; without it the system freezes the idle
     * process and computers stop getting answers. Asked when the switch is turned on, and kept as a
     * warning on the card while it is on and still missing (wording in [BatteryText]).
     */
    fun needsBatteryExemption(enabled: Boolean, exempt: Boolean): Boolean = enabled && !exempt

    fun errorText(message: String?, strings: Strings): String = when {
        message?.startsWith("agentos.desktop.disabled") == true -> strings.get(R.string.desktop_error_disabled)
        else -> strings.get(R.string.settings_operation_failed)
    }

    private fun JsonArray?.orEmpty() = this ?: emptyList()
}
