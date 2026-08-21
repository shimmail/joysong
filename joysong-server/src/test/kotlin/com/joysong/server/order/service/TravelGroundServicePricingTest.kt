package com.joysong.server.order.service

import com.joysong.server.config.OrderSplitProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TravelGroundServicePricingTest {
    private val properties = OrderSplitProperties().apply { platformRate = BigDecimal("40.00") }
    private val pricing = TravelGroundServicePricing(OrderSplitRatePolicy(properties))

    @Test
    fun `quotes USD fee from list price and platform basis points`() {
        val quote = pricing.quote(BigDecimal("1000.00"))

        assertEquals("USD", quote.currency)
        assertEquals(100_000L, quote.medicalListPriceMinor)
        assertEquals(4_000, quote.platformServiceRateBps)
        assertEquals(40_000L, quote.travelGroundServiceFeeMinor)
    }

    @Test
    fun `rounds fractional minor unit half up`() {
        properties.platformRate = BigDecimal("50.00")

        assertEquals(1L, pricing.quote(BigDecimal("0.01")).travelGroundServiceFeeMinor)
    }

    @Test
    fun `rejects zero list price`() {
        assertEquals(
            "MEDICAL_LIST_PRICE_NOT_POSITIVE",
            assertThrows<IllegalArgumentException> { pricing.quote(BigDecimal.ZERO) }.message
        )
    }
}
