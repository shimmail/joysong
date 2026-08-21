package com.joysong.server.payment.service

import com.joysong.server.payment.domain.PaymentStatus
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
            if (payment.status in EXPIRABLE_STATUSES &&
                payment.expiresAt?.let { !it.isAfter(now) } == true
            ) {
                paymentRepository.save(
                    payment.copy(
                        status = PaymentStatus.EXPIRED.name,
                        updatedAt = now
                    )
                )
                expired++
            }
        }
        return expired
    }
}
