package com.joysong.server.settlement

import com.joysong.server.order.service.OrderSplitRates
import com.joysong.server.settlement.service.SettlementAmountAllocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class SettlementAmountAllocatorTest {
    private val allocator = SettlementAmountAllocator()

    @Test
    fun `allocations conserve every minor unit`() {
        val result = allocator.allocate(10_001, rates("40.00", "30.00", "10.00", "20.00"))

        assertEquals(10_001, result.platform + result.institution + result.consultant + result.doctor)
        assertEquals(2_001, result.doctor)
    }

    @Test
    fun `invalid rates and non-positive totals are rejected`() {
        val validRates = rates("40.00", "30.00", "10.00", "20.00")

        assertThrows<IllegalArgumentException> { allocator.allocate(0, validRates) }
        assertThrows<IllegalArgumentException> { allocator.allocate(100, rates("60", "30", "20", "-10")) }
    }

    @Test
    fun `rates that do not total 100 are rejected`() {
        assertThrows<IllegalArgumentException> {
            allocator.allocate(100, rates("40", "30", "10", "10"))
        }
    }

    private fun rates(
        platform: String,
        institution: String,
        consultant: String,
        doctor: String
    ) = OrderSplitRates(
        platformRate = BigDecimal(platform),
        institutionRate = BigDecimal(institution),
        consultantRate = BigDecimal(consultant),
        doctorRate = BigDecimal(doctor)
    )
}
