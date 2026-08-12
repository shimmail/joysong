package com.joysong.server.refund.repository

import com.joysong.server.refund.entity.RefundItemEntity
import com.joysong.server.payment.entity.PaymentEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType

interface RefundItemRepository : JpaRepository<RefundItemEntity, String> {
    @Query(
        "select coalesce(sum(i.amountMinor), 0) from RefundItemEntity i " +
            "join PaymentEntity p on p.id = i.paymentId " +
            "where p.orderId = :orderId and i.status = 'SUCCEEDED'"
    )
    fun sumCompletedAmountMinor(@Param("orderId") orderId: String): Long

    fun findAllByRefundIdOrderByCreatedAtAsc(refundId: String): List<RefundItemEntity>
    fun findByProviderAndProviderRefundId(provider: String, providerRefundId: String): RefundItemEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM RefundItemEntity i WHERE i.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): RefundItemEntity?
}
