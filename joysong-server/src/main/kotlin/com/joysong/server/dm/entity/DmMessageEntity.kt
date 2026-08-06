package com.joysong.server.dm.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "dm_messages")
data class DmMessageEntity(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString(),

    @Column(name = "conversation_id", nullable = false)
    var conversationId: String = "",

    @Column(name = "sender_id", nullable = false)
    var senderId: String = "",

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    var content: String = "",

    @Column(name = "message_type", nullable = false)
    var messageType: String = "TEXT",

    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false,

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.now()
)
