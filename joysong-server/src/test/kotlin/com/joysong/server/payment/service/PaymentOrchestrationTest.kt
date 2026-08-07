package com.joysong.server.payment.service

import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.provider.PaymentGateway
import com.joysong.server.payment.provider.PaymentGatewayRegistry
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.payment.provider.ProviderCreatePaymentRequest
import com.joysong.server.payment.provider.ProviderPaymentResult
import com.joysong.server.payment.repository.PaymentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PaymentOrchestrationTest {
    private val paymentRepository = mockk<PaymentRepository>()
    private val orderRepository = mockk<OrderRepository>()
    private val orderStatusLogService = mockk<OrderStatusLogService>()
    private val persistence = mockk<PaymentPersistenceService>()
    private val gateway = mockk<PaymentGateway>()

    @Test
    fun `provider create uses payment scoped stable idempotency key`() {
        val prepared = payment(status = PaymentStatus.CREATED.name)
        val succeeded = prepared.copy(status = PaymentStatus.SUCCEEDED.name, providerPaymentId = "pi_1")
        val request = slot<ProviderCreatePaymentRequest>()
        every { gateway.provider } returns PaymentProvider.STRIPE
        every {
            persistence.prepareAttempt(any(), any(), any(), any(), any(), any())
        } returns prepared
        every { gateway.createPayment(capture(request)) } returns ProviderPaymentResult(
            PaymentStatus.SUCCEEDED,
            "pi_1",
            amountMinor = 1000,
            currency = "USD"
        )
        every { persistence.applyProviderResult(prepared.id, any()) } returns succeeded

        val result = service().createPaymentSession(
            prepared.orderId,
            prepared.userId,
            PaymentType.CONSULTATION_FEE,
            PaymentProvider.STRIPE,
            "card",
            "client-key-123"
        )

        assertEquals("payment-create-${prepared.id}", request.captured.idempotencyKey)
        assertEquals(PaymentStatus.SUCCEEDED.name, result.payment.status)
    }

    @Test
    fun `unknown provider outcome remains processing for reconciliation`() {
        val prepared = payment(status = PaymentStatus.CREATED.name)
        every { gateway.provider } returns PaymentProvider.STRIPE
        every {
            persistence.prepareAttempt(any(), any(), any(), any(), any(), any())
        } returns prepared
        every { gateway.createPayment(any()) } throws PaymentProviderException(
            "PROVIDER_TIMEOUT",
            retryable = true,
            outcomeUnknown = true
        )
        every {
            persistence.markProviderError(
                prepared.id,
                PaymentStatus.PROCESSING,
                "PROVIDER_TIMEOUT",
                any()
            )
        } returns prepared.copy(status = PaymentStatus.PROCESSING.name)

        assertThrows(PaymentProviderException::class.java) {
            service().createPaymentSession(
                prepared.orderId,
                prepared.userId,
                PaymentType.CONSULTATION_FEE,
                PaymentProvider.STRIPE,
                "CARD",
                "client-key-123"
            )
        }
        verify(exactly = 1) {
            persistence.markProviderError(
                prepared.id,
                PaymentStatus.PROCESSING,
                "PROVIDER_TIMEOUT",
                any()
            )
        }
    }

    private fun service() = PaymentService(
        paymentRepository,
        orderRepository,
        orderStatusLogService,
        PaymentGatewayRegistry(listOf(gateway)),
        persistence
    )

    private fun payment(status: String) = PaymentEntity(
        id = "payment-1",
        orderId = "order-1",
        userId = "user-1",
        amount = BigDecimal("10.00"),
        method = "ONLINE",
        status = status,
        paymentType = PaymentType.CONSULTATION_FEE.name,
        provider = PaymentProvider.STRIPE.name,
        paymentMethod = "CARD",
        currency = "USD",
        amountMinor = 1000
    )
}
