package org.agentos.sample.sms.agentos

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import org.agentos.acp.AgentOs
import org.agentos.acp.AgentOsConnection
import org.agentos.acp.AgentOsEvent as SdkEvent
import org.agentos.acp.AgentOsException as SdkException
import org.agentos.acp.AgentOsSession
import org.agentos.acp.Waiting as SdkWaiting

/**
 * 真网关：`sdk:acp-android` 的 [AgentOs] 到 [AgentOsGateway] 的薄适配层。没有任何业务逻辑，只做三件事：
 * 把 SDK 的类型映射成本 App 的类型（[SdkMapping]，枚举一一对应）、把 SDK 的异常映射成 [AgentOsException]、持有连接和会话。
 *
 * 一次“让 AgentOS 安排”用一个实例（见 [AgentScheduleUseCase]）；不是线程安全的共享对象。
 */
class RealAgentOsGateway(context: Context) : AgentOsGateway {
    private val appContext = context.applicationContext
    private var connection: AgentOsConnection? = null
    private var session: AgentOsSession? = null

    override fun isAvailable(): Boolean = sdk { AgentOs.isInstalled(appContext) }

    override suspend fun connect(onWaiting: (Waiting) -> Unit) {
        connection = sdk { AgentOs.connect(appContext) { _: SdkWaiting -> onWaiting(Waiting.AUTHORIZATION) } }
    }

    override suspend fun newSession(toolScope: List<ToolRef>) {
        val c = connection ?: throw AgentOsException(AgentOsError.DISCONNECTED, "not connected")
        session = sdk { c.newSession(toolScope.map(SdkMapping::toSdk)) }
    }

    override fun prompt(text: String): Flow<GatewayEvent> = flow {
        val s = session ?: throw AgentOsException(AgentOsError.DISCONNECTED, "no session")
        emitAll(s.prompt(text).mapNotNull(SdkMapping::fromSdk))
    }.catch { e ->
        // 只捕获上游（SDK 这一侧）；取消不会进到这里
        throw when (e) {
            is AgentOsException -> e
            is SdkException -> SdkMapping.fromSdk(e)
            is NotImplementedError -> SdkMapping.notImplemented(e)
            else -> e
        }
    }

    override suspend fun cancel() {
        val s = session ?: return
        try {
            s.cancel()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 取消尽力而为：连接已经断了也没关系，随后 close()
        } catch (_: NotImplementedError) {
        }
    }

    override fun bringApprovalToFront() {
        try {
            AgentOs.bringApprovalToFront(appContext)
        } catch (_: Exception) {
        } catch (_: NotImplementedError) {
        }
    }

    override fun close() {
        val c = connection ?: return
        connection = null
        session = null
        try {
            c.close()
        } catch (_: Exception) {
        } catch (_: NotImplementedError) {
        }
    }

    /** 调 SDK：它的异常换成我们的，没实现的骨架（NotImplementedError）当成普通失败，不让整个 App 崩掉。 */
    private inline fun <T> sdk(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SdkException) {
        throw SdkMapping.fromSdk(e)
    } catch (e: NotImplementedError) {
        throw SdkMapping.notImplemented(e)
    }
}

/** SDK 类型 ↔ 本 App 类型的映射（纯函数，可在 JVM 上单测）。每个枚举都用穷尽的 `when`，SDK 新增取值时这里会编译失败而不是悄悄漏掉。 */
internal object SdkMapping {
    fun toSdk(ref: ToolRef): org.agentos.acp.ToolRef = org.agentos.acp.ToolRef(ref.plugin, ref.tool)

    fun fromSdk(status: org.agentos.acp.ToolStatus): ToolStatus = when (status) {
        org.agentos.acp.ToolStatus.PENDING_APPROVAL -> ToolStatus.PENDING_APPROVAL
        org.agentos.acp.ToolStatus.RUNNING -> ToolStatus.RUNNING
        org.agentos.acp.ToolStatus.COMPLETED -> ToolStatus.COMPLETED
        org.agentos.acp.ToolStatus.DENIED -> ToolStatus.DENIED
        org.agentos.acp.ToolStatus.FAILED -> ToolStatus.FAILED
    }

    fun fromSdk(error: org.agentos.acp.AgentOsError): AgentOsError = when (error) {
        org.agentos.acp.AgentOsError.NOT_INSTALLED -> AgentOsError.NOT_INSTALLED
        org.agentos.acp.AgentOsError.AUTHORIZATION_PENDING_TIMEOUT -> AgentOsError.AUTHORIZATION_PENDING_TIMEOUT
        org.agentos.acp.AgentOsError.DENIED -> AgentOsError.DENIED
        org.agentos.acp.AgentOsError.NO_MODEL -> AgentOsError.NO_MODEL
        org.agentos.acp.AgentOsError.BUSY -> AgentOsError.BUSY
        org.agentos.acp.AgentOsError.RATE_LIMITED -> AgentOsError.RATE_LIMITED
        org.agentos.acp.AgentOsError.TOO_LARGE -> AgentOsError.TOO_LARGE
        org.agentos.acp.AgentOsError.DISCONNECTED -> AgentOsError.DISCONNECTED
        org.agentos.acp.AgentOsError.FAILED -> AgentOsError.FAILED
        // 这个 App 只用 newSession(toolScope) 和 prompt：不带 MCP 服务器、不回到旧会话、不换模式和模型。这三种只会来自请求本身的
        // 问题或旧版本的 AgentOS，对用户来说就是“失败了”，界面不单独写文案
        org.agentos.acp.AgentOsError.SESSION_NOT_FOUND,
        org.agentos.acp.AgentOsError.INVALID_REQUEST,
        org.agentos.acp.AgentOsError.UNSUPPORTED,
        -> AgentOsError.FAILED
    }

    /** SDK 异常的 message 只用于日志（SDK 保证不含用户文字），照搬给 detail 没问题。 */
    fun fromSdk(e: SdkException): AgentOsException = AgentOsException(fromSdk(e.error), e.message, e)

    fun fromSdk(ref: org.agentos.acp.ToolRef): ToolRef = ToolRef(ref.plugin, ref.tool)

    /** 思考和历史里的用户消息只在调用方要求时才有（这个 App 都没要）；万一来了就跳过（null），不当作文字。 */
    fun fromSdk(event: SdkEvent): GatewayEvent? = when (event) {
        is SdkEvent.Text -> GatewayEvent.Text(event.chunk)
        is SdkEvent.ToolCall -> GatewayEvent.ToolCall(
            id = event.id,
            tool = event.tool,
            status = fromSdk(event.status),
            resultJson = event.resultJson,
            argumentsJson = event.argumentsJson,
            ref = event.ref?.let(::fromSdk),
        )
        is SdkEvent.Done -> GatewayEvent.Done(event.stopReason)
        is SdkEvent.Thought, is SdkEvent.UserMessage -> null
    }

    fun notImplemented(e: NotImplementedError): AgentOsException =
        AgentOsException(AgentOsError.FAILED, "AgentOS SDK is not available in this build: ${e.message ?: "not implemented"}", e)
}
