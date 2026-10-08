package org.agentos.app.i18n

import org.agentos.runtime.consent.ConsentMessages
import org.agentos.runtime.i18n.MessageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 核心层产出的每个文案 key，在 app 里都有登记，且中英文模板都在、占位符个数和登记的一致、不会多填少填。 */
class CoreMessagesTest {
    private val placeholder = Regex("%(\\d+)[$]s")

    @Test
    fun everyCoreKeyIsRegisteredAndHasBothLanguages() {
        for (key in ConsentMessages.ALL) {
            val entry = CoreMessages.entry(key)
            assertNotNull("core key $key has no entry in CoreMessages", entry)
            for (strings in listOf(ResStrings.zh, ResStrings.en)) {
                val template = strings.template(entry!!.id)
                val used = placeholder.findAll(template).map { it.groupValues[1].toInt() }.toSet()
                assertEquals("$key in ${strings.locale}: placeholders of <$template>", (1..entry.arity).toSet(), used)
            }
        }
        assertEquals("CoreMessages registers exactly the keys the core defines", ConsentMessages.ALL.toSet(), CoreMessages.keys)
    }

    @Test
    fun aRefIsFilledInOrderInBothLanguages() {
        val title = MessageRef.of(ConsentMessages.TITLE, "note_create")
        assertEquals("要允许「note_create」吗？", ResStrings.zh.get(title))
        assertEquals("Allow “note_create”?", ResStrings.en.get(title))
        val app = MessageRef.of(ConsentMessages.INITIATOR_APP, "Notes", "org.agentos.sample.notes")
        assertEquals("由 Notes 发起（org.agentos.sample.notes）", ResStrings.zh.get(app))
        assertEquals("Requested by Notes (org.agentos.sample.notes)", ResStrings.en.get(app))
        val source = MessageRef.of(ConsentMessages.SOURCE, "notes", "main")
        assertEquals("来自插件「notes」 · 服务器「main」", ResStrings.zh.get(source))
        assertEquals("From plugin “notes” · server “main”", ResStrings.en.get(source))
        assertEquals("由电脑端发起", ResStrings.zh.get(MessageRef.of(ConsentMessages.INITIATOR_DESKTOP)))
        assertEquals("Requested by your computer", ResStrings.en.get(MessageRef.of(ConsentMessages.INITIATOR_DESKTOP)))
    }

    @Test
    fun anUnknownKeyOrMissingArgumentsNeverCrash() {
        assertEquals("some_future_key", ResStrings.en.get(MessageRef.of("some_future_key", "x")))
        assertEquals("要允许「」吗？", ResStrings.zh.get(MessageRef.of(ConsentMessages.TITLE)))
        assertTrue(ResStrings.en.get(MessageRef.of(ConsentMessages.SOURCE, "only-plugin")).contains("only-plugin"))
    }
}
