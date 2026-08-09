package com.joysong.server.chat.repository

import com.joysong.server.chat.entity.ChatSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import jakarta.persistence.LockModeType

interface ChatSessionRepository : JpaRepository<ChatSessionEntity, String> {
    fun findByUserIdAndDeletedAtIsNull(userId: String): List<ChatSessionEntity>
    fun findByUserIdAndPersonaAndDeletedAtIsNull(userId: String, persona: String): List<ChatSessionEntity>
    fun findByIdAndUserIdAndDeletedAtIsNull(id: String, userId: String): ChatSessionEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ChatSessionEntity session where session.id = :sessionId and session.deletedAt is null")
    fun findByIdForUpdate(sessionId: String): ChatSessionEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ChatSessionEntity session where session.id = :sessionId and session.userId = :userId and session.deletedAt is null")
    fun findByIdAndUserIdForUpdate(sessionId: String, userId: String): ChatSessionEntity?
}
