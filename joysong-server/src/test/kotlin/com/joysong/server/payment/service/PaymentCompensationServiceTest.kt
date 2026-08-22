package com.joysong.server.payment.service

import com.joysong.server.payment.domain.PaymentCompensationStatus
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.entity.PaymentCompensationCaseEntity
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.provider.PaymentGateway
import com.joysong.server.payment.provider.PaymentGatewayRegistry
import com.joysong.server.payment.provider.ProviderRefundResult
import com.joysong.server.payment.repository.PaymentCompensationCaseRepository
import com.joysong.server.payment.repository.PaymentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class PaymentCompensationServiceTest {
    private val paymentRepository = mockk<PaymentRepository>()
    private val compensationRepository = mockk<PaymentCompensationCaseRepository>()
    private val gateway = mockk<PaymentGateway>()

    @Test
    fun `first compensation refund submits the exact captured provider charge with a stable key`() {
        val payment = anomalousPayment()
        var caseState = compensationCase(status = PaymentCompensationStatus.PENDING_REVIEW.name)
        val request = slot<com.joysong.server.payment.provider.ProviderRefundRequest>()
        every { compensationRepository.findByIdForUpdate(caseState.id) } answers { caseState }
        every { paymentRepository.findByIdForUpdate(payment.id) } returns payment
        every { compensationRepository.save(any()) } answers {
            firstArg<PaymentCompensationCaseEntity>().also { caseState = it }
        }
        every { gateway.provider } returns PaymentProvider.ALIPAY_PLUS
        every { gateway.refund(capture(request)) } returns ProviderRefundResult(
            PaymentStatus.PROCESSING,
            "provider-refund-1"
        )

        val result = service().retryCompensationRefund(caseState.id, "admin-1")

        assertEquals(PaymentCompensationStatus.PROCESSING.name, result.status)
        assertEquals(39_999L, request.captured.amountMinor)
        assertEquals("USD", request.captured.currency)
        assertEquals("payment-compensation-payment-1", request.captured.idempotencyKey)
        assertEquals(caseState.id, request.captured.refundItemId)
    }

    @Test
    fun `retry queries an existing compensation provider refund instead of resending it`() {
        val payment = anomalousPayment()
        var caseState = compensationCase(
            status = PaymentCompensationStatus.FAILED.name,
            providerRefundId = "provider-refund-1"
        )
        every { compensationRepository.findByIdForUpdate(caseState.id) } answers { caseState }
        every { paymentRepository.findByIdForUpdate(payment.id) } returns payment
        every { compensationRepository.save(any()) } answers {
            firstArg<PaymentCompensationCaseEntity>().also { caseState = it }
        }
        every { gateway.provider } returns PaymentProvider.ALIPAY_PLUS
        every { gateway.queryRefund("provider-refund-1") } returns ProviderRefundResult(
            PaymentStatus.SUCCEEDED,
            "provider-refund-1"
        )

        val result = service().retryCompensationRefund(caseState.id, "admin-1")

        assertEquals(PaymentCompensationStatus.SUCCEEDED.name, result.status)
        verify(exactly = 1) { gateway.queryRefund("provider-refund-1") }
        verify(exactly = 0) { gateway.refund(any()) }
    }

    @Test
    fun `processing compensation without a provider refund id does not resend while the first request is in flight`() {
        val payment = anomalousPayment()
        var caseState = compensationCase(
            status = PaymentCompensationStatus.PROCESSING.name
        ).copy(updatedAt = LocalDateTime.now())
        every { compensationRepository.findByIdForUpdate(caseState.id) } answers { caseState }
        every { paymentRepository.findByIdForUpdate(payment.id) } returns payment
        every { compensationRepository.save(any()) } answers {
            firstArg<PaymentCompensationCaseEntity>().also { caseState = it }
        }
        every { gateway.provider } returns PaymentProvider.ALIPAY_PLUS
        every { gateway.refund(any()) } returns ProviderRefundResult(PaymentStatus.PROCESSING, "provider-refund-1")

        val error = assertThrows(IllegalArgumentException::class.java) {
            service().retryCompensationRefund(caseState.id, "admin-1")
        }

        assertEquals("PAYMENT_COMPENSATION_REFUND_IN_FLIGHT", error.message)
        verify(exactly = 0) { gateway.refund(any()) }
        verify(exactly = 0) { gateway.queryRefund(any()) }
    }

    private fun anomalousPayment() = PaymentEntity(
        id = "payment-1",
        orderId = "order-1",
        userId = "user-1",
        amount = BigDecimal("400.00"),
        method = "ONLINE",
        status = PaymentStatus.SUCCEEDED.name,
        paymentType = "TRAVEL_GROUND_SERVICE_FEE",
        provider = PaymentProvider.ALIPAY_PLUS.name,
        currency = "USD",
        amountMinor = 40_000L,
        providerPaymentId = "provider-payment-1"
    )

    private fun compensationCase(
        status: String,
        providerRefundId: String? = null
    ) = PaymentCompensationCaseEntity(
        id = "compensation-1",
        paymentId = "payment-1",
        orderId = "order-1",
        userId = "user-1",
        provider = PaymentProvider.ALIPAY_PLUS.name,
        providerPaymentId = "provider-payment-1",
        amountMinor = 39_999L,
        currency = "USD",
        reasonCode = "PAYMENT_SUCCEEDED_AMOUNT_MISMATCH",
        status = status,
        idempotencyKey = "payment-compensation-payment-1",
        providerRefundId = providerRefundId
    )

    private fun service() = PaymentCompensationService(
        PaymentCompensationPersistenceService(paymentRepository, compensationRepository),
        PaymentGatewayRegistry(listOf(gateway))
    )
}
