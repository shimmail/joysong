package com.joysong.server.refund.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RefundReasonCodeTest {
    @Test
    fun `missing reason code is normalized to OTHER`() {
        assertEquals(RefundReasonCode.OTHER, RefundReasonCode.normalize(null))
    }

    @Test
    fun `unknown reason code is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RefundReasonCode.normalize("SCHEDULE_CONFLICT")
        }
    }
}
