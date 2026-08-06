package com.joysong.server.payment.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.payment.service.PaymentWebhookService
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/payment-webhooks")
class PaymentWebhookController(
    private val paymentWebhookService: PaymentWebhookService
) {
    @PostMapping("/{provider}")
    fun receive(
        @PathVariable provider: String,
        @RequestBody payload: String,
        @RequestHeader headers: HttpHeaders
    ): BaseResponse<*> = try {
        val normalized = PaymentProvider.parse(provider)
        val flatHeaders = headers.toSingleValueMap()
        val event = paymentWebhookService.receive(normalized, payload, flatHeaders)
        BaseResponse.success(mapOf("eventId" to event.providerEventId, "status" to event.processingStatus))
    } catch (error: IllegalArgumentException) {
        BaseResponse.error<Any>(error.message ?: "INVALID_PAYMENT_WEBHOOK", 400)
    } catch (error: PaymentProviderException) {
        BaseResponse.error<Any>(error.message ?: "PAYMENT_WEBHOOK_UNAVAILABLE", 503)
    } catch (error: IllegalStateException) {
        BaseResponse.error<Any>(error.message ?: "PAYMENT_WEBHOOK_UNAVAILABLE", 503)
    }
}
