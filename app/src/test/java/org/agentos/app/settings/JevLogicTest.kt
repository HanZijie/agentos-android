package org.agentos.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JevLogicTest {
    private val none = """{"configured":false,"endpoint":"https://api.typesafe.ai/v1/systemone","defaultEndpoint":"https://api.typesafe.ai/v1/systemone","customEndpoint":false,"keySet":false,"keyMasked":null,"usable":false,"problems":[]}"""
    private val set = """{"configured":true,"endpoint":"https://api.typesafe.ai/v1/systemone","defaultEndpoint":"https://api.typesafe.ai/v1/systemone","customEndpoint":false,"keySet":true,"keyMasked":"jev-…wxyz","usable":true,"problems":[]}"""

    @Test
    fun parsesStatus() {
        val a = Jev.parse(none)
        assertFalse(a.configured)
        assertNull(a.keyMasked)
        assertTrue(Jev.statusText(a).startsWith("未配置"))
        val b = Jev.parse(set)
        assertEquals("jev-…wxyz", b.keyMasked)
        assertEquals("已启用", Jev.statusText(b))
        val broken = Jev.parse(set.replace("\"usable\":true", "\"usable\":false").replace("\"problems\":[]", "\"problems\":[\"key_unreadable\"]"))
        assertEquals(listOf("key_unreadable"), broken.problems)
        assertTrue(Jev.problemText("key_unreadable").contains("重新填写"))
    }

    @Test
    fun validation() {
        val cur = Jev.parse(set)
        assertNotNull("nothing saved and no key", Jev.validate("", keyEntered = false, current = Jev.parse(none)))
        assertNull(Jev.validate("", keyEntered = true, current = Jev.parse(none)))
        assertNull("keep the saved key on the same endpoint", Jev.validate("", keyEntered = false, current = cur))
        assertNull(Jev.validate(cur.endpoint, keyEntered = false, current = cur))
        assertEquals("key", Jev.validate("https://other.example/v1", keyEntered = false, current = cur)?.field)
        assertNull(Jev.validate("https://other.example/v1", keyEntered = true, current = cur))
        // F9 endpoint rule
        assertEquals("endpoint", Jev.validate("http://jev.example.com/v1", keyEntered = true, current = cur)?.field)
        assertNull(Jev.validate("http://127.0.0.1:18788/v1/systemone", keyEntered = true, current = cur))
        assertEquals("endpoint", Jev.validate("https://u:p@jev.example.com/", keyEntered = true, current = cur)?.field)
    }

    @Test
    fun errorTextsNeverEchoInput() {
        assertTrue(Jev.errorText("agentos.jev.invalid_endpoint: the endpoint must be https").startsWith("地址不合法"))
        assertTrue(Jev.errorText("agentos.jev.key_required: x").contains("重新填写 key"))
        assertEquals("操作失败，请重试", Jev.errorText(null))
        assertEquals("操作失败（brand_new）", Jev.errorText("agentos.jev.brand_new: sk-secret"))
    }
}
