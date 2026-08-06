package com.joysong.server.chat.repository

import com.joysong.server.chat.entity.ChatSessionEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ChatSessionRepository : JpaRepository<ChatSessionEntity, String> {
    fun findByUserIdAndDeletedAtIsNull(userId: String): List<ChatSessionEntity>
    fun findByUserIdAndPersonaAndDeletedAtIsNull(userId: String, persona: String): List<ChatSessionEntity>
    fun findByIdAndUserIdAndDeletedAtIsNull(id: String, userId: String): ChatSessionEntity?
}
