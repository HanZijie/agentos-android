package org.agentos.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.agentclientprotocol.rpc.JsonRpcMessage
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.agentos.acp.JsonRpcCodec
import org.agentos.runtime.acp.LineCodec
import org.agentos.runtime.acp.LineTransportStats
import org.agentos.runtime.desktop.DesktopConnectionInfo
import org.agentos.runtime.desktop.DesktopEndpoint
import org.agentos.runtime.desktop.DesktopGatewayCore
import org.agentos.runtime.desktop.DesktopListener
import org.agentos.runtime.desktop.DesktopListenerFactory
import org.agentos.runtime.desktop.DesktopPairing
import org.agentos.runtime.desktop.DesktopPairingInfo
import org.agentos.runtime.desktop.FilePairingStore
import org.agentos.runtime.desktop.PairingCode
import org.agentos.runtime.ports.RuntimeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 电脑端接入（architecture F11，W9）：`:agent` 在抽象 socket [SOCKET_NAME] 上按行收发 ACP JSON-RPC，
 * 电脑经 `adb forward tcp:<端口> localabstract:agentos-acp`（或 tools/acp-bridge）连进来。
 *
 * 平台无关的部分（开关、一次性配对码、令牌、握手、按行传输、连接管理）在 core:runtime 的
 * [DesktopGatewayCore]，这里只做 Android 接线：
 * - 监听：[LocalServerSocket]，**只在开关打开时**存在；开关默认关闭，状态存在 DE 存储的 `files/desktop/pairing.json`
 *   （只有开关和令牌的 SHA-256；配对码只在内存里）。
 * - 对端：只接受 adbd 转发过来的连接（SO_PEERCRED 的 UID 是 shell 2000，或 root 0）；其他 App 连上来直接关闭，不读数据。
 * - 编码：sdk:acp-android 的 [JsonRpcCodec]（与 Binder 通道相同，没有 SDK StdioTransport 多出的 `"type"` 字段）。
 * - 每条连接以 `CallerKind.DESKTOP`（ownerKey `desktop`）交给 `AgentRuntime.serveAcp`；确认照常在手机上弹出。
 *
 * 设置页（D，W8）经 IAgentControl v3（C3 进 main 之后由 A 追加）调用 [setEnabled]、[newPairingCode]、[pairings]、
 * [revoke]、[connections]、[status]。这些方法会读写文件、开关 socket，**不要在主线程调用**。
 * 在那之前，debug 包用 [DesktopGatewayDebugReceiver] 做测试入口。
 */
class DesktopGateway(private val process: AgentProcess) {

    private val stateFile = File(process.app.createDeviceProtectedStorageContext().filesDir, STATE_FILE)

    val core = DesktopGatewayCore(
        runtime = process.runtime,
        pairing = DesktopPairing(FilePairingStore(stateFile)),
        codec = AndroidLineCodec,
        listenerFactory = { LocalSocketListener(SOCKET_NAME) },
        parentScope = process.scope,
        peerAllowed = DesktopPeerPolicy::allowed,
        log = AndroidRuntimeLog,
    )

    init {
        // 开关是打开的（上次打开后没关）就开始监听；读文件、开 socket 不在主线程
        process.scope.launch(Dispatchers.IO + CoroutineName("desktop-gateway-restore")) { core.restore() }
    }

    fun isEnabled(): Boolean = core.enabled

    /** 打开或关闭电脑端接入。关闭时断开所有电脑端连接，清掉配对码和全部配对。 */
    fun setEnabled(on: Boolean) = core.setEnabled(on)

    /** 生成一次性配对码（5 分钟有效，替换旧的），给设置页显示。开关关闭时抛 IllegalStateException。 */
    fun newPairingCode(): PairingCode = core.newPairingCode()

    fun activePairingCode(): PairingCode? = core.pairing.activeCode()

    fun pairings(): List<DesktopPairingInfo> = core.pairing.pairings()

    fun revoke(pairingId: String): Boolean = core.revoke(pairingId)

    fun revokeAll(): Int = core.revokeAll()

    fun connections(): List<DesktopConnectionInfo> = core.connections()

    /** 给设置页：开关、是否在监听、监听失败原因、配对码到期时间、已配对的电脑、当前连接。不含配对码和令牌。 */
    fun status(): JSONObject {
        val s = core.stats()
        return JSONObject()
            .put("enabled", s.enabled)
            .put("listening", s.listening)
            .put("socket", SOCKET_NAME)
            .put("listenError", s.listenError ?: JSONObject.NULL)
            .put("code", s.code?.let { JSONObject().put("expiresAtMs", it.expiresAtMillis).put("attemptsLeft", it.attemptsLeft) } ?: JSONObject.NULL)
            .put("pairings", JSONArray(core.pairing.pairings().map { it.toJson() }))
            .put("connections", JSONArray(s.connections.map { it.toJson() }))
    }

    /** 诊断（AgentProcess.diagnostics 的 `desktop`）：不含配对码、令牌和消息内容。 */
    fun stats(): JSONObject {
        val s = core.stats()
        return status()
            .put("accepted", s.accepted)
            .put("served", s.served)
            .put("closed", s.closed)
            .put("rejectedPeer", s.rejectedPeer)
            .put("rejectedBusy", s.rejectedBusy)
            .put("pendingHandshakes", s.pendingHandshakes)
            .put("handshakeTimeouts", s.handshakeTimeouts)
            .put("handshakeFailures", JSONObject(s.handshakeFailures as Map<*, *>))
            .put("recentCloses", JSONArray(s.recentCloses.map {
                JSONObject().put("id", it.id).put("pairingId", it.pairingId).put("aliveMs", it.aliveMillis).put("reason", it.reason)
            }))
    }

    private fun DesktopPairingInfo.toJson() = JSONObject()
        .put("id", id).put("label", label).put("pairedAtMs", pairedAtMillis).put("lastSeenMs", lastSeenMillis)

    private fun DesktopConnectionInfo.toJson() = JSONObject()
        .put("id", id).put("pairingId", pairingId).put("label", label).put("peerUid", peerUid)
        .put("openedAtMs", openedAtMillis).put("transport", transport.toJson())

    private fun LineTransportStats.toJson() = JSONObject()
        .put("state", state).put("linesIn", linesIn).put("linesOut", linesOut).put("decodeErrors", decodeErrors)
        .put("droppedTooLarge", droppedTooLarge).put("syntheticErrors", syntheticErrors).put("droppedClosed", droppedClosed)
        .put("queuedChars", queuedChars).put("closeReason", closeReason ?: JSONObject.NULL)

    companion object {
        /** architecture 5.3：`adb forward tcp:<端口> localabstract:agentos-acp`。 */
        const val SOCKET_NAME = "agentos-acp"

        /** DE 存储 files/ 下的状态文件。 */
        const val STATE_FILE = "desktop/pairing.json"
    }
}

/** 谁能连电脑端网关：adb forward 过来的 adbd（shell）；userdebug 上 `adb root` 之后是 root。 */
object DesktopPeerPolicy {
    const val ROOT_UID = 0
    const val SHELL_UID = 2000

    fun allowed(uid: Int): Boolean = uid == SHELL_UID || uid == ROOT_UID
}

/** C 的 [JsonRpcCodec]：与 Binder 通道同一份编码。 */
private object AndroidLineCodec : LineCodec {
    override fun encode(message: JsonRpcMessage): String = JsonRpcCodec.encode(message)

    override fun decode(line: String): JsonRpcMessage = JsonRpcCodec.decode(line)
}

private object AndroidRuntimeLog : RuntimeLog {
    override fun log(level: RuntimeLog.Level, tag: String, message: String, error: Throwable?) {
        val t = "AgentOS.$tag"
        // 不带异常堆栈：可能含有消息片段
        val m = if (error != null) "$message (${error.javaClass.simpleName})" else message
        when (level) {
            RuntimeLog.Level.DEBUG -> Log.d(t, m)
            RuntimeLog.Level.INFO -> Log.i(t, m)
            RuntimeLog.Level.WARN -> Log.w(t, m)
            RuntimeLog.Level.ERROR -> Log.e(t, m)
        }
    }
}

/** 抽象 socket 的监听端。开关打开时创建；抽象名被别的进程占用时构造失败（IOException，进 listenError）。 */
private class LocalSocketListener(name: String) : DesktopListener {
    private val server = LocalServerSocket(name)

    override fun accept(): DesktopEndpoint = LocalSocketEndpoint(server.accept())

    override fun close() {
        // shutdown 让阻塞中的 accept 立即返回，再关掉 fd
        runCatching { Os.shutdown(server.fileDescriptor, OsConstants.SHUT_RDWR) }
        runCatching { server.close() }
    }
}

private class LocalSocketEndpoint(private val socket: LocalSocket) : DesktopEndpoint {
    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream
    override val peerUid: Int = runCatching { socket.peerCredentials.uid }.getOrDefault(-1)

    override fun close() {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }
}

/**
 * **debug 包专用的测试入口**（IAgentControl v3 之前；tests/acp-conformance 的设备模式用它）：
 *
 * ```
 * adb shell am broadcast -f 32 -n org.agentos.app/.agent.DesktopGatewayDebugReceiver --es op <op> [--el ttlMs <毫秒>]
 * ```
 *
 * op：`enable`、`disable`、`pair`（生成配对码，返回 code）、`status`、`revoke_all`。结果在广播的 result data 里（JSON）。
 * Manifest 要求发送方持有 DUMP 权限（adb shell 和系统有，普通 App 没有）；release 包（不可调试）一律返回 `not_debuggable`。
 * 运行在 `:agent` 进程；`:agent` 没在运行时由这条广播拉起。
 */
class DesktopGatewayDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val process = AgentProcess.get(context)
        val op = intent.getStringExtra("op")
        val result = try {
            if (!process.debuggable) {
                JSONObject().put("ok", false).put("error", "not_debuggable")
            } else {
                val gw = process.desktop
                when (op) {
                    "enable" -> gw.setEnabled(true).let { gw.status().put("ok", true) }
                    "disable" -> gw.setEnabled(false).let { gw.status().put("ok", true) }
                    "status" -> gw.stats().put("ok", true)
                    "revoke_all" -> JSONObject().put("ok", true).put("revoked", gw.revokeAll())
                    "pair" -> {
                        val ttl = intent.getLongExtra("ttlMs", 0L)
                        val code = if (ttl > 0) gw.core.newPairingCode(ttl) else gw.newPairingCode()
                        JSONObject().put("ok", true).put("code", code.code).put("expiresAtMs", code.expiresAtMillis).put("ttlMs", code.ttlMillis)
                    }
                    else -> JSONObject().put("ok", false).put("error", "unknown op: $op")
                }
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        }
        resultCode = if (result.optBoolean("ok")) RESULT_OK_CODE else RESULT_ERROR_CODE
        resultData = result.put("op", op ?: JSONObject.NULL).toString()
    }

    companion object {
        const val RESULT_OK_CODE = 1
        const val RESULT_ERROR_CODE = 2
    }
}
