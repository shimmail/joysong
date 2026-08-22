package com.joysong.server.payment.entity

import com.joysong.server.payment.domain.PaymentCompensationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

/** A single durable provider-refund workflow for one anomalous successful payment. */
@Entity
@Table(
    name = "payment_compensation_cases",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_payment_compensation_cases_payment", columnNames = ["payment_id"]),
        UniqueConstraint(name = "uk_payment_compensation_cases_idempotency", columnNames = ["idempotency_key"])
    ]
)
data class PaymentCompensationCaseEntity(
    @Id val id: String,
    @Column(name = "payment_id", nullable = false, length = 36) val paymentId: String,
    @Column(name = "order_id", nullable = false, length = 36) val orderId: String,
    @Column(name = "user_id", nullable = false, length = 36) val userId: String,
    @Column(name = "provider", nullable = false, length = 30) val provider: String,
    @Column(name = "provider_payment_id", nullable = false, length = 150) val providerPaymentId: String,
    @Column(name = "amount_minor", nullable = false) val amountMinor: Long,
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)") val currency: String,
    @Column(name = "reason_code", nullable = false, length = 100) val reasonCode: String,
    @Column(name = "reason_message", nullable = false, length = 500) val reasonMessage: String = "",
    @Column(name = "status", nullable = false, length = 30)
    val status: String = PaymentCompensationStatus.PENDING_REVIEW.name,
    @Column(name = "idempotency_key", nullable = false, length = 100) val idempotencyKey: String,
    @Column(name = "provider_refund_id", length = 150) val providerRefundId: String? = null,
    @Column(name = "reviewed_by", length = 36) val reviewedBy: String? = null,
    @Column(name = "reviewed_at") val reviewedAt: LocalDateTime? = null,
    @Column(name = "failure_code", length = 100) val failureCode: String? = null,
    @Column(name = "failure_message", length = 500) val failureMessage: String? = null,
    @Column(name = "completed_at") val completedAt: LocalDateTime? = null,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null
)
