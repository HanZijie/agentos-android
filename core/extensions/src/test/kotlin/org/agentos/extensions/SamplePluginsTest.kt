package org.agentos.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 三个示例 App（docs/sample-apps.md）的插件清单与工具名：契约里的写法必须被接受，工具名必须唯一且合法。 */
class SamplePluginsTest {
    private val schema = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"

    private val tools = mapOf(
        "alarm" to "alarm_list alarm_get alarm_create alarm_update alarm_set_enabled alarm_delete alarm_next alarm_dismiss",
        "calendar" to "calendar_list calendar_create calendar_delete event_list event_get event_create event_update event_delete event_search agenda_today free_slots",
        "notes" to "note_list note_get note_create note_update note_append note_search note_trash note_restore note_delete tag_list",
    )

    private fun pluginJson(name: String) = """
        {"${'$'}schema":"$schema","name":"$name","version":"1.0.0","description":"sample $name",
         "extensions":{"com.openai":{"interface":{"displayName":"示例 $name"}},
           "org.agentos":{"mcpServers":{"$name":{"service":"org.agentos.sample.$name.agent.${name.replaceFirstChar { it.uppercase() }}McpService"}}}}}
    """.trimIndent()

    @Test
    fun `the sample plugins are accepted as installed app plugins and refused as imported packages`() {
        for (name in tools.keys) {
            val r = ManifestReader.read(PluginFiles(pluginJson(name), skillFiles = listOf("skills/$name/SKILL.md")), PluginOrigin.INSTALLED_APP)
            assertIs<ManifestResult.Accepted>(r)
            assertEquals(listOf(name), r.manifest.servers.map { it.name })
            assertEquals(listOf(SkillRef(name, "skills/$name/SKILL.md")), r.manifest.skills)
            assertEquals("示例 $name", r.manifest.displayName)
            val imported = ManifestReader.read(PluginFiles(pluginJson(name)), PluginOrigin.IMPORTED)
            assertEquals(listOf(ManifestErrorCode.BINDER_NOT_ALLOWED), (imported as ManifestResult.Rejected).errors.map { it.code })
        }
    }

    @Test
    fun `all sample tools get unique valid names, unchanged by the catalog they sit in`() {
        val ids = tools.flatMap { (plugin, names) -> names.split(' ').map { ToolId(plugin, plugin, it) } }
        val names = ToolNaming.assign(ids)
        assertEquals(ids.size, names.values.toSet().size)
        assertEquals("mcp__alarm__alarm__alarm_delete", names.getValue(ToolId("alarm", "alarm", "alarm_delete")))
        assertEquals("mcp__calendar__calendar__event_search", names.getValue(ToolId("calendar", "calendar", "event_search")))
        for ((id, name) in names) {
            assertTrue(name.length <= 64 && Regex("[A-Za-z0-9_-]+").matches(name), name)
            assertEquals(ToolNaming.nameOf(id), name, "no collisions among the samples, so the plain name is used")
        }
    }
}
