package com.joysong.server.agent.repository

import com.joysong.server.agent.entity.AgentTurnEntity
import com.joysong.server.agent.entity.AgentTurnStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import jakarta.persistence.LockModeType

interface AgentTurnRepository : JpaRepository<AgentTurnEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findBySessionIdAndIdempotencyKey(sessionId: String, idempotencyKey: String): AgentTurnEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findBySessionIdAndStatus(sessionId: String, status: AgentTurnStatus): AgentTurnEntity?
    fun countBySessionId(sessionId: String): Long
    fun deleteBySessionId(sessionId: String)

    @Query("select turn.sessionId from AgentTurnEntity turn where turn.id = :turnId")
    fun findSessionIdById(turnId: String): String?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select turn from AgentTurnEntity turn where turn.id = :turnId")
    fun findByIdForUpdate(turnId: String): AgentTurnEntity?
}
