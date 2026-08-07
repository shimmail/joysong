package com.joysong.server.payment.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.entity.PaymentEventEntity
import com.joysong.server.payment.provider.PaymentGatewayRegistry
import com.joysong.server.payment.repository.PaymentEventRepository
import com.joysong.server.payment.repository.PaymentRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class PaymentWebhookService(
    private val paymentGatewayRegistry: PaymentGatewayRegistry,
    private val paymentEventRepository: PaymentEventRepository,
    private val paymentRepository: PaymentRepository,
    private val paymentService: PaymentService,
    private val objectMapper: ObjectMapper
) {
    fun receive(provider: PaymentProvider, payload: String, headers: Map<String, String>): PaymentEventEntity {
        // Reject malformed data before retaining it in the JSON audit column.
        objectMapper.readTree(payload)
        val verified = paymentGatewayRegistry.require(provider).verifyWebhook(payload, headers)
        paymentEventRepository.findByProviderAndProviderEventId(provider.name, verified.providerEventId)
            ?.let { return it }

        val payment = paymentRepository.findByProviderAndProviderPaymentId(
            provider.name,
            verified.providerPaymentId
        ) ?: verified.localPaymentId?.let { id ->
            paymentRepository.findById(id).orElse(null)?.takeIf { it.provider == provider.name }
        }
        val received = paymentEventRepository.saveAndFlush(
            PaymentEventEntity(
                id = UUID.randomUUID().toString(),
                paymentId = payment?.id,
                provider = provider.name,
                providerEventId = verified.providerEventId,
                eventType = verified.eventType,
                payload = payload,
                signatureValid = true
            )
        )

        return try {
            paymentService.handleProviderPaymentEvent(
                provider = provider,
                providerPaymentId = verified.providerPaymentId,
                providerTransactionId = verified.providerTransactionId,
                status = verified.paymentStatus,
                amountMinor = verified.amountMinor,
                currency = verified.currency,
                failureCode = verified.failureCode,
                failureMessage = verified.failureMessage,
                localPaymentId = verified.localPaymentId
            )
            paymentEventRepository.save(
                received.copy(processingStatus = "PROCESSED", processedAt = LocalDateTime.now())
            )
        } catch (error: Exception) {
            paymentEventRepository.save(
                received.copy(
                    processingStatus = "FAILED",
                    retryCount = received.retryCount + 1,
                    errorMessage = (error.message ?: error.javaClass.simpleName).take(500)
                )
            )
            throw error
        }
    }

    /** Retries a previously verified callback by querying the provider's canonical payment state. */
    fun retryFailedEvent(eventId: String): PaymentEventEntity {
        val event = paymentEventRepository.findById(eventId)
            .orElseThrow { IllegalArgumentException("PAYMENT_EVENT_NOT_FOUND") }
        if (event.processingStatus == "PROCESSED") return event
        val paymentId = requireNotNull(event.paymentId) { "PAYMENT_EVENT_NOT_LINKED" }
        return try {
            paymentService.reconcilePayment(paymentId)
            paymentEventRepository.save(
                event.copy(
                    processingStatus = "PROCESSED",
                    processedAt = LocalDateTime.now(),
                    errorMessage = null
                )
            )
        } catch (error: Exception) {
            paymentEventRepository.save(
                event.copy(
                    processingStatus = "FAILED",
                    retryCount = event.retryCount + 1,
                    errorMessage = (error.message ?: error.javaClass.simpleName).take(500)
                )
            )
            throw error
        }
    }
}
