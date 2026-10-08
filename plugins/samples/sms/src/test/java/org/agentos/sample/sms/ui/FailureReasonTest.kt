package org.agentos.sample.sms.ui

import org.agentos.sample.sms.data.SendResults
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureReasonTest {
    @Test fun `the reasons the app produces itself have a user-facing text`() {
        for (reason in listOf("radio_off", "no_service", "generic_failure", "limit_exceeded", "short_code_not_allowed", "delivery_failed", "permission_denied")) {
            assertNotNull(reason, FailureReason.res(reason))
        }
    }

    @Test fun `result-code reasons from the system map or fall back to the raw code`() {
        assertNotNull(FailureReason.res(SendResults.errorFor(2)))
        assertNotNull(FailureReason.res(SendResults.errorFor(4)))
        assertNotNull(FailureReason.res(SendResults.errorFor(1)))
        assertNull(FailureReason.res(SendResults.errorFor(999)))
        assertTrue(SendResults.errorFor(999).startsWith("error_"))
    }
}
