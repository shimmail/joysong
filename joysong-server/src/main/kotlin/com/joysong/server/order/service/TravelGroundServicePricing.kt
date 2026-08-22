package com.joysong.server.order.service

import com.joysong.server.payment.domain.Money
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

data class TravelGroundServiceQuote(
    val currency: String,
    val medicalListPriceMinor: Long,
    val platformServiceRateBps: Int,
    val travelGroundServiceFeeMinor: Long
)

@Component
class TravelGroundServicePricing(
    private val splitRatePolicy: OrderSplitRatePolicy
) {
    fun quote(medicalListPrice: BigDecimal): TravelGroundServiceQuote {
        require(medicalListPrice > BigDecimal.ZERO) { "MEDICAL_LIST_PRICE_NOT_POSITIVE" }
        Money.requireUsdAmount(medicalListPrice)
        val currency = "USD"
        val listMinor = Money.toMinor(medicalListPrice, currency)
        val rateBps = splitRatePolicy.currentPlatformRate()
            .movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact()
        val feeMinor = BigDecimal.valueOf(listMinor)
            .multiply(BigDecimal.valueOf(rateBps.toLong()))
            .divide(BigDecimal("10000"), 0, RoundingMode.HALF_UP)
            .longValueExact()
        require(feeMinor > 0) { "TRAVEL_GROUND_SERVICE_FEE_NOT_POSITIVE" }
        return TravelGroundServiceQuote(currency, listMinor, rateBps, feeMinor)
    }
}
