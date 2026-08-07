package com.joysong.server.payment.controller

import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.payment.service.PaymentWebhookService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
    ): ResponseEntity<Map<String, String>> = try {
        val normalized = PaymentProvider.parse(provider)
        val flatHeaders = headers.toSingleValueMap()
        val event = paymentWebhookService.receive(normalized, payload, flatHeaders)
        ResponseEntity.ok(mapOf("eventId" to event.providerEventId, "status" to event.processingStatus))
    } catch (error: IllegalArgumentException) {
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to (error.message ?: "INVALID_PAYMENT_WEBHOOK")))
    } catch (error: PaymentProviderException) {
        val status = if (error.retryable) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_REQUEST
        ResponseEntity.status(status)
            .body(mapOf("error" to (error.message ?: "PAYMENT_WEBHOOK_UNAVAILABLE")))
    } catch (error: IllegalStateException) {
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(mapOf("error" to (error.message ?: "PAYMENT_WEBHOOK_UNAVAILABLE")))
    }
}
