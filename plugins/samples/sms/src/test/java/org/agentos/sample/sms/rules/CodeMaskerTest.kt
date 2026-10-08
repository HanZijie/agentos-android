package org.agentos.sample.sms.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S3：验证码遮蔽。规则不跟界面语言走，中英文模板都要覆盖；也要有“不该遮蔽”的反例（金额、日期、尾号、订单号）。 */
class CodeMaskerTest {
    private fun masked(body: String) = CodeMasker.mask(body)

    private fun assertMasks(body: String, expected: String, count: Int = 1) {
        val r = masked(body)
        assertEquals(expected, r.text)
        assertEquals("masked count of: $body", count, r.maskedCount)
        assertEquals("structure (length) is kept", body.length, r.text.length)
        assertTrue(r.masked)
    }

    private fun assertUntouched(body: String) {
        val r = masked(body)
        assertEquals(body, r.text)
        assertEquals(0, r.maskedCount)
        assertFalse(r.masked)
        assertFalse(CodeMasker.containsCode(body))
    }

    // ---- 中文模板 ----

    @Test fun `chinese - verification code with comma`() =
        assertMasks("【腾讯】您的验证码是 123456，5分钟内有效，请勿泄露。", "【腾讯】您的验证码是 ••••••，5分钟内有效，请勿泄露。")

    @Test fun `chinese - four digit code with full-width colon`() =
        assertMasks("验证码：8837，请在10分钟内输入", "验证码：••••，请在10分钟内输入")

    @Test fun `chinese - jiaoyan ma`() =
        assertMasks("【支付宝】校验码 482910，您正在登录", "【支付宝】校验码 ••••••，您正在登录")

    @Test fun `chinese - dynamic code and dynamic password`() {
        assertMasks("动态码 7391 (有效期5分钟)", "动态码 •••• (有效期5分钟)")
        assertMasks("【某银行】动态密码 654321 用于登录", "【某银行】动态密码 •••••• 用于登录")
    }

    @Test fun `chinese - security code of eight digits and confirm code`() {
        assertMasks("您的安全码为 90817234，请勿告知他人", "您的安全码为 ••••••••，请勿告知他人")
        assertMasks("确认码：5521", "确认码：••••")
    }

    @Test fun `chinese - code written before the keyword`() =
        assertMasks("482910是您的登录验证码，请勿转发", "••••••是您的登录验证码，请勿转发")

    @Test fun `chinese - full-width digits are masked too`() =
        assertMasks("验证码：１２３４５６", "验证码：••••••")

    // ---- 英文模板 ----

    @Test fun `english - verification code is`() =
        assertMasks("Your verification code is 482910. Do not share it.", "Your verification code is ••••••. Do not share it.")

    @Test fun `english - code before the keyword`() =
        assertMasks("123456 is your Google verification code.", "•••••• is your Google verification code.")

    @Test fun `english - google style prefix keeps the letter and dash`() =
        assertMasks("G-123456 is your Google verification code.", "G-•••••• is your Google verification code.")

    @Test fun `english - OTP PIN and passcode`() {
        assertMasks("Your OTP is 7391", "Your OTP is ••••")
        assertMasks("PIN: 4821", "PIN: ••••")
        assertMasks("Use 884422 as your passcode", "Use •••••• as your passcode")
    }

    @Test fun `english - code in other phrasing`() {
        assertMasks("Your Uber code is 4321. Never share this code.", "Your Uber code is ••••. Never share this code.")
        assertMasks("Your Apple ID Code is: 123456", "Your Apple ID Code is: ••••••")
        assertMasks("Your login code is 112233. It expires in 5 minutes", "Your login code is ••••••. It expires in 5 minutes")
        assertMasks("Your one-time password is 902817", "Your one-time password is ••••••")
        assertMasks("2FA code 556677", "2FA code ••••••")
    }

    @Test fun `english - grouped digits keep their separator`() {
        assertMasks("Your code: 123 456", "Your code: ••• •••")
        assertMasks("Your security code is 1234 5678", "Your security code is •••• ••••")
        assertMasks("Verification code 123-456", "Verification code •••-•••")
    }

    @Test fun `mixed chinese and english template masks both occurrences`() =
        assertMasks("【Netflix】验证码 4455 (verification code 4455)", "【Netflix】验证码 •••• (verification code ••••)", count = 2)

    @Test fun `keywords are matched case-insensitively`() {
        assertMasks("YOUR OTP IS 7391", "YOUR OTP IS ••••")
        assertMasks("Your Verification Code: 7391", "Your Verification Code: ••••")
    }

    // ---- 不该遮蔽 ----

    @Test fun `no keyword - amounts dates tails and order numbers stay`() {
        assertUntouched("您尾号1234的账户于10月8日支出5000.00元")
        assertUntouched("2026年10月8日 14:30 需求评审会")
        assertUntouched("Your balance is \$12,345.67 as of 2026-10-08")
        assertUntouched("订单号 20261008123 已发货，预计 3 天内送达")
        assertUntouched("快递已到，请凭 1234 取件")
        assertUntouched("Call me at 4155550123 tomorrow")
        assertUntouched("Version 2.0.1234 released")
        assertUntouched("Sale code SAVE20 gives 15% off")
    }

    @Test fun `keyword present but the digits are an amount - only the code is masked`() {
        assertMasks("验证码 123456，转账 8000 元", "验证码 ••••••，转账 8000 元")
        assertMasks("Your code is 123456. Pay USD 5000 to confirm.", "Your code is ••••••. Pay USD 5000 to confirm.")
        assertMasks("Your code is 123456. Total \$2500 due", "Your code is ••••••. Total \$2500 due")
    }

    @Test fun `keyword present but only a date or phone number follows`() {
        assertUntouched("验证码有效期至 2026-10-08")
        assertUntouched("请回复验证码到 13800138000")
        assertUntouched("Your code expires on 2026/10/08")
        assertUntouched("Call 415-555-0123 for the verification desk")
    }

    @Test fun `a digit run far from every keyword is left alone`() {
        val body = "Your code is ready. " + "x".repeat(40) + " ref 1234"
        assertUntouched(body)
    }

    @Test fun `only the digits next to the keyword are masked, not every number in the message`() =
        assertMasks("Your code is 123456. Order 20261008 shipped", "Your code is ••••••. Order 20261008 shipped")

    @Test fun `words that merely contain a keyword do not count`() {
        assertUntouched("Your shipping 1234 order")
        assertUntouched("Opinion poll 5678 results")
        assertUntouched("Unlock 4455 now")
    }

    @Test fun `numbers shorter than four or longer than eight digits are not codes`() {
        assertUntouched("验证码 123")
        assertUntouched("验证码 123456789")
        assertUntouched("Your code is 12")
    }

    @Test fun `empty and keyword-only bodies are fine`() {
        assertUntouched("")
        assertUntouched("请输入验证码")
        assertUntouched("Enter the verification code")
    }

    @Test fun `the result is independent of the default locale`() {
        val old = java.util.Locale.getDefault()
        try {
            val body = "【银行】验证码 654321 / Your code is 112233"
            java.util.Locale.setDefault(java.util.Locale.US)
            val en = masked(body)
            java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
            val zh = masked(body)
            assertEquals(en, zh)
            assertEquals("【银行】验证码 •••••• / Your code is ••••••", zh.text)
        } finally {
            java.util.Locale.setDefault(old)
        }
    }
}
