package com.joysong.server.chat.repository

import com.joysong.server.chat.entity.ChatMessageEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime
import org.springframework.data.jpa.repository.Query

interface ChatMessageRepository : JpaRepository<ChatMessageEntity, String> {
    fun findBySessionIdOrderByCreatedAtAsc(sessionId: String): List<ChatMessageEntity>
    fun findBySessionIdOrderByCreatedAtDesc(sessionId: String, pageable: Pageable): List<ChatMessageEntity>
    fun findBySessionIdAndCreatedAtBeforeOrderByCreatedAtDesc(
        sessionId: String,
        before: LocalDateTime,
        pageable: Pageable
    ): List<ChatMessageEntity>
    fun findTop10BySessionIdOrderByCreatedAtDesc(sessionId: String): List<ChatMessageEntity>
    fun findFirstBySessionIdOrderByCreatedAtDesc(sessionId: String): ChatMessageEntity?
    fun findBySessionIdOrderBySequenceNoAsc(sessionId: String): List<ChatMessageEntity>
    fun findByTurnIdAndRole(turnId: String, role: String): ChatMessageEntity?
    fun findByIdAndSessionId(id: String, sessionId: String): ChatMessageEntity?
    fun countBySessionId(sessionId: String): Long
    fun deleteBySessionId(sessionId: String)
    fun deleteByTurnId(turnId: String)

    @Query("""
        select message from ChatMessageEntity message
        join AgentTurnEntity turn on turn.id = message.turnId
        where message.sessionId = :sessionId and turn.status = com.joysong.server.agent.entity.AgentTurnStatus.SUCCEEDED
        order by message.sequenceNo asc
    """)
    fun findSucceededTurnMessagesBySessionId(sessionId: String): List<ChatMessageEntity>
}
