package com.joysong.server.favorite.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "favorites")
data class FavoriteEntity(
    @Id
    @Column(name = "id", length = 36)
    var id: String = "",

    @Column(name = "user_id", nullable = false, length = 36)
    var userId: String = "",

    @Column(name = "target_type", nullable = false, length = 20)
    var targetType: String = "",

    @Column(name = "target_id", nullable = false, length = 36)
    var targetId: String = "",

    @Column(name = "target_name", length = 200)
    var targetName: String = "",

    @Column(name = "target_image", length = 500)
    var targetImage: String = "",

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
)
