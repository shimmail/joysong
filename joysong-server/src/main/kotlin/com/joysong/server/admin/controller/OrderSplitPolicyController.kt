package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.order.service.OrderSplitRatePolicy
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@RestController
@RequestMapping("/api/admin/order-split-policy")
class OrderSplitPolicyController(
    private val splitRatePolicy: OrderSplitRatePolicy,
    private val managementAccessService: ManagementAccessService
) {
    @GetMapping
    fun get(authentication: Authentication): BaseResponse<OrderSplitPolicyView> {
        managementAccessService.actor(authentication)
        return BaseResponse.success(OrderSplitPolicyView(splitRatePolicy.currentPlatformRate()))
    }
}

data class OrderSplitPolicyView(val platformRate: BigDecimal)
