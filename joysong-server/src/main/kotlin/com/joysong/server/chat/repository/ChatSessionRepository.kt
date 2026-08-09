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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ChatSessionEntity session where session.userId = :userId and session.persona = :persona and session.deletedAt is null order by session.id")
    fun findByUserIdAndPersonaForUpdate(userId: String, persona: String): List<ChatSessionEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ChatSessionEntity session, ChatMessageEntity message where message.id = :messageId and message.sessionId = session.id and session.userId = :userId and session.deletedAt is null")
    fun findByMessageIdAndUserIdForUpdate(messageId: String, userId: String): ChatSessionEntity?
}
