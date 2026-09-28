package org.agentos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [QuickJsEngine.QUICKJS_KT_VERSION] is part of the bytecode cache key: if it lagged behind the
 * real quickjs-kt version, bytecode from the old engine could be loaded by the new one.
 */
class QuickJsEngineVersionTest {

    @Test
    fun versionConstantMatchesTheVersionCatalog() {
        // Unit tests run in the module directory.
        val toml = File("../gradle/libs.versions.toml").readText()
        val pinned = Regex("""(?m)^quickjs-kt\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)
        assertEquals(pinned, QuickJsEngine.QUICKJS_KT_VERSION)
    }
}
