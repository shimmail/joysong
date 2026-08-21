package com.joysong.server.dm.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.dm.dto.DmConversationResponse
import com.joysong.server.dm.service.OrderServiceConversationService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/orders")
class OrderServiceConversationController(
    private val service: OrderServiceConversationService
) {
    @PostMapping("/{orderId}/service-conversation")
    fun getOrCreate(
        @PathVariable orderId: String,
        authentication: Authentication
    ): BaseResponse<DmConversationResponse> {
        val userId = authentication.principal as String
        return BaseResponse.success(service.getOrCreate(orderId, userId))
    }
}
