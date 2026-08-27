package com.joysong.server.order.service

import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.config.TravelGroundServicePricingProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TravelGroundServicePricingTest {
    private val properties = TravelGroundServicePricingProperties().apply { serviceFeeRate = BigDecimal("40.00") }
    private val pricing = TravelGroundServicePricing(TravelGroundServiceFeeRatePolicy(properties))

    @Test
    fun `quotes USD fee from list price and public service fee basis points`() {
        val quote = pricing.quote(BigDecimal("1000.00"))

        assertEquals("USD", quote.currency)
        assertEquals(100_000L, quote.medicalListPriceMinor)
        assertEquals(4_000, quote.platformServiceRateBps)
        assertEquals(40_000L, quote.travelGroundServiceFeeMinor)
        assertEquals("travel-ground-service-rate:0.400000", quote.pricingPolicyRevision)
    }

    @Test
    fun `uses its own service fee rate when settlement platform rate differs`() {
        properties.serviceFeeRate = BigDecimal("50.00")
        val settlementRate = OrderSplitRatePolicy(OrderSplitProperties().apply {
            platformRate = BigDecimal("10.00")
        }).currentPlatformRate()

        val quote = pricing.quote(BigDecimal("0.01"))

        assertEquals(BigDecimal("10.00"), settlementRate)
        assertEquals(5_000, quote.platformServiceRateBps)
        assertEquals(1L, quote.travelGroundServiceFeeMinor)
        assertEquals("travel-ground-service-rate:0.500000", quote.pricingPolicyRevision)
    }

    @Test
    fun `rejects zero list price`() {
        assertEquals(
            "MEDICAL_LIST_PRICE_NOT_POSITIVE",
            assertThrows<IllegalArgumentException> { pricing.quote(BigDecimal.ZERO) }.message
        )
    }

    @Test
    fun `rejects fractional cents with the same USD configuration contract`() {
        assertEquals(
            "金额须在范围内且最多两位小数",
            assertThrows<IllegalArgumentException> { pricing.quote(BigDecimal("1000.005")) }.message
        )
    }

    @Test
    fun `policy quote preserves its compatibility contract`() {
        val quote = pricing.quoteWithPolicy(BigDecimal("1000.00"))

        assertEquals(BigDecimal("40.00"), quote.platformRate)
        assertEquals("travel-ground-service-rate:0.400000", quote.pricingPolicyRevision)
        assertEquals(BigDecimal("400.00"), quote.serviceFee)
    }

    @Test
    fun `rejects out of range configured service fee rate`() {
        val properties = TravelGroundServicePricingProperties().apply { serviceFeeRate = BigDecimal("100.01") }

        assertEquals(
            "出行地接服务费比例 须在 0～100 之间",
            assertThrows<IllegalStateException> {
                TravelGroundServiceFeeRatePolicy(properties).currentServiceFeeRate()
            }.message
        )
    }

    @Test
    fun `rejects configured service fee rate with more than two decimal places`() {
        val properties = TravelGroundServicePricingProperties().apply { serviceFeeRate = BigDecimal("40.001") }

        assertEquals(
            "出行地接服务费比例 最多保留两位小数",
            assertThrows<IllegalStateException> {
                TravelGroundServiceFeeRatePolicy(properties).currentServiceFeeRate()
            }.message
        )
    }
}
