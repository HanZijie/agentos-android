package org.agentos.app.agent.acp

import android.content.Context
import android.content.pm.PackageManager
import org.agentos.acp.AcpServiceContract
import org.agentos.app.ext.registry.AppPluginScanner
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind

/** 由 Binder 调用方 UID 解析出来的第三方 App（不是调用方自报的）。 */
data class ResolvedCaller(val packageName: String, val signingDigest: String, val label: String)

/** UID 对应的第三方 App。解析不出（查不到包、共享 UID、没有签名）返回 null。 */
fun interface CallerResolver {
    fun resolve(uid: Int): ResolvedCaller?
}

/** 按包名解析已安装的 App（调试入口预授权用；准入永远按 UID，不按包名）。 */
interface PackageLookup {
    fun lookup(packageName: String): ResolvedCaller?
}

/** 一次 open 的决定。 */
sealed interface AcpDecision {
    /** 打开通道。[app] 不为 null 时是第三方 App（调用方身份是 [CallerKind.APP]）。 */
    data class Open(val caller: CallerIdentity, val app: ResolvedCaller?) : AcpDecision

    /** 拒绝；[message] 是 SecurityException 的完整 message（`agentos.acp.<code>: <说明>`）。 */
    data class Reject(val message: String, val reason: String) : AcpDecision
}

/**
 * 谁能打开 ACP 通道（docs/third-party-acp.md 4.1）。
 *
 * - 调用方的 UID 就是 AgentOS 自己：SELF，和以前一样。
 * - 其他 UID：先解析成**恰好一个包**（共享 UID、查不到包一律 `not_open`），再查 [CallerRegistry]：已允许 → [CallerKind.APP]
 *   （label = App 名）；没记录 / 签名变了 → 记为待用户决定并**立刻**返回 `authorization_pending`；拒绝冷却内 → `denied`。
 * - 调用方在请求里自报的任何包名、名字都不参与（`open` 只带一个 IChannel；`initialize` 的 clientInfo 也不看）。
 */
object AcpAccessPolicy {
    fun decide(callerUid: Int, myUid: Int, resolver: CallerResolver, registry: CallerRegistry): AcpDecision {
        if (callerUid == myUid) return AcpDecision.Open(CallerIdentity(uid = callerUid, kind = CallerKind.SELF, label = "AgentOS"), null)
        val app = resolver.resolve(callerUid)
            ?: return reject(AcpServiceContract.REASON_NOT_OPEN, "this caller cannot be identified as exactly one app")
        return when (val a = registry.admit(app.packageName, app.signingDigest, app.label)) {
            is Admission.Allowed -> AcpDecision.Open(CallerIdentity(uid = callerUid, kind = CallerKind.APP, label = a.label), app)
            is Admission.Pending ->
                reject(AcpServiceContract.REASON_AUTHORIZATION_PENDING, "the user has not decided yet; retry open in a second (request ${a.requestId.take(8)})")
            is Admission.Denied -> reject(AcpServiceContract.REASON_DENIED, "the user denied this app; try again after ${a.untilMillis}")
            is Admission.Refused -> reject(AcpServiceContract.REASON_NOT_OPEN, a.reason)
        }
    }

    private fun reject(reason: String, detail: String) = AcpDecision.Reject(AcpServiceContract.message(reason, detail), reason)
}

/** Android 上的 [CallerResolver]：`getPackagesForUid` 必须恰好一个包；签名摘要和插件用同一个函数；App 名取标签。 */
class PackageCallerResolver(private val context: Context) : CallerResolver, PackageLookup {
    override fun resolve(uid: Int): ResolvedCaller? {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        if (packages == null || packages.size != 1) return null
        return lookup(packages[0])
    }

    override fun lookup(packageName: String): ResolvedCaller? {
        val pm = context.packageManager
        val pkg = packageName
        return try {
            val info = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            val digest = AppPluginScanner.signerDigest(info)
            if (digest.isEmpty()) return null
            val label = info.applicationInfo?.let { pm.getApplicationLabel(it).toString() }.orEmpty()
            ResolvedCaller(pkg, digest, cleanLabel(label).ifEmpty { pkg })
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    companion object {
        const val MAX_LABEL_CHARS = 64

        /** App 名是第三方文字：去掉控制字符和格式字符（含换行、双向控制），折叠空白，截断。 */
        fun cleanLabel(raw: String): String {
            val sb = StringBuilder()
            var lastSpace = true
            for (cp in raw.codePoints().toArray()) {
                val type = Character.getType(cp)
                val bad = Character.isISOControl(cp) || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
                    type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
                if (bad || Character.isWhitespace(cp)) {
                    if (!lastSpace) sb.append(' ')
                    lastSpace = true
                } else {
                    sb.appendCodePoint(cp)
                    lastSpace = false
                }
                if (sb.length >= MAX_LABEL_CHARS) break
            }
            return sb.toString().trim()
        }
    }
}
