package org.agentos.runtime.pi

import org.agentos.runtime.pi.desktop.QuickJsJvmEngine
import org.agentos.runtime.pi.testing.PiAdapterContract

/**
 * The AgentCore contract on the real Pi, desktop engine (quickjs-kt-jvm). The scenarios live in
 * testFixtures ([PiAdapterContract]) so the app's instrumented tests run the same ones on a device
 * with the Android QuickJsEngine.
 */
class PiAdapterContractTest : PiAdapterContract() {
    override val engineFactory: JsEngineFactory = QuickJsJvmEngine.factory
    override fun loadBundle(): PiBundle? = PiAssets.bundle
    override fun loadCatalog(): ModelCatalog? = PiAssets.catalog
}
