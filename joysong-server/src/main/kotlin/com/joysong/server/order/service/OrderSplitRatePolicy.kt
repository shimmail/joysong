package com.joysong.server.order.service

import com.joysong.server.config.OrderSplitProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class OrderSplitRatePolicy(
    private val properties: OrderSplitProperties
) {
    fun currentPlatformRate(): BigDecimal = properties.platformRate.also {
        checkConfiguredRate("平台分账比例", it)
    }

    fun defaultInstitutionRate(): BigDecimal = properties.institutionRate.also {
        checkConfiguredRate("合作医疗机构分账比例", it)
    }

    fun defaults(): OrderSplitRates = resolve(defaultInstitutionRate(), BigDecimal.ZERO)

    fun resolve(institutionRate: BigDecimal, consultantRate: BigDecimal): OrderSplitRates {
        val platformRate = currentPlatformRate()
        requireInputRate("合作医疗机构分账比例", institutionRate)
        requireInputRate("医美顾问分账比例", consultantRate)
        val doctorRate = HUNDRED - platformRate - institutionRate - consultantRate
        require(doctorRate >= BigDecimal.ZERO) {
            "平台、合作医疗机构和医美顾问分账比例合计不能超过 100%"
        }
        return OrderSplitRates(platformRate, institutionRate, consultantRate, doctorRate)
    }

    private fun checkConfiguredRate(label: String, rate: BigDecimal) {
        check(rate >= BigDecimal.ZERO && rate <= HUNDRED) { "$label 须在 0～100 之间" }
        check(rate.stripTrailingZeros().scale() <= 2) { "$label 最多保留两位小数" }
    }

    private fun requireInputRate(label: String, rate: BigDecimal) {
        require(rate >= BigDecimal.ZERO && rate <= HUNDRED) { "$label 须在 0～100 之间" }
        require(rate.stripTrailingZeros().scale() <= 2) { "$label 最多保留两位小数" }
    }

    private companion object {
        val HUNDRED = BigDecimal("100.00")
    }
}

data class OrderSplitRates(
    val platformRate: BigDecimal,
    val institutionRate: BigDecimal,
    val consultantRate: BigDecimal,
    val doctorRate: BigDecimal
)
