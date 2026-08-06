package com.joysong.server.article.entity

import jakarta.persistence.*
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "expert_articles")
@SQLDelete(sql = "UPDATE expert_articles SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
data class ArticleEntity(
    @Id val id: String,
    val title: String,
    @Column(name = "author_name") val authorName: String = "",
    val summary: String = "",
    @Column(name = "cover_image") val coverImage: String = "",
    @Column(name = "publish_date") val publishDate: LocalDate = LocalDate.now(),
    val content: String = "",
    @Column(name = "read_count") val readCount: Int = 0,
    @Column(name = "doctor_id") val doctorId: String = "",
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null,
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = null
)
