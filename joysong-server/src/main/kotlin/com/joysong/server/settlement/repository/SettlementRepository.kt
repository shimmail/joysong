package com.joysong.server.settlement.repository

import com.joysong.server.settlement.entity.SettlementEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * 结算分账记录仓储
 *
 * @author joysong
 * @since 2026-07-30
 */
@Repository
interface SettlementRepository : JpaRepository<SettlementEntity, Long> {
    fun findByOrderId(orderId: String): SettlementEntity?
    fun findByStatusAndSettledAtBefore(status: String, settledAt: LocalDateTime): List<SettlementEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SettlementEntity s WHERE s.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): SettlementEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SettlementEntity s WHERE s.orderId = :orderId")
    fun findByOrderIdForUpdate(@Param("orderId") orderId: String): SettlementEntity?

    @Query(
        "SELECT s.id FROM SettlementEntity s " +
            "WHERE s.status = :status AND s.settledAt <= :settledAt ORDER BY s.id ASC"
    )
    fun findDueSettlementIds(
        @Param("status") status: String,
        @Param("settledAt") settledAt: LocalDateTime,
        pageable: Pageable
    ): List<Long>
}
