package com.joysong.server.order.service

import com.joysong.server.config.TravelGroundServicePricingProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class TravelGroundServiceFeeRatePolicy(
    private val properties: TravelGroundServicePricingProperties
) {
    fun currentServiceFeeRate(): BigDecimal = properties.serviceFeeRate.also { rate ->
        check(rate >= BigDecimal.ZERO && rate <= HUNDRED) { "出行地接服务费比例 须在 0～100 之间" }
        check(rate.stripTrailingZeros().scale() <= 2) { "出行地接服务费比例 最多保留两位小数" }
    }

    private companion object {
        val HUNDRED = BigDecimal("100.00")
    }
}
