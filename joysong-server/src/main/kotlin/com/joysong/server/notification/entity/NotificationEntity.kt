package com.joysong.server.notification.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "notifications")
data class NotificationEntity(
    @Id
    @Column(name = "id")
    var id: String = UUID.randomUUID().toString(),

    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    @Column(name = "type", nullable = false)
    var type: String = "",

    @Column(name = "title", nullable = false)
    var title: String = "",

    @Column(name = "content", columnDefinition = "TEXT")
    var content: String = "",

    @Column(name = "target_type")
    var targetType: String = "",

    @Column(name = "target_id")
    var targetId: String = "",

    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false,

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
)
