package com.joysong.server.payment.repository

import com.joysong.server.payment.entity.PaymentEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType
import java.util.Optional
import java.time.LocalDateTime

interface PaymentRepository : JpaRepository<PaymentEntity, String> {
    fun findByOrderId(orderId: String): Optional<PaymentEntity>
    fun findByOrderIdAndPaymentTypeAndStatus(orderId: String, paymentType: String, status: String): PaymentEntity?
    fun findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtDesc(
        orderId: String,
        paymentType: String,
        statuses: Collection<String>
    ): PaymentEntity?
    fun findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtAsc(
        orderId: String,
        paymentType: String,
        statuses: Collection<String>
    ): PaymentEntity?
    fun findFirstByOrderIdAndPaymentTypeOrderByCreatedAtDesc(
        orderId: String,
        paymentType: String
    ): PaymentEntity?
    fun findByUserIdAndIdempotencyKey(userId: String, idempotencyKey: String): PaymentEntity?
    fun findByProviderAndProviderPaymentId(provider: String, providerPaymentId: String): PaymentEntity?
    fun findAllByOrderIdAndStatusInOrderByCreatedAtAsc(orderId: String, statuses: Collection<String>): List<PaymentEntity>
    fun findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
        statuses: Collection<String>,
        updatedAt: LocalDateTime
    ): List<PaymentEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentEntity p WHERE p.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): PaymentEntity?
}
