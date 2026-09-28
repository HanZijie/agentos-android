package org.agentos.app.agent

import android.app.Application
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.agentos.runtime.pi.JsEngineFactory
import org.agentos.runtime.pi.ModelCatalog
import org.agentos.runtime.pi.PiBundle
import org.agentos.runtime.pi.testing.PiAdapterContract
import kotlin.test.Test
import kotlin.test.assertEquals

/** Test helpers shared by the device tests of lane B. */
internal object Device {
    const val AGENT_PROCESS = "org.agentos.app:agent"
    const val TAG = "AgentOS-B3"

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
}

/**
 * The AgentCore contract of core:runtime (testFixtures `PiAdapterContract`, the same scenarios the
 * JVM runs on quickjs-kt-jvm) on this device: PiAdapter + the APK's `assets/pi-agent.js` on the
 * Android [QuickJsEngine], in the `:agent` process, against the in-process fake model endpoint.
 */
class PiAdapterDeviceContractTest : PiAdapterContract() {
    override val engineFactory: JsEngineFactory = QuickJsEngine.factory
    override val scenarioTimeoutMs: Long = 60_000

    override fun loadBundle(): PiBundle = runBlocking { PiAgentCores.loadBundle(Device.context) }
    override fun loadCatalog(): ModelCatalog = PiAgentCores.loadCatalog(Device.context)

    @Test
    fun runsInTheAgentProcess() {
        assertEquals(Device.AGENT_PROCESS, Application.getProcessName())
    }
}
