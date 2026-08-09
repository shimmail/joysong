package com.joysong.server.agent.repository

import com.joysong.server.agent.entity.AgentTurnEntity
import com.joysong.server.agent.entity.AgentTurnStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import jakarta.persistence.LockModeType

interface AgentTurnRepository : JpaRepository<AgentTurnEntity, String> {
    fun findBySessionIdAndIdempotencyKey(sessionId: String, idempotencyKey: String): AgentTurnEntity?
    fun findBySessionIdAndStatus(sessionId: String, status: AgentTurnStatus): AgentTurnEntity?
    fun deleteBySessionId(sessionId: String)

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select turn from AgentTurnEntity turn where turn.id = :turnId")
    fun findByIdForUpdate(turnId: String): AgentTurnEntity?
}
