package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.payment.service.PaymentService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin")
class AdminPaymentController(
    private val paymentService: PaymentService
) {

    @GetMapping("/payments")
    fun listPayments(): BaseResponse<*> = BaseResponse.success(paymentService.adminListAll())
}
