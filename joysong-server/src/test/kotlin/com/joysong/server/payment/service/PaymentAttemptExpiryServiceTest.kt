package com.joysong.server.payment.service

import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.repository.PaymentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.persistence.LockModeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.math.BigDecimal
import java.time.LocalDateTime

class PaymentAttemptExpiryServiceTest {
    private val paymentRepository = mockk<PaymentRepository>()

    @Test
    fun `expires only eligible due attempts without touching processing`() {
        val now = LocalDateTime.of(2026, 8, 22, 13, 0)
        val statuses = slot<Collection<String>>()
        val created = payment("created", PaymentStatus.CREATED, now.minusSeconds(1))
        val requiresAction = payment("action", PaymentStatus.REQUIRES_ACTION, now)
        val providerAccepted = payment("provider-accepted", PaymentStatus.REQUIRES_ACTION, now.minusSeconds(1))
            .copy(providerPaymentId = "provider-payment-1")
        val processing = payment("processing", PaymentStatus.PROCESSING, now.minusMinutes(1))
        val future = payment("future", PaymentStatus.CREATED, now.plusSeconds(1))
        every { paymentRepository.findExpirableAttempts(capture(statuses), now) } returns
            listOf(created, requiresAction, providerAccepted, processing, future)
        every { paymentRepository.save(any()) } answers { firstArg() }

        val count = PaymentAttemptExpiryService(paymentRepository).expireDueAttempts(now)

        assertEquals(
            setOf(PaymentStatus.CREATED.name, PaymentStatus.REQUIRES_ACTION.name),
            statuses.captured.toSet()
        )
        assertEquals(2, count)
        verify(exactly = 1) {
            paymentRepository.save(match { it.id == created.id && it.status == PaymentStatus.EXPIRED.name })
        }
        verify(exactly = 1) {
            paymentRepository.save(match { it.id == requiresAction.id && it.status == PaymentStatus.EXPIRED.name })
        }
        verify(exactly = 0) {
            paymentRepository.save(match {
                it.id == providerAccepted.id || it.id == processing.id || it.id == future.id
            })
        }
    }

    @Test
    fun `expiry selection locks attempts against concurrent success callback`() {
        val method = PaymentRepository::class.java.getMethod(
            "findExpirableAttempts",
            Collection::class.java,
            LocalDateTime::class.java
        )

        assertEquals(LockModeType.PESSIMISTIC_WRITE, method.getAnnotation(Lock::class.java).value)
        val query = method.getAnnotation(Query::class.java).value
        assertTrue(query.contains("providerPaymentId IS NULL"))
        assertTrue(query.contains("TRIM(p.providerPaymentId) = ''"))
    }

    private fun payment(id: String, status: PaymentStatus, expiresAt: LocalDateTime) = PaymentEntity(
        id = id,
        orderId = "order-1",
        userId = "user-1",
        amount = BigDecimal("400.00"),
        method = "ONLINE",
        status = status.name,
        paymentType = "TRAVEL_GROUND_SERVICE_FEE",
        provider = "ALIPAY_PLUS",
        paymentMethod = "ALIPAY_PLUS_CASHIER",
        currency = "USD",
        amountMinor = 40_000,
        expiresAt = expiresAt
    )
}
