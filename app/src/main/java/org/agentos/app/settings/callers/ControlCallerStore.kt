package org.agentos.app.settings.callers

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.agentos.app.settings.AgentControlClient

/** [CallerStore] 的真实现：绑定 `:agent` 的 IAgentControl v5（每批调用绑一次）。在 IO 线程调用。 */
class ControlCallerStore(context: Context) : CallerStore {
    private val control = AgentControlClient(context)

    override fun list(): String? = runBlocking { control.use { it.listAcpCallers() } }

    override fun set(packageName: String, state: String) {
        runBlocking { control.use { it.setAcpCaller(packageName, state) } }
    }
}
