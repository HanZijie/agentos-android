package org.agentos.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.agentos.app.agent.consent.ConsentWire
import org.agentos.app.i18n.AndroidStrings
import org.agentos.app.i18n.Strings
import org.agentos.runtime.consent.AutoConsentResponder
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
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
        // 设备测试脚本（tests/device/acp-channel/*.py）按中文整句比对 title / initiatorLine / source 等字段：这里固定用中文渲染，
        // 不跟界面语言（界面切到英文时脚本的判据不能变）。线上的 wire 格式是 key + 参数，这里只是把它在测试出口展开。
        val zh: Strings = AndroidStrings.forLocale(context, Locale.SIMPLIFIED_CHINESE)
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
                                .put("title", zh.get(it.title))
                                .put("initiatorLine", zh.get(it.initiatorLine))
                                .put("sourceLine", it.sourceLine?.let { ref -> zh.get(ref) } ?: JSONObject.NULL)
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
                op == "auth_inject" -> authInject(process, intent)
                op == "auth_answer" -> {
                    val id = intent.getStringExtra("id")
                    val allow = intent.getStringExtra("allow")
                    if (id == null || allow !in listOf("true", "false")) {
                        JSONObject().put("ok", false).put("error", "need --es id and --es allow true|false")
                    } else {
                        JSONObject().put("ok", true).put("accepted", authAnswer(process, id, allow == "true"))
                    }
                }
                op == "auth_decision" -> JSONObject().put("ok", true).put("decision", authDecisions[intent.getStringExtra("id")] ?: "unknown")
                op == "decision" -> JSONObject().put("ok", true).put("decision", decisions[intent.getStringExtra("id")] ?: "unknown")
                op == "recent" -> JSONObject().put("ok", true).put("mode", responder.mode.name).put(
                    "recent",
                    JSONArray(
                        responder.recent.value.map { e ->
                            JSONObject()
                                .put("requestId", e.requestId)
                                .put("tool", e.toolName)
                                .put("risk", e.risk.name)
                                .put("source", e.sourceLine?.let { zh.get(it) } ?: JSONObject.NULL)
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

    /**
     * 向授权提示的界面接缝发一条授权请求（stand-in for C 的 CallerRegistry）：`--es pkg`（默认 org.agentos.debug.app）、`--es label`（可选，
     * 不给就让界面自己解析）、`--es digest`（64 位十六进制，默认固定值）、`--ez changed true`（签名变了）、`--el timeoutMs`（默认 30000）。
     * 回答用 `auth_answer`（或界面上点）；到点没有回答按拒绝。决定用 `auth_decision` 读：pending / allow / deny:user / deny:timeout。
     */
    private fun authInject(process: AgentProcess, intent: Intent): JSONObject {
        val id = "dbga_" + java.util.UUID.randomUUID().toString().take(8)
        val timeout = intent.getLongExtra("timeoutMs", 30_000L)
        val req = ConsentWire.AuthRequest(
            requestId = id,
            packageName = intent.getStringExtra("pkg") ?: "org.agentos.debug.app",
            appLabel = intent.getStringExtra("label"),
            signingDigest = intent.getStringExtra("digest") ?: "7920a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e",
            signatureChanged = intent.getBooleanExtra("changed", false),
            deadlineMillis = System.currentTimeMillis() + timeout,
            timeoutMillis = timeout,
        )
        // 真正的 CallerRegistry 接上后会自己设 authorizationAnswer；这里只在没人设时补一个记录决定的
        if (process.consentBridge.authorizationAnswer == null) {
            process.consentBridge.authorizationAnswer = { rid, allow -> authAnswer(process, rid, allow) }
        }
        authDecisions[id] = "pending"
        process.consentBridge.authorizationRequested(req)
        scope.launch {
            kotlinx.coroutines.delay(timeout)
            if (authDecisions[id] == "pending") {
                authDecisions[id] = "deny:timeout"
                process.consentBridge.authorizationResolved(id, org.agentos.runtime.consent.ConsentResolution(org.agentos.runtime.consent.ConsentEnd.TIMED_OUT))
            }
        }
        return JSONObject().put("ok", true).put("requestId", id)
    }

    private fun authAnswer(process: AgentProcess, id: String, allow: Boolean): Boolean {
        if (authDecisions[id] != "pending") return false
        authDecisions[id] = if (allow) "allow" else "deny:user"
        process.consentBridge.authorizationResolved(id, org.agentos.runtime.consent.ConsentResolution(org.agentos.runtime.consent.ConsentEnd.ANSWERED))
        return true
    }

    private companion object {
        val authDecisions = ConcurrentHashMap<String, String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val decisions = ConcurrentHashMap<String, String>()
    }
}
