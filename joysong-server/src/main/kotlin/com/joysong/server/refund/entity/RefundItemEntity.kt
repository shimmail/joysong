package com.joysong.server.refund.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "refund_items")
data class RefundItemEntity(
    @Id val id: String,
    @Column(name = "refund_id", nullable = false) val refundId: String,
    @Column(name = "payment_id", nullable = false) val paymentId: String,
    @Column(name = "provider", nullable = false, length = 30) val provider: String,
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)") val currency: String,
    @Column(name = "amount_minor", nullable = false) val amountMinor: Long,
    @Column(name = "provider_refund_id", length = 150) val providerRefundId: String? = null,
    @Column(name = "status", nullable = false, length = 30) val status: String = "CREATED",
    @Column(name = "failure_code", length = 100) val failureCode: String? = null,
    @Column(name = "failure_message", length = 500) val failureMessage: String? = null,
    @Column(name = "requested_at") val requestedAt: LocalDateTime? = null,
    @Column(name = "completed_at") val completedAt: LocalDateTime? = null,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null
)
