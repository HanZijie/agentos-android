package org.agentos.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.agentos.runtime.consent.AutoConsentResponder
import org.json.JSONArray
import org.json.JSONObject

/**
 * **debug 包专用的测试入口**（app/src/debug 源集，release 包里没有这个组件）：adb 无人值守地回答 AgentOS 的工具确认。
 *
 * ```
 * adb shell am broadcast -n org.agentos.app/.agent.ConsentDebugReceiver --es op <op> [--es mode allow|allowOnce|deny|off]
 * ```
 *
 * op：`mode`（设置自动应答：`allow` = 允许，能选“本会话内不再询问”就选，否则允许一次；`allowOnce`；`deny`；`off` = 不自动回答）、
 * `status`（当前 mode）、`recent`（最近的请求摘要：工具名、风险、来源、清理并截断后的参数摘要、可选项、自动应答的选择、结案方式；
 * 没有参数全文、没有任何 key）。结果在广播的 result data 里（JSON）。自动应答**从不**选“始终允许”，不会改用户策略。
 * 要求 DUMP 权限（adb shell 和系统有，普通 App 没有）；不可调试的包一律返回 `not_debuggable`。运行在 `:agent`（确认协调器在那里）。
 */
class ConsentDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val process = AgentProcess.get(context)
        val op = intent.getStringExtra("op")
        val responder = process.autoConsent
        val result = try {
            when {
                !process.debuggable || responder == null -> JSONObject().put("ok", false).put("error", "not_debuggable")
                op == "mode" -> {
                    val m = when (intent.getStringExtra("mode")?.lowercase()) {
                        "allow" -> AutoConsentResponder.Mode.ALLOW
                        "allowonce" -> AutoConsentResponder.Mode.ALLOW_ONCE
                        "deny" -> AutoConsentResponder.Mode.DENY
                        "off" -> AutoConsentResponder.Mode.OFF
                        else -> null
                    }
                    if (m == null) {
                        JSONObject().put("ok", false).put("error", "mode must be allow|allowOnce|deny|off")
                    } else {
                        responder.mode = m
                        JSONObject().put("ok", true).put("mode", m.name)
                    }
                }
                op == "status" -> JSONObject().put("ok", true).put("mode", responder.mode.name)
                op == "recent" -> JSONObject().put("ok", true).put("mode", responder.mode.name).put(
                    "recent",
                    JSONArray(
                        responder.recent.value.map { e ->
                            JSONObject()
                                .put("requestId", e.requestId)
                                .put("tool", e.toolName)
                                .put("risk", e.risk.name)
                                .put("source", e.sourceLine ?: JSONObject.NULL)
                                .put("args", e.argumentsSummary)
                                .put("options", JSONArray(e.options.map { it.name }))
                                .put("answeredWith", e.answeredWith?.name ?: JSONObject.NULL)
                                .put("end", e.end?.name ?: JSONObject.NULL)
                                .put("notice", e.notice ?: JSONObject.NULL)
                        },
                    ),
                )
                else -> JSONObject().put("ok", false).put("error", "unknown op: $op")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        }
        setResult(1, result.put("op", op).toString(), null)
    }
}
