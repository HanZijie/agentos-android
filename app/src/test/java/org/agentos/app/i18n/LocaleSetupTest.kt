package org.agentos.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 语言的接线（docs/next-apps-plan.md 7.2 R5、D8），设备上验证过、这里防回退：
 * - 要有一个带 zh 限定的资源（`xml-zh/` 里的占位，文字全在未加限定的 `values/`）。没有它，系统在应用语言 zh + 系统语言 en-US、
 *   或系统语言列表 [zh-CN, en-US] 时会改选 `values-en`：中文用户看到英文。
 * - `locales_config.xml` 只列 zh 和 en，manifest 声明了它（系统设置里才有“应用语言”）。
 */
class LocaleSetupTest {
    private fun res(path: String): File =
        listOf(File("src/main/res/$path"), File("app/src/main/res/$path")).firstOrNull { it.exists() } ?: error("missing res/$path")

    private fun names(file: File, tag: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttribute("name").ifEmpty { (nodes.item(it) as org.w3c.dom.Element).getAttribute("android:name") } }
    }

    @Test
    fun theApkListsChineseThroughAMarkerNotThroughAValuesZhFolder() {
        // not values-zh: lint's MissingTranslation would want every key translated into it (a second copy of the Chinese)
        assertFalse("values-zh must not exist", listOf(File("src/main/res/values-zh"), File("app/src/main/res/values-zh")).any { it.exists() })
        // the marker differs from the default (aapt2 drops what equals it), and the default exists (lint MissingDefaultResource)
        assertEquals("zh", attribute(res("xml-zh/agentos_locale_marker.xml"), "language"))
        assertEquals("default", attribute(res("xml/agentos_locale_marker.xml"), "language"))
        // the Chinese text itself stays in values/, English in values-en/
        assertTrue(ResStrings.zh.template("ui_hint_input").any { it.code in 0x4E00..0x9FFF })
    }

    private fun attribute(file: File, name: String): String =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.getAttribute(name)

    @Test
    fun localeConfigListsChineseAndEnglishAndTheManifestDeclaresIt() {
        assertEquals(listOf("zh", "en"), names(res("xml/locales_config.xml"), "locale"))
        val manifest = (File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")).readText()
        assertTrue(manifest.contains("android:localeConfig=\"@xml/locales_config\""))
    }
}
