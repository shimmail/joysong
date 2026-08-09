package com.joysong.server.agent.repository

import com.joysong.server.agent.entity.AgentTurnEntity
import com.joysong.server.agent.entity.AgentTurnStatus
import org.springframework.data.jpa.repository.JpaRepository

interface AgentTurnRepository : JpaRepository<AgentTurnEntity, String> {
    fun findBySessionIdAndIdempotencyKey(sessionId: String, idempotencyKey: String): AgentTurnEntity?
    fun findBySessionIdAndStatus(sessionId: String, status: AgentTurnStatus): AgentTurnEntity?
}
