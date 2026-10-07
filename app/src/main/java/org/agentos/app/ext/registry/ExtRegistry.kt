package org.agentos.app.ext.registry

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.agentos.app.ext.ExtError
import org.agentos.extensions.McpServerDecl
import org.agentos.extensions.registry.ApprovalStore
import org.agentos.extensions.registry.FileBackedTextFile
import org.agentos.extensions.registry.PersistedRegistry
import org.agentos.extensions.registry.PluginRecord
import org.agentos.extensions.registry.PluginRegistry
import org.agentos.extensions.registry.PluginScanLogic
import org.agentos.extensions.registry.PluginStatus
import org.agentos.extensions.registry.PolicyHealth
import org.agentos.extensions.registry.RegistryEvent
import org.agentos.extensions.registry.ScanResult
import org.agentos.runtime.broker.ApprovalMode
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.PolicyScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Extension Host（:ext）里的插件注册表与用户策略：[AppPluginScanner] 读 PackageManager，规则全在 A 的
 * [PluginScanLogic]；记忆（[PersistedRegistry]）和策略（[ApprovalStore]）都存在 :ext 的 CE 私有目录 `files/ext/`。
 * **:ext 是策略的唯一写入方**；运行时（:agent）经 IExtensionHost.getCatalog 拿到同一份策略的副本。
 *
 * - 记忆文件不存在（第一次运行）→ 空记忆；存在但读不出来 → null（记忆丢失，第三方插件一律“签名未确认”，A8-reg-2）。
 * - 扫描在 [ApprovalStore.update] 里做（扫描是纯函数），所以和插件页的并发改动不会互相覆盖；
 *   策略文件坏了（fail closed）时扫描照常，只是不写策略。
 * - [events] 发出扫描产生的事件；Extension Host 要执行其中的 [RegistryEvent.Revoke]（关闭连接、作废授权）。
 *
 * 线程：所有方法可以在任何非主线程调用；互相之间串行。
 */
class ExtRegistry(
    private val context: Context,
    private val scanner: AppPluginScanner = AppPluginScanner(context),
    dir: File = File(context.filesDir, "ext"),
) {
    private val registryFile = FileBackedTextFile(File(dir, REGISTRY_FILE))
    val approvals = ApprovalStore(
        primary = FileBackedTextFile(File(dir, POLICY_FILE)),
        backup = FileBackedTextFile(File(dir, POLICY_BACKUP_FILE)),
    )
    private val builtinPackages = setOf(context.packageName)

    private val registryFlow = MutableStateFlow(PluginRegistry())
    val registry: StateFlow<PluginRegistry> = registryFlow.asStateFlow()

    private val eventsFlow = MutableSharedFlow<RegistryEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RegistryEvent> = eventsFlow.asSharedFlow()

    @Volatile var scans = 0
        private set
    @Volatile var lastScanError: String? = null
        private set
    @Volatile var memoryLost = false
        private set

    /** 重新扫描已安装的 App。返回扫描结果（失败时抛出，注册表保持上一次的结果）。 */
    @Synchronized
    fun rescan(): ScanResult {
        val views = scanner.scanViews()
        val previous = loadMemory()
        memoryLost = previous == null
        var result: ScanResult? = null
        if (policyFailClosed()) {
            // 策略文件坏了且没有可用的副本（fail closed）：扫描照常，策略不写，等用户在插件页重置
            Log.w(TAG, "approval policy is fail-closed; scanning without updating it")
            result = PluginScanLogic.scan(views, previous, approvals.policy.value, builtinPackages)
        } else {
            approvals.update { current ->
                PluginScanLogic.scan(views, previous, current, builtinPackages).also { result = it }.policy
            }
        }
        val r = result!!
        registryFile.writeAtomically(r.persisted.toJson())
        lastGoodMemory = r.persisted
        registryFlow.value = r.registry
        scans++
        lastScanError = null
        r.events.forEach { eventsFlow.tryEmit(it) }
        Log.i(TAG, "scan #$scans: ${r.registry.plugins.size} plugin(s), ${r.events.size} event(s)")
        return r
    }

    /** 扫描失败只记下原因（插件页显示），不抛给调用方。 */
    fun rescanQuietly(): Boolean = try {
        rescan()
        true
    } catch (e: Exception) {
        lastScanError = "${e.javaClass.simpleName}: ${e.message}"
        Log.w(TAG, "scan failed: $lastScanError")
        false
    }

    fun plugin(id: String?): PluginRecord = registry.value[id ?: ""] ?: throw ExtError.notFound(id)

    /** 插件级启用 / 停用。启用要求插件 READY（签名变化 / 未确认的要先 [confirmSignature]）。 */
    @Synchronized
    fun setPluginEnabled(id: String?, enabled: Boolean): PluginRecord {
        val p = plugin(id)
        val name = p.name ?: throw ExtError.notReady("plugin $id has no valid manifest")
        if (enabled && p.status != PluginStatus.READY) throw ExtError.notReady("plugin $id is ${p.status.name.lowercase()}")
        updatePolicy { it.withEnabled(PolicyScope.Plugin(name), enabled) }
        return p
    }

    /** 插件级审批方式；[mode] 为 ""（清除）、"ask"、"always"。 */
    @Synchronized
    fun setPluginApproval(id: String?, mode: String?): PluginRecord {
        ExtError.checkMode(mode)
        val p = plugin(id)
        val name = p.name ?: throw ExtError.notReady("plugin $id has no valid manifest")
        updatePolicy { it.withApproval(PolicyScope.Plugin(name), mode.toApprovalMode()) }
        return p
    }

    /** 策略文件坏了且没有可用的副本：[ApprovalStore.update] 会拒绝，要用户先在插件页重置。 */
    fun policyFailClosed(): Boolean = approvals.health.value.let { it is PolicyHealth.Corrupt && it.using == PolicyHealth.Using.FAIL_CLOSED }

    /** 改用户策略（插件页的全部写入都经这里）。fail closed 时报 unavailable。 */
    fun updatePolicy(change: (ApprovalPolicy) -> ApprovalPolicy): ApprovalPolicy {
        if (policyFailClosed()) throw ExtError.unavailable("the approval policy file is unreadable; reset it first")
        return approvals.update(change)
    }

    /** 用户确认插件的当前签名：记为被信任，重新扫描（恢复可用，仍停用）。 */
    @Synchronized
    fun confirmSignature(id: String?): PluginRecord {
        val p = plugin(id)
        if (p.status != PluginStatus.SIGNATURE_CHANGED && p.status != PluginStatus.SIGNATURE_UNCONFIRMED) {
            throw ExtError.notNeeded("plugin $id has no unconfirmed signature")
        }
        val memory = loadMemory() ?: PersistedRegistry.EMPTY
        val confirmed = PluginScanLogic.confirmSignature(memory, p.id)
        registryFile.writeAtomically(confirmed.toJson())
        lastGoodMemory = confirmed
        rescan()
        return plugin(id)
    }

    /**
     * 读记忆：文件不存在 → 空记忆（首次运行，[PersistedRegistry.EMPTY]）；读不出来 → 本进程里上一份可用的，没有就 null
     * （记忆丢失：PluginScanLogic 把所有第三方插件按“签名未确认”处理，**不能**当空记忆，否则等于把当前签名当可信）。
     */
    private fun loadMemory(): PersistedRegistry? {
        val text = try {
            registryFile.readText()
        } catch (e: Exception) {
            Log.w(TAG, "plugin registry memory unreadable: ${e.javaClass.simpleName}")
            return lastGoodMemory
        } ?: return lastGoodMemory ?: PersistedRegistry.EMPTY
        return try {
            PersistedRegistry.fromJson(text).also { lastGoodMemory = it }
        } catch (e: Exception) {
            Log.w(TAG, "plugin registry memory is corrupt: ${e.javaClass.simpleName}")
            lastGoodMemory
        }
    }

    /** 本进程里最后一份读到或写出的记忆。 */
    @Volatile private var lastGoodMemory: PersistedRegistry? = null

    // ------------------------------------------------------------------ 给插件页的 JSON（IExtensionHost.listPlugins）

    /**
     * 插件 JSON（字段见 IExtensionHost.aidl）。[serverStates] 是“服务器名 → (state, error, toolCount)”，由 Extension Host 的
     * 连接状态提供（没有时按 idle / disabled 推断）。
     */
    fun pluginJson(p: PluginRecord, serverStates: Map<String, ServerView> = emptyMap()): JSONObject {
        val policy = approvals.policy.value
        val entry = p.name?.let { policy.plugins[it]?.entry }
        val enabled = p.name != null && policy.pluginEnabled(p.name!!)
        val m = p.manifest
        val versionName = runCatching {
            context.packageManager.getPackageInfo(p.identity.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        }.getOrNull()
        return JSONObject()
            .put("id", p.id)
            .put("source", "app")
            .put("packageName", p.identity.packageName)
            .put("name", p.name ?: JSONObject.NULL)
            .put("displayName", m?.displayName ?: p.name ?: p.identity.packageName)
            .put("description", m?.description ?: m?.display?.shortDescription ?: JSONObject.NULL)
            .put("versionName", versionName ?: JSONObject.NULL)
            .put("versionCode", p.identity.versionCode)
            .put("signingDigest", p.identity.signerDigest)
            .put("status", p.status.name.lowercase())
            .put("unavailableReason", p.unavailableReason?.name?.lowercase() ?: JSONObject.NULL)
            .put("builtin", p.builtin)
            .put("enabled", enabled)
            .put("approval", entry?.approval?.wire ?: JSONObject.NULL)
            .put("problems", JSONArray(p.problems.map { JSONObject().put("code", it.code).put("message", it.message) }))
            .put("unsupported", JSONArray(m?.unsupported.orEmpty().map {
                JSONObject().put("kind", it.kind.name.lowercase()).put("location", it.location).put("detail", it.reason)
            }))
            .put("servers", JSONArray(p.servers.map { s ->
                val v = serverStates[s.name]
                val state = v?.state ?: when {
                    p.status != PluginStatus.READY || !enabled -> "disabled"
                    else -> "idle"
                }
                JSONObject().put("name", s.name)
                    .apply {
                        when (s) {
                            is McpServerDecl.Binder -> put("service", s.service)
                            is McpServerDecl.StreamableHttp -> put("url", s.url)
                        }
                    }
                    .put("state", state).put("error", v?.error ?: JSONObject.NULL).put("toolCount", v?.toolCount ?: 0)
            }))
            .put("rejectedServers", JSONArray(p.rejectedServers.map {
                JSONObject().put("name", it.name).put("service", it.service).put("reason", it.reason.name.lowercase())
            }))
            .put("toolCount", serverStates.values.sumOf { it.toolCount })
    }

    /** 一个服务器在插件页上的状态。 */
    data class ServerView(val state: String, val error: String?, val toolCount: Int)

    /** 诊断（不含插件内容）。 */
    fun diagnostics(): JSONObject = JSONObject()
        .put("plugins", registry.value.plugins.size)
        .put("scans", scans)
        .put("lastScanError", lastScanError ?: JSONObject.NULL)
        .put("memoryLost", memoryLost)
        .put("policy", policyStatus())
        .put("policyHealth", when (val h = approvals.health.value) {
            is PolicyHealth.Ok -> "ok"
            is PolicyHealth.Corrupt -> "corrupt(${h.using.name.lowercase()})"
        })

    /** 用户策略文件的状态（IExtensionHost.getPolicyStatus，插件页显示）。 */
    fun policyStatus(): JSONObject {
        val h = approvals.health.value as? PolicyHealth.Corrupt
        return JSONObject()
            .put("health", if (h == null) "ok" else "corrupt")
            .put("using", h?.using?.name?.lowercase() ?: JSONObject.NULL)
            .put("reason", h?.reason ?: JSONObject.NULL)
            .put("failClosed", policyFailClosed())
            .put("lastBackupError", approvals.lastBackupError ?: JSONObject.NULL)
    }

    /**
     * 用户确认“重置策略”（IExtensionHost.resetPolicy）：[ApprovalStore.resetToDefault]，现有的第三方插件都写成停用，
     * 重置不会把它们重新启用。任何状态下都可以调用。
     */
    @Synchronized
    fun resetPolicy(): JSONObject {
        val thirdParty = registry.value.plugins.filter { !it.builtin }.mapNotNull { it.name }
        approvals.resetToDefault(thirdParty)
        Log.i(TAG, "approval policy reset; ${thirdParty.size} third-party plugin(s) left disabled")
        return policyStatus()
    }

    companion object {
        private const val TAG = "ExtRegistry"
        const val REGISTRY_FILE = "plugin-registry.json"
        const val POLICY_FILE = "approval-policy.json"
        const val POLICY_BACKUP_FILE = "approval-policy.backup.json"
    }
}

/** IExtensionHost 的 mode 参数 → ApprovalMode（"" 表示清除这一层）。调用前已经 [ExtError.checkMode]。 */
internal fun String?.toApprovalMode(): ApprovalMode? = when (this) {
    "ask" -> ApprovalMode.ASK
    "always" -> ApprovalMode.ALWAYS
    else -> null
}
