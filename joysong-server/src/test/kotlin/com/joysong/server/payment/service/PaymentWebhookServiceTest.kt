package com.joysong.server.payment.service

import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.entity.PaymentEventEntity
import com.joysong.server.payment.provider.PaymentGateway
import com.joysong.server.payment.provider.PaymentGatewayRegistry
import com.joysong.server.payment.provider.ProviderCreatePaymentRequest
import com.joysong.server.payment.provider.ProviderPaymentResult
import com.joysong.server.payment.provider.VerifiedProviderEvent
import com.joysong.server.payment.repository.PaymentEventRepository
import com.joysong.server.payment.repository.PaymentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaymentWebhookServiceTest {

    @Test
    fun `raw form webhook reaches provider verification and storage unchanged`() {
        val rawPayload = "notify_id=n%2B1&trade_status=TRADE_SUCCESS&memo=a+b%26c"
        var verifiedPayload: String? = null
        val gateway = object : PaymentGateway {
            override val provider = PaymentProvider.ALIPAY_PLUS

            override fun createPayment(request: ProviderCreatePaymentRequest): ProviderPaymentResult =
                error("not used")

            override fun verifyWebhook(
                payload: String,
                headers: Map<String, String>
            ): VerifiedProviderEvent {
                verifiedPayload = payload
                return VerifiedProviderEvent(
                    providerEventId = "event-1",
                    eventType = "PAYMENT.SUCCEEDED",
                    providerPaymentId = "provider-payment-1",
                    paymentStatus = PaymentStatus.SUCCEEDED,
                    amountMinor = 40_000,
                    currency = "USD"
                )
            }
        }
        val events = mockk<PaymentEventRepository>()
        val payments = mockk<PaymentRepository>()
        val paymentService = mockk<PaymentService>()
        val stored = slot<PaymentEventEntity>()
        every { events.findByProviderAndProviderEventId(PaymentProvider.ALIPAY_PLUS.name, "event-1") } returns null
        every {
            payments.findByProviderAndProviderPaymentId(
                PaymentProvider.ALIPAY_PLUS.name,
                "provider-payment-1"
            )
        } returns null
        every { events.saveAndFlush(capture(stored)) } answers { stored.captured }
        every {
            paymentService.handleProviderPaymentEvent(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns mockk<PaymentEntity>()
        every { events.save(any()) } answers { firstArg() }

        val result = PaymentWebhookService(
            paymentGatewayRegistry = PaymentGatewayRegistry(listOf(gateway)),
            paymentEventRepository = events,
            paymentRepository = payments,
            paymentService = paymentService
        ).receive(
            PaymentProvider.ALIPAY_PLUS,
            rawPayload,
            mapOf("x-provider-signature" to "signed-value")
        )

        assertEquals(rawPayload, verifiedPayload)
        assertEquals(rawPayload, stored.captured.payload)
        assertEquals("PROCESSED", result.processingStatus)
    }
}
