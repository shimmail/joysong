package com.joysong.server.chat.repository

import com.joysong.server.chat.entity.ChatMessageEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime

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
    fun deleteBySessionId(sessionId: String)
}
