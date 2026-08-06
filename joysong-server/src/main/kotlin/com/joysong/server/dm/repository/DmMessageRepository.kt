package com.joysong.server.dm.repository

import com.joysong.server.dm.entity.DmMessageEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

interface DmMessageRepository : JpaRepository<DmMessageEntity, String> {
    fun findByConversationIdOrderByCreatedAtDesc(conversationId: String, pageable: Pageable): List<DmMessageEntity>
    fun findByConversationIdAndCreatedAtBeforeOrderByCreatedAtDesc(
        conversationId: String,
        before: LocalDateTime,
        pageable: Pageable
    ): List<DmMessageEntity>

    @Modifying
    @Query(value = "UPDATE dm_messages SET is_read = 1 WHERE conversation_id = :conversationId AND sender_id <> :userId AND is_read = 0", nativeQuery = true)
    fun markAsRead(conversationId: String, userId: String): Int
}
