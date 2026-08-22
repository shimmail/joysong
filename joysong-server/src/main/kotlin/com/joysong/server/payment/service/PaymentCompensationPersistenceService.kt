package com.joysong.server.payment.service

import com.joysong.server.payment.domain.PaymentCompensationStatus
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.entity.PaymentCompensationCaseEntity
import com.joysong.server.payment.provider.ProviderRefundResult
import com.joysong.server.payment.repository.PaymentCompensationCaseRepository
import com.joysong.server.payment.repository.PaymentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class PaymentCompensationPersistenceService(
    private val paymentRepository: PaymentRepository,
    private val compensationRepository: PaymentCompensationCaseRepository
) {
    companion object {
        /** Avoid a second admin click issuing a concurrent provider refund before the first result is known. */
        private const val PROVIDER_REQUEST_LEASE_MINUTES = 2L
    }

    @Transactional(rollbackFor = [Exception::class])
    fun prepareRefund(caseId: String, adminId: String): PaymentCompensationCaseEntity {
        val compensation = compensationRepository.findByIdForUpdate(caseId)
            ?: throw IllegalArgumentException("PAYMENT_COMPENSATION_NOT_FOUND")
        if (compensation.status == PaymentCompensationStatus.SUCCEEDED.name) return compensation
        require(compensation.status in setOf(
            PaymentCompensationStatus.PENDING_REVIEW.name,
            PaymentCompensationStatus.PROCESSING.name,
            PaymentCompensationStatus.FAILED.name
        )) { "INVALID_PAYMENT_COMPENSATION_STATUS" }
        val payment = paymentRepository.findByIdForUpdate(compensation.paymentId)
            ?: throw IllegalArgumentException("PAYMENT_NOT_FOUND")
        require(payment.status in PaymentStatus.successfulDatabaseValues) { "PAYMENT_NOT_SUCCEEDED" }
        require(payment.provider == compensation.provider) { "PAYMENT_COMPENSATION_PROVIDER_MISMATCH" }
        require(payment.providerPaymentId == compensation.providerPaymentId) { "PAYMENT_COMPENSATION_PAYMENT_ID_MISMATCH" }
        val now = LocalDateTime.now()
        if (compensation.status == PaymentCompensationStatus.PROCESSING.name &&
            compensation.providerRefundId.isNullOrBlank()
        ) {
            val lastProviderRequestAt = compensation.updatedAt ?: compensation.reviewedAt ?: compensation.createdAt
            require(lastProviderRequestAt.isBefore(now.minusMinutes(PROVIDER_REQUEST_LEASE_MINUTES))) {
                "PAYMENT_COMPENSATION_REFUND_IN_FLIGHT"
            }
        }
        return compensationRepository.save(
            compensation.copy(
                status = PaymentCompensationStatus.PROCESSING.name,
                reviewedBy = compensation.reviewedBy ?: adminId,
                reviewedAt = compensation.reviewedAt ?: now,
                failureCode = null,
                failureMessage = null,
                updatedAt = now
            )
        )
    }

    @Transactional(rollbackFor = [Exception::class])
    fun applyProviderResult(caseId: String, result: ProviderRefundResult): PaymentCompensationCaseEntity {
        require(result.status in setOf(PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED, PaymentStatus.FAILED)) {
            "UNSUPPORTED_PROVIDER_REFUND_STATUS"
        }
        require(result.providerRefundId.isNotBlank()) { "PROVIDER_REFUND_ID_MISSING" }
        val compensation = compensationRepository.findByIdForUpdate(caseId)
            ?: throw IllegalArgumentException("PAYMENT_COMPENSATION_NOT_FOUND")
        if (compensation.status == PaymentCompensationStatus.SUCCEEDED.name) return compensation
        val now = LocalDateTime.now()
        return compensationRepository.save(
            compensation.copy(
                status = result.status.name,
                providerRefundId = result.providerRefundId,
                failureCode = result.failureCode,
                failureMessage = result.failureMessage?.take(500),
                completedAt = if (result.status == PaymentStatus.SUCCEEDED) now else compensation.completedAt,
                updatedAt = now
            )
        )
    }

    @Transactional(rollbackFor = [Exception::class])
    fun markProviderError(caseId: String, status: PaymentCompensationStatus, code: String, message: String?) {
        require(status in setOf(PaymentCompensationStatus.PROCESSING, PaymentCompensationStatus.FAILED)) {
            "INVALID_PAYMENT_COMPENSATION_ERROR_STATUS"
        }
        val compensation = compensationRepository.findByIdForUpdate(caseId)
            ?: throw IllegalArgumentException("PAYMENT_COMPENSATION_NOT_FOUND")
        if (compensation.status == PaymentCompensationStatus.SUCCEEDED.name) return
        compensationRepository.save(
            compensation.copy(
                status = status.name,
                failureCode = code.take(100),
                failureMessage = message?.take(500),
                updatedAt = LocalDateTime.now()
            )
        )
    }

    fun listAll(): List<PaymentCompensationCaseEntity> = compensationRepository.findAllByOrderByCreatedAtDesc()
}
