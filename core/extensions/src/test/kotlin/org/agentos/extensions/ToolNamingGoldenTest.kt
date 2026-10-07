package org.agentos.extensions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Python 侧的工具名计算（tests/device/acp-channel/scripted_tools.py，设备验收用的假模型脚本里写原始三元组）必须和 [ToolNaming.nameOf] 一致：
 * 两边读同一份 tests/device/acp-channel/tool_naming_golden.json（Python 侧在 unit/test_scripted_tools.py 里核对）。
 */
class ToolNamingGoldenTest {
    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").isFile) d = d.parentFile
        return d ?: error("settings.gradle.kts not found")
    }

    @Test
    fun `the golden vectors shared with the Python fake model match ToolNaming`() {
        val file = File(repoRoot(), "tests/device/acp-channel/tool_naming_golden.json")
        val vectors = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonArray
        assertTrue(vectors.size >= 10, "the golden file has vectors")
        for (v in vectors) {
            val o = v.jsonObject
            val id = ToolId(o["plugin"]!!.jsonPrimitive.content, o["server"]!!.jsonPrimitive.content, o["tool"]!!.jsonPrimitive.content)
            assertEquals(o["name"]!!.jsonPrimitive.content, ToolNaming.nameOf(id), "name of $id")
        }
    }
}
