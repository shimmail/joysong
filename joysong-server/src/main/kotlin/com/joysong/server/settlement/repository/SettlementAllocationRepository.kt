package com.joysong.server.settlement.repository

import com.joysong.server.settlement.entity.SettlementAllocationEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SettlementAllocationRepository : JpaRepository<SettlementAllocationEntity, Long> {
    fun findAllBySettlementIdOrderByIdAsc(settlementId: Long): List<SettlementAllocationEntity>
    fun findAllBySettlementIdOrderByIdAsc(settlementId: Long, pageable: Pageable): Page<SettlementAllocationEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM SettlementAllocationEntity a WHERE a.settlementId = :settlementId ORDER BY a.id ASC")
    fun findAllBySettlementIdOrderByIdAscForUpdate(@Param("settlementId") settlementId: Long): List<SettlementAllocationEntity>
}
