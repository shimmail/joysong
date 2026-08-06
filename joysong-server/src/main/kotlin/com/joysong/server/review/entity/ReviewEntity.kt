package com.joysong.server.review.entity

import jakarta.persistence.*
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.time.LocalDateTime

@Entity
@Table(name = "reviews")
@SQLDelete(sql = "UPDATE reviews SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
data class ReviewEntity(
    @Id val id: String,
    @Column(name = "order_id") val orderId: String,
    @Column(name = "user_id") val userId: String,
    @Column(name = "doctor_id") val doctorId: String = "",
    val rating: Int,
    val content: String,
    val tags: String = "",
    val images: String = "",
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "target_type") val targetType: String = "",
    @Column(name = "target_id") val targetId: String = "",
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = null,
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null
)
