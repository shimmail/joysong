package com.joysong.server.settlement.repository

import com.joysong.server.settlement.entity.SettlementEntity
import org.springframework.data.jpa.repository.JpaRepository
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
}
