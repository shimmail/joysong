package com.joysong.server.comment.entity

import jakarta.persistence.*
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "comments")
@SQLDelete(sql = "UPDATE comments SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
data class CommentEntity(
    @Id
    @Column(name = "id", length = 36)
    val id: String = UUID.randomUUID().toString(),

    @Column(name = "diary_id", nullable = false, length = 36)
    val diaryId: String = "",

    @Column(name = "user_id", nullable = false, length = 36)
    val userId: String = "",

    @Column(name = "content", columnDefinition = "TEXT")
    val content: String = "",

    // 回复功能字段
    @Column(name = "parent_id", length = 36)
    val parentId: String? = null,

    @Column(name = "reply_to_user_id", length = 36)
    val replyToUserId: String? = null,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "deleted_at")
    val deletedAt: LocalDateTime? = null,

    @Column(name = "like_count")
    val likeCount: Int = 0,

    @Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null
)
