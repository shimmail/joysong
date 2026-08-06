package com.joysong.server.payment.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.payment.dto.PaymentAttemptResponse
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.payment.service.PaymentService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/payments")
class PaymentController(
    private val paymentService: PaymentService
) {
    @GetMapping("/{id}")
    fun getPayment(
        @PathVariable id: String,
        @RequestParam(defaultValue = "false") refresh: Boolean,
        authentication: Authentication
    ): BaseResponse<*> = respond {
        PaymentAttemptResponse.from(
            paymentService.getPayment(id, authentication.principal as String, refresh)
        )
    }

    @PostMapping("/{id}/confirm")
    fun confirmPayment(
        @PathVariable id: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        authentication: Authentication
    ): BaseResponse<*> = respond {
        PaymentAttemptResponse.from(
            paymentService.confirmPayment(id, authentication.principal as String, idempotencyKey)
        )
    }

    private fun respond(block: () -> Any): BaseResponse<*> = try {
        BaseResponse.success(block())
    } catch (error: Exception) {
        val code = when (error) {
            is PaymentProviderException -> 503
            else -> if (error.message == "PAYMENT_NOT_FOUND") 404 else 400
        }
        BaseResponse.error<Any>(error.message ?: "支付操作失败", code)
    }
}
