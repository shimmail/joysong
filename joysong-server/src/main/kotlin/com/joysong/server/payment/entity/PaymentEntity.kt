package com.joysong.server.payment.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "payments")
data class PaymentEntity(
    @Id val id: String,
    @Column(name = "order_id") val orderId: String,
    @Column(name = "user_id") val userId: String,
    val amount: BigDecimal,
    val method: String,
    val status: String,
    @Column(name = "paid_at") val paidAt: LocalDateTime? = null,
    @Column(name = "transaction_id") val transactionId: String = "",

    /** 支付类型：CONSULTATION_FEE-面诊金，BALANCE-尾款 */
    @Column(name = "payment_type", nullable = false, length = 30) val paymentType: String = "CONSULTATION_FEE",
    @Column(name = "provider", nullable = false, length = 30) val provider: String = "DEMO",
    @Column(name = "payment_method", length = 50) val paymentMethod: String? = null,
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)") val currency: String = "CNY",
    @Column(name = "amount_minor") val amountMinor: Long? = null,
    @Column(name = "refunded_amount_minor", nullable = false) val refundedAmountMinor: Long = 0,
    @Column(name = "provider_payment_id", length = 150) val providerPaymentId: String? = null,
    @Column(name = "provider_transaction_id", length = 150) val providerTransactionId: String? = null,
    @Column(name = "idempotency_key", length = 100) val idempotencyKey: String? = null,
    @Column(name = "failure_code", length = 100) val failureCode: String? = null,
    @Column(name = "failure_message", length = 500) val failureMessage: String? = null,
    @Column(name = "authorized_at") val authorizedAt: LocalDateTime? = null,
    @Column(name = "cancelled_at") val cancelledAt: LocalDateTime? = null,
    @Column(name = "expires_at") val expiresAt: LocalDateTime? = null,
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null,
    @Version @Column(name = "version", nullable = false) val version: Long = 0
)
