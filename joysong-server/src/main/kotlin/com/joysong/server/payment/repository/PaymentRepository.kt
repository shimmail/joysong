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
    @Query(
        "select coalesce(sum(p.amountMinor), 0) from PaymentEntity p " +
            "where p.orderId = :orderId and p.status in ('SUCCEEDED', 'SUCCESS')"
    )
    fun sumSucceededAmountMinor(@Param("orderId") orderId: String): Long

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

    @Query(
        "SELECT COUNT(p.id) FROM PaymentEntity p " +
            "WHERE UPPER(TRIM(p.provider)) = UPPER(TRIM(:provider)) AND (" +
            "p.status IS NULL OR UPPER(TRIM(p.status)) IN :inFlightStatuses OR " +
            "(UPPER(TRIM(p.status)) IN :refundableStatuses AND (" +
            "p.amountMinor IS NULL OR p.refundedAmountMinor IS NULL OR " +
            "p.refundedAmountMinor <> p.amountMinor)) OR " +
            "UPPER(TRIM(p.status)) NOT IN :knownStatuses)"
    )
    fun countActionableLiabilitiesByProvider(
        @Param("provider") provider: String,
        @Param("inFlightStatuses") inFlightStatuses: Collection<String>,
        @Param("refundableStatuses") refundableStatuses: Collection<String>,
        @Param("knownStatuses") knownStatuses: Collection<String>
    ): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT p FROM PaymentEntity p " +
            "WHERE p.status IN :statuses AND p.expiresAt IS NOT NULL AND p.expiresAt <= :now " +
            "ORDER BY p.id"
    )
    fun findExpirableAttempts(
        @Param("statuses") statuses: Collection<String>,
        @Param("now") now: LocalDateTime
    ): List<PaymentEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentEntity p WHERE p.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): PaymentEntity?
}
