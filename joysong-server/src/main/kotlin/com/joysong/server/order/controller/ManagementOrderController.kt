package com.joysong.server.order.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.order.service.OrderService
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class OrderVerificationRequest(
    @field:Pattern(regexp = "^\\d{6}$", message = "请输入 6 位核销码")
    val verificationCode: String
)

@RestController
@RequestMapping("/api/management/orders")
class ManagementOrderController(
    private val orderService: OrderService,
    private val managementAccessService: ManagementAccessService
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "20") limit: Int
    ): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        return BaseResponse.success(orderService.getOrdersForManagement(actor, status, offset, limit))
    }

    @GetMapping("/{id}")
    fun detail(authentication: Authentication, @PathVariable id: String): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        return BaseResponse.success(
            com.joysong.server.order.dto.OrderResponse.forManagement(orderService.requireOrderForManagement(actor, id))
        )
    }

    @PostMapping("/{id}/verify")
    fun verify(
        authentication: Authentication,
        @PathVariable id: String,
        @Valid @RequestBody request: OrderVerificationRequest
    ): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        orderService.requireOrderForManagement(actor, id)
        return BaseResponse.success(
            orderService.confirmVerification(id, actor.userId, request.verificationCode)
        )
    }

    @PostMapping("/{id}/request-completion")
    fun requestCompletion(
        authentication: Authentication,
        @PathVariable id: String,
        @Valid @RequestBody request: OrderVerificationRequest
    ): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        orderService.requireOrderForManagement(actor, id)
        return BaseResponse.success(
            orderService.requestCompletion(id, actor.userId, request.verificationCode)
        )
    }
}
