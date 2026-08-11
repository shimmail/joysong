package com.joysong.server.settlement.repository

import com.joysong.server.settlement.entity.SettlementAllocationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SettlementAllocationRepository : JpaRepository<SettlementAllocationEntity, Long> {
    fun findAllBySettlementIdOrderByIdAsc(settlementId: Long): List<SettlementAllocationEntity>
}
