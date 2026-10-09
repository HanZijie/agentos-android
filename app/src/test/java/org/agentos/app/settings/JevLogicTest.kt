package org.agentos.app.settings

import org.agentos.app.i18n.ResStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JevLogicTest {
    private val none = """{"configured":false,"endpoint":"https://api.typesafe.ai/v1/systemone","defaultEndpoint":"https://api.typesafe.ai/v1/systemone","customEndpoint":false,"keySet":false,"keyMasked":null,"usable":false,"problems":[]}"""
    private val set = """{"configured":true,"endpoint":"https://api.typesafe.ai/v1/systemone","defaultEndpoint":"https://api.typesafe.ai/v1/systemone","customEndpoint":false,"keySet":true,"keyMasked":"jev-…wxyz","usable":true,"problems":[]}"""
    private val zh = ResStrings.zh
    private val en = ResStrings.en

    @Test
    fun parsesStatus() {
        val a = Jev.parse(none)
        assertFalse(a.configured)
        assertNull(a.keyMasked)
        val b = Jev.parse(set)
        assertEquals("jev-…wxyz", b.keyMasked)
        val broken = Jev.parse(set.replace("\"usable\":true", "\"usable\":false").replace("\"problems\":[]", "\"problems\":[\"key_unreadable\"]"))
        assertEquals(listOf("key_unreadable"), broken.problems)
    }

    @Test
    fun statusTextInChinese() {
        assertTrue(Jev.statusText(Jev.parse(none), zh).startsWith("未配置"))
        assertEquals("已启用", Jev.statusText(Jev.parse(set), zh))
        assertEquals("已保存，但暂时不可用", Jev.statusText(Jev.parse(set.replace("\"usable\":true", "\"usable\":false")), zh))
        assertTrue(Jev.problemText("key_unreadable", zh).contains("重新填写"))
    }

    @Test
    fun statusTextInEnglish() {
        assertEquals("Not configured: when a caller has several past sessions, a new session is created every time", Jev.statusText(Jev.parse(none), en))
        assertEquals("Enabled", Jev.statusText(Jev.parse(set), en))
        assertEquals("Saved, but not usable right now", Jev.statusText(Jev.parse(set.replace("\"usable\":true", "\"usable\":false")), en))
        assertTrue(Jev.problemText("key_unreadable", en).contains("enter the key again"))
        assertEquals("something_new", Jev.problemText("something_new", en))
    }

    @Test
    fun validation() {
        for (s in listOf(zh, en)) {
            val cur = Jev.parse(set)
            assertNotNull("nothing saved and no key", Jev.validate("", keyEntered = false, current = Jev.parse(none), strings = s))
            assertNull(Jev.validate("", keyEntered = true, current = Jev.parse(none), strings = s))
            assertNull("keep the saved key on the same endpoint", Jev.validate("", keyEntered = false, current = cur, strings = s))
            assertNull(Jev.validate(cur.endpoint, keyEntered = false, current = cur, strings = s))
            assertEquals("key", Jev.validate("https://other.example/v1", keyEntered = false, current = cur, strings = s)?.field)
            assertNull(Jev.validate("https://other.example/v1", keyEntered = true, current = cur, strings = s))
            // F9 endpoint rule
            assertEquals("endpoint", Jev.validate("http://jev.example.com/v1", keyEntered = true, current = cur, strings = s)?.field)
            assertNull(Jev.validate("http://127.0.0.1:18788/v1/systemone", keyEntered = true, current = cur, strings = s))
            assertEquals("endpoint", Jev.validate("https://u:p@jev.example.com/", keyEntered = true, current = cur, strings = s)?.field)
        }
        assertEquals("请填写 Jev key", Jev.validate("", false, Jev.parse(none), zh)?.message)
        assertEquals("Enter the Jev key", Jev.validate("", false, Jev.parse(none), en)?.message)
        assertEquals("换了地址，需要重新填写 key", Jev.validate("https://other.example/v1", false, Jev.parse(set), zh)?.message)
        assertEquals("You changed the address, so enter the key again", Jev.validate("https://other.example/v1", false, Jev.parse(set), en)?.message)
    }

    @Test
    fun errorTextsNeverEchoInputInChinese() {
        assertTrue(Jev.errorText("agentos.jev.invalid_endpoint: the endpoint must be https", zh).startsWith("地址不合法"))
        assertTrue(Jev.errorText("agentos.jev.key_required: x", zh).contains("重新填写 key"))
        assertEquals("操作失败，请重试", Jev.errorText(null, zh))
        assertEquals("操作失败（brand_new）", Jev.errorText("agentos.jev.brand_new: sk-secret", zh))
    }

    @Test
    fun errorTextsNeverEchoInputInEnglish() {
        assertTrue(Jev.errorText("agentos.jev.invalid_endpoint: the endpoint must be https", en).startsWith("Invalid address"))
        assertTrue(Jev.errorText("agentos.jev.key_required: x", en).contains("enter the key again"))
        assertEquals("That didn't work. Please try again.", Jev.errorText(null, en))
        assertEquals("That didn't work (brand_new)", Jev.errorText("agentos.jev.brand_new: sk-secret", en))
    }

    @Test
    fun explainSaysWhatIsSentAndWhatIsNotInBothLanguages() {
        // session-selection.md: said before the user enters a key — what goes out (question + summaries) and that the model key does not
        assertTrue(Jev.explain(zh).contains("摘要含会话里的问答文字，不含模型 key"))
        assertTrue(Jev.explain(en).contains("The summaries contain the question-and-answer text of the sessions, not the model key."))
        assertTrue(Jev.explain(en).contains("sends the current question and summaries of that caller's own few most recent sessions to the Jev service"))
    }
}
