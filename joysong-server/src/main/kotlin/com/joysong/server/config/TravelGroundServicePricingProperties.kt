package com.joysong.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
@ConfigurationProperties(prefix = "order.pricing.travel-ground-service")
class TravelGroundServicePricingProperties {
    /** 出行地接服务费比例（百分比） */
    var serviceFeeRate: BigDecimal = BigDecimal("40.00")
}
