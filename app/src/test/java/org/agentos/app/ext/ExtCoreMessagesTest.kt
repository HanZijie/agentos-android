package org.agentos.app.ext

import org.agentos.app.i18n.ResStrings
import org.agentos.extensions.ExtMessages
import org.agentos.runtime.i18n.MessageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** core/extensions 产出的每个 key，在 app 里都有登记、中英文模板齐全、占位符个数和核心层说的参数个数一致。 */
class ExtCoreMessagesTest {
    private val both = listOf(ResStrings.zh, ResStrings.en)
    private val placeholder = Regex("%(\\d+)[$]s")

    @Test
    fun everyCoreKeyHasAnEntryAndATemplateInBothLanguagesWithTheRightPlaceholders() {
        assertEquals("the table and the core registry list the same keys", ExtMessages.ALL.toSet(), ExtCoreMessages.keys)
        for (key in ExtMessages.ALL) {
            val entry = ExtCoreMessages.entry(key)
            assertNotNull("no entry for $key", entry)
            assertEquals("arity of $key", ExtMessages.ARITY.getValue(key), entry!!.arity)
            for (s in both) {
                val template = s.template(key)
                assertEquals("the entry of $key points at the resource of the same name (${s.locale})", template, s.template(entry.id))
                val numbers = placeholder.findAll(template).map { it.groupValues[1].toInt() }.toSet()
                assertEquals("placeholders of $key in ${s.locale}: <$template>", (1..entry.arity).toSet(), numbers)
            }
        }
    }

    @Test
    fun renderingFillsTheArgumentsAndNeverCrashes() {
        for (s in both) {
            // an unknown key shows the key, a missing argument is empty
            assertEquals("ext_msg_from_the_future", ExtCoreMessages.render(MessageRef.of("ext_msg_from_the_future", "x"), s))
            assertEquals(s.get(org.agentos.app.R.string.ext_msg_duplicate_server, ""), ExtCoreMessages.render(MessageRef.of(ExtMessages.DUPLICATE_SERVER), s))
            // extra arguments are ignored
            assertEquals(s.get(org.agentos.app.R.string.ext_msg_not_json), ExtCoreMessages.render(MessageRef.of(ExtMessages.NOT_JSON, "extra"), s))
            // arguments are plain text: no format characters are interpreted
            assertTrue(ExtCoreMessages.render(MessageRef.of(ExtMessages.DUPLICATE_SERVER, "%s%1\$d{0}"), s).contains("%s%1\$d{0}"))
        }
        assertEquals("The server name “a” appears more than once in the same plugin", ExtCoreMessages.render(MessageRef.of(ExtMessages.DUPLICATE_SERVER, "a"), ResStrings.en))
        assertEquals("服务器名「a」在同一个插件里出现了不止一次", ExtCoreMessages.render(MessageRef.of(ExtMessages.DUPLICATE_SERVER, "a"), ResStrings.zh))
        assertEquals(
            "Server “hidden” (Service class “org.x.Hidden”) was rejected: this Service is not exported",
            ExtCoreMessages.render(MessageRef.of(ExtMessages.SERVER_REJECTED_NOT_EXPORTED, "hidden", "org.x.Hidden"), ResStrings.en),
        )
    }
}
