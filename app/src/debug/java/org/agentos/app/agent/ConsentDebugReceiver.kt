package org.agentos.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.agentos.app.agent.consent.ConsentWire
import org.agentos.runtime.consent.AutoConsentResponder
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

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
 *
 * 不需要任何插件就能制造确认请求并读到决定（D5.2 的设备测试、界面的人工验证）：
 * - `inject`：向协调器发一条确认请求，立刻返回 requestId，决定之后用 `decision` 读。参数：`--es risk read|write|high`（默认 write）、
 *   `--es tool <名字>`、`--es args <参数文本>`（不是 key，参数摘要会被协调器清理、截断）、`--es plugin/server`（带来源 → 可以有“始终允许”，
 *   默认 plugin=debug server=debug）、`--es caller app|desktop|self`（默认 app，包名 `--es pkg`）、`--el timeoutMs`（默认 60000）、
 *   `--ez remember true|false`（默认 true）。
 * - `pending`：协调器现在待确认的请求（ConsentView 的显示字段：标题、发起者、来源、风险、选项、剩余毫秒）。
 * - `respond`：`--es id <requestId> --es choice ALLOW_ONCE|ALLOW_FOR_SESSION|ALWAYS_ALLOW|DENY`（与对话框、通知按钮走同一个协调器入口）。
 * - `decision`：`--es id <requestId>`：pending / allow / allowForSession / deny:user|timeout|unavailable。
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
                op == "inject" -> inject(process, intent)
                op == "pending" -> JSONObject().put("ok", true).put(
                    "pending",
                    JSONArray(
                        process.consent.pending.value.map {
                            JSONObject(ConsentWire.encodeViewString(it)).put("remainingMs", it.deadlineMillis - System.currentTimeMillis())
                        },
                    ),
                )
                op == "respond" -> {
                    val choice = ConsentWire.parseChoice(intent.getStringExtra("choice"))
                    val id = intent.getStringExtra("id")
                    if (choice == null || id == null) {
                        JSONObject().put("ok", false).put("error", "need --es id and --es choice ALLOW_ONCE|ALLOW_FOR_SESSION|ALWAYS_ALLOW|DENY")
                    } else {
                        JSONObject().put("ok", true).put("accepted", process.consent.respond(id, choice))
                    }
                }
                op == "decision" -> JSONObject().put("ok", true).put("decision", decisions[intent.getStringExtra("id")] ?: "unknown")
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

    /** 向协调器发一条确认请求（和 CapabilityBroker 走的是同一个 ConsentPort.request）。 */
    private fun inject(process: AgentProcess, intent: Intent): JSONObject {
        val risk = when (intent.getStringExtra("risk")?.lowercase()) {
            "read" -> ToolRisk.READ
            "high" -> ToolRisk.HIGH
            null, "write" -> ToolRisk.WRITE
            else -> return JSONObject().put("ok", false).put("error", "risk must be read|write|high")
        }
        val kind = when (intent.getStringExtra("caller")?.lowercase()) {
            "desktop" -> CallerKind.DESKTOP
            "self" -> CallerKind.SELF
            null, "app" -> CallerKind.APP
            else -> return JSONObject().put("ok", false).put("error", "caller must be app|desktop|self")
        }
        val id = "dbg_" + java.util.UUID.randomUUID().toString().take(8)
        val pkg = intent.getStringExtra("pkg") ?: "org.agentos.debug.caller"
        val tool = intent.getStringExtra("tool") ?: "debug_tool"
        val request = ConsentRequest(
            requestId = id, sessionId = "ses_debug", taskId = "tsk_debug", toolCallId = "call_$id",
            toolName = "mcp__debug__$tool", toolTitle = tool, risk = risk,
            caller = CallerIdentity(uid = if (kind == CallerKind.SELF) android.os.Process.myUid() else 10999, kind = kind, label = pkg),
            argumentsPreview = intent.getStringExtra("args") ?: "{\"note\":\"debug\"}",
            rememberable = intent.getBooleanExtra("remember", true),
            timeoutMillis = intent.getLongExtra("timeoutMs", ConsentRequest.DEFAULT_TIMEOUT_MILLIS),
            source = ToolSource(intent.getStringExtra("plugin") ?: "debug", intent.getStringExtra("server") ?: "debug", tool),
        )
        decisions[id] = "pending"
        scope.launch {
            val d = process.consent.request(request)
            decisions[id] = when (d) {
                is ConsentDecision.Allow -> if (d.rememberForSession) "allowForSession" else "allow"
                is ConsentDecision.Deny -> "deny:" + d.reason.name.lowercase()
            }
        }
        return JSONObject().put("ok", true).put("requestId", id)
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decisions = ConcurrentHashMap<String, String>()
    }
}
