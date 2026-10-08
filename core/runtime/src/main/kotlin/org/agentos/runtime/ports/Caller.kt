package org.agentos.runtime.ports

/**
 * 调用方身份。一律来自可信的连接上下文（Binder 的调用方 UID、电脑端配对），不相信客户端在消息里自报的任何身份。
 *
 * @property uid Binder 调用方 UID；电脑端为 adbd 转发时的对端 UID（通常是 shell 2000），仅作记录。
 * @property kind 调用方类别，决定会话空间和授权规则（architecture 5.3）。
 * @property label 给人看的名字（App 名或“电脑端”），由宿主层根据 UID 解析，不来自客户端。**只是显示用**：任何 App 都可以把自己叫成系统 App 的名字，
 *   所以确认界面不能只靠它认人。
 * @property packageName **第三方 App（[CallerKind.APP]）的包名**，由宿主层根据 UID 解析（恰好一个包才放行），不来自客户端；其他调用方为 null
 *   （[CallerKind.SELF]、[CallerKind.DESKTOP]、[CallerKind.SYSTEM] 没有包名这个概念，设了也不会被存、不会显示）。确认界面把它和 [label] 一起写明。
 *   不参与 [ownerKey]：会话仍按 UID 隔离。
 */
data class CallerIdentity(
    val uid: Int,
    val kind: CallerKind,
    val label: String? = null,
    val packageName: String? = null,
) {
    /**
     * 会话归属键：会话属于创建它的调用方。第三方 App 与自带界面按 UID 区分；电脑端作为一个独立的调用方，
     * 共用一个会话空间（F11）；运行时自己发起的操作（恢复）用 system。
     */
    val ownerKey: String
        get() = when (kind) {
            CallerKind.SELF, CallerKind.APP -> "uid:$uid"
            CallerKind.DESKTOP -> "desktop"
            CallerKind.SYSTEM -> "system"
        }

    companion object {
        /** 运行时自己（恢复流程等）。 */
        val SYSTEM = CallerIdentity(uid = -1, kind = CallerKind.SYSTEM, label = "AgentOS")
    }
}

enum class CallerKind {
    /** AgentOS App 自带界面：用户的控制中心，可以查看所有会话（architecture 5.3 会话隔离）。 */
    SELF,

    /** 后装的第三方 App（M4 起开放）。 */
    APP,

    /** 电脑端（已配对，W9）。 */
    DESKTOP,

    /** 运行时自己。 */
    SYSTEM,
}

/**
 * 出站背压：ACP 层每发一条流式 `session/update` 之前调用 [awaitWritable]，等传输层的本地积压降下来
 * （binder-channel-v1 第 5 节：生产者高水位 16,384 字符）。
 *
 * core/runtime 不依赖 Android，所以由接线方提供：
 * - W6（Binder）：`OutboundGate { transport.awaitWritable(16_384) }`，transport 是 BinderAcpTransport；
 * - W9（电脑端网关，抽象 socket）：`OutboundGate { transport.awaitWritable(16_384) }`，transport 是
 *   [org.agentos.runtime.acp.LineTransport]（本地出站队列的积压，由 DesktopGatewayCore 接好）；
 * - 电脑上的 stdio（测试）：[NONE]。
 */
fun interface OutboundGate {
    suspend fun awaitWritable()

    companion object {
        val NONE = OutboundGate { }

        /** binder-channel-v1 第 8 节的建议值。 */
        const val BINDER_HIGH_WATER_CHARS = 16_384L
    }
}
