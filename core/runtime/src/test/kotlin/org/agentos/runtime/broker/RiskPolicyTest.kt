package org.agentos.runtime.broker

import org.agentos.runtime.ports.ToolRisk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** W16 的策略部分：风险等级与是否确认的纯函数，所有组合。 */
class RiskPolicyTest {
    private val risks = ToolRisk.entries
    private val hints = listOf(null, true, false)

    @Test
    fun `third party tools default to write`() {
        assertEquals(ToolRisk.WRITE, RiskPolicy.DEFAULT_RISK)
        assertEquals(ToolRisk.WRITE, RiskPolicy.effectiveRisk())
        assertEquals(ToolRisk.WRITE, RiskPolicy.effectiveRisk(null, ToolAnnotations()))
    }

    @Test
    fun `annotations can only raise the risk, over every combination`() {
        for (declared in listOf<ToolRisk?>(null) + risks) {
            val base = declared ?: ToolRisk.WRITE
            for (readOnly in hints) for (destructive in hints) {
                val got = RiskPolicy.effectiveRisk(declared, ToolAnnotations(readOnly, destructive))
                val expected = if (destructive == true) ToolRisk.HIGH else base
                assertEquals(expected, got, "declared=$declared readOnly=$readOnly destructive=$destructive")
                assertTrue(got >= base, "never below the base")
            }
        }
    }

    @Test
    fun `readOnlyHint never lowers the risk, even when the server says it is read only`() {
        assertEquals(ToolRisk.WRITE, RiskPolicy.effectiveRisk(null, ToolAnnotations(readOnlyHint = true)))
        assertEquals(ToolRisk.WRITE, RiskPolicy.effectiveRisk(ToolRisk.WRITE, ToolAnnotations(readOnlyHint = true, destructiveHint = false)))
        // 自相矛盾的注解：升高的那条算数
        assertEquals(ToolRisk.HIGH, RiskPolicy.effectiveRisk(null, ToolAnnotations(readOnlyHint = true, destructiveHint = true)))
    }

    @Test
    fun `trusted sources can declare read, and a destructive hint still raises it`() {
        assertEquals(ToolRisk.READ, RiskPolicy.effectiveRisk(ToolRisk.READ, null))
        assertEquals(ToolRisk.READ, RiskPolicy.effectiveRisk(ToolRisk.READ, ToolAnnotations(destructiveHint = false)))
        assertEquals(ToolRisk.HIGH, RiskPolicy.effectiveRisk(ToolRisk.READ, ToolAnnotations(destructiveHint = true)))
        assertEquals(ToolRisk.HIGH, RiskPolicy.effectiveRisk(ToolRisk.HIGH, null))
    }

    @Test
    fun `always allow is possible for everything except high`() {
        assertTrue(RiskPolicy.mayAlwaysAllow(ToolRisk.READ))
        assertTrue(RiskPolicy.mayAlwaysAllow(ToolRisk.WRITE))
        assertFalse(RiskPolicy.mayAlwaysAllow(ToolRisk.HIGH))
        assertFalse(RiskPolicy.maySessionRemember(ToolRisk.READ)) // 读不需要确认，也就没有“不再询问”
        assertTrue(RiskPolicy.maySessionRemember(ToolRisk.WRITE))
        assertFalse(RiskPolicy.maySessionRemember(ToolRisk.HIGH))
    }

    /** 参照表：不复用实现里的 when，逐条写出每个组合的期望。 */
    private fun expected(risk: ToolRisk, approval: ApprovalMode, remembered: Boolean, hookAsk: Boolean): ConsentRequirement {
        if (hookAsk) return ConsentRequirement.ASK
        return when (risk) {
            ToolRisk.READ -> ConsentRequirement.NOT_NEEDED_READ
            ToolRisk.HIGH -> ConsentRequirement.ASK
            ToolRisk.WRITE -> when {
                approval == ApprovalMode.ALWAYS -> ConsentRequirement.ALWAYS_BY_POLICY
                remembered -> ConsentRequirement.REMEMBERED_IN_SESSION
                else -> ConsentRequirement.ASK
            }
        }
    }

    @Test
    fun `consent requirement over every combination of risk, approval, remembered and hook ask`() {
        var cases = 0
        for (risk in risks) for (approval in ApprovalMode.entries) for (remembered in listOf(false, true)) for (hookAsk in listOf(false, true)) {
            val got = RiskPolicy.consentRequirement(risk, approval, remembered, hookAsk)
            assertEquals(expected(risk, approval, remembered, hookAsk), got, "$risk $approval remembered=$remembered hookAsk=$hookAsk")
            assertEquals(got == ConsentRequirement.ASK, RiskPolicy.needsConsent(risk, approval, remembered, hookAsk))
            cases++
        }
        assertEquals(24, cases)
    }

    @Test
    fun `high risk is always confirmed, whatever the policy file says`() {
        for (approval in ApprovalMode.entries) for (remembered in listOf(false, true)) {
            assertTrue(RiskPolicy.needsConsent(ToolRisk.HIGH, approval, remembered, hookAsk = false), "$approval remembered=$remembered")
        }
    }

    @Test
    fun `a hook ask confirms even reads and tools set to always allow`() {
        assertTrue(RiskPolicy.needsConsent(ToolRisk.READ, ApprovalMode.ASK, hookAsk = true))
        assertTrue(RiskPolicy.needsConsent(ToolRisk.WRITE, ApprovalMode.ALWAYS, rememberedInSession = true, hookAsk = true))
    }

    @Test
    fun `audit reasons match events md`() {
        assertEquals("policy", ConsentRequirement.ALWAYS_BY_POLICY.reason)
        assertEquals("remembered", ConsentRequirement.REMEMBERED_IN_SESSION.reason)
        assertEquals(null, ConsentRequirement.ASK.reason)
        assertEquals(null, ConsentRequirement.NOT_NEEDED_READ.reason)
    }
}
