package com.joysong.server.dm.repository

import com.joysong.server.dm.entity.DmConversationEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface DmConversationRepository : JpaRepository<DmConversationEntity, String> {
    @Query("""
        SELECT conversation FROM DmConversationEntity conversation
        WHERE conversation.userAId = :participantId OR conversation.userBId = :participantId
        ORDER BY conversation.lastMessageAt DESC
    """)
    fun findByParticipantOrderByLastMessageAtDesc(
        @Param("participantId") participantId: String
    ): List<DmConversationEntity>

    @Query("""
        SELECT conversation FROM DmConversationEntity conversation
        WHERE conversation.conversationType = :conversationType
          AND (conversation.userAId = :participantId OR conversation.userBId = :participantId)
        ORDER BY conversation.lastMessageAt DESC
    """)
    fun findByConversationTypeAndParticipantOrderByLastMessageAtDesc(
        @Param("conversationType") conversationType: String,
        @Param("participantId") participantId: String
    ): List<DmConversationEntity>

    fun findByConversationTypeAndUserAIdAndUserBId(
        conversationType: String,
        userAId: String,
        userBId: String
    ): DmConversationEntity?

    @Modifying
    @Query(
        value = """
            INSERT INTO dm_conversations
                (id, conversation_type, order_id, user_a_id, user_b_id, created_at, updated_at)
            VALUES
                (:id, 'DIRECT', NULL, :userAId, :userBId, :createdAt, :updatedAt)
            ON DUPLICATE KEY UPDATE id = id
        """,
        nativeQuery = true
    )
    fun insertDirectIfAbsent(
        @Param("id") id: String,
        @Param("userAId") userAId: String,
        @Param("userBId") userBId: String,
        @Param("createdAt") createdAt: LocalDateTime,
        @Param("updatedAt") updatedAt: LocalDateTime
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT conversation FROM DmConversationEntity conversation
        WHERE conversation.conversationType = 'DIRECT'
          AND conversation.userAId = :userAId
          AND conversation.userBId = :userBId
    """)
    fun findDirectByPairForUpdate(
        @Param("userAId") userAId: String,
        @Param("userBId") userBId: String
    ): DmConversationEntity?

    fun findByConversationTypeAndOrderId(
        conversationType: String,
        orderId: String
    ): DmConversationEntity?

    @Query("""
        SELECT conversation FROM DmConversationEntity conversation
        WHERE conversation.id = :id
          AND conversation.conversationType = :conversationType
          AND (conversation.userAId = :participantId OR conversation.userBId = :participantId)
    """)
    fun findByIdAndConversationTypeAndParticipant(
        @Param("id") id: String,
        @Param("conversationType") conversationType: String,
        @Param("participantId") participantId: String
    ): DmConversationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT conversation FROM DmConversationEntity conversation WHERE conversation.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): DmConversationEntity?
}
