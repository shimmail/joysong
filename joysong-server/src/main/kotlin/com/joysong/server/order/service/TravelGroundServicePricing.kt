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

data class TravelGroundServicePolicyQuote(
    val platformRate: BigDecimal,
    val pricingPolicyRevision: String,
    val serviceFee: BigDecimal
)

@Component
class TravelGroundServicePricing(
    private val splitRatePolicy: OrderSplitRatePolicy
) {
    fun quote(medicalListPrice: BigDecimal): TravelGroundServiceQuote {
        require(medicalListPrice > BigDecimal.ZERO) { "MEDICAL_LIST_PRICE_NOT_POSITIVE" }
        Money.requireUsdAmount(medicalListPrice)
        return quoteWithRate(medicalListPrice, splitRatePolicy.currentPlatformRate())
    }

    fun quoteWithPolicy(price: BigDecimal): TravelGroundServicePolicyQuote {
        require(price > BigDecimal.ZERO) { "MEDICAL_LIST_PRICE_NOT_POSITIVE" }
        Money.requireUsdAmount(price)
        val configuredRate = splitRatePolicy.currentPlatformRate()
        val decimalRate = configuredRate.movePointLeft(2).setScale(6, RoundingMode.UNNECESSARY)
        val quote = quoteWithRate(price, configuredRate)
        return TravelGroundServicePolicyQuote(
            platformRate = configuredRate,
            pricingPolicyRevision = "travel-ground-service-rate:${decimalRate.toPlainString()}",
            serviceFee = Money.fromMinor(quote.travelGroundServiceFeeMinor, quote.currency)
        )
    }

    private fun quoteWithRate(medicalListPrice: BigDecimal, configuredRate: BigDecimal): TravelGroundServiceQuote {
        val currency = "USD"
        val listMinor = Money.toMinor(medicalListPrice, currency)
        val rateBps = configuredRate
            .movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact()
        val feeMinor = BigDecimal.valueOf(listMinor)
            .multiply(BigDecimal.valueOf(rateBps.toLong()))
            .divide(BigDecimal("10000"), 0, RoundingMode.HALF_UP)
            .longValueExact()
        require(feeMinor > 0) { "TRAVEL_GROUND_SERVICE_FEE_NOT_POSITIVE" }
        return TravelGroundServiceQuote(currency, listMinor, rateBps, feeMinor)
    }
}
