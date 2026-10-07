package org.agentos.extensions.registry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.ApprovalPolicyFormatException
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.ports.ApprovalPolicyPort
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 一个可以整体读、整体原子写的文本文件。Android 与电脑上的测试都用 [FileBackedTextFile]；测试用假实现注入失败。 */
interface AtomicTextFile {
    /** 全文；文件不存在返回 null。读不了抛 [IOException]。 */
    fun readText(): String?

    /** 整体替换：写不完整的文件绝不能出现在目标路径上（失败时目标保持原样）。失败抛 [IOException]。 */
    fun writeAtomically(text: String)
}

/** 先写同目录下的临时文件、`fsync`，再 rename 覆盖目标（能原子移动时原子移动）。 */
class FileBackedTextFile(private val file: File) : AtomicTextFile {
    override fun readText(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    override fun writeAtomically(text: String) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("no parent directory for $file")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val tmp = File(dir, "${file.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmp.delete()
            throw if (e is IOException) e else IOException(e)
        }
    }
}

/** 策略读出来了没有、现在用的是哪一份。给插件页显示。 */
sealed interface PolicyHealth {
    /** 正常：文件读到了，或者还没有文件（第一次运行，全部默认）。 */
    data object Ok : PolicyHealth

    /**
     * 文件读不出来（[reason]：版本缺失、结构坏、IO 错误…）。[using] 是现在实际生效的策略来源。
     * 这时 [ApprovalStore.update] 在 [Using.FAIL_CLOSED] 下被拒绝，其余照常（写成功后回到 [Ok]）。
     */
    data class Corrupt(val reason: String, val using: Using) : PolicyHealth

    enum class Using {
        /** 这个进程里上一份读到或写过的策略。 */
        PREVIOUS,

        /** 备份文件里的策略。 */
        BACKUP,

        /** 没有任何可用的策略：除自带插件外，所有插件的工具都当作禁用（fail closed）。 */
        FAIL_CLOSED,
    }
}

/**
 * 用户策略（[ApprovalPolicy]）的存取，同时是 `HostPort.approvals`（[ApprovalPolicyPort]）：[policy] 在每次改动后发布新值，
 * CapabilityBroker 每次调用前读它。不依赖 Android。
 *
 * **写**：[update] 在持有锁时算出新策略，经 [primary] 原子写入（先写临时文件再 rename）；写成功才发布；写失败抛 [IOException]，
 * 当前策略不变。成功后同样写 [backup]（尽力而为，失败只记在 [lastBackupError]）。
 *
 * **读**（[load]，构造时自动调用一次）：先读 [primary]；
 * - 没有文件 → 全部默认，[PolicyHealth.Ok]；
 * - 读不出来（版本缺失、结构坏、IO 错误）→ **不回到默认**（那会把用户禁用的插件重新启用）：
 *   依次用这个进程里上一份可用的、[backup] 里的；都没有就 **fail closed**——`unlistedPluginsEnabled = false`，
 *   除 [builtinPlugins] 外的插件全部当作禁用。状态在 [health]，插件页要显示。
 *   fail closed 时 [update] 抛 [IllegalStateException]，用户要先 [resetToDefault]（显式确认丢掉原来的设置）。
 *
 * @param builtinPlugins AgentOS 自带插件的插件名；fail closed 时它们仍然启用
 */
class ApprovalStore(
    private val primary: AtomicTextFile,
    private val backup: AtomicTextFile? = null,
    private val builtinPlugins: Set<String> = emptySet(),
) : ApprovalPolicyPort {
    private val lock = Any()
    private val policyFlow = MutableStateFlow(ApprovalPolicy.DEFAULT)
    private val healthFlow = MutableStateFlow<PolicyHealth>(PolicyHealth.Ok)
    private var lastGood: ApprovalPolicy? = null

    override val policy: StateFlow<ApprovalPolicy> = policyFlow.asStateFlow()
    val health: StateFlow<PolicyHealth> = healthFlow.asStateFlow()

    /** 最近一次写备份失败的原因；没有失败为 null。 */
    @Volatile var lastBackupError: String? = null
        private set

    init {
        load()
    }

    /** 重新从文件读（构造时已经读过一次）。返回读之后的状态。 */
    fun load(): PolicyHealth = synchronized(lock) {
        val attempt = readFile(primary)
        when (attempt) {
            is Read.Missing -> {
                // 第一次运行（或文件被删）：如果这个进程里有上一份可用的就继续用它，否则全部默认
                publish(lastGood ?: ApprovalPolicy.DEFAULT, PolicyHealth.Ok)
            }
            is Read.Good -> {
                lastGood = attempt.policy
                publish(attempt.policy, PolicyHealth.Ok)
            }
            is Read.Bad -> {
                val previous = lastGood
                val fromBackup = if (previous == null && backup != null) (readFile(backup) as? Read.Good)?.policy else null
                when {
                    previous != null -> publish(previous, PolicyHealth.Corrupt(attempt.reason, PolicyHealth.Using.PREVIOUS))
                    fromBackup != null -> {
                        lastGood = fromBackup
                        publish(fromBackup, PolicyHealth.Corrupt(attempt.reason, PolicyHealth.Using.BACKUP))
                    }
                    else -> publish(failClosed(), PolicyHealth.Corrupt(attempt.reason, PolicyHealth.Using.FAIL_CLOSED))
                }
            }
        }
        healthFlow.value
    }

    /**
     * 改策略：[change] 收到当前策略，返回新策略；原子写入后发布并返回新策略。
     * 写失败抛 [IOException]（当前策略不变）；fail closed 时抛 [IllegalStateException]。
     */
    fun update(change: (ApprovalPolicy) -> ApprovalPolicy): ApprovalPolicy = synchronized(lock) {
        val h = healthFlow.value
        check(!(h is PolicyHealth.Corrupt && h.using == PolicyHealth.Using.FAIL_CLOSED)) {
            "the approval policy file is unreadable and no earlier copy exists; call resetToDefault first"
        }
        val next = change(policyFlow.value).copy(unlistedPluginsEnabled = true)
        write(next)
        next
    }

    /**
     * 策略文件读不出来、又没有可用的备份时，用户明确选择“重置”：写入全新的策略，其中 [disablePlugins] 里的插件
     * （所有现有的第三方插件名）写成停用，避免重置把它们全部重新启用。任何状态下都可以调用。
     */
    fun resetToDefault(disablePlugins: Collection<String> = emptyList()): ApprovalPolicy = synchronized(lock) {
        var fresh = ApprovalPolicy.DEFAULT
        for (name in disablePlugins) fresh = fresh.withEnabled(PolicyScope.Plugin(name), false)
        write(fresh)
        fresh
    }

    private fun write(next: ApprovalPolicy) {
        primary.writeAtomically(next.toJson())
        try {
            backup?.writeAtomically(next.toJson())
            lastBackupError = null
        } catch (e: Exception) {
            lastBackupError = e.javaClass.simpleName
        }
        lastGood = next
        publish(next, PolicyHealth.Ok)
    }

    private fun publish(policy: ApprovalPolicy, health: PolicyHealth) {
        policyFlow.value = policy
        healthFlow.value = health
    }

    private fun failClosed(): ApprovalPolicy {
        var p = ApprovalPolicy(unlistedPluginsEnabled = false)
        for (name in builtinPlugins) p = p.withEnabled(PolicyScope.Plugin(name), true)
        return p
    }

    private sealed interface Read {
        data object Missing : Read

        data class Good(val policy: ApprovalPolicy) : Read

        data class Bad(val reason: String) : Read
    }

    private fun readFile(file: AtomicTextFile): Read {
        val text = try {
            file.readText()
        } catch (e: IOException) {
            return Read.Bad("io: ${e.javaClass.simpleName}")
        } ?: return Read.Missing
        return try {
            Read.Good(ApprovalPolicy.fromJson(text))
        } catch (e: ApprovalPolicyFormatException) {
            Read.Bad(e.message ?: "format")
        }
    }
}
