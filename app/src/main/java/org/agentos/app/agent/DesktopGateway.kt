package org.agentos.app.agent

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.system.Os
import android.system.OsConstants
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
 * 设置页（D，W8）经 IAgentControl v3（`getDesktopAccess`、`setDesktopAccessEnabled`、`newDesktopPairingCode`、
 * `revokeDesktopPairing`，服务端在 AgentControl.kt）调用这里的方法。它们会读写文件、开关 socket，**不要在主线程调用**。
 * 自动化测试（tests/acp-conformance 的设备模式）以 shell 身份运行，绑不了 IAgentControl，用 debug 源集里的
 * `DesktopGatewayDebugReceiver`（app/src/debug，release 包里没有）。
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
        // C 的 AndroidRuntimeLog（经 KeystoreSecrets 脱敏）
        log = process.hostPort.log,
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

    /** IAgentControl v3 的 newDesktopPairingCode：`{"code","expiresAtMs","ttlMs"}`；开关关闭时抛 `agentos.desktop.disabled`。 */
    fun newPairingCodeJson(ttlMillis: Long = 0): JSONObject {
        if (!core.enabled) throw IllegalStateException("agentos.desktop.disabled: turn on desktop access first")
        val code = if (ttlMillis > 0) core.newPairingCode(ttlMillis) else core.newPairingCode()
        return JSONObject().put("code", code.code).put("expiresAtMs", code.expiresAtMillis).put("ttlMs", code.ttlMillis)
    }

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
