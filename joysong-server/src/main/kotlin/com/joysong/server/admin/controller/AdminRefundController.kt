package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.refund.service.RefundService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin")
class AdminRefundController(
    private val refundService: RefundService
) {

    @GetMapping("/refunds")
    fun listRefunds(): BaseResponse<*> = BaseResponse.success(refundService.adminListAll())

    @PutMapping("/refunds/{id}/status")
    fun updateRefundStatus(
        @PathVariable id: String,
        @RequestBody body: Map<String, String>,
        authentication: Authentication
    ): BaseResponse<*> {
        val newStatus = body["status"] ?: return BaseResponse.error<Any>("状态不能为空")
        val adminId = authentication.principal as String
        val rejectReason = body.getOrDefault("rejectReason", "")
        val result = refundService.adminUpdateStatus(id, newStatus, adminId, rejectReason)
            ?: return BaseResponse.error<Any>("退款记录不存在")
        return BaseResponse.success(result)
    }

    @PostMapping("/refunds/{id}/retry")
    fun retryRefund(
        @PathVariable id: String,
        authentication: Authentication
    ): BaseResponse<*> {
        val adminId = authentication.principal as String
        return BaseResponse.success(refundService.retryFailedProcessingRefund(id, adminId))
    }
}
