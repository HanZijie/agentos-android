package org.agentos.app.ui

import org.agentos.channel.CloseCause

/**
 * A failure shown to the user: a short title, what to do about it, and whether sending again may help.
 * Built from AgentOS error codes (core/contracts/errors.md: JSON-RPC -32040…-32059 with
 * `data.agentosCode`), from the Binder channel's close cause, or from connection failures.
 * Never contains a key, a prompt, or tool arguments.
 */
data class AgentError(
    val title: String,
    val hint: String?,
    val retryable: Boolean,
    /** AgentOS error code, or a local code (`channel_*`, `connect_*`, `rpc_<n>`), for diagnostics. */
    val code: String,
)

object AgentErrors {
    private class Text(val title: String, val hint: String? = null)

    private val byCode: Map<String, Text> = mapOf(
        // request (errors.md 3.1)
        "invalid_params" to Text("请求参数不合法"),
        "unsupported" to Text("请求了 AgentOS 不支持的能力"),
        "auth_required" to Text("需要先完成认证"),
        "not_open" to Text("AgentOS 还没有对这个调用方开放"),
        "forbidden" to Text("没有权限执行这个操作"),
        "session_not_found" to Text("会话不存在了", "运行时可能重启过；再发一次会自动开始新会话"),
        "task_not_found" to Text("任务不存在了"),
        "invalid_state" to Text("当前状态不允许这个操作", "等上一轮结束或取消完成后再试"),
        "request_conflict" to Text("提交内容与之前的请求冲突"),
        "session_terminal" to Text("这个会话已经结束", "点“新对话”开始新的会话"),
        "cursor_too_old" to Text("恢复位置太旧，需要重新加载"),
        "payload_too_large" to Text("输入太长", "把内容拆成几段发送"),
        "busy" to Text("运行时正忙", "稍后再试"),
        "quota_exceeded" to Text("超过了用量上限", "稍后再试"),
        "recovery_required" to Text("这个会话有等你处理的恢复任务", "处理之前不能发新消息"),
        "safe_mode" to Text("AgentOS 处于安全模式，不执行新任务", "在 root 管理器里用 AgentOS 模块的“动作”按钮退出安全模式"),
        // model (3.2)
        "model_not_configured" to Text("还没有配置模型", "到设置页选择模型厂商并填写 key"),
        "model_auth_failed" to Text("模型服务拒绝了 key", "到设置页检查 key 是否正确、是否有这个模型的权限"),
        "model_quota_exhausted" to Text("模型账户的余额或额度用完了", "到模型厂商的控制台充值后再试"),
        "model_bad_request" to Text("模型服务拒绝了这个请求", "模型名或参数可能不对，或者对话太长；可以点“新对话”再试"),
        "model_request_too_large" to Text("请求太大，模型服务不接受", "点“新对话”，或缩短输入"),
        "model_rate_limited" to Text("被模型服务限流了", "稍等一会儿再发"),
        "model_unavailable" to Text("模型服务暂时不可用", "稍后再试"),
        "model_network" to Text("连不上模型服务", "检查网络后再试"),
        "model_timeout" to Text("模型服务响应超时", "检查网络后再试"),
        "model_stream_interrupted" to Text("回复中途断开了", "检查网络后再试"),
        "model_tls_failed" to Text("与模型服务的安全连接校验失败", "当前网络可能被劫持，换一个网络再试"),
        "model_protocol" to Text("模型服务返回了无法识别的内容", "检查设置页里的地址和协议是否匹配"),
        // tool (3.3): normally tool errors do not fail a turn
        "tool_not_in_catalog" to Text("模型请求了一个不存在的工具"),
        "tool_denied" to Text("工具调用被拒绝"),
        "tool_blocked" to Text("工具调用被拦截"),
        "tool_failed" to Text("工具调用失败"),
        "tool_timeout" to Text("工具调用超时"),
        "tool_unavailable" to Text("工具暂时不可用"),
        "tool_result_unknown" to Text("工具调用的结果未知"),
        "tool_result_too_large" to Text("工具返回的结果太大"),
        // task (3.4)
        "queue_timeout" to Text("排队超时，任务没有开始", "稍后再试"),
        "execution_timeout" to Text("任务超过时限，已取消"),
        "agent_core_failed" to Text("Agent 内核出错，这一轮需要恢复"),
        "abandoned" to Text("任务已放弃"),
        "store_failed" to Text("AgentOS 读写存储失败", "检查手机剩余空间"),
        "internal" to Text("AgentOS 内部错误"),
    )

    /**
     * From a JSON-RPC error of `session/prompt` or another request. [reason] is `data.details.reason`
     * (architecture F9: `model_not_configured` with `key_revoked` = the key was cleared during this turn).
     */
    fun fromRpc(
        rpcCode: Int,
        message: String?,
        agentosCode: String?,
        retryable: Boolean?,
        retryAfterSeconds: Long? = null,
        reason: String? = null,
    ): AgentError {
        if (agentosCode == "model_not_configured" && reason == "key_revoked") {
            return AgentError("模型 key 已在设置中清除，这一轮已停止", "到设置页重新选择模型厂商并填写 key", false, agentosCode)
        }
        val known = agentosCode?.let { byCode[it] }
        if (known != null) {
            val hint = if (agentosCode == "model_rate_limited" && retryAfterSeconds != null && retryAfterSeconds > 0) {
                "约 $retryAfterSeconds 秒后再发"
            } else {
                known.hint
            }
            return AgentError(known.title, hint, retryable ?: false, agentosCode)
        }
        val title = when (rpcCode) {
            -32601 -> "运行时不支持这个请求"
            -32602 -> "请求参数不合法"
            -32700, -32600 -> "协议错误"
            -32000 -> "需要先完成认证"
            -32002 -> "请求的对象不存在"
            -32800 -> "请求被取消了"
            else -> "请求失败"
        }
        val detail = message?.substringBefore('\n')?.take(120)?.takeIf { it.isNotBlank() }
        return AgentError(title, detail, retryable ?: false, agentosCode ?: "rpc_$rpcCode")
    }

    /** The Binder channel closed while a request was in flight (prompt ends with "Protocol closed"). */
    fun fromClose(cause: CloseCause?): AgentError = when (cause) {
        is CloseCause.PeerDied -> AgentError(
            "运行时进程重启了",
            "可能是被系统回收了；运行时恢复后，这一轮的结果以运行时为准。可以再发一次",
            true, "channel_peer_died",
        )
        is CloseCause.Remote -> AgentError("运行时关闭了连接", "再发一次会重新连接", true, "channel_remote")
        is CloseCause.Violation -> AgentError("连接出现异常（${cause.reason}）", "再发一次会重新连接", true, "channel_violation")
        is CloseCause.Failure -> AgentError("连接出现异常", "再发一次会重新连接", true, "channel_failure")
        is CloseCause.Local -> AgentError("连接已关闭", null, true, "channel_local")
        null -> AgentError("连接已断开", "再发一次会重新连接", true, "channel_unknown")
    }

    fun connectFailed(reason: String?): AgentError = when (reason) {
        "agentos.acp.not_open" -> AgentError("AgentOS 拒绝了连接", "这个版本只接受 AgentOS 自己的界面", false, "connect_not_open")
        null -> AgentError("连不上 AgentOS 运行时", "稍后再试；如果一直这样，打开设置页查看运行状态", true, "connect_failed")
        else -> AgentError("AgentOS 拒绝了连接（$reason）", null, false, "connect_rejected")
    }

    fun serviceMissing(): AgentError =
        AgentError("找不到 AgentOS 运行时服务", "请重新安装 AgentOS", false, "connect_no_service")

    fun unexpected(t: Throwable): AgentError =
        AgentError("出现意外错误（${t.javaClass.simpleName}）", null, true, "client_exception")
}
