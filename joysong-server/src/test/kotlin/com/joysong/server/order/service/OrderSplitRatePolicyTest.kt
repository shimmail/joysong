package com.joysong.server.order.service

import com.joysong.server.config.OrderSplitProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class OrderSplitRatePolicyTest {
    private fun policy(platform: String = "40.00", institution: String = "40.00") =
        OrderSplitRatePolicy(OrderSplitProperties().apply {
            platformRate = BigDecimal(platform)
            institutionRate = BigDecimal(institution)
        })

    @Test
    fun `platform 40 institution 35 consultant 10 leaves doctor 15`() {
        val rates = policy().resolve(BigDecimal("35.00"), BigDecimal("10.00"))
        assertEquals(BigDecimal("40.00"), rates.platformRate)
        assertEquals(BigDecimal("15.00"), rates.doctorRate)
    }

    @Test
    fun `total exactly 100 allows zero doctor rate`() {
        assertEquals(
            BigDecimal("0.00"),
            policy().resolve(BigDecimal("40.00"), BigDecimal("20.00")).doctorRate
        )
    }

    @Test
    fun `total over 100 is rejected`() {
        val error = assertThrows<IllegalArgumentException> {
            policy().resolve(BigDecimal("40.00"), BigDecimal("20.01"))
        }
        assertEquals("平台、合作医疗机构和医美顾问分账比例合计不能超过 100%", error.message)
    }

    @Test
    fun `platform override is the source of truth`() {
        assertEquals(
            BigDecimal("12.50"),
            policy(platform = "37.50").resolve(BigDecimal("40.00"), BigDecimal("10.00")).doctorRate
        )
    }

    @Test
    fun `input with more than two decimals is rejected`() {
        assertThrows<IllegalArgumentException> {
            policy().resolve(BigDecimal("39.999"), BigDecimal("10.00"))
        }
    }
}
