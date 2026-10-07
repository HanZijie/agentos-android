package org.agentos.plugin

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.agentos.channel.IChannel
import org.agentos.channel.IMcpService
import org.agentos.plugin.internal.AndroidMcpLog
import org.agentos.plugin.internal.McpServerSession
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 插件 App 的 MCP 服务基类（docs/extensions.md 4.1、5.1）：继承它，在 [onRegisterTools] 里注册工具即可。
 *
 * Manifest：
 * ```xml
 * <service android:name=".NotesMcpService" android:exported="true"
 *     android:permission="org.agentos.permission.BIND_MCP_SERVICE">
 *     <intent-filter><action android:name="org.agentos.intent.action.PLUGIN" /></intent-filter>
 *     <meta-data android:name="org.agentos.plugin.assets" android:value="agent-plugin" />
 * </service>
 * ```
 *
 * - 对外实现 `org.agentos.channel.IMcpService`；每次 `open` 是一条连接（binder-channel-v1），连接上跑 MCP（只做 tools）。
 * - [onRegisterTools] 在第一次被 bind 时调用（主线程）；[notifyToolsChanged] 会重新调用它、换掉工具表，
 *   再向所有已连接的客户端发 `notifications/tools/list_changed`。工具集是动态的话，在 [onRegisterTools] 里按当前状态注册。
 * - handler 在 SDK 的协程作用域里运行（[Dispatchers.IO]），连接断开或收到取消时被取消；Service 销毁时全部取消。
 * - 日志里只有方法名、长度和计数，不记参数和结果。
 */
abstract class McpBinderService : Service() {

    /** MCP `initialize` 里的 serverInfo.name，与 plugin.json 里 mcpServers 的名字一致。 */
    protected abstract val serverName: String

    protected open val serverVersion: String get() = "1.0.0"

    protected abstract fun onRegisterTools(registry: McpToolRegistry)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("McpBinderService"))
    private val sessions = CopyOnWriteArraySet<McpServerSession>()
    private val tableLock = Any()
    @Volatile private var table: ToolTable? = null

    private fun currentTools(): ToolTable =
        table ?: synchronized(tableLock) { table ?: ToolTable.build { onRegisterTools(it) }.also { table = it } }

    /** 工具集变化后调用：重新注册工具，并向已连接的客户端发 `notifications/tools/list_changed`。可以在任何线程调用。 */
    protected fun notifyToolsChanged() {
        synchronized(tableLock) { table = ToolTable.build { onRegisterTools(it) } }
        sessions.forEach { it.notifyToolsChanged() }
    }

    private val binder = object : IMcpService.Stub() {
        override fun open(client: IChannel?): IChannel {
            requireNotNull(client) { "IMcpService.open: client channel is null" }
            val uid = Binder.getCallingUid()
            val transport = McpBinderTransport.accept(client, uid, serviceScope, name = "mcp-server:$serverName(uid=$uid)")
            val session = McpServerSession(transport.pipe, serverName, serverVersion, ::currentTools, serviceScope, log = AndroidMcpLog)
            sessions += session
            session.start()
            serviceScope.launch {
                val reason = session.done.await()
                sessions -= session
                AndroidMcpLog.log('I', "$serverName: connection from uid $uid closed ($reason)")
            }
            AndroidMcpLog.log('I', "$serverName: connection opened by uid $uid")
            return transport.channel.binder
        }
    }

    final override fun onBind(intent: Intent): IBinder {
        currentTools() // 注册错误（重名、schema 不对）在 bind 时就暴露
        return binder
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
