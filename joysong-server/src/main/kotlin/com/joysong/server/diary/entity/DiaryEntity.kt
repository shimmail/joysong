package com.joysong.server.diary.entity

import jakarta.persistence.*
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "diaries")
@SQLDelete(sql = "UPDATE diaries SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
data class DiaryEntity(
    @Id val id: String,
    val title: String,
    @Column(name = "user_id") val userId: String = "",
    @Column(name = "author_name") val authorName: String = "",
    @Column(name = "author_avatar") val authorAvatar: String = "",
    val content: String = "",
    val images: String = "",
    @Column(name = "like_count") val likeCount: Int = 0,
    @Column(name = "comment_count") val commentCount: Int = 0,
    val tags: String = "",
    @Column(name = "publish_date") val publishDate: LocalDate = LocalDate.now(),
    val status: String = "published",
    @Column(name = "doctor_id") val doctorId: String = "",
    @Column(name = "project_id") val projectId: String = "",
    @Column(name = "institution_project_id") val institutionProjectId: String = "",
    @Column(name = "institution_id") val institutionId: String = "",
    @Column(name = "project_name") var projectName: String = "",
    @Column(name = "doctor_name") var doctorName: String = "",
    @Column(name = "institution_name") var institutionName: String = "",
    @Column(name = "before_images") val beforeImages: String = "",
    @Column(name = "after_images") val afterImages: String = "",
    @Column(name = "order_id") val orderId: String = "",
    val rating: Int = 0,
    @Column(name = "favorite_count") var favoriteCount: Int = 0,
    @Column(name = "created_at", insertable = false, updatable = false) val createdAt: LocalDateTime? = null,
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null,
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = null
) {
    @get:Transient
    val coverImage: String
        get() = imageUrls.firstOrNull().orEmpty()

    @get:Transient
    val imageUrls: List<String>
        get() = images.toImageUrlList()

    @get:Transient
    val beforeImageUrls: List<String>
        get() = beforeImages.toImageUrlList()

    @get:Transient
    val afterImageUrls: List<String>
        get() = afterImages.toImageUrlList()
}

private fun String.toImageUrlList(): List<String> =
    split(',').map(String::trim).filter(String::isNotEmpty)
