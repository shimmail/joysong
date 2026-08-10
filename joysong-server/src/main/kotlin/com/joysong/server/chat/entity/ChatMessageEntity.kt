package com.joysong.server.chat.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "agent_messages")
data class ChatMessageEntity(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString(),

    @Column(name = "session_id", nullable = false)
    var sessionId: String = "",

    @Column(name = "turn_id")
    var turnId: String? = null,

    @Column(name = "sequence_no", nullable = false)
    var sequenceNo: Long = 0,

    @Column(name = "role", nullable = false)
    var role: String = "",  // USER | ASSISTANT | SYSTEM

    @Column(name = "content", columnDefinition = "TEXT")
    var content: String = "",

    @Column(name = "content_type", nullable = false)
    var contentType: String = "TEXT",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "json", nullable = false)
    var metadataJson: String = "{}",

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
)
