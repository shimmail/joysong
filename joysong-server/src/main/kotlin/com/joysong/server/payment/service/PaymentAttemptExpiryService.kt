package com.joysong.server.payment.service

import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.repository.PaymentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class PaymentAttemptExpiryService(
    private val paymentRepository: PaymentRepository
) {
    companion object {
        private val EXPIRABLE_STATUSES = setOf(
            PaymentStatus.CREATED.name,
            PaymentStatus.REQUIRES_ACTION.name
        )
    }

    /**
     * The selection is pessimistically locked and every row is rechecked before
     * update, so a verified success callback cannot be blindly overwritten.
     */
    @Transactional(rollbackFor = [Exception::class])
    fun expireDueAttempts(now: LocalDateTime = LocalDateTime.now()): Int {
        var expired = 0
        paymentRepository.findExpirableAttempts(EXPIRABLE_STATUSES, now).forEach { payment ->
            if (isDue(payment, now)) {
                expire(payment, now)
                expired++
            }
        }
        return expired
    }

    /** Reconciliation guard: never submit an already-due attempt that has no provider payment. */
    @Transactional(rollbackFor = [Exception::class])
    fun expireDueUnsubmittedAttempt(paymentId: String, now: LocalDateTime = LocalDateTime.now()): PaymentEntity {
        val payment = paymentRepository.findByIdForUpdate(paymentId)
            ?: throw IllegalArgumentException("PAYMENT_NOT_FOUND")
        return if (isDue(payment, now)) expire(payment, now) else payment
    }

    private fun isDue(payment: PaymentEntity, now: LocalDateTime): Boolean =
        payment.providerPaymentId.isNullOrBlank() &&
            payment.status in EXPIRABLE_STATUSES &&
            payment.expiresAt?.let { !it.isAfter(now) } == true

    private fun expire(payment: PaymentEntity, now: LocalDateTime): PaymentEntity =
        paymentRepository.save(
            payment.copy(
                status = PaymentStatus.EXPIRED.name,
                updatedAt = now
            )
        )
}
