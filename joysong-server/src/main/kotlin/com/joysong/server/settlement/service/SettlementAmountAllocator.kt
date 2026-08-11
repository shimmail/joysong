package com.joysong.server.settlement.service

import com.joysong.server.order.service.OrderSplitRates
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class SettlementAmountAllocator {
    fun allocate(totalMinor: Long, rates: OrderSplitRates): AllocationAmounts {
        require(totalMinor > 0) { "结算金额必须大于 0" }
        val allRates = listOf(rates.platformRate, rates.institutionRate, rates.consultantRate, rates.doctorRate)
        require(allRates.all { it >= BigDecimal.ZERO }) { "分账比例不能为负数" }
        require(allRates.reduce(BigDecimal::add).compareTo(HUNDRED) == 0) { "分账比例合计必须等于 100" }

        val platform = amountFor(totalMinor, rates.platformRate)
        val institution = amountFor(totalMinor, rates.institutionRate)
        val consultant = amountFor(totalMinor, rates.consultantRate)
        val doctor = totalMinor - platform - institution - consultant
        val allocation = AllocationAmounts(platform, institution, consultant, doctor)

        require(listOf(allocation.platform, allocation.institution, allocation.consultant, allocation.doctor).all { it >= 0 }) {
            "分账金额不能为负数"
        }
        require(allocation.platform + allocation.institution + allocation.consultant + allocation.doctor == totalMinor) {
            "分账金额合计必须等于结算金额"
        }
        return allocation
    }

    private fun amountFor(totalMinor: Long, rate: BigDecimal): Long = BigDecimal.valueOf(totalMinor)
        .multiply(rate)
        .divide(HUNDRED, 0, RoundingMode.HALF_UP)
        .longValueExact()

    private companion object {
        val HUNDRED = BigDecimal("100")
    }
}

data class AllocationAmounts(
    val platform: Long,
    val institution: Long,
    val consultant: Long,
    val doctor: Long
)
