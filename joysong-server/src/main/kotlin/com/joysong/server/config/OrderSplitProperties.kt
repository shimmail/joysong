package com.joysong.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 订单分账比例配置
 *
 * @author joysong
 * @since 2026-07-30
 */
@Component
@ConfigurationProperties(prefix = "order.split")
class OrderSplitProperties {
    /** 平台服务费比例（百分比） */
    var platformRate: BigDecimal = BigDecimal("40.00")
    /** 机构分成比例（百分比） */
    var institutionRate: BigDecimal = BigDecimal("40.00")
}
