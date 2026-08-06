package com.joysong.server.refund.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "refunds")
data class RefundEntity(
    @Id val id: String,
    @Column(name = "refund_no", length = 50) val refundNo: String? = null,
    @Column(name = "order_id") val orderId: String,
    @Column(name = "user_id") val userId: String,
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)") val currency: String = "CNY",
    val amount: BigDecimal,
    @Column(name = "requested_amount_minor") val requestedAmountMinor: Long? = null,
    @Column(name = "refunded_amount_minor", nullable = false) val refundedAmountMinor: Long = 0,
    val reason: String,
    @Column(name = "reason_code", length = 50) val reasonCode: String? = null,
    val description: String = "",
    val status: String,
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "processed_at") val processedAt: LocalDateTime? = null,
    @Column(name = "evidence_url") val evidenceUrl: String = "",
    @Column(name = "refund_type") val refundType: String = "FULL",
    @Column(name = "refund_amount") val refundAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "user_phone") val userPhone: String = "",
    @Column(name = "order_no") val orderNo: String = "",
    @Column(name = "project_name") val projectName: String = "",
    @Column(name = "payment_amount") val paymentAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "payment_time") val paymentTime: LocalDateTime? = null,
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null,

    /** 退款前订单状态，用于取消退款时恢复 */
    @Column(name = "original_status") val originalStatus: String = "",
    @Column(name = "reviewed_by") val reviewedBy: String? = null,
    @Column(name = "reviewed_at") val reviewedAt: LocalDateTime? = null,
    @Column(name = "reject_reason", length = 500) val rejectReason: String? = null,
    @Column(name = "requested_at") val requestedAt: LocalDateTime? = null,
    @Column(name = "completed_at") val completedAt: LocalDateTime? = null
)
