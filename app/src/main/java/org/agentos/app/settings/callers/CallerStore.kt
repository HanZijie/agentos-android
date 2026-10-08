package org.agentos.app.settings.callers

import android.content.Context

/**
 * “已授权的应用”页读写 `:agent` 里调用方注册表的出口（C 的 `IAgentControl.listAcpCallers / setAcpCaller`，v5）。
 * 抽成接口让页面逻辑可以用假数据跑；真实现 [ControlCallerStore] 在 IAgentControl v5 合入后接上。
 * 出错抛异常（页面统一显示固定文案，不回显原始异常文字）。在 IO 线程调用。
 */
interface CallerStore {
    /** `listAcpCallers()` 的 JSON 数组。 */
    fun list(): String?

    /** `setAcpCaller(packageName, state)`，state 是 `allowed` / `denied` / `removed`；返回更新后的 JSON 数组（或 null）。 */
    fun set(packageName: String, state: String)

    companion object {
        /** IAgentControl v5 合入前：没有真实现，页面显示“运行时版本过旧”。 */
        fun default(context: Context): CallerStore = object : CallerStore {
            override fun list(): String? = throw IllegalStateException("IAgentControl v5 not available")
            override fun set(packageName: String, state: String) = throw IllegalStateException("IAgentControl v5 not available")
        }
    }
}
