package com.joysong.server.order.consultant

import com.joysong.server.common.BaseResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/consultant/orders")
class ConsultantOrderController(
    private val service: ConsultantOrderQueryService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) stage: String?,
        @RequestParam(required = false) institutionId: String?,
        @RequestParam(required = false) offset: String?,
        @RequestParam(required = false) limit: String?,
        authentication: Authentication,
    ): BaseResponse<ConsultantOrderPageResponse> = BaseResponse.success(
        service.list(
            authentication.name,
            ConsultantOrderListQuery.parse(stage, institutionId, offset, limit),
        ),
    )

    @GetMapping("/{orderId}")
    fun detail(
        @PathVariable orderId: String,
        authentication: Authentication,
    ): BaseResponse<ConsultantOrderDetailResponse> = BaseResponse.success(
        service.detail(authentication.name, orderId),
    )
}
