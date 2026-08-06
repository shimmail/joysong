package com.joysong.server.like.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "likes")
data class LikeEntity(
    @Id
    @Column(name = "id", length = 36)
    var id: String = UUID.randomUUID().toString(),

    @Column(name = "user_id", nullable = false, length = 36)
    var userId: String = "",

    @Column(name = "target_type", nullable = false, length = 20)
    var targetType: String = "",

    @Column(name = "target_id", nullable = false, length = 36)
    var targetId: String = "",

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
)
