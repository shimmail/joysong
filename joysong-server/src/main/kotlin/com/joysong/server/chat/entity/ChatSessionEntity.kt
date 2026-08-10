package com.joysong.server.chat.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "agent_sessions")
@SQLDelete(sql = "UPDATE agent_sessions SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
data class ChatSessionEntity(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString(),

    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    @Column(name = "persona", nullable = false)
    var persona: String = "BESTIE",  // BESTIE | CONSULTANT

    @Column(name = "context_type", nullable = false)
    var contextType: String = "GENERAL",  // DOCTOR | PROJECT | GENERAL

    @Column(name = "context_id")
    var contextId: String = "",

    @Column(name = "title")
    var title: String = "",

    @Column(name = "next_sequence_no", nullable = false)
    var nextSequenceNo: Long = 1,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary_json", columnDefinition = "json", nullable = false)
    var summaryJson: String = "{}",

    @Column(name = "summary_updated_at")
    var summaryUpdatedAt: LocalDateTime? = null,

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
)
