package com.joysong.server.dm.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "dm_conversations")
data class DmConversationEntity(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString(),

    @Column(name = "conversation_type", nullable = false, length = 30)
    var conversationType: String = DIRECT,

    @Column(name = "order_id", length = 36)
    var orderId: String? = null,

    @Column(name = "user_a_id", nullable = false)
    var userAId: String = "",

    @Column(name = "user_b_id", nullable = false)
    var userBId: String = "",

    @Column(name = "last_message", columnDefinition = "TEXT")
    var lastMessage: String? = null,

    @Column(name = "last_message_at")
    var lastMessageAt: LocalDateTime? = null,

    @Column(name = "user_a_unread", nullable = false)
    var userAUnread: Int = 0,

    @Column(name = "user_b_unread", nullable = false)
    var userBUnread: Int = 0,

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    companion object {
        const val DIRECT = "DIRECT"
        const val ORDER_SERVICE = "ORDER_SERVICE"
    }
}
