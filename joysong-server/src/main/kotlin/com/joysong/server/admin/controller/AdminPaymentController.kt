package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.payment.service.PaymentCompensationService
import com.joysong.server.payment.service.PaymentService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin")
class AdminPaymentController(
    private val paymentService: PaymentService,
    private val paymentCompensationService: PaymentCompensationService
) {

    @GetMapping("/payments")
    fun listPayments(): BaseResponse<*> = BaseResponse.success(paymentService.adminListAll())

    /** Provider-confirmed charges which must be refunded independently of the order lifecycle. */
    @GetMapping("/payment-compensations")
    fun listPaymentCompensations(): BaseResponse<*> =
        BaseResponse.success(paymentCompensationService.adminListAll())

    @PostMapping("/payment-compensations/{id}/retry")
    fun retryPaymentCompensation(
        @PathVariable id: String,
        authentication: Authentication
    ): BaseResponse<*> = BaseResponse.success(
        paymentCompensationService.retryCompensationRefund(id, authentication.principal as String)
    )
}
